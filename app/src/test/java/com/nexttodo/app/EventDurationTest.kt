package com.nexttodo.app

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDurationTest {
    private val event = Event(id = "long", time = "2026-11-18T10:00", endTime = "2026-11-20T18:00")
    private val now = LocalDateTime.parse("2026-11-19T12:00")

    private fun visible(start: String, end: String = start, target: Event = event) =
        activeLongEvents(listOf(target), now, LocalDate.parse(start), LocalDate.parse(end))

    @Test fun activeEventAppearsOnEachOccupiedDay() {
        for (day in listOf("2026-11-18", "2026-11-19", "2026-11-20")) {
            assertEquals(day, listOf(event.id), visible(day).map { it.id })
        }
    }

    @Test fun activeEventDoesNotLeakBeforeOrAfterItsDates() {
        for (day in listOf("2026-11-17", "2026-11-21", "2026-12-01")) {
            assertTrue(day, visible(day).isEmpty())
        }
    }

    @Test fun weekAndMonthViewsRequireOverlap() {
        assertEquals(1, visible("2026-11-16", "2026-11-22").size)
        assertEquals(1, visible("2026-11-01", "2026-11-30").size)
        assertTrue(visible("2026-11-23", "2026-11-29").isEmpty())
        assertTrue(visible("2026-12-01", "2026-12-31").isEmpty())
        assertTrue(visible("2026-11-01", "2026-11-17").isEmpty())
    }

    @Test fun midnightEndDoesNotOccupyFollowingDay() {
        val target = event.copy(endTime = "2026-11-20T00:00")
        assertEquals(1, visible("2026-11-19", target = target).size)
        assertTrue(visible("2026-11-20", target = target).isEmpty())
    }

    @Test fun legacyDateOnlyEventIncludesItsLastDay() {
        val target = event.copy(time = "", day = LocalDate.parse("2026-11-18"),
            endTime = "", endDay = LocalDate.parse("2026-11-20"))
        assertEquals(1, visible("2026-11-20", target = target).size)
        assertTrue(visible("2026-11-21", target = target).isEmpty())
    }

    @Test fun onlyCurrentlyActiveEventsEnterLongEventsSection() {
        val start = LocalDateTime.parse(event.time)
        val end = LocalDateTime.parse(event.endTime)
        assertTrue(activeLongEvents(listOf(event), start.minusSeconds(1)).isEmpty())
        assertEquals(1, activeLongEvents(listOf(event), start).size)
        assertTrue(activeLongEvents(listOf(event), end).isEmpty())
    }

    @Test fun repeatingEventUsesCurrentOccurrenceDates() {
        val target = event.copy(time = "2026-11-11T10:00", endTime = "2026-11-13T18:00",
            repetition = EventRepeat.WEEKLY)
        assertEquals(LocalDate.parse("2026-11-18"), visible("2026-11-20", target = target).single().occurrenceDate)
        assertTrue(visible("2026-11-13", target = target).isEmpty())
        assertTrue(visible("2026-11-21", target = target).isEmpty())
    }
}
