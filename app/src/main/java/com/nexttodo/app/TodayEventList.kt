package com.nexttodo.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun TodayEventList(displayed: List<Event>, ongoing: List<Event>, model: EventViewModel,
    appearance: TodayAppearanceViewModel, onOpen: (Event) -> Unit, onAddChild: (Event) -> Unit,
    allowReorder: Boolean = true, wallpaperInk: Color? = null, emptyLabel: String = "这天暂无待办") {
    val all = model.events
    val filter = appearance.filter
    val entries = (ongoing + displayed).distinctBy { it.id to it.occurrenceDate }
    val leaves = visibleEventLeaves(entries, all)
    val counts = EventStatusFilter.entries.associateWith { option -> leaves.count(option::matches) }
    val branches = remember(entries, all, filter) { eventBranches(entries, all, filter) }
    val parents = all.associateBy { it.id }
    var deleting by remember { mutableStateOf<Event?>(null) }
    var completing by remember { mutableStateOf<Event?>(null) }
    val toggle: (Event, Boolean) -> Unit = { event, done ->
        val children = all.filter { it.parentId == event.id }.mapNotNull { child ->
            if (event.repetition == EventRepeat.NONE) child else child.onDate(event.occurrenceDate ?: event.startDay())
        }
        if (done && children.any { !it.completed }) completing = event else model.complete(event, done)
    }
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            @Composable fun Filters() {
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    EventStatusFilter.entries.forEach { option ->
                        val selected = filter == option
                        Surface(shape = RoundedCornerShape(10.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            contentColor = if (selected) MaterialTheme.colorScheme.primary else wallpaperInk ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("event-filter-${option.name}").selectable(selected, role = Role.Tab, onClick = { appearance.changeFilter(option) }) ) {
                            Text("${option.label} ${counts[option]}", Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            @Composable fun Views() {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.padding(3.dp).selectableGroup()) {
                        EventDisplay.entries.forEach { option ->
                            val selected = appearance.display == option
                            Surface(shape = RoundedCornerShape(9.dp),
                                color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                                contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("event-display-${option.name}").selectable(selected, role = Role.Tab,
                                    onClick = { appearance.changeDisplay(option) }).semantics { contentDescription = "${option.label}视图" }) {
                                Text(option.label, Modifier.padding(horizontal = 10.dp, vertical = 10.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            if (maxWidth / LocalDensity.current.fontScale < 350.dp) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Filters()
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { Views() }
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) { Filters(); Views() }
        }
        val empty = when (filter) {
            EventStatusFilter.ALL -> emptyLabel
            EventStatusFilter.COMPLETED -> "还没有已完成的事件"
            EventStatusFilter.PENDING -> "没有待完成的事件"
        }
        Box(Modifier.weight(1f)) {
            if (appearance.display == EventDisplay.TREE && branches.isNotEmpty()) {
                EventTreeList(branches, !model.busy, onOpen, toggle, model::moveEvent,
                    model::pin, { deleting = it }, onAddChild, allowReorder, wallpaperInk)
            } else {
                // A parent is a group in the tree, so the flat view lists its actionable children once.
                val visible = leaves.filter(filter::matches)
                EventList(visible, !model.busy, onOpen, toggle, model::moveEvent,
                    model::pin, { deleting = it }, allowReorder, wallpaperInk, empty,
                    ongoingEvents = ongoing.filter { value -> visible.any { it.id == value.id && it.occurrenceDate == value.occurrenceDate } },
                    confirmDeletion = false, showSections = false, parentTitles = parents.mapValues { it.value.title })
            }
        }
    }
    completing?.let { event ->
        AlertDialog(onDismissRequest = { completing = null }, title = { Text("完成整个事件？") },
            text = { Text("「${event.title}」还有未完成的子事件，将一起标为完成。") },
            confirmButton = { TextButton(enabled = !model.busy, onClick = { completing = null; model.complete(event, true) }) { Text("全部完成") } },
            dismissButton = { TextButton(onClick = { completing = null }) { Text("取消") } })
    }
    deleting?.let { event ->
        val hasChildren = all.any { it.parentId == event.id }
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(if (hasChildren) "删除主事件？" else "删除事件？") },
            text = { Text(if (hasChildren) "可以整组删除，也可以保留子事件，让它们成为独立事件。"
                else event.title + if (event.repetition != EventRepeat.NONE) "\n将删除整个重复事件及后续安排。" else "") },
            confirmButton = { TextButton(enabled = !model.busy, onClick = { deleting = null; model.delete(event) }) { Text(if (hasChildren) "整组删除" else "删除") } },
            dismissButton = {
                Row {
                    if (hasChildren) TextButton(enabled = !model.busy, onClick = { deleting = null; model.delete(event, true) }) { Text("保留子事件") }
                    TextButton(onClick = { deleting = null }) { Text("取消") }
                }
            })
    }
}
