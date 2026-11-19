package com.nexttodo.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

@Composable
internal fun EventList(
    events: List<Event>,
    enabled: Boolean,
    onOpen: (Event) -> Unit,
    onToggle: (Event, Boolean) -> Unit,
    onMove: (String, String) -> Unit,
    onPin: (Event) -> Unit = {},
    onDelete: (Event) -> Unit = {},
    allowReorder: Boolean = true,
    wallpaperInk: Color? = null,
) {
    if (events.isEmpty()) {
        Text(
            "这天暂无事件",
            modifier = Modifier.padding(20.dp),
            color = wallpaperInk ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    var deleting by remember { mutableStateOf<Event?>(null) }
    deleting?.let { event ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除事件？") },
            text = { Text(event.title + if (event.repetition != EventRepeat.NONE) "\n将删除整个重复事件及后续安排。" else "") },
            confirmButton = { TextButton(enabled = enabled, onClick = { deleting = null; onDelete(event) }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
    val (completed, pending) = events.sortedByDescending { it.pinned }.partition { it.completed }
    val listState = rememberLazyListState()
    val latestPending by rememberUpdatedState(pending.associateBy(::eventListKey))
    val latestEnabled by rememberUpdatedState(enabled && allowReorder)
    val latestOnMove by rememberUpdatedState(onMove)
    val autoScrollZone = with(LocalDensity.current) { 54.dp.toPx() }
    val maxScroll = with(LocalDensity.current) { 8.dp.toPx() }
    var drag by remember { mutableStateOf<PendingDrag?>(null) }

    LaunchedEffect(drag?.event?.id) {
        while (drag != null) {
            withFrameNanos { }
            val current = drag ?: break
            val end = listState.layoutInfo.viewportEndOffset
            val speed = when {
                current.center < autoScrollZone -> (current.center - autoScrollZone) / autoScrollZone
                current.center > end - autoScrollZone -> (current.center - end + autoScrollZone) / autoScrollZone
                else -> 0f
            }.coerceIn(-1f, 1f)
            if (speed != 0f) listState.scrollBy(speed * maxScroll)
        }
    }

    Box(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).clipToBounds()
            .pointerInput(listState) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!latestEnabled || latestPending.size < 2) return@awaitEachGesture
                    val item = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                        down.position.y >= it.offset && down.position.y < it.offset + it.size
                    } ?: return@awaitEachGesture
                    val event = (item.key as? String)?.let(latestPending::get) ?: return@awaitEachGesture
                    val onHandle = down.position.x >= size.width - 44.dp.toPx()
                    val (start, initialDelta) = if (onHandle) {
                        awaitHandleDrag(down) ?: return@awaitEachGesture
                    } else {
                        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        longPress to 0f
                    }

                    drag = PendingDrag(event, item.offset + initialDelta, item.size)
                    val dropped = try {
                        verticalDrag(start.id) { change ->
                            drag?.let { current ->
                                drag = current.copy(top = current.top + change.positionChange().y)
                            }
                            change.consume()
                        }
                    } catch (exception: CancellationException) {
                        drag = null
                        throw exception
                    }
                    val current = drag
                    drag = null
                    if (dropped && current != null) {
                        val target = listState.layoutInfo.visibleItemsInfo
                            .filter { (it.key as? String)?.let(latestPending::containsKey) == true }
                            .minByOrNull { abs(current.center - (it.offset + it.size / 2f)) }
                        val targetId = target?.key as? String ?: latestPending.values.lastOrNull()?.let(::eventListKey)
                        val targetEvent = targetId?.let(latestPending::get)
                        if (targetEvent != null && targetEvent.id != current.event.id) {
                            latestOnMove(current.event.id, targetEvent.id)
                        }
                    }
                }
            },
    ) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            if (pending.isNotEmpty()) item { SectionLabel("待完成 · ${pending.size}", wallpaperInk) }
            items(pending, key = ::eventListKey) { event ->
                val index = pending.indexOf(event)
                val reorderActions = if (enabled && allowReorder) buildList {
                    pending.getOrNull(index - 1)?.let { previous ->
                        add(CustomAccessibilityAction("上移") { onMove(event.id, previous.id); true })
                    }
                    pending.getOrNull(index + 1)?.let { next ->
                        add(CustomAccessibilityAction("下移") { onMove(event.id, next.id); true })
                    }
                } else emptyList()
                SwipeEventRow(
                    event, enabled, onOpen, onToggle,
                    onPin = onPin, onDelete = { deleting = it },
                    modifier = Modifier.animateItem().alpha(if (drag?.event?.id == event.id) 0.22f else 1f),
                    showHandle = allowReorder && pending.size > 1,
                    reorderActions = reorderActions,
                    wallpaperInk = wallpaperInk,
                )
            }
            if (completed.isNotEmpty()) item { SectionLabel("已完成 · ${completed.size}", wallpaperInk) }
            items(completed, key = ::eventListKey) { SwipeEventRow(it, enabled, onOpen, onToggle,
                onPin = onPin, onDelete = { deleting = it }, wallpaperInk = wallpaperInk) }
        }
        drag?.let { current ->
            EventRow(
                current.event, enabled = true, onOpen = {}, onToggle = { _, _ -> },
                modifier = Modifier.graphicsLayer { translationY = current.top }
                    .clearAndSetSemantics {},
                showHandle = true,
                dragging = true,
                wallpaperInk = wallpaperInk,
            )
        }
    }
}

