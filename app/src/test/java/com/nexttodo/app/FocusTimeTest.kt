package com.nexttodo.app

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class FocusTimeTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(value: String) = ZonedDateTime.parse("${value}+08:00[Asia/Shanghai]").toInstant().toEpochMilli()
    private fun segment(id: String, session: String, start: String, end: String, kind: FocusKind = FocusKind.FOCUS,
        completed: Boolean = false) = FocusSegment(id, session, at(start), at(end), kind, completed)

    @Test fun pausesAreExcludedAndResumeCountsAsOneSession() {
        val records = listOf(segment("a", "one", "2026-11-21T10:00:00", "2026-11-21T10:10:00"),
            segment("b", "one", "2026-11-21T10:20:00", "2026-11-21T10:35:00", completed = true))
        assertEquals(FocusTotals(25 * 60_000L, sessions = 1, completed = 1), focusTotals(records))
    }
    @Test fun focusRestAndActualPinTimeDoNotOverlapInDonut() {
        val records = listOf(segment("a", "one", "2026-11-21T10:00:00", "2026-11-21T10:10:00"),
            segment("b", "one", "2026-11-21T10:10:00", "2026-11-21T10:25:00", FocusKind.LOCK, true),
            segment("c", "break", "2026-11-21T10:25:00", "2026-11-21T10:30:00", FocusKind.REST, true))
        val totals = focusTotals(records)
        assertEquals(30 * 60_000L, totals.total)
        assertEquals(25 * 60_000L, totals.focused)
        assertEquals(5 * 60_000L, totals.rest)
        assertEquals(1, totals.sessions)
    }
    @Test fun midnightSessionSplitsAcrossDaysWithoutDoubleCounting() {
        val records = listOf(segment("a", "one", "2026-11-21T23:50:00", "2026-11-22T00:15:00", completed = true))
        assertEquals(10 * 60_000L, dailyFocus(records, LocalDate.parse("2026-11-21"), zone).focused)
        assertEquals(15 * 60_000L, dailyFocus(records, LocalDate.parse("2026-11-22"), zone).focused)
        assertEquals(2, focusActiveDays(records, zone))
        assertEquals(25 * 60_000L, focusTotals(records).focused)
    }
    @Test fun exactMidnightEndDoesNotCreateAnExtraActiveDay() {
        val records = listOf(segment("a", "one", "2026-11-21T23:50:00", "2026-11-22T00:00:00"))
        assertEquals(1, focusActiveDays(records, zone))
        assertEquals(0, dailyFocus(records, LocalDate.parse("2026-11-22"), zone).sessions)
    }
    @Test fun duplicateFinalizedAndLiveSegmentsAreNotAddedTwice() {
        val record = segment("a", "one", "2026-11-21T10:00:00", "2026-11-21T10:10:00")
        assertEquals(10 * 60_000L, focusTotals(listOf(record, record)).focused)
    }
    @Test fun hourlyChartSplitsAtHourBoundaries() {
        val records = listOf(segment("a", "one", "2026-11-21T10:50:00", "2026-11-21T11:15:00"))
        val hours = hourlyFocus(records, LocalDate.parse("2026-11-21"), zone)
        assertEquals(10 * 60_000L, hours[10]); assertEquals(15 * 60_000L, hours[11])
        assertEquals(25 * 60_000L, hours.sum())
    }
    @Test fun emptyHistoryAndSubMinuteTimesAreHonest() {
        assertEquals(FocusTotals(), focusTotals(emptyList()))
        assertEquals("0 分钟", focusDurationLabel(0))
        assertEquals("不足 1 分钟", focusDurationLabel(30_000))
    }
    @Test fun scheduledPlanUsesSelectedWeekdaysAndSkipsElapsedTime() {
        val plan = FocusPlan("a", "阅读", 9, 0, 25, setOf(1))
        val now = ZonedDateTime.parse("2026-11-23T09:00:00+08:00[Asia/Shanghai]")
        assertEquals(now.plusWeeks(1), nextFocusPlan(plan, now))
        assertEquals(now, nextFocusPlan(plan, now.minusSeconds(1)))
        assertNull(nextFocusPlan(plan.copy(enabled = false), now))
    }
}
