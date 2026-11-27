package com.nexttodo.app

internal fun reorderedEvents(events: List<Event>, id: String, targetId: String): List<Event>? {
    val from = events.indexOfFirst { it.id == id }
    val to = events.indexOfFirst { it.id == targetId }
    if (from < 0 || to < 0 || from == to) return null
    if (events[from].pinned != events[to].pinned) return null
    if (events[from].parentId != events[to].parentId) return null
    return events.toMutableList().apply { add(to, removeAt(from)) }
}
