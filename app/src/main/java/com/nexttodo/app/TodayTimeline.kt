package com.nexttodo.app

import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val timelineStart = LocalDate.of(1900, 1, 1)
private val timelineLast = LocalDate.of(2200, 12, 31)

internal enum class TodayPeriod(val label: String, val unit: ChronoUnit) {
    DAY("每日待办", ChronoUnit.DAYS), WEEK("每周待办", ChronoUnit.WEEKS), MONTH("每月待办", ChronoUnit.MONTHS);

    fun start(day: LocalDate): LocalDate = when (this) {
        DAY -> day
        WEEK -> day.monday()
        MONTH -> day.withDayOfMonth(1)
    }

    fun end(day: LocalDate): LocalDate = when (this) {
        DAY -> day
        WEEK -> start(day).plusDays(6)
        MONTH -> day.withDayOfMonth(day.lengthOfMonth())
    }

    val pageCount: Int get() = unit.between(timelineStart, start(timelineLast)).toInt() + 1
    fun pageFor(day: LocalDate): Int = unit.between(timelineStart, start(day)).toInt().coerceIn(0, pageCount - 1)
    fun dateFor(page: Int): LocalDate = timelineStart.plus(page.toLong(), unit)

    fun relativeLabel(today: LocalDate, day: LocalDate): String {
        val distance = unit.between(start(today), start(day))
        val suffix = when (this) { DAY -> "天"; WEEK -> "周"; MONTH -> "个月" }
        return when {
            distance > 0 -> "距离 $distance $suffix"
            distance < 0 -> "已过 ${-distance} $suffix"
            else -> when (this) { DAY -> "今天"; WEEK -> "本周"; MONTH -> "本月" }
        }
    }
}
