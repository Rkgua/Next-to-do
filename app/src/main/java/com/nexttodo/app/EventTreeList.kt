package com.nexttodo.app

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CancellationException

private data class TreeEntry(val event: Event, val branch: EventBranch, val child: Boolean = false,
    val footer: Boolean = false, val last: Boolean = false) {
    val key: String get() = "${branch.parent.id}:${branch.parent.occurrenceDate}:${event.id}:${event.occurrenceDate}:$footer"
}
private data class TreeDrag(val row: TreeEntry, val top: Float, val height: Int) {
    val center get() = top + height / 2f
}

@Composable
internal fun EventTreeList(branches: List<EventBranch>, enabled: Boolean, onOpen: (Event) -> Unit,
    onToggle: (Event, Boolean) -> Unit, onMove: (String, String) -> Unit,
    onPin: (Event) -> Unit, onDelete: (Event) -> Unit, onAddChild: (Event) -> Unit,
    allowReorder: Boolean, wallpaperInk: Color?) {
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val entries = buildList {
        branches.forEach { branch ->
            add(TreeEntry(branch.parent, branch))
            if (branch.total.isNotEmpty() && branch.parent.id !in collapsed) {
                branch.children.forEachIndexed { index, child -> add(TreeEntry(child, branch, child = true, last = index == branch.children.lastIndex)) }
                add(TreeEntry(branch.parent, branch, footer = true))
            }
        }
    }
    fun peers(row: TreeEntry, rows: List<TreeEntry>) = rows.filter {
        !it.footer && it.child == row.child && it.event.parentId == row.event.parentId &&
            it.event.pinned == row.event.pinned && it.event.completed == row.event.completed
    }
    val listState = rememberLazyListState()
    var drag by remember { mutableStateOf<TreeDrag?>(null) }
    val latestEntries by rememberUpdatedState(entries)
    val latestEnabled by rememberUpdatedState(enabled && allowReorder)
    val latestMove by rememberUpdatedState(onMove)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val autoZone = with(density) { 54.dp.toPx() }
    val maxScroll = with(density) { 8.dp.toPx() }
    LaunchedEffect(drag?.row?.key) {
        while (drag != null) {
            withFrameNanos { }
            val current = drag ?: break
            val end = listState.layoutInfo.viewportEndOffset
            val speed = when {
                current.center < autoZone -> (current.center - autoZone) / autoZone
                current.center > end - autoZone -> (current.center - end + autoZone) / autoZone
                else -> 0f
            }.coerceIn(-1f, 1f)
            if (speed != 0f) listState.scrollBy(speed * maxScroll)
        }
    }
    fun actions(row: TreeEntry): List<CustomAccessibilityAction> {
        if (!enabled || !allowReorder) return emptyList()
        val related = peers(row, entries)
        val index = related.indexOf(row)
        return buildList {
            related.getOrNull(index - 1)?.let { previous -> add(CustomAccessibilityAction("上移") { onMove(row.event.id, previous.event.id); true }) }
            related.getOrNull(index + 1)?.let { next -> add(CustomAccessibilityAction("下移") { onMove(row.event.id, next.event.id); true }) }
        }
    }
    val cardColor = if (wallpaperInk == null) MaterialTheme.colorScheme.surface
        else if (wallpaperInk.luminance() >= .5f) Color(0xFF151A24).copy(alpha = .88f) else Color.White.copy(alpha = .93f)
    val lineColor = wallpaperInk?.copy(alpha = .22f) ?: MaterialTheme.colorScheme.outlineVariant
    @Composable fun Render(row: TreeEntry, modifier: Modifier = Modifier) {
        val handle = enabled && allowReorder && peers(row, entries).size > 1
        when {
            row.footer -> Surface(color = cardColor, shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)) {
                Column(Modifier.fillMaxWidth().padding(start = 96.dp, bottom = 8.dp, end = 8.dp)) {
                    if (row.branch.children.isEmpty()) Text("此日期没有符合筛选的子事件", style = MaterialTheme.typography.bodySmall,
                        color = wallpaperInk ?: MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { onAddChild(row.branch.parent) }, enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = "添加子事件 · ${row.branch.parent.title}" }) { Text("＋ 添加子事件") }
                }
            }
            row.child -> Surface(color = cardColor, modifier = modifier) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Canvas(Modifier.width(92.dp).fillMaxHeight()) {
                        val x = 68.dp.toPx()
                        val mid = size.height / 2
                        val radius = minOf(16.dp.toPx(), mid)
                        val stroke = 1.25.dp.toPx()
                        if (!row.last) drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), stroke)
                        val path = Path().apply {
                            moveTo(x, 0f); lineTo(x, mid - radius)
                            quadraticTo(x, mid, x + radius, mid)
                            lineTo(104.dp.toPx(), mid)
                        }
                        drawPath(path, lineColor, style = Stroke(stroke))
                    }
                    SwipeEventRow(row.event, enabled, onOpen, onToggle, onPin, onDelete,
                        modifier = Modifier.weight(1f), showHandle = handle, reorderActions = actions(row),
                        wallpaperInk = wallpaperInk, containerColor = Color.Transparent)
                }
            }
            else -> TreeParentRow(row.branch, enabled, row.event.id !in collapsed, onOpen, onToggle,
                onExpand = { collapsed = if (row.event.id in collapsed) collapsed - row.event.id else collapsed + row.event.id },
                onPin = onPin, onDelete = onDelete, onAddChild = onAddChild, showHandle = handle,
                color = cardColor, lineColor = lineColor, ink = wallpaperInk,
                modifier = modifier.semantics { customActions = actions(row) })
        }
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp).clipToBounds().pointerInput(listState) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!latestEnabled) return@awaitEachGesture
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { down.position.y >= it.offset && down.position.y < it.offset + it.size }
                ?: return@awaitEachGesture
            val row = latestEntries.find { it.key == item.key } ?: return@awaitEachGesture
            if (row.footer || peers(row, latestEntries).size < 2) return@awaitEachGesture
            val (start, initial) = if (down.position.x >= size.width - 44.dp.toPx()) awaitHandleDrag(down) ?: return@awaitEachGesture
                else (awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture) to 0f
            drag = TreeDrag(row, item.offset + initial, item.size)
            val dropped = try {
                verticalDrag(start.id) { change ->
                    drag?.let { drag = it.copy(top = it.top + change.positionChange().y) }
                    change.consume()
                }
            } catch (exception: CancellationException) { drag = null; throw exception }
            val current = drag
            drag = null
            if (dropped && current != null && latestEnabled) {
                val related = peers(current.row, latestEntries).associateBy { it.key }
                val target = listState.layoutInfo.visibleItemsInfo.filter { related.containsKey(it.key) }
                    .minByOrNull { abs(current.center - (it.offset + it.size / 2f)) }
                related[target?.key]?.event?.let { if (it.id != current.row.event.id) latestMove(current.row.event.id, it.id) }
            }
        }
    }) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
            items(entries, key = { it.key }) { row ->
                val modifier = Modifier.animateItem().alpha(if (drag?.row?.key == row.key) .22f else 1f)
                    .then(if (!row.child && !row.footer) Modifier.padding(top = 10.dp) else Modifier)
                Render(row, modifier)
            }
        }
        drag?.let { current ->
            Box(Modifier.graphicsLayer { translationY = current.top }.clearAndSetSemantics {}) { Render(current.row) }
        }
    }
}