private data class PendingDrag(val event: Event, val top: Float, val height: Int) {
    val center: Float get() = top + height / 2f
}

private suspend fun AwaitPointerEventScope.awaitHandleDrag(
    down: PointerInputChange,
): Pair<PointerInputChange, Float>? {
    while (true) {
        // Claim a drag that starts on the handle before the list consumes it as scrolling.
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
            ?: return null
        if (!change.pressed || change.isConsumed) return null
        val distance = change.position.y - down.position.y
        if (abs(distance) >= viewConfiguration.touchSlop) {
            change.consume()
            awaitPointerEvent(PointerEventPass.Main)
            return change to distance
        }
    }
}

@Composable
private fun SectionLabel(label: String, wallpaperInk: Color? = null) {
    Text(
        label,
        Modifier.padding(top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleSmall,
        color = wallpaperInk ?: MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun EventRow(
    event: Event,
    enabled: Boolean,
    onOpen: (Event) -> Unit,
    onToggle: (Event, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showHandle: Boolean = false,
    dragging: Boolean = false,
    reorderActions: List<CustomAccessibilityAction> = emptyList(),
    wallpaperInk: Color? = null,
) {
    val cardColor = if (wallpaperInk == null) null else
        if (wallpaperInk.luminance() < .5f) Color.White.copy(alpha = .87f) else Color(0xFF151A24).copy(alpha = .82f)
    Surface(
        modifier = modifier.semantics { customActions = reorderActions },
        color = cardColor ?: if (event.completed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = wallpaperInk ?: MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        shadowElevation = if (dragging) 8.dp else 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = event.completed,
                onCheckedChange = { onToggle(event, it) },
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "完成状态 · ${event.title}" },
            )
            Column(
                Modifier.weight(1f).clickable { onOpen(event) }.padding(vertical = 14.dp, horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    (if (event.pinned) "置顶 · " else "") + event.title,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (event.completed) TextDecoration.LineThrough else TextDecoration.None,
                )
                Text(
                    listOf(event.type.label, event.scheduleLabel().takeIf { it.isNotBlank() })
                        .filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = wallpaperInk?.copy(alpha = .8f) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showHandle) {
                val handleColor = MaterialTheme.colorScheme.onSurfaceVariant
                Box(
                    Modifier.size(44.dp).semantics { contentDescription = "拖动排序" },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(20.dp)) {
                        for (row in 1..3) {
                            drawCircle(handleColor, 1.25.dp.toPx(), Offset(size.width * 0.375f, size.height * row / 4f))
                            drawCircle(handleColor, 1.25.dp.toPx(), Offset(size.width * 0.625f, size.height * row / 4f))
                        }
                    }
                }
            }
        }
    }
}

private fun eventListKey(event: Event) = "${event.id}:${event.occurrenceDate}"
