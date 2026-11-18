package com.nexttodo.app

import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class BackgroundOption(val skin: TimetableSkin, val image: String? = null, val label: String = skin.label)

@Composable
internal fun TimetableAppearance(model: TimetableViewModel, aspectRatio: Float, onDismiss: () -> Unit) {
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerError by rememberSaveable { mutableStateOf("") }
    val backgrounds = TimetableSkin.entries.filter { it != TimetableSkin.CUSTOM }.map { BackgroundOption(it) } +
        model.selected.backgroundImages.mapIndexed { index, image ->
            BackgroundOption(TimetableSkin.CUSTOM, image, "自定义 ${index + 1}")
        }
    val picker = rememberLauncherForActivityResult(GalleryPicker()) { uri ->
        source = uri?.toString()
    }
    AppearancePage("课表外观", !model.busy, onDismiss) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("课表背景 · 选择背景", style = MaterialTheme.typography.titleMedium)
            Text("长按背景可设置文字颜色和大小", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            backgrounds.chunked(2).forEach { options ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    options.forEach { option ->
                        SkinOption(option.skin, option.image,
                            model.selected.skin == option.skin && (option.image == null || model.selected.backgroundImage == option.image),
                            !model.busy, Modifier.weight(1f), label = option.label,
                            colors = model.selected.backgroundTextColors[backgroundKey(option.skin, option.image)]
                                ?: defaultBackgroundText(option.skin, timetableDark),
                            onLongClick = { model.clearMessage(); editing = backgroundKey(option.skin, option.image) },
                            onRemove = option.image?.let { image -> { model.removeBackground(image); Unit } },
                            onClick = {
                                if (option.image == null) model.changeSkin(option.skin)
                                else model.selectBackground(option.image)
                            })
                    }
                    if (options.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            HorizontalDivider()
            Text("添加背景", style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                model.clearMessage(); pickerError = ""
                runCatching { picker.launch(Unit) }.onFailure { pickerError = "无法打开相册，请检查相册应用是否已启用" }
            }, enabled = !model.busy,
                modifier = Modifier.fillMaxWidth()) { Text("导入图片并裁剪") }
            Text("外观仅应用于当前课表。导入后可拖动、双指缩放裁剪，夜间会自动压暗背景。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (model.message.isNotEmpty()) Text(model.message, color = MaterialTheme.colorScheme.error)
            if (pickerError.isNotEmpty()) Text(pickerError, color = MaterialTheme.colorScheme.error)
        }
    }
    backgrounds.find { backgroundKey(it.skin, it.image) == editing }?.let { option ->
        BackgroundTextColorDialog(model, option.skin, option.image, option.label) { editing = null; model.clearMessage() }
    }
    source?.let { value ->
        WallpaperCropEditor(Uri.parse(value), aspectRatio, model.busy, model.message,
            onDismiss = { source = null },
            onApply = { bitmap, crop -> model.saveBackground(bitmap, crop) { source = null } })
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SkinOption(skin: TimetableSkin, image: String?, selected: Boolean, enabled: Boolean,
    modifier: Modifier, label: String = skin.label, colors: BackgroundTextColors,
    onLongClick: () -> Unit, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).border(if (selected) 2.dp else 1.dp,
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
        .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick,
            onLongClickLabel = "设置文字").padding(6.dp)) {
        Box(Modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(10.dp))) {
            TimetableWallpaper(skin, image, Modifier.fillMaxSize())
            Text("一    二    三    四    五", Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                color = Color(colors.header), style = MaterialTheme.typography.labelSmall)
            Text("1\n08:00", Modifier.padding(start = 5.dp, top = 34.dp), color = Color(colors.time),
                style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxSize().padding(start = 30.dp, top = 32.dp, end = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                courseColors.take(3).forEachIndexed { index, color ->
                    Box(Modifier.weight(1f).padding(top = (index * 13).dp)
                        .height((58 - index * 9).dp).clip(RoundedCornerShape(4.dp))
                        .background(color.copy(alpha = .8f)))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (selected) Text("已选", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            if (onRemove != null) TextButton(onClick = onRemove, enabled = enabled,
                modifier = Modifier.height(32.dp).semantics { contentDescription = "移除$label" },
                contentPadding = PaddingValues(horizontal = 4.dp)) { Text("移除", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
internal fun AppearancePage(title: String, enabled: Boolean, onDismiss: () -> Unit,
    action: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if (enabled) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val window = (view.parent as DialogWindowProvider).window
        val dark = timetableDark
        SideEffect {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TimetableAction(TableGlyph.CLOSE, "关闭$title", enabled, onClick = onDismiss)
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    action()
                }
                content()
            }
        }
    }
}

@Composable
internal fun WallpaperCropEditor(uri: Uri, aspectRatio: Float, busy: Boolean, message: String,
    onDismiss: () -> Unit, onApply: (Bitmap, WallpaperCrop) -> Unit) {
    val context = LocalContext.current
    var loadError by remember(uri) { mutableStateOf("") }
    val bitmap by produceState<Bitmap?>(null, uri) {
        try {
            value = withContext(Dispatchers.IO) { readWallpaper(context, uri) }
        } catch (exception: CancellationException) { throw exception
        } catch (_: Exception) { loadError = "无法读取这张图片，请返回后重新选择" }
    }
    var zoom by rememberSaveable(uri) { mutableStateOf(1f) }
    var panX by rememberSaveable(uri) { mutableStateOf(0f) }
    var panY by rememberSaveable(uri) { mutableStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    fun crop() = WallpaperCrop(viewport.width, viewport.height, zoom, Offset(panX, panY))
    fun clampPan(image: Bitmap, newZoom: Float, newPan: Offset) {
        val bounds = cropBounds(image, WallpaperCrop(viewport.width, viewport.height, newZoom, newPan))
        panX = bounds.left - (viewport.width - bounds.width()) / 2
        panY = bounds.top - (viewport.height - bounds.height()) / 2
        zoom = newZoom
    }
    AppearancePage("裁剪背景", !busy, onDismiss, action = {
        TextButton(enabled = !busy && bitmap != null && viewport.width > 0 && viewport.height > 0,
            onClick = { bitmap?.let { onApply(it, crop()) } }) { Text(if (busy) "保存中…" else "使用") }
    }) {
        Text("拖动调整位置，双指或滑杆缩放", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            val width = minOf(maxWidth, maxHeight * aspectRatio)
            Box(Modifier.width(width).aspectRatio(aspectRatio).clip(RoundedCornerShape(6.dp))
                .background(Color.Black).border(1.dp, MaterialTheme.colorScheme.outline)
                .onSizeChanged { viewport = it }
                .semantics { contentDescription = "图片裁剪区域" }) {
                val image = bitmap
                if (image != null) {
                    Canvas(Modifier.fillMaxSize().pointerInput(image, busy) {
                        detectTransformGestures { centroid, pan, gestureZoom, _ ->
                            if (!busy && viewport.width > 0 && viewport.height > 0) {
                                val next = (zoom * gestureZoom).coerceIn(1f, 5f)
                                val ratio = next / zoom
                                val center = Offset(viewport.width / 2f, viewport.height / 2f)
                                val oldPan = Offset(panX, panY)
                                clampPan(image, next, oldPan * ratio + (centroid - center) * (1f - ratio) + pan)
                            }
                        }
                    }) {
                        if (viewport.width > 0 && viewport.height > 0) {
                            drawIntoCanvas { it.nativeCanvas.drawBitmap(image, null, cropBounds(image, crop()), paint) }
                            for (i in 1..2) {
                                drawLine(Color.White.copy(alpha = .4f), Offset(size.width * i / 3, 0f),
                                    Offset(size.width * i / 3, size.height), 1.dp.toPx())
                                drawLine(Color.White.copy(alpha = .4f), Offset(0f, size.height * i / 3),
                                    Offset(size.width, size.height * i / 3), 1.dp.toPx())
                            }
                        }
                    }
                } else if (loadError.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
        if (loadError.isNotEmpty() || message.isNotEmpty()) Text(loadError.ifEmpty { message },
            Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("缩放", style = MaterialTheme.typography.bodySmall)
            Slider(zoom, { next -> bitmap?.let { if (viewport.width > 0) clampPan(it, next, Offset(panX, panY)) } },
                Modifier.weight(1f).padding(horizontal = 12.dp), enabled = !busy && bitmap != null, valueRange = 1f..5f)
            TextButton(enabled = !busy, onClick = { zoom = 1f; panX = 0f; panY = 0f }) { Text("重置") }
        }
    }
}
