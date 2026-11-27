package com.nexttodo.app

import java.time.LocalDate

internal enum class EventStatusFilter(val label: String) {
    ALL("全部"), COMPLETED("已完成"), PENDING("待完成");
    fun matches(event: Event): Boolean = this == ALL || event.completed == (this == COMPLETED)
}

internal enum class EventDisplay(val label: String) { LIST("条状"), TREE("树状") }

internal fun Event.newChild(): Event = Event(
    id = java.util.UUID.randomUUID().toString(), parentId = id,
    day = calendarDay ?: LocalDate.now(), followsParentDate = true,
    repetition = repetition, repeatDays = repeatDays,
    repeatInterval = repeatInterval, repeatWorkDays = repeatWorkDays,
)

internal fun Event.withCompletion(done: Boolean, occurrence: LocalDate): Event =
    if (repetition == EventRepeat.NONE) copy(completed = done)
    else copy(completedDates = if (done) completedDates + occurrence else completedDates - occurrence)

internal fun Event.withChildProgress(children: List<Event>): Event {
    if (children.isEmpty()) return this
    if (repetition == EventRepeat.NONE) return copy(completed = children.all { it.completed })
    val dates = completedDates + children.flatMap { it.completedDates }
    return copy(completedDates = dates.filter { day ->
        val occurrences = children.mapNotNull { it.onDate(day) }
        if (occurrences.isEmpty()) day in completedDates else occurrences.all { it.completed }
    }.toSet())
}

internal fun completionChanges(all: List<Event>, event: Event, done: Boolean): List<Event> {
    val base = all.firstOrNull { it.id == event.id } ?: return emptyList()
    val day = event.occurrenceDate ?: event.occurrenceStart(LocalDate.now()) ?: base.startDay()
    val changes = linkedMapOf(base.id to base.withCompletion(done, day))
    val children = all.filter { it.parentId == base.id }
    children.forEach { child ->
        if (base.repetition == EventRepeat.NONE || child.onDate(day) != null) {
            changes[child.id] = child.withCompletion(done, day)
        }
    }
    base.parentId?.let { parentId ->
        val parent = all.firstOrNull { it.id == parentId } ?: return@let
        val siblings = all.filter { it.parentId == parentId }
        val relevant = if (parent.repetition == EventRepeat.NONE) siblings else siblings.filter { it.onDate(day) != null }
        if (relevant.isNotEmpty()) {
            val finished = relevant.all { child ->
                val updated = changes[child.id] ?: child
                if (updated.repetition == EventRepeat.NONE) updated.completed else day in updated.completedDates
            }
            changes[parent.id] = parent.withCompletion(finished, day)
        }
    }
    return changes.values.toList()
}

internal data class EventBranch(val parent: Event, val children: List<Event>, val total: List<Event>)

internal fun eventBranches(displayed: List<Event>, all: List<Event>, filter: EventStatusFilter): List<EventBranch> {
    val byId = all.associateBy { it.id }
    val visibleParents = displayed.filter { it.parentId == null }.associateBy { it.id to it.occurrenceDate }
    val branches = linkedMapOf<Pair<String, LocalDate?>, Pair<Event, MutableList<Event>>>()
    displayed.forEach { event ->
        val parent = event.parentId?.let(byId::get)
        val root = if (parent == null) event else {
            val day = event.occurrenceDate ?: event.calendarDay ?: parent.startDay()
            if (parent.repetition == EventRepeat.NONE) {
                visibleParents.values.firstOrNull { it.id == parent.id }
                    ?: parent.copy(occurrenceDate = parent.startDay())
            } else visibleParents[parent.id to day] ?: parent.onDate(day) ?: parent.copy(occurrenceDate = day)
        }
        val key = root.id to root.occurrenceDate
        val branch = branches.getOrPut(key) { root to mutableListOf() }
        if (parent != null && filter.matches(event)) branch.second.add(event)
    }
    return branches.values.mapNotNull { (parent, children) ->
        val total = all.filter { it.parentId == parent.id }.mapNotNull { child ->
            if (parent.repetition == EventRepeat.NONE) child else child.onDate(parent.occurrenceDate ?: parent.startDay())
        }
        val visibleRoot = displayed.any { it.id == parent.id && it.occurrenceDate == parent.occurrenceDate }
        if (children.isEmpty() && !(visibleRoot && filter.matches(parent))) null
        else EventBranch(parent, children.distinctBy { it.id to it.occurrenceDate }.sortedByDescending { it.pinned }, total)
    }.sortedWith(compareByDescending<EventBranch> { it.parent.pinned }.thenBy { branch ->
        displayed.indexOfFirst { it.id == branch.parent.id }.takeIf { it >= 0 }
            ?: displayed.indexOfFirst { it.parentId == branch.parent.id }
    })
}

internal fun visibleEventLeaves(displayed: List<Event>, all: List<Event>): List<Event> {
    val parents = all.mapNotNull { it.parentId }.toSet()
    return displayed.filter { it.id !in parents }
}
