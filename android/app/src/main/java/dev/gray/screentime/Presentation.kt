package dev.gray.screentime

import java.time.LocalDate

internal data class AppTotal(val pkg: String, val label: String, val system: Boolean?,
    val time: Long?, val count: Long?, val days: Set<LocalDate>)

internal fun totals(rows: List<Daily>, apps: Map<String, Catalog>): List<AppTotal> =
    rows.groupBy { it.pkg }.map { (pkg, records) ->
        val app = apps[pkg]
        AppTotal(pkg, app?.label ?: pkg, app?.system,
            records.mapNotNull { it.ms }.takeIf { it.isNotEmpty() }?.sum(),
            records.mapNotNull { it.entries?.toLong() }.takeIf { it.isNotEmpty() }?.sum(),
            records.filter { (it.ms ?: 0) > 0 }.mapTo(mutableSetOf()) { LocalDate.parse(it.date) })
    }

internal fun ranked(apps: List<AppTotal>, sort: Int): List<AppTotal> = apps.sortedWith(
    compareByDescending<AppTotal> { when (sort) { 1 -> it.days.size.toLong(); 2 -> it.count ?: -1L; else -> it.time ?: -1L } }
        .thenBy { it.label }.thenBy { it.pkg })

internal fun formatTime(ms: Long?, asDays: Boolean = false): String {
    if (ms == null) return "未知"
    if (ms == 0L) return "0 分钟"
    val minutes = ms / 60_000
    val hours = minutes / 60
    return when {
        minutes == 0L -> "不足 1 分钟"
        asDays && hours >= 24 -> "${hours / 24} 天 ${hours % 24} 小时"
        hours > 0 -> "${hours} 小时 ${minutes % 60} 分"
        else -> "${minutes} 分钟"
    }
}

// A recorded entry without a closed interval has an unknown duration, even on a covered day.
internal fun metricValue(row: Daily?, complete: Boolean, byTime: Boolean): Long? {
    val value = if (byTime) row?.ms else row?.entries?.toLong()
    if (value != null) return value
    if (byTime && (row?.entries ?: 0) > 0) return null
    return if (complete) 0 else null
}
