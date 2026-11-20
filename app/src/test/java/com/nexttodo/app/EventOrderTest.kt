package com.nexttodo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventOrderTest {
    private val first = Event(id = "first", time = "2026-11-18T09:00", endTime = "2026-11-21T18:00")
    private val second = first.copy(id = "second")
    private val third = first.copy(id = "third")

    @Test fun longEventsCanMoveInBothDirections() {
        val events = listOf(first, second, third)
        assertEquals(listOf(third, first, second), reorderedEvents(events, third.id, first.id))
        assertEquals(listOf(second, third, first), reorderedEvents(events, first.id, third.id))
    }

    @Test fun completedEventsCanMoveWithoutChangingTheirData() {
        val a = first.copy(completed = true)
        val b = second.copy(completed = true)
        assertEquals(listOf(b, a), reorderedEvents(listOf(a, b), b.id, a.id))
    }

    @Test fun movingLongEventsPreservesOtherEventsAndTheirRelativeOrder() {
        val other = Event(id = "ordinary")
        val hidden = Event(id = "other-day")
        val events = listOf(first, other, second, hidden, third)
        val moved = reorderedEvents(events, third.id, first.id)!!
        assertEquals(listOf(third, first, other, second, hidden), moved)
        assertEquals(listOf(other, hidden), moved.filter { !it.isDuration })
        assertEquals(events.toSet(), moved.toSet())
        assertEquals(events, listOf(first, other, second, hidden, third))
    }

    @Test fun pinnedEventsReorderWithinTheirOwnGroup() {
        val a = first.copy(pinned = true)
        val b = second.copy(pinned = true)
        assertEquals(listOf(b, a, third), reorderedEvents(listOf(a, b, third), b.id, a.id))
        assertNull(reorderedEvents(listOf(a, b, third), third.id, a.id))
        assertNull(reorderedEvents(listOf(a, b, third), a.id, third.id))
    }

    @Test fun staleOrUnchangedMovesAreIgnored() {
        val events = listOf(first, second)
        assertNull(reorderedEvents(events, "missing", first.id))
        assertNull(reorderedEvents(events, first.id, "missing"))
        assertNull(reorderedEvents(events, first.id, first.id))
    }
}
