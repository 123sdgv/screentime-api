package dev.gray.screentime

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.os.UserManager
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

internal class Collector(private val context: Context, private val db: Archive) {
    private val dao = db.dao()
    private val manager = context.getSystemService(UsageStatsManager::class.java)
    fun allowed() = context.getSystemService(AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED

    suspend fun refresh(): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val previous = dao.checkpoint() ?: Checkpoint()
            suspend fun gap(message: String): String {
                dao.saveState(previous.copy(anchor = null))
                return message
            }
            if (!allowed()) return@withLock gap("使用情况访问未开启。已归档数据仍可查看。")
            if (!context.getSystemService(UserManager::class.java).isUserUnlocked)
                return@withLock gap("请先解锁设备，再刷新。")
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val today = now.dateIn(zone)
            val changedZone = previous.zone.isNotEmpty() && previous.zone != zone.id
            val clockBack = previous.lastSuccess?.let { now < it } == true
            // On a zone change start a new observation period; avoid reassigning old instants to dates.
            val cutoff = if (changedZone || clockBack) now else previous.cutOff
            val boundaryChange = changedZone || clockBack
            val start = maxOf(today.minusDays(6).start(zone), cutoff)
            try {
                val events = readSignals(start, now)
                val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now).orEmpty()
                val values = mutableMapOf<Key, DayValue>()
                for (s in stats) {
                    if (s.firstTimeStamp < start || s.lastTimeStamp > now || s.totalTimeInForeground <= 0) continue
                    val date = bucketDay(s.firstTimeStamp, s.lastTimeStamp, zone) ?: continue
                    val key = Key(s.packageName, date)
                    values[key] = bestEvidence(values[key], DayValue(s.totalTimeInForeground, null, "system"))
                }
                val reconstructed = reduceEvents(events, zone)
                for ((key, evidence) in reconstructed.records) {
                    values[key] = bestEvidence(values[key], DayValue(evidence.millis.takeIf { it > 0 },
                        evidence.starts.takeIf { it > 0 }, "events"))
                }
                if (!allowed()) return@withLock gap("采集期间权限已收回，保留原有档案。")
                if (events.isEmpty() && values.isEmpty()) {
                    db.withTransaction {
                        if (boundaryChange) markMixedDay(today, zone)
                        dao.saveState(previous.copy(zone = zone.id, cutOff = cutoff, anchor = null,
                            lastSuccess = previous.lastSuccess.takeUnless { clockBack }))
                    }
                    return@withLock "系统暂未返回可用记录。使用几个 APP 后再刷新，空结果保留为未知。"
                }
                val fromDate = start.dateIn(zone).toString()
                val endDate = today.toString()
                val existingCatalog = dao.catalogSnapshot().associateBy { it.pkg }
                val catalog = values.keys.map { it.pkg }.distinct().mapNotNull { pkg ->
                    loadCatalog(pkg, existingCatalog[pkg])
                }
                val firstEvent = events.firstOrNull()?.at
                val lastEvent = events.lastOrNull()?.at?.let { minOf(it, reconstructed.pendingFrom ?: it) }
                val anchor = previous.anchor
                val bridge = if (firstEvent != null && lastEvent != null && anchor != null &&
                    !changedZone && !clockBack && firstEvent <= anchor &&
                    now - anchor in 0..Duration.ofHours(30).toMillis()) {
                    val reset = events.lastOrNull { it.kind == Kind.BOOT && it.at >= anchor }?.at ?: anchor
                    Span(maxOf(reset, anchor), lastEvent).takeIf { it.end > it.from }
                } else null
                db.withTransaction {
                    if (boundaryChange) markMixedDay(today, zone)
                    val old = dao.existing(fromDate, endDate).associateBy { it.pkg to it.date }
                    val historicCoverage = dao.priorCoverage(fromDate, endDate).associateBy { it.date }
                    val rows = values.mapNotNull { (key, value) ->
                        val date = key.day.toString()
                        val saved = old[key.pkg to date]
                        // A civil date with two observed zones is kept conservatively partial.
                        if (saved != null && saved.zone != zone.id) return@mapNotNull null
                        val merged = bestEvidence(saved?.value(), value)
                        Daily(key.pkg, date, merged.ms, merged.entries, merged.source, zone.id)
                    }
                    dao.saveDays(rows)
                    dao.saveCatalog(catalog)
                    if (bridge != null) {
                        val spans = mutableListOf<Cover>()
                        splitInterval(bridge.from, bridge.end, zone) { date, _ ->
                            val name = date.toString()
                            val oldSpan = historicCoverage[name]
                            if (oldSpan == null || oldSpan.zone == zone.id) {
                                val left = date.start(zone)
                                val right = date.plusDays(1).start(zone)
                                val joined = unionSpan(oldSpan?.let { Span(it.fromMs, it.endMs) },
                                    Span(maxOf(left, bridge.from), minOf(right, bridge.end)))
                                spans += Cover(name, zone.id, joined.from, joined.end, joined.covers(left, right))
                            }
                        }
                        dao.saveCoverage(spans)
                    }
                    dao.saveState(Checkpoint(lastSuccess = now, anchor = lastEvent?.takeIf { now - it <= Duration.ofHours(30).toMillis() },
                        zone = zone.id, cutOff = cutoff))
                }
                if (values.isEmpty()) "已检查系统记录，当前窗口未发现可归档的 APP 使用。"
                else "已刷新本地档案。"
            } catch (_: SecurityException) {
                gap("系统拒绝读取使用情况，请检查授权。已归档数据仍可查看。")
            }
        }
    }

    private suspend fun markMixedDay(date: LocalDate, zone: ZoneId) {
        val day = date.toString()
        val prior = dao.priorCoverage(day, day).firstOrNull()
        if (prior != null || dao.existing(day, day).isNotEmpty())
            dao.saveCoverage(listOf(Cover(day, "mixed", date.start(zone), date.start(zone), false)))
    }

    private fun readSignals(start: Long, end: Long): List<Signal> {
        val raw = manager.queryEvents(start, end) ?: return emptyList()
        val event = UsageEvents.Event()
        val result = mutableListOf<Signal>()
        while (raw.hasNextEvent()) {
            if (!raw.getNextEvent(event)) break
            val kind = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> Kind.RESUME
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> Kind.PAUSE
                UsageEvents.Event.KEYGUARD_SHOWN, UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.DEVICE_SHUTDOWN -> Kind.BARRIER
                UsageEvents.Event.DEVICE_STARTUP -> Kind.BOOT
                else -> Kind.OTHER
            }
            if (event.timeStamp in start until end)
                result += Signal(event.timeStamp, event.packageName.orEmpty(), event.className.orEmpty(), kind)
        }
        return result.sortedBy { it.at }
    }

    private fun loadCatalog(pkg: String, old: Catalog?): Catalog? {
        val pm = context.packageManager
        val info = try { pm.getApplicationInfo(pkg, 0) } catch (_: PackageManager.NameNotFoundException) {
            return if (old == null) Catalog(pkg, pkg, null, null) else null
        }
        val label = info.loadLabel(pm).toString().ifBlank { pkg }
        val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
        if (old?.label == label && old.system == system && old.icon != null) return null
        val icon = runCatching {
            val graphic = info.loadIcon(pm)
            val bitmap = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
            graphic.setBounds(0, 0, 72, 72)
            graphic.draw(Canvas(bitmap))
            ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        }.getOrNull()
        return Catalog(pkg, label, system, icon ?: old?.icon)
    }

    companion object { private val lock = Mutex() }
}
