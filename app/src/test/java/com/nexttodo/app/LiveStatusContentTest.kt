package com.nexttodo.app

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LiveStatusContentTest {
    private val day = LocalDate.of(2026, 11, 27)
    private val course = CourseSlot("企业级应用开发（JavaEE）", day.dayOfWeek.value, 3, 3, "王老师", "教学楼 301", "1-20")
    private fun table(vararg courses: CourseSlot) = Timetable("test", "课表", day.monday(), courses.toList())
    private fun snapshot(time: String, table: Timetable? = table(course), events: List<Event> = emptyList(),
        timer: PomodoroState? = null, courses: Boolean = true, tasks: Boolean = true, timers: Boolean = true): LiveSnapshot? =
        buildLiveSnapshot(LocalDateTime.parse("${day}T$time"), table, events, timer, courses, tasks, timers)
    private fun millis(time: String) = LocalDateTime.parse("${day}T$time").atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun upcomingCourseShowsNameTimePeriodAndHourMinuteCountdown() {
        val state = checkNotNull(snapshot("08:20"))
        assertEquals(course.name, state.title)
        assertEquals("10:00–10:45 · 第3节\n地点 · 教学楼 301", state.detail)
        assertEquals("距上课 1小时40分钟", liveCapsuleLabel(state, millis("08:20")))
    }

    @Test fun activeCourseCountsDownToEndWithoutRepeatingTaskStatistics() {
        val event = Event("task", title = "取快递", day = day)
        val state = checkNotNull(snapshot("10:15", events = listOf(event)))
        assertEquals("距下课 30分钟", liveCapsuleLabel(state, millis("10:15")))
        assertFalse(state.detail.contains("待办"))
        assertFalse(state.detail.contains("剩余"))
        assertEquals(millis("10:45"), state.deadline)
    }

    @Test fun classBoundarySwitchesToNextCourse() {
        val next = course.copy(name = "数据库", firstPeriod = 4, lastPeriod = 4)
        val state = checkNotNull(snapshot("10:45", table(course, next)))
        assertEquals("数据库", state.title)
        assertEquals("距上课 10分钟", liveCapsuleLabel(state, millis("10:45")))
    }

    @Test fun multiPeriodCourseUsesHoursAndPeriodRange() {
        val state = checkNotNull(snapshot("14:10", table(course.copy(firstPeriod = 5, lastPeriod = 6))))
        assertEquals("距下课 1小时30分钟", liveCapsuleLabel(state, millis("14:10")))
        assertTrue(state.detail.startsWith("14:00–15:40 · 第5–6节"))
    }

    @Test fun customCourseTimeOverridesDefaultPeriodTime() {
        val state = checkNotNull(snapshot("09:20", table(course.copy(customTime = "09:10-11:20"))))
        assertTrue(state.detail.startsWith("09:10–11:20"))
        assertEquals("距下课 2小时0分钟", liveCapsuleLabel(state, millis("09:20")))
    }

    @Test fun untimedOrInvalidCourseDoesNotInventADeadline() {
        val state = checkNotNull(snapshot("08:20", table(course.copy(firstPeriod = null, lastPeriod = null))))
        assertNull(state.deadline)
        assertTrue(state.detail.contains("上课时间待确认"))
        assertEquals("课程时间待确认", liveCapsuleLabel(state, millis("08:20")))
    }

    @Test fun inactiveWeekAndDisabledCoursesDoNotAppear() {
        assertEquals("today", snapshot("08:20", table(course.copy(weeks = "2-20")))?.target)
        assertEquals("today", snapshot("08:20", courses = false)?.target)
        assertNull(snapshot("08:20", table = null, tasks = false))
    }

    @Test fun tasksOnlyCountLeavesAndShowTaskNamesOnce() {
        val parent = Event("root", title = "复习计划", day = day)
        val a = parent.newChild().copy(title = "准备资料")
        val b = parent.newChild().copy(title = "完成练习", completed = true)
        val state = checkNotNull(snapshot("12:00", events = listOf(parent, a, b)))
        assertEquals("今日待办 · 1项", state.title)
        assertEquals("准备资料", state.detail)
        assertEquals(state.title, liveCapsuleLabel(state, millis("12:00")))
    }

    @Test fun runningTimerWinsButDoesNotMixInTaskCounts() {
        val timer = PomodoroState(PomodoroPhase.FOCUS, 1, 24 * 60_000L + 58_000, true, PomodoroDurations())
        val state = checkNotNull(snapshot("10:15", timer = timer))
        assertEquals("timer", state.target)
        assertEquals("专注中 24:58", liveCapsuleLabel(state, millis("10:15")))
        assertFalse(state.detail.contains("待办"))
        assertTrue(state.runningTimer)
    }

    @Test fun pausedTimerDoesNotBlockCourseAndRetainsRemainingTime() {
        val timer = PomodoroState(PomodoroPhase.FOCUS, 1, 12 * 60_000L + 34_000, false, PomodoroDurations())
        assertEquals("courses", snapshot("10:15", timer = timer)?.target)
        val paused = checkNotNull(snapshot("12:00", timer = timer))
        assertEquals("专注已暂停 12:34", liveCapsuleLabel(paused, millis("12:00")))
        assertNull(paused.deadline)
    }

    @Test fun longShortAndExpiredCountdownsAreReadable() {
        assertEquals("1小时20分钟", liveRemainingLabel(80 * 60_000L))
        assertEquals("1小时0分钟", liveRemainingLabel(60 * 60_000L))
        assertEquals("59分钟", liveRemainingLabel(59 * 60_000L))
        assertEquals("不足1分钟", liveRemainingLabel(59_000))
        assertEquals("0分钟", liveRemainingLabel(-1))
        assertEquals("0:30", liveRemainingLabel(30_000, true))
        val state = checkNotNull(snapshot("10:15"))
        assertEquals("课程切换中", liveCapsuleLabel(state, millis("10:45")))
    }
}