@Composable
private fun TreeParentRow(branch: EventBranch, enabled: Boolean, expanded: Boolean,
    onOpen: (Event) -> Unit, onToggle: (Event, Boolean) -> Unit, onExpand: () -> Unit,
    onPin: (Event) -> Unit, onDelete: (Event) -> Unit, onAddChild: (Event) -> Unit,
    showHandle: Boolean, color: Color, lineColor: Color, ink: Color?, modifier: Modifier) {
    val event = branch.parent
    val children = branch.total
    val done = children.count { it.completed }
    val open = expanded && children.isNotEmpty()
    var menu by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(if (expanded) 0f else 90f, tween(180), label = "eventExpandArrow")
    Surface(modifier, color = color, contentColor = ink ?: MaterialTheme.colorScheme.onSurface,
        shape = if (open) RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp) else RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).drawBehind {
            if (open && branch.children.isNotEmpty()) drawLine(lineColor,
                Offset(68.dp.toPx(), size.height / 2 + 12.dp.toPx()), Offset(68.dp.toPx(), size.height), 1.25.dp.toPx())
        }.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (children.isEmpty()) onAddChild(event) else onExpand() }, enabled = enabled,
                modifier = Modifier.width(40.dp).semantics {
                    contentDescription = if (children.isEmpty()) "添加子事件 · ${event.title}" else "${if (expanded) "收起" else "展开"}子事件 · ${event.title}"
                }) {
                if (children.isEmpty()) {
                    Text("+", color = ink ?: MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Icon(
                        painter = painterResource(if (expanded) R.drawable.event_expand_blue else R.drawable.event_expand_gray),
                        contentDescription = null,
                        tint = if (!expanded && MaterialTheme.colorScheme.background.luminance() < .5f)
                            ink ?: MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                        modifier = Modifier.size(16.8.dp).graphicsLayer { rotationZ = arrowRotation }
                            .alpha(if (enabled) 1f else .38f),
                    )
                }
            }
            TriStateCheckbox(state = when {
                children.isNotEmpty() && done in 1 until children.size -> ToggleableState.Indeterminate
                event.completed -> ToggleableState.On
                else -> ToggleableState.Off
            }, onClick = { onToggle(event, !event.completed) }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "完成状态 · ${event.title}" })
            Column(Modifier.weight(1f).padding(vertical = 6.dp, horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onOpen(event) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(event.title, style = MaterialTheme.typography.titleMedium,
                            textDecoration = if (event.completed) TextDecoration.LineThrough else TextDecoration.None)
                        Text(if (children.isEmpty()) listOf(event.type.label, event.scheduleLabel()).filter { it.isNotBlank() }.joinToString(" · ")
                            else "${done}/${children.size} 已完成 · 当前显示 ${branch.children.size} 项",
                            style = MaterialTheme.typography.bodySmall, color = ink?.copy(alpha = .8f) ?: MaterialTheme.colorScheme.onSurfaceVariant)
                        if (children.isNotEmpty()) LinearProgressIndicator(progress = { done.toFloat() / children.size },
                            modifier = Modifier.fillMaxWidth().height(3.dp), drawStopIndicator = {})
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.width(36.dp).semantics { contentDescription = "事件操作 · ${event.title}" }) { Text("⋮") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("添加子事件") }, enabled = enabled, onClick = { menu = false; onAddChild(event) })
                            DropdownMenuItem(text = { Text(if (event.pinned) "取消置顶" else "置顶") }, enabled = enabled, onClick = { menu = false; onPin(event) })
                            DropdownMenuItem(text = { Text("删除") }, enabled = enabled, onClick = { menu = false; onDelete(event) })
                        }
                    }
                    if (showHandle) Box(Modifier.width(32.dp).height(44.dp).semantics { contentDescription = "拖动排序" }, contentAlignment = Alignment.Center) {
                        Text("⠿", color = ink ?: MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
