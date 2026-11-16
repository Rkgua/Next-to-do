package com.nexttodo.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
internal fun EventList(
    events: List<Event>,
    enabled: Boolean,
    onOpen: (Event) -> Unit,
    onToggle: (Event, Boolean) -> Unit,
    onMove: (String, String) -> Unit,
) {
    if (events.isEmpty()) {
        Text(
            "这天暂无事件",
            modifier = Modifier.padding(20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val (completed, pending) = events.partition { it.completed }
    val listState = rememberLazyListState()
    val latestPending by rememberUpdatedState(pending.associateBy(Event::id))
    val latestEnabled by rememberUpdatedState(enabled)
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
                        val targetId = target?.key as? String ?: latestPending.values.lastOrNull()?.id
                        if (targetId != null && targetId != current.event.id) {
                            latestOnMove(current.event.id, targetId)
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
            if (pending.isNotEmpty()) item { SectionLabel("待完成 · ${pending.size}") }
            items(pending, key = { it.id }) { event ->
                val index = pending.indexOf(event)
                val reorderActions = if (enabled) buildList {
                    pending.getOrNull(index - 1)?.let { previous ->
                        add(CustomAccessibilityAction("上移") { onMove(event.id, previous.id); true })
                    }
                    pending.getOrNull(index + 1)?.let { next ->
                        add(CustomAccessibilityAction("下移") { onMove(event.id, next.id); true })
                    }
                } else emptyList()
                EventRow(
                    event, enabled, onOpen, onToggle,
                    modifier = Modifier.animateItem().alpha(if (drag?.event?.id == event.id) 0.22f else 1f),
                    showHandle = pending.size > 1,
                    reorderActions = reorderActions,
                )
            }
            if (completed.isNotEmpty()) item { SectionLabel("已完成 · ${completed.size}") }
            items(completed, key = { it.id }) { EventRow(it, enabled, onOpen, onToggle) }
        }
        drag?.let { current ->
            EventRow(
                current.event, enabled = true, onOpen = {}, onToggle = { _, _ -> },
                modifier = Modifier.graphicsLayer { translationY = current.top }
                    .clearAndSetSemantics {},
                showHandle = true,
                dragging = true,
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
private fun SectionLabel(label: String) {
    Text(
        label,
        Modifier.padding(top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EventRow(
    event: Event,
    enabled: Boolean,
    onOpen: (Event) -> Unit,
    onToggle: (Event, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showHandle: Boolean = false,
    dragging: Boolean = false,
    reorderActions: List<CustomAccessibilityAction> = emptyList(),
) {
    Surface(
        modifier = modifier.semantics { customActions = reorderActions },
        color = if (event.completed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
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
                    event.title,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (event.completed) TextDecoration.LineThrough else TextDecoration.None,
                )
                Text(
                    listOf(event.type.label, event.time.takeIf { it.isNotBlank() }?.let(::eventTimeLabel))
                        .filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
internal fun EventDetails(event: Event, onBack: () -> Unit, onEdit: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row {
            TextButton(onClick = onBack) { Text("返回") }
            TextButton(onClick = onEdit) { Text("编辑") }
        }
        Text(event.title, style = MaterialTheme.typography.headlineSmall)
        Text(
            "${event.type.label} · ${if (event.completed) "已完成" else "待完成"}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (event.description.isNotBlank()) Text(event.description)
        if (event.time.isNotBlank()) DetailValue("事件时间", eventTimeLabel(event.time))
        else event.day?.let { DetailValue("日期", it.format(dayFormat)) }
        if (event.reminderEnabled && !event.completed) DetailValue("到点提醒", "已开启")
        event.fields.forEach { field ->
            when (field.type) {
                FieldType.TEXT -> if (field.value.isNotBlank()) DetailValue(field.name, field.value)
                FieldType.TIME -> if (field.value.isNotBlank()) DetailValue(field.name, eventTimeLabel(field.value))
                FieldType.IMAGE -> field.attachment?.let {
                    SectionLabel(field.name)
                    ImagePreview(it)
                }
                FieldType.FILE -> field.attachment?.let {
                    SectionLabel(field.name)
                    OpenAttachment(it)
                }
            }
        }
        event.originalImage?.let {
            SectionLabel("原图")
            ImagePreview(it)
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(label)
        Text(value)
    }
}

@Composable
private fun OpenAttachment(attachment: Attachment) {
    val context = LocalContext.current
    var failed by remember(attachment.uri) { mutableStateOf(false) }
    OutlinedButton(onClick = {
        val uri = Uri.parse(attachment.uri)
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, context.contentResolver.getType(uri) ?: "application/octet-stream")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
            failed = false
        } catch (_: ActivityNotFoundException) {
            failed = true
        }
    }) { Text("打开 ${attachment.name}") }
    if (failed) Text("没有可打开此文件的应用")
}

@Composable
internal fun ImagePreview(attachment: Attachment) {
    val context = LocalContext.current
    var expanded by remember(attachment.uri) { mutableStateOf(false) }
    var failed by remember(attachment.uri) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, attachment.uri) {
        value = null
        value = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val uri = Uri.parse(attachment.uri)
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                var sampleSize = 1
                while (maxOf(options.outWidth, options.outHeight) / sampleSize > 2400) sampleSize *= 2
                options.inJustDecodeBounds = false
                options.inSampleSize = sampleSize
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?.asImageBitmap()
            }.getOrNull()
        }
        failed = value == null
    }
    val image = bitmap
    if (image != null) {
        Image(
            image,
            "查看 ${attachment.name}",
            Modifier.fillMaxWidth().heightIn(max = 200.dp).clickable { expanded = true },
        )
        if (expanded) {
            Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(12.dp)) {
                        Image(image, attachment.name, Modifier.fillMaxSize().padding(top = 48.dp))
                        TextButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.TopEnd)) {
                            Text("关闭")
                        }
                    }
                }
            }
        }
    } else if (failed) {
        Text("无法预览图片")
    }
}
