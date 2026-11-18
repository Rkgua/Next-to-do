package com.nexttodo.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun TodayFocusScreen(day: LocalDate, events: EventViewModel, timetable: TimetableViewModel,
    appearance: TodayAppearanceViewModel, onDismiss: () -> Unit, onOpen: (Event) -> Unit, onAdd: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    var backgrounds by rememberSaveable { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val background = appearance.effective(timetable.selected)
    val ink = background.headerColor?.let(::Color)
        ?: Color(defaultBackgroundText(background.skin, timetableDark).header)
    val darkInk = ink.luminance() < .5f
    val dateLabel = remember { DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE) }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val window = (view.parent as DialogWindowProvider).window
        SideEffect {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = darkInk
                isAppearanceLightNavigationBars = darkInk
            }
        }
        Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }) {
            TimetableWallpaper(background.skin, background.image, Modifier.matchParentSize(), background.dim)
            // A subtle scrim keeps the title legible on photographs without whitening the image.
            if (!darkInk) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = .18f), Color.Transparent, Color.Black.copy(alpha = .12f)))))
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TimetableAction(TableGlyph.BACK, "返回日历", color = ink, onClick = onDismiss)
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Today 更多" }) {
                            Text("⋮", color = ink, style = MaterialTheme.typography.headlineSmall)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("排序依据") }, onClick = { menu = false; sorting = true })
                            DropdownMenuItem(text = { Text("背景图设置") }, onClick = { menu = false; backgrounds = true })
                        }
                    }
                }
                Text("Today", Modifier.padding(horizontal = 24.dp), color = ink,
                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(day.format(dateLabel), Modifier.padding(start = 24.dp, top = 6.dp, bottom = 20.dp),
                    color = ink, style = MaterialTheme.typography.titleMedium)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        events.loadingFailed -> Button(onClick = events::reload, enabled = !events.busy,
                            modifier = Modifier.padding(20.dp)) { Text("重新读取事件") }
                        events.busy && events.events.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else -> EventList(
                            events = appearance.sort.sorted(events.events.filter { (it.calendarDay ?: LocalDate.now()) == day }),
                            enabled = !events.busy, onOpen = onOpen, onToggle = events::complete,
                            onMove = events::movePending, allowReorder = appearance.sort == TodaySort.MANUAL,
                            wallpaperInk = ink,
                        )
                    }
                    if (!events.loadingFailed) FloatingActionButton(onClick = onAdd,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp).semantics { contentDescription = "Today 添加事件" },
                        containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                        Text("+", style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        }
        if (sorting) AlertDialog(onDismissRequest = { sorting = false }, title = { Text("排序依据") }, text = {
            Column {
                TodaySort.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable { appearance.changeSort(option); sorting = false }
                        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = appearance.sort == option, onClick = { appearance.changeSort(option); sorting = false })
                        Text(option.label)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { sorting = false }) { Text("关闭") } })
        if (backgrounds) TodayBackgroundSettings(appearance, timetable,
            if (viewport.height > 0) viewport.width.toFloat() / viewport.height else .5f) { backgrounds = false }
    }
}

@Composable
private fun TodayBackgroundSettings(model: TodayAppearanceViewModel, timetable: TimetableViewModel,
    aspectRatio: Float, onDismiss: () -> Unit) {
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerError by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(GalleryPicker()) { source = it?.toString() }
    val background = model.effective(timetable.selected)
    val synced = model.synced
    val busy = model.busy || timetable.busy
    val images = if (synced) timetable.selected.backgroundImages else model.images
    val options = TimetableSkin.entries.filter { it != TimetableSkin.CUSTOM }.map { it to null } +
        images.map { TimetableSkin.CUSTOM to it }
    val message = model.message.ifEmpty { timetable.message }
    AppearancePage("Today 背景", !busy, onDismiss) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("选择背景", style = MaterialTheme.typography.titleMedium)
            Text(if (synced) "已同步课表背景，选择或导入后会同时应用于课表。可在 My 关闭同步。"
                else "当前仅修改 Today 背景。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            options.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (skin, image) ->
                        val selected = background.skin == skin && (skin != TimetableSkin.CUSTOM || background.image == image)
                        val label = if (image == null) skin.label else "自定义 ${images.indexOf(image) + 1}"
                        val shape = RoundedCornerShape(14.dp)
                        Column(Modifier.weight(1f).clip(shape).border(if (selected) 2.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
                            .clickable(enabled = !busy) {
                                if (!synced) model.select(skin, image)
                                else if (image == null) timetable.changeSkin(skin) else timetable.selectBackground(image)
                            }.padding(6.dp)) {
                            Box(Modifier.fillMaxWidth().height(155.dp).clip(RoundedCornerShape(10.dp))) {
                                TimetableWallpaper(skin, image, Modifier.matchParentSize())
                                Text("Today", Modifier.padding(12.dp), color = Color(defaultBackgroundText(skin, timetableDark).header),
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                if (selected) Text("已选", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                if (image != null) TextButton(enabled = !busy, contentPadding = PaddingValues(4.dp),
                                    onClick = { if (synced) timetable.removeBackground(image) else model.remove(image) }) { Text("移除") }
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            HorizontalDivider()
            Text("添加背景", style = MaterialTheme.typography.titleMedium)
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                model.clearMessage(); timetable.clearMessage(); pickerError = ""
                runCatching { picker.launch(Unit) }.onFailure { pickerError = "无法打开相册，请检查相册应用是否已启用" }
            }) { Text("从相册导入并裁剪") }
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
            if (pickerError.isNotEmpty()) Text(pickerError, color = MaterialTheme.colorScheme.error)
        }
    }
    source?.let { uri ->
        WallpaperCropEditor(Uri.parse(uri), aspectRatio, busy, message, onDismiss = { source = null }, onApply = { bitmap, crop ->
            if (synced) timetable.saveBackground(bitmap, crop) { source = null }
            else model.saveBackground(bitmap, crop) { source = null }
        })
    }
}
