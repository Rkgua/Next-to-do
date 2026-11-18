package com.nexttodo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal data class BackgroundTextColors(val header: Int, val time: Int)
internal val brightBackgroundText = BackgroundTextColors(0xFF253247.toInt(), 0xFF46556A.toInt())
internal val darkBackgroundText = BackgroundTextColors(0xFFF1F5FC.toInt(), 0xFFD3DEEE.toInt())

internal fun backgroundKey(skin: TimetableSkin, image: String?) =
    if (skin == TimetableSkin.CUSTOM) "CUSTOM:${image.orEmpty()}" else skin.name

internal fun defaultBackgroundText(skin: TimetableSkin, dark: Boolean): BackgroundTextColors = when (skin) {
    TimetableSkin.DEFAULT -> if (dark) darkBackgroundText else brightBackgroundText
    TimetableSkin.AURORA -> BackgroundTextColors(0xFFF1FFF9.toInt(), 0xFFD4F3EA.toInt())
    TimetableSkin.STARS -> BackgroundTextColors(0xFFEDF5FF.toInt(), 0xFFCCDDF1.toInt())
    TimetableSkin.STARS_2 -> BackgroundTextColors(0xFFF4F1FF.toInt(), 0xFFDDD9F2.toInt())
    TimetableSkin.SWIRL -> BackgroundTextColors(0xFFFFF4E5.toInt(), 0xFFF1E0CB.toInt())
    TimetableSkin.CUSTOM -> darkBackgroundText
}

@Composable
internal fun BackgroundTextColorDialog(model: TimetableViewModel, skin: TimetableSkin, image: String?,
    label: String, onDismiss: () -> Unit) {
    val key = backgroundKey(skin, image)
    val initial = model.selected.backgroundTextColors[key] ?: defaultBackgroundText(skin, timetableDark)
    var header by rememberSaveable(key) { mutableStateOf(initial.header) }
    var time by rememberSaveable(key) { mutableStateOf(initial.time) }
    var field by rememberSaveable(key) { mutableStateOf<String?>(null) }
    val colors = BackgroundTextColors(header, time)
    AlertDialog(
        onDismissRequest = { if (!model.busy) onDismiss() },
        title = { Text("$label · 文字颜色") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BackgroundTextPreview(skin, image, colors)
                Text("通用配色", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(colors == brightBackgroundText, { header = brightBackgroundText.header; time = brightBackgroundText.time },
                        label = { Text("亮色背景") }, enabled = !model.busy)
                    FilterChip(colors == darkBackgroundText, { header = darkBackgroundText.header; time = darkBackgroundText.time },
                        label = { Text("暗色背景") }, enabled = !model.busy)
                }
                HorizontalDivider()
                Text("自定义文字颜色", style = MaterialTheme.typography.titleSmall)
                TextColorRow("头部文字", header, !model.busy) { field = "header" }
                TextColorRow("左侧时间", time, !model.busy) { field = "time" }
                TextButton(enabled = !model.busy, onClick = {
                    model.saveBackgroundTextColors(key, null, onDismiss)
                }) { Text("恢复推荐配色") }
                if (model.message.isNotEmpty()) Text(model.message, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(enabled = !model.busy, onClick = { model.saveBackgroundTextColors(key, colors, onDismiss) }) { Text("保存") }
        },
        dismissButton = { TextButton(enabled = !model.busy, onClick = onDismiss) { Text("取消") } },
    )
    field?.let { editing ->
        TextColorPicker(if (editing == "header") "头部文字颜色" else "左侧时间颜色",
            if (editing == "header") header else time, { field = null }) {
            if (editing == "header") header = it else time = it
            field = null
        }
    }
}

@Composable
private fun TextColorRow(label: String, color: Int, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.weight(1f))
        Text("#%06X".format(color and 0xFFFFFF), style = MaterialTheme.typography.bodySmall)
        Box(Modifier.size(24.dp).clip(CircleShape).background(Color(color))
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
    }
}

@Composable
private fun BackgroundTextPreview(skin: TimetableSkin, image: String?, colors: BackgroundTextColors) {
    Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp))) {
        TimetableWallpaper(skin, image, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("11月    一 16    二 17    三 18", color = Color(colors.header), fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("1\n08:00\n08:45", color = Color(colors.time), style = MaterialTheme.typography.bodySmall)
                Surface(color = courseColors[2], shape = RoundedCornerShape(5.dp)) {
                    Text("课程名称\n@教室", Modifier.padding(10.dp), color = Color.White,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun TextColorPicker(title: String, initial: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var hex by rememberSaveable { mutableStateOf("%06X".format(initial and 0xFFFFFF)) }
    val parsed = parseTextColor(hex)
    val palette = listOf(0xFFF1F5FC, 0xFFD3DEEE, 0xFFD4F3EA, 0xFFDDD9F2, 0xFFF1E0CB,
        0xFF253247, 0xFF46556A, 0xFF23564C, 0xFF414775, 0xFF654D3C)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            palette.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { color ->
                        Box(Modifier.size(32.dp).clip(CircleShape).background(Color(color))
                            .border(if (parsed == color.toInt()) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .semantics { contentDescription = "颜色 #%06X".format(color and 0xFFFFFF) }
                            .clickable { hex = "%06X".format(color and 0xFFFFFF) })
                    }
                }
            }
            OutlinedTextField(hex, { hex = it }, label = { Text("颜色值（如 #D4F3EA）") },
                singleLine = true, isError = parsed == null, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        Button(enabled = parsed != null, onClick = { parsed?.let(onSave) }) { Text("确定") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

internal fun parseTextColor(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    return if (hex.matches(Regex("[0-9a-fA-F]{6}"))) (0xFF000000L or hex.toLong(16)).toInt() else null
}
