package com.nexttodo.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun SwipeEventRow(event: Event, enabled: Boolean, onOpen: (Event) -> Unit,
    onToggle: (Event, Boolean) -> Unit, onPin: (Event) -> Unit, onDelete: (Event) -> Unit,
    modifier: Modifier = Modifier, showHandle: Boolean = false,
    reorderActions: List<CustomAccessibilityAction> = emptyList(), wallpaperInk: Color? = null,
    parentTitle: String? = null, containerColor: Color? = null) {
    var offset by remember(event.id, event.occurrenceDate) { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val width = with(LocalDensity.current) { 144.dp.toPx() }
    val animated by animateFloatAsState(offset, if (dragging) snap() else tween(180), label = "eventSwipe")
    val pin by rememberUpdatedState(onPin)
    val delete by rememberUpdatedState(onDelete)
    val allowed by rememberUpdatedState(enabled)
    LaunchedEffect(enabled) { if (!enabled) offset = 0f }
    Box(modifier.clip(MaterialTheme.shapes.large)) {
        if (animated < -1f) Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.width(72.dp).fillMaxHeight()) {
                TextButton(enabled = enabled, onClick = { offset = 0f; onPin(event) }) { Text(if (event.pinned) "取消置顶" else "置顶") }
            }
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.width(72.dp).fillMaxHeight()) {
                TextButton(enabled = enabled, onClick = { offset = 0f; onDelete(event) }) { Text("删除", color = MaterialTheme.colorScheme.onErrorContainer) }
            }
        }
        EventRow(event, enabled, { if (offset < 0) offset = 0f else onOpen(it) }, onToggle,
            modifier = Modifier.offset { IntOffset(animated.roundToInt(), 0) }.pointerInput(event.id, event.occurrenceDate, width) {
                detectHorizontalDragGestures(onDragStart = { dragging = true }, onDragCancel = {
                    dragging = false; offset = if (offset < -width / 3) -width else 0f
                }, onDragEnd = { dragging = false; offset = if (offset < -width / 3) -width else 0f }) { change, amount ->
                    if (allowed) { offset = (offset + amount).coerceIn(-width, 0f); change.consume() }
                }
            }, showHandle = showHandle,
            reorderActions = reorderActions + listOf(
                CustomAccessibilityAction(if (event.pinned) "取消置顶" else "置顶") { if (enabled) { pin(event); true } else false },
                CustomAccessibilityAction("删除") { if (enabled) { delete(event); true } else false }), wallpaperInk = wallpaperInk,
            parentTitle = parentTitle, containerColor = containerColor)
    }
}
