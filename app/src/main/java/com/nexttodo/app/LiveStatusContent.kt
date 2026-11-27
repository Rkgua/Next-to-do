package com.nexttodo.app

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class LiveSnapshot(val title: String, val detail: String, val target: String,
    val deadline: Long? = null, val started: Long? = null, val runningTimer: Boolean = false,
    val countdownLabel: String? = null, val remainingMillis: Long? = null)

/** Approximate course countdowns use hours and minutes; short timers retain seconds. */
internal fun liveRemainingLabel(milliseconds: Long, showSeconds: Boolean = false): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1000) + if (milliseconds > 0 && milliseconds % 1000 != 0L) 1 else 0
    return when {
        seconds >= 3600 -> "${seconds / 3600}小时${seconds / 60 % 60}分钟"
        showSeconds -> "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        seconds == 0L -> "0分钟"
        seconds < 60 -> "不足1分钟"
        else -> "${seconds / 60}分钟"
    }
}

internal fun liveCapsuleLabel(state: LiveSnapshot, now: Long): String {
    val remaining = state.deadline?.let { (it - now).coerceAtLeast(0) } ?: state.remainingMillis
    if (state.target == "courses" && state.deadline != null && remaining == 0L) return "课程切换中"
    val heading = state.countdownLabel ?: state.title
    return remaining?.let { "$heading ${liveRemainingLabel(it, showSeconds = state.target == "timer")}" } ?: heading
}

private data class LiveCourse(val course: CourseSlot, val start: LocalDateTime, val end: LocalDateTime)
private val liveCourseClock = DateTimeFormatter.ofPattern("HH:mm")

internal fun buildLiveSnapshot(now: LocalDateTime, table: Timetable?, events: List<Event>, timer: PomodoroState?,
    coursesEnabled: Boolean, tasksEnabled: Boolean, timerEnabled: Boolean): LiveSnapshot? {
    val zone = ZoneId.systemDefault()
    val millis = now.atZone(zone).toInstant().toEpochMilli()
    val today = now.toLocalDate()
    val timerSnapshot = timer?.takeIf { timerEnabled && (it.running || it.remaining < it.total) }?.let {
        LiveSnapshot("${it.phase.label}${if (it.running) "中" else "已暂停"}",
            "本轮 ${liveRemainingLabel(it.total)}\n已完成 ${it.completedFocus.coerceIn(0, 4)}/4 轮专注", "timer",
            if (it.running) millis + it.remaining else null,
            if (it.running) millis + it.remaining - it.total else null, it.running,
            remainingMillis = it.remaining.coerceAtLeast(0))
    }
    if (timerSnapshot?.runningTimer == true) return timerSnapshot
    if (coursesEnabled && table != null) {
        val week = table.currentWeek(today)
        val todayCourses = table.courses.filter { it.weekday == today.dayOfWeek.value && it.takesPlaceIn(week) && week >= 1 }
        val sessions = todayCourses.mapNotNull { course ->
            val custom = Regex("(\\d{1,2}):(\\d{2})\\s*[-—–~～至]\\s*(\\d{1,2}):(\\d{2})").find(course.customTime)
            val clocks = if (custom != null) runCatching {
                LocalTime.of(custom.groupValues[1].toInt(), custom.groupValues[2].toInt()) to
                    LocalTime.of(custom.groupValues[3].toInt(), custom.groupValues[4].toInt())
            }.getOrNull() else course.firstPeriod?.let { first -> course.lastPeriod?.let { last ->
                periodTimes.getOrNull(first - 1)?.first?.let { start -> periodTimes.getOrNull(last - 1)?.second?.let { end ->
                    LocalTime.parse(start) to LocalTime.parse(end)
                } }
            } }
            clocks?.takeIf { it.second > it.first }?.let { LiveCourse(course, today.atTime(it.first), today.atTime(it.second)) }
        }.sortedBy { it.start }
        val active = sessions.firstOrNull { now >= it.start && now < it.end }
        val upcoming = sessions.firstOrNull { it.start > now }
        val item = active ?: upcoming
        if (item != null) {
            val course = item.course
            val period = course.firstPeriod?.takeIf { it > 0 }?.let { first ->
                course.lastPeriod?.takeIf { it >= first }?.let { last -> if (first == last) "第${first}节" else "第${first}–${last}节" }
            }
            val schedule = listOfNotNull("${item.start.format(liveCourseClock)}–${item.end.format(liveCourseClock)}", period).joinToString(" · ")
            val status = if (active != null) "距下课" else "距上课"
            return LiveSnapshot(course.name, listOfNotNull(schedule, course.room.takeIf { it.isNotBlank() }?.let { "地点 · $it" }).joinToString("\n"),
                "courses", (if (active != null) item.end else item.start).atZone(zone).toInstant().toEpochMilli(),
                if (active != null) item.start.atZone(zone).toInstant().toEpochMilli() else null, countdownLabel = status)
        }
        val untimed = todayCourses.filter { course -> sessions.none { it.course == course } }
        if (untimed.isNotEmpty()) return LiveSnapshot(untimed.first().name,
            listOfNotNull("上课时间待确认", untimed.first().room.takeIf { it.isNotBlank() },
                "另有 ${untimed.size - 1} 门课待确认".takeIf { untimed.size > 1 }).joinToString("\n"),
            "courses", countdownLabel = "课程时间待确认")
    }
    if (timerSnapshot != null) return timerSnapshot
    if (tasksEnabled) {
        val pending = visibleEventLeaves(events.mapNotNull { it.onDate(today, today) }, events)
            .filterNot { it.completed }.sortedByDescending { it.pinned }
        return LiveSnapshot(if (pending.isEmpty()) "今日待办已完成" else "今日待办 · ${pending.size}项",
            pending.take(3).joinToString("\n") { it.title }.ifBlank { "今天没有待完成事件" }, "today")
    }
    return null
}
