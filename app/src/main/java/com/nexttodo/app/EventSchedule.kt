package com.nexttodo.app

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

enum class EventRepeat(val label: String) {
    NONE("只发生一次"), DAILY("每天"), WEEKLY("每周"), MONTHLY("每月"),
    WEEKDAYS("周一至周五"), WEEKENDS("周六、周日"), SUNDAY_OFF("单休（仅休周日）"),
    BIWEEKLY("每两周"), CUSTOM("自定义星期"), ROTATION("轮班周期"),
    WORKDAYS("法定工作日（含调休）"), HOLIDAYS("节假日及周末（除调休）"), ALTERNATING("大小周");
}

internal fun Event.startDay(today: LocalDate = LocalDate.now()): LocalDate = calendarDay ?: today
internal fun Event.finishDay(today: LocalDate = LocalDate.now()): LocalDate =
    endTime.takeIf { it.isNotBlank() }?.let { LocalDateTime.parse(it).toLocalDate() } ?: endDay ?: startDay(today)

internal fun Event.occurrenceStart(day: LocalDate, today: LocalDate = LocalDate.now()): LocalDate? {
    val start = startDay(today)
    if (day < start) return null
    val duration = ChronoUnit.DAYS.between(start, finishDay(today)).coerceIn(0, 3660)
    if (repetition == EventRepeat.NONE) return start.takeIf { day <= start.plusDays(duration) }
    for (offset in 0..duration) {
        val candidate = day.minusDays(offset)
        if (candidate < start) break
        val days = repeatDays.ifEmpty { setOf(start.dayOfWeek.value) }
        val matches = when (repetition) {
            EventRepeat.NONE -> false
            EventRepeat.DAILY -> true
            EventRepeat.WEEKLY, EventRepeat.CUSTOM -> candidate.dayOfWeek.value in days
            EventRepeat.MONTHLY -> candidate.dayOfMonth == minOf(start.dayOfMonth, candidate.lengthOfMonth())
            EventRepeat.WEEKDAYS -> candidate.dayOfWeek.value <= 5
            EventRepeat.WEEKENDS -> candidate.dayOfWeek.value >= 6
            EventRepeat.SUNDAY_OFF -> candidate.dayOfWeek.value != 7
            EventRepeat.BIWEEKLY -> candidate.dayOfWeek.value in days &&
                ChronoUnit.WEEKS.between(start.monday(), candidate.monday()) % 2L == 0L
            EventRepeat.ROTATION -> ChronoUnit.DAYS.between(start, candidate) % repeatInterval.coerceIn(1, 365) < repeatWorkDays.coerceIn(1, repeatInterval.coerceIn(1, 365))
            EventRepeat.WORKDAYS -> ChinaWorkCalendar.isWorkday(candidate) == true
            EventRepeat.HOLIDAYS -> ChinaWorkCalendar.isWorkday(candidate) == false
            EventRepeat.ALTERNATING -> candidate.dayOfWeek.value <= 5 ||
                (candidate.dayOfWeek.value == 6 && ChronoUnit.WEEKS.between(start.monday(), candidate.monday()) % 2L == 0L)
        }
        if (matches) return candidate
    }
    return null
}

internal fun Event.onDate(day: LocalDate, today: LocalDate = LocalDate.now()): Event? {
    val occurrence = occurrenceStart(day, today) ?: return null
    return copy(occurrenceDate = occurrence,
        completed = if (repetition == EventRepeat.NONE) completed else occurrence in completedDates)
}

internal fun Event.scheduleLabel(): String {
    val shift = occurrenceDate?.let { ChronoUnit.DAYS.between(startDay(), it) } ?: 0L
    return listOfNotNull(
        time.takeIf(String::isNotBlank)?.let { eventTimeLabel(LocalDateTime.parse(it).plusDays(shift).toString()) } ?: day?.plusDays(shift)?.toString(),
        endTime.takeIf(String::isNotBlank)?.let { "至 ${eventTimeLabel(LocalDateTime.parse(it).plusDays(shift).toString())}" } ?: endDay?.plusDays(shift)?.let { "至 $it" },
        repetition.takeUnless { it == EventRepeat.NONE }?.label,
    ).joinToString(" · ")
}

internal fun Event.validateSchedule(): String? {
    if (repetition == EventRepeat.ROTATION && (repeatInterval !in 1..365 || repeatWorkDays !in 1..repeatInterval))
        return "轮班工作天数需要在周期范围内"
    if (repeatDays.any { it !in 1..7 }) return "重复星期需要在周一至周日范围内"
    val start = startDay()
    val end = finishDay()
    if (end < start) return "结束日期不能早于开始日期"
    if (ChronoUnit.DAYS.between(start, end) > 3660) return "单次持续时间请设在十年以内"
    if (time.isNotBlank() && endTime.isNotBlank() && LocalDateTime.parse(endTime) <= LocalDateTime.parse(time))
        return "结束时间需要晚于开始时间"
    return null
}

internal fun Event.nextReminderAfter(now: LocalDateTime): LocalDateTime? {
    if (!reminderEnabled || time.isBlank() || (repetition == EventRepeat.NONE && completed)) return null
    val clock = LocalDateTime.parse(time).toLocalTime()
    for (offset in 0L..3660L) {
        val day = now.toLocalDate().plusDays(offset)
        val start = occurrenceStart(day) ?: continue
        if (start != day || (repetition != EventRepeat.NONE && start in completedDates)) continue
        val at = day.atTime(clock)
        if (at > now) return at
        if (repetition == EventRepeat.NONE) return null
    }
    return null
}

internal fun periodEvents(events: List<Event>, start: LocalDate, end: LocalDate): List<Event> =
    (0..ChronoUnit.DAYS.between(start, end)).flatMap { offset -> events.mapNotNull { it.onDate(start.plusDays(offset)) } }
        .distinctBy { it.id to it.occurrenceDate }
