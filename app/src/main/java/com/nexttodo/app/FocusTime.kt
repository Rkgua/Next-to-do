package com.nexttodo.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class FocusKind(val label: String) { FOCUS("番茄专注"), REST("休息"), LOCK("屏幕固定") }
internal data class FocusSegment(val id: String, val session: String, val start: Long, val end: Long,
    val kind: FocusKind, val completed: Boolean = false)
internal data class FocusTotals(val focus: Long = 0, val rest: Long = 0, val locked: Long = 0,
    val sessions: Int = 0, val completed: Int = 0) {
    val total: Long get() = focus + rest + locked
    val focused: Long get() = focus + locked
}

internal fun focusTotals(segments: List<FocusSegment>, start: Long = Long.MIN_VALUE, end: Long = Long.MAX_VALUE): FocusTotals {
    val selected = segments.distinctBy { it.id }.mapNotNull { record ->
        val duration = (minOf(record.end, end) - maxOf(record.start, start)).coerceAtLeast(0)
        if (duration > 0) record to duration else null
    }
    fun sum(kind: FocusKind) = selected.filter { it.first.kind == kind }.sumOf { it.second }
    val focus = selected.filter { it.first.kind != FocusKind.REST }.map { it.first }
    return FocusTotals(sum(FocusKind.FOCUS), sum(FocusKind.REST), sum(FocusKind.LOCK),
        focus.map { it.session }.distinct().size, focus.filter { it.completed }.map { it.session }.distinct().size)
}

internal fun dayBounds(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> =
    day.atStartOfDay(zone).toInstant().toEpochMilli() to day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

internal fun dailyFocus(segments: List<FocusSegment>, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): FocusTotals =
    dayBounds(day, zone).let { focusTotals(segments, it.first, it.second) }

internal fun hourlyFocus(segments: List<FocusSegment>, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Long> =
    (0..23).map { hour ->
        val start = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val end = day.atTime(hour, 0).plusHours(1).atZone(zone).toInstant().toEpochMilli()
        focusTotals(segments, start, end).focused
    }

internal fun focusActiveDays(segments: List<FocusSegment>, zone: ZoneId = ZoneId.systemDefault()): Int {
    val days = mutableSetOf<LocalDate>()
    segments.filter { it.kind != FocusKind.REST && it.end > it.start }.forEach { record ->
        var day = Instant.ofEpochMilli(record.start).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(record.end - 1).atZone(zone).toLocalDate()
        while (!day.isAfter(last)) { days += day; day = day.plusDays(1) }
    }
    return days.size
}

internal fun focusDurationLabel(milliseconds: Long): String = when {
    milliseconds <= 0 -> "0 分钟"
    milliseconds < 60_000 -> "不足 1 分钟"
    milliseconds < 3_600_000 -> "${milliseconds / 60_000} 分钟"
    else -> "${milliseconds / 3_600_000} 小时 ${milliseconds / 60_000 % 60} 分钟"
}
