package dev.gray.screentime

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ScreenTime(model: ScreenModel, authorize: () -> Unit) {
    val today by model.today.collectAsStateWithLifecycle()
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    var activePkg by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var systems by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var daysUnit by rememberSaveable { mutableStateOf(false) }
    val dao = model.dao
    val permitted by model.permission.collectAsStateWithLifecycle()
    val busy by model.working.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val catalogs by remember(dao) { dao.catalog() }.collectAsStateWithLifecycle(emptyList())
    val names = remember(catalogs) { catalogs.associateBy { it.pkg } }
    BackHandler(settings || activePkg != null) { if (settings) settings = false else activePkg = null }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        if (settings) {
            SettingsPage(model, permitted, busy, notice, systems, { systems = it }, authorize) { settings = false }
        } else {
            if (activePkg != null) TextButton(onClick = { activePkg = null }) { Text("返回年度总览") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (activePkg != null) AppIcon(names[activePkg], Modifier.padding(end = 8.dp))
                Text(if (activePkg == null) "我的数字生活" else names[activePkg]?.label ?: activePkg.orEmpty(),
                    Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { settings = true }) { Text("设置") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { year-- }, enabled = year > 1970) { Text("上一年") }
                Text("${year} 年", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { year++ }, enabled = year < today.year) { Text("下一年") }
                Spacer(Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = model::refresh) { Text(if (busy) "采集中" else "刷新") }
            }
            if (!permitted) TextButton(onClick = authorize) { Text("使用情况访问未开启 · 去授权") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            key(year) {
                val first = "${year}-01-01"
                val last = "${year}-12-31"
                val loaded by remember(dao, year) { dao.year(first, last) }.collectAsStateWithLifecycle(initialValue = null)
                val coverage by remember(dao, year) { dao.coverage(first, last) }.collectAsStateWithLifecycle(initialValue = null)
                val rows = loaded
                val covers = coverage
                if (rows == null || covers == null) Text("正在读取本地档案…")
                else if (activePkg != null) {
                    val pkg = activePkg.orEmpty()
                    key(pkg) { Heatmap(pkg, year, today, remember(rows, pkg) { rows.filter { it.pkg == pkg } }, covers) }
                } else {
                    val all = remember(rows, names, systems) { totals(rows, names).filter { systems || it.system != true } }
                    val order = remember(all, query, sort) { ranked(all.filter {
                        it.label.contains(query.trim(), true) || it.pkg.contains(query.trim(), true)
                    }, sort) }
                    val total = remember(all) { all.mapNotNull { it.time }.takeIf { it.isNotEmpty() }?.sum() }
                    val entries = remember(all) { all.mapNotNull { it.count }.takeIf { it.isNotEmpty() }?.sum() }
                    val activeDays = remember(all) { all.flatMap { it.days }.toSet().size }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                        item {
                            Text("年度累计使用时长", color = MaterialTheme.colorScheme.primary)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(formatTime(total, daysUnit), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.SemiBold)
                                TextButton(onClick = { daysUnit = !daysUnit }) { Text(if (daysUnit) "按小时" else "按天换算") }
                            }
                            val evidence = all.isNotEmpty() || covers.any { it.complete }
                            Text("已记录活跃 ${if (evidence) activeDays else "—"} 天  ·  前台进入 ${entries ?: "未知"}（估算）  ·  ${if (evidence) all.size else "—"} 个 APP",
                                style = MaterialTheme.typography.bodyMedium)
                            Text("完整覆盖 ${covers.count { it.complete }} 天 · " + if (systems) "包含系统应用" else "已隐藏系统应用",
                                style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(16.dp))
                            Text("APP 排行榜", style = MaterialTheme.typography.titleLarge)
                            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                                label = { Text("搜索名称或包名") })
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("时长", "活跃天数", "进入次数").forEachIndexed { index, label ->
                                    FilterChip(sort == index, { sort = index }, { Text(label) })
                                }
                            }
                            if (order.isEmpty()) {
                                val message = when {
                                    query.isNotBlank() -> "没有匹配的 APP。"
                                    rows.isNotEmpty() -> "当前筛选下没有 APP。可在设置中显示系统应用。"
                                    !permitted -> "授权后回到这里，手机会尝试读取近期记录。更早的日期会保留为未知。"
                                    else -> "这一年尚无可展示的记录。系统通常只保留近期事件，首次授权无法恢复全年。"
                                }
                                Text(message, Modifier.padding(vertical = 12.dp))
                            }
                            if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
                        }
                        items(order, key = { it.pkg }) { app ->
                            Row(Modifier.fillMaxWidth().clickable { activePkg = app.pkg }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(names[app.pkg])
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    Text("${formatTime(app.time)} · ${app.days.size} 个活跃日 · ${app.count ?: "未知"} 次（估算）",
                                        style = MaterialTheme.typography.bodySmall)
                                    val share = if (total != null && total > 0) (app.time ?: 0).toFloat() / total else 0f
                                    LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppIcon(app: Catalog?, modifier: Modifier = Modifier) {
    val icon = remember(app) { app?.icon?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    Box(modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, null, Modifier.fillMaxSize())
        else Text(app?.label?.take(1).orEmpty())
    }
}

@Composable
private fun SettingsPage(model: ScreenModel, permitted: Boolean, busy: Boolean, notice: String,
    systems: Boolean, changeFilter: (Boolean) -> Unit, authorize: () -> Unit, back: () -> Unit) {
    val checkpoint by remember { model.dao.status() }.collectAsStateWithLifecycle(null)
    val first by remember { model.dao.firstDay() }.collectAsStateWithLifecycle(null)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { TextButton(onClick = back) { Text("返回") }; Text("设置", style = MaterialTheme.typography.headlineMedium) }
        item {
            Text("使用情况访问权限 · " + if (permitted) "已授权" else "未授权")
            TextButton(onClick = authorize) { Text("打开系统权限设置") }
        }
        item {
            val at = checkpoint?.lastSuccess?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) } ?: "尚无成功采集"
            Text("上次成功采集　$at"); Text("最早归档日期　${first ?: "尚无数据"}")
            TextButton(enabled = !busy, onClick = model::refresh) { Text(if (busy) "采集中…" else "立即补采") }
            Text(notice)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("显示系统应用", Modifier.weight(1f)); Switch(systems, changeFilter)
            }
            Text("按 Android 的系统应用标记筛选。识别不到的包名仍显示。筛选同时作用于总览和排行榜。",
                style = MaterialTheme.typography.bodySmall)
        }
        item {
            HorizontalDivider(); Spacer(Modifier.height(12.dp))
            Text("数据只保存在本机。此原型在打开或刷新时采集，建议每天打开。结束异常、权限变化或长时间未采集都可能留下空缺。")
            Spacer(Modifier.height(8.dp))
            Text("前台进入次数按会话估算，同一 APP 内切页会去重。分屏时各 APP 的时长可能重叠。当天与回填的历史通常只有部分记录。")
        }
    }
}
