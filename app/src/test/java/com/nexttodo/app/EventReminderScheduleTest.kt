package com.nexttodo.app

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class EventReminderScheduleTest {
    private val due = LocalDateTime.parse("2026-11-25T10:00")
    private val event = Event("reminder", time = due.toString(), reminderEnabled = true)

    @Test fun restoresAlarmThatBecameDueDuringRestart() {
        assertEquals(due, event.reminderToSchedule(due.plusMinutes(2), null))
        assertEquals(due, event.reminderToSchedule(due, null))
    }

    @Test fun doesNotReplayDeliveredOrOldReminder() {
        assertNull(event.reminderToSchedule(due.plusMinutes(2), due.toString()))
        assertNull(event.reminderToSchedule(due.plusMinutes(15).plusSeconds(1), null))
        assertEquals(due, event.reminderToSchedule(due.plusMinutes(15), null))
    }

    @Test fun disabledOrCompletedEventsDoNotRing() {
        assertNull(event.copy(reminderEnabled = false).reminderToSchedule(due, null))
        assertNull(event.copy(completed = true).reminderToSchedule(due, null))
        assertNull(event.copy(time = "").reminderToSchedule(due, null))
    }

    @Test fun restoresAcrossMidnight() {
        val at = LocalDateTime.parse("2026-11-24T23:55")
        assertEquals(at, event.copy(time = at.toString()).reminderToSchedule(at.plusMinutes(10), null))
    }

    @Test fun recurringReminderAdvancesAfterDelivery() {
        val recurring = event.copy(repetition = EventRepeat.DAILY)
        assertEquals(due, recurring.reminderToSchedule(due.plusMinutes(2), null))
        assertEquals(due.plusDays(1), recurring.reminderToSchedule(due.plusMinutes(2), due.toString()))
        assertEquals(due.plusDays(1), recurring.reminderToSchedule(due.plusHours(1), null))
        assertEquals(due.plusDays(1), recurring.copy(completedDates = setOf(due.toLocalDate()))
            .reminderToSchedule(due, null))
    }

    @Test fun futureAlarmIsNotDeliveredEarly() {
        assertEquals(due, event.reminderToSchedule(due.minusHours(1), null))
    }
}
