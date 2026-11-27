package com.nexttodo.app

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class EventHierarchyTest {
    private val day = LocalDate.of(2026, 11, 26)
    private val parent = Event("parent", title = "期末复习", day = day)
    private val first = Event("first", title = "第一章", day = day, parentId = parent.id)
    private val second = first.copy(id = "second", title = "第二章", completed = true)

    @Test fun completingLastChildCompletesParentAndReopeningClearsIt() {
        val result = completionChanges(listOf(parent, first, second), first, true).associateBy { it.id }
        assertTrue(result.getValue(parent.id).completed)
        assertTrue(result.getValue(first.id).completed)
        val reopened = completionChanges(listOf(result.getValue(parent.id), result.getValue(first.id), second), first, false)
        assertFalse(reopened.first { it.id == parent.id }.completed)
    }

    @Test fun parentCompletionIncludesChildrenOnOtherDates() {
        val tomorrow = first.copy(id = "tomorrow", day = day.plusDays(1))
        val updates = completionChanges(listOf(parent, first, tomorrow), parent, true)
        assertEquals(setOf(parent.id, first.id, tomorrow.id), updates.map { it.id }.toSet())
        assertTrue(updates.all { it.completed })
    }

    @Test fun repeatedFamiliesOnlyCompleteTheSelectedOccurrence() {
        val root = parent.copy(repetition = EventRepeat.DAILY)
        val a = first.copy(repetition = EventRepeat.DAILY)
        val b = second.copy(completed = false, repetition = EventRepeat.DAILY, completedDates = setOf(day))
        val updates = completionChanges(listOf(root, a, b), a.copy(occurrenceDate = day), true)
        assertEquals(setOf(day), updates.first { it.id == root.id }.completedDates)
        assertFalse(day.plusDays(1) in updates.first { it.id == root.id }.completedDates)
        assertFalse(updates.first { it.id == root.id }.completed)
    }

    @Test fun editingRepeatedChildrenPreservesCompletedOccurrences() {
        val root = parent.copy(repetition = EventRepeat.DAILY, completedDates = setOf(day))
        val child = first.copy(repetition = EventRepeat.DAILY, completedDates = setOf(day))
        assertEquals(setOf(day), root.withChildProgress(listOf(child.copy(title = "改名"))).completedDates)
        assertEquals(emptySet<LocalDate>(), root.withChildProgress(listOf(child, child.copy(id = "new", completedDates = emptySet()))).completedDates)
        assertEquals(setOf(day), root.copy(completedDates = emptySet()).withChildProgress(listOf(child)).completedDates)
    }

    @Test fun statusFilterKeepsParentContextAndOnlyMatchingChildren() {
        val all = listOf(parent, first, second)
        val displayed = periodEvents(all, day, day)
        val completed = eventBranches(displayed, all, EventStatusFilter.COMPLETED).single()
        assertEquals(parent.id, completed.parent.id)
        assertEquals(listOf(second.id), completed.children.map { it.id })
        assertEquals(2, completed.total.size)
        val pending = eventBranches(displayed, all, EventStatusFilter.PENDING).single()
        assertEquals(listOf(first.id), pending.children.map { it.id })
    }

    @Test fun independentDateChildShowsItsParentOnThatDate() {
        val tomorrow = first.copy(day = day.plusDays(1))
        val all = listOf(parent, tomorrow)
        val displayed = periodEvents(all, day.plusDays(1), day.plusDays(1))
        val branch = eventBranches(displayed, all, EventStatusFilter.ALL).single()
        assertEquals(parent.id, branch.parent.id)
        assertEquals(tomorrow.id, branch.children.single().id)
        assertEquals(1, visibleEventLeaves(displayed, all).size)
    }

    @Test fun weekViewSeparatesRepeatedOccurrencesAndAvoidsDuplicateParents() {
        val all = listOf(parent, first).map { it.copy(repetition = EventRepeat.DAILY) }
        val branches = eventBranches(periodEvents(all, day, day.plusDays(2)), all, EventStatusFilter.ALL)
        assertEquals(3, branches.size)
        assertTrue(branches.all { it.children.size == 1 })
        assertEquals(3, visibleEventLeaves(periodEvents(all, day, day.plusDays(2)), all).size)
    }

    @Test fun movingChildrenCannotChangeParentsOrRootOrder() {
        assertNull(reorderedEvents(listOf(parent, first, second), first.id, parent.id))
        assertNull(reorderedEvents(listOf(parent, first, second.copy(parentId = "other")), first.id, second.id))
        val sibling = second.copy(completed = false)
        assertEquals(listOf(parent, sibling, first), reorderedEvents(listOf(parent, first, sibling), sibling.id, first.id))
    }

    @Test fun movingRootMovesTheEntireRenderedBranch() {
        val other = Event("other", day = day)
        val reordered = reorderedEvents(listOf(first, parent, other), other.id, parent.id)!!
        assertEquals(listOf(other.id, parent.id), eventBranches(periodEvents(reordered, day, day), reordered, EventStatusFilter.ALL).map { it.parent.id })
    }
}
