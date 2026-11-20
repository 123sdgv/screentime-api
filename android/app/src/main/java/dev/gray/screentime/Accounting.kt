package dev.gray.screentime

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal fun Long.dateIn(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
internal fun LocalDate.start(zone: ZoneId): Long = atStartOfDay(zone).toInstant().toEpochMilli()

internal enum class Kind { RESUME, PAUSE, BARRIER, BOOT, OTHER }
internal data class Signal(val at: Long, val pkg: String, val activity: String, val kind: Kind)
internal data class Key(val pkg: String, val day: LocalDate)
internal data class Evidence(val millis: Long = 0, val starts: Int = 0)

internal fun splitInterval(from: Long, until: Long, zone: ZoneId, visit: (LocalDate, Long) -> Unit) {
    var cursor = from
    while (cursor < until) {
        val day = cursor.dateIn(zone)
        val next = minOf(until, day.plusDays(1).start(zone))
        visit(day, next - cursor)
        cursor = next
    }
}

internal data class EventResult(val records: Map<Key, Evidence>, val pendingFrom: Long?)

/** Only closed, observed intervals contribute duration. Each package can own several resumed activities. */
internal fun reduceEvents(events: List<Signal>, zone: ZoneId): EventResult {
    data class Open(val from: Long, val activities: MutableSet<String>)
    val open = mutableMapOf<String, Open>()
    val recentlyClosed = mutableMapOf<String, Long>()
    val values = mutableMapOf<Key, Evidence>()
    var lastResumed: String? = null
    fun close(pkg: String, end: Long) {
        val session = open.remove(pkg) ?: return
        splitInterval(session.from, end, zone) { day, ms ->
            val key = Key(pkg, day)
            val old = values[key] ?: Evidence()
            values[key] = old.copy(millis = old.millis + ms)
        }
        recentlyClosed[pkg] = end
    }
    for (e in events) {
        when (e.kind) {
            Kind.RESUME -> {
                val session = open[e.pkg]
                if (session == null) {
                    val recent = recentlyClosed[e.pkg]
                    val handoff = lastResumed == e.pkg && recent != null && e.at - recent in 0..1500
                    if (!handoff) {
                        val key = Key(e.pkg, e.at.dateIn(zone))
                        val old = values[key] ?: Evidence()
                        values[key] = old.copy(starts = old.starts + 1)
                    }
                    open[e.pkg] = Open(e.at, mutableSetOf(e.activity))
                } else session.activities.add(e.activity)
                lastResumed = e.pkg
            }
            Kind.PAUSE -> {
                val session = open[e.pkg] ?: continue
                if (session.activities.remove(e.activity) && session.activities.isEmpty()) close(e.pkg, e.at)
            }
            Kind.BARRIER, Kind.BOOT -> {
                // After startup a missing shutdown can leave stale sessions. Never charge the reboot gap.
                if (e.kind == Kind.BARRIER) open.keys.toList().forEach { close(it, e.at) }
                open.clear()
                recentlyClosed.clear()
                lastResumed = null
            }
            Kind.OTHER -> Unit
        }
    }
    return EventResult(values, open.values.minOfOrNull { it.from })
}

/** A system bucket is usable only if its entire interval belongs to one local day. */
internal fun bucketDay(first: Long, end: Long, zone: ZoneId): LocalDate? {
    if (end <= first) return null
    return first.dateIn(zone).takeIf { it == (end - 1).dateIn(zone) }
}

internal data class Span(val from: Long, val end: Long) {
    fun covers(start: Long, until: Long) = from <= start && end >= until
}

/** Keep one contiguous span. Joining across a gap would invent full-day coverage. */
internal fun unionSpan(old: Span?, next: Span): Span = when {
    old == null -> next
    next.from <= old.end && old.from <= next.end -> Span(minOf(old.from, next.from), maxOf(old.end, next.end))
    next.end - next.from >= old.end - old.from -> next
    else -> old
}

internal data class DayValue(val ms: Long?, val entries: Int?, val source: String)
internal fun bestEvidence(old: DayValue?, fresh: DayValue): DayValue {
    if (old == null) return fresh
    // Daily system totals take precedence over reconstructed activity durations.
    val takeFresh = when {
        fresh.ms == null -> false
        old.ms == null -> true
        fresh.source != old.source -> fresh.source == "system"
        else -> fresh.ms >= old.ms
    }
    val time = if (takeFresh) fresh else old
    return DayValue(time.ms, listOfNotNull(old.entries, fresh.entries).maxOrNull(), time.source)
}

internal fun calendarWeeks(year: Int): List<List<LocalDate?>> {
    val first = LocalDate.of(year, 1, 1)
    val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
    val last = first.withDayOfYear(first.lengthOfYear())
    return generateSequence(start) { it.plusWeeks(1) }.takeWhile { it <= last }.map { monday ->
        (0L..6L).map { monday.plusDays(it).takeIf { day -> day.year == year } }
    }.toList()
}
internal fun longestStreak(days: Set<LocalDate>): Int {
    var longest = 0
    for (day in days) {
        if (day.minusDays(1) in days) continue
        var n = 1
        while (day.plusDays(n.toLong()) in days) n++
        longest = maxOf(longest, n)
    }
    return longest
}
