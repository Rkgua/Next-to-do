package com.nexttodo.app

import java.time.ZonedDateTime

internal data class FocusPlan(val id: String, val title: String, val hour: Int, val minute: Int,
    val duration: Int, val days: Set<Int> = (1..7).toSet(), val enabled: Boolean = true) {
    init { require(hour in 0..23 && minute in 0..59 && duration in 1..180 && days.isNotEmpty() && days.all { it in 1..7 }) }
    val timeLabel: String get() = "%02d:%02d".format(hour, minute)
    val repeatLabel: String get() = if (days.size == 7) "每天" else days.sorted().joinToString(" ") { "周${"一二三四五六日"[it - 1]}" }
}

internal fun nextFocusPlan(plan: FocusPlan, after: ZonedDateTime): ZonedDateTime? {
    if (!plan.enabled) return null
    return (0..7).asSequence().map {
        after.toLocalDate().plusDays(it.toLong()).atTime(plan.hour, plan.minute).atZone(after.zone)
    }.firstOrNull { it.dayOfWeek.value in plan.days && it.isAfter(after) }
}
