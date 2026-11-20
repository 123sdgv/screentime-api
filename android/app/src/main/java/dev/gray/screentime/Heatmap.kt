package dev.gray.screentime

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun Heatmap(pkg: String, year: Int, today: LocalDate, rows: List<Daily>, covers: List<Cover>) {
    var byTime by rememberSaveable { mutableStateOf(false) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val days = remember(rows) { rows.associateBy { it.date } }
    val full = remember(covers) { covers.filter { it.complete }.mapTo(hashSetOf()) { it.date } }
    val partial = remember(covers) { covers.filterNot { it.complete }.mapTo(hashSetOf()) { it.date } }
    val active = remember(rows) { rows.filter { (it.ms ?: 0) > 0 }.mapTo(hashSetOf()) { LocalDate.parse(it.date) } }
    val weeks = remember(year) { calendarWeeks(year) }
    val max = remember(rows, byTime) { rows.mapNotNull { metricValue(it, false, byTime) }.maxOrNull() ?: 1L }
    val sum = remember(rows) { rows.mapNotNull { it.ms }.takeIf { it.isNotEmpty() }?.sum() }
    val entries = remember(rows) { rows.mapNotNull { it.entries?.toLong() }.takeIf { it.isNotEmpty() }?.sum() }
    val dark = isSystemInDarkTheme()
    val greens = if (dark) listOf(0xff23332b, 0xff265640, 0xff36815a, 0xff65af80, 0xffa0deb4)
        else listOf(0xffe8f1e9, 0xffb3dcbf, 0xff74bb91, 0xff39895f, 0xff155539)
    val unknown = if (dark) Color(0xff393d40) else Color(0xffe2e4e6)
    val position = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var months by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(formatTime(sum), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("已记录 ${active.size} 个活跃日 · 前台进入 ${entries ?: "未知"} 次（估算）")
        if (active.isNotEmpty()) Text("已知最长连续活跃 ${longestStreak(active)} 天")
        Text(pkg, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(!byTime, { byTime = false }, { Text("进入次数") })
            Spacer(Modifier.width(5.dp))
            FilterChip(byTime, { byTime = true }, { Text("使用时长") })
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick = { months = true }) { Text("定位月份") }
                DropdownMenu(months, { months = false }) {
                    (1..12).forEach { month ->
                        DropdownMenuItem(text = { Text("${month} 月") }, onClick = {
                            months = false
                            val index = weeks.indexOfFirst { week -> week.any { it?.monthValue == month } }
                            scope.launch { position.scrollToItem(index.coerceAtLeast(0)) }
                        })
                    }
                }
            }
        }
        Text("颜色按这个 APP 本年的已记录数值分档。横滑查看全年，点日期看明细。",
            style = MaterialTheme.typography.bodySmall)
        Row {
            Column(Modifier.width(28.dp)) {
                Spacer(Modifier.height(23.dp))
                listOf("一","二","三","四","五","六","日").forEach {
                    Box(Modifier.height(43.dp), contentAlignment = Alignment.Center) { Text(it, fontSize = 11.sp) }
                }
            }
            LazyRow(state = position) {
                itemsIndexed(weeks) { _, week ->
                    Column {
                        val month = week.firstOrNull { it?.dayOfMonth == 1 }?.monthValue
                        Box(Modifier.size(43.dp, 23.dp)) { if (month != null) Text("${month}月", fontSize = 12.sp) }
                        week.forEach { day ->
                            if (day == null || day > today) Spacer(Modifier.size(43.dp))
                            else {
                                val date = day.toString()
                                val complete = date in full
                                val row = days[date]
                                val value = metricValue(row, complete, byTime)
                                val level = when {
                                    value == null || value == 0L -> 0
                                    else -> kotlin.math.ceil(4.0 * value / max.coerceAtLeast(1)).toInt().coerceIn(1, 4)
                                }
                                val label = if (value == null) "未知" else if (byTime) formatTime(value) else "${value} 次"
                                val status = if (complete) "整日覆盖" else if (row != null || date in partial) "部分采集" else "未采集"
                                Box(Modifier.size(43.dp).clickable { chosen = date }
                                    .semantics { contentDescription = "$date，$label，$status" }, contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(4.dp))
                                        .background(if (value == null) unknown else Color(greens[level])),
                                        contentAlignment = Alignment.Center) {
                                        if (value == null) Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        else if (value == 0L) Text("0", fontSize = 10.sp,
                                            color = if (dark) Color.White else Color(0xff46594a))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("0 ", fontSize = 12.sp)
            greens.forEach { Box(Modifier.padding(horizontal = 1.dp).size(14.dp).background(Color(it))) }
            Text(" 高     · 未知     空白 未来", fontSize = 12.sp)
        }
        Text("有记录的数值可能只覆盖部分时段。未获证实的日期保留为未知。",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
    }
    chosen?.let { date ->
        val row = days[date]
        val complete = date in full
        val ms = metricValue(row, complete, true)
        val count = metricValue(row, complete, false)
        val status = if (complete) "整日覆盖（按连续观测判断）" else if (row != null || date in partial) "部分采集" else "未采集／未知"
        val source = when (row?.source) { "system" -> "系统日汇总"; "events" -> "前后台事件"; else -> "无单独记录" }
        AlertDialog(onDismissRequest = { chosen = null }, title = { Text(date) },
            text = { Text("累计时长　${formatTime(ms)}\n前台进入　${count ?: "未知"} 次（估算）\n采集状态　$status\n时长来源　$source") },
            confirmButton = { TextButton(onClick = { chosen = null }) { Text("关闭") } })
    }
}
