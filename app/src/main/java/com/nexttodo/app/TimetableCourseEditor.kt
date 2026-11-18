package com.nexttodo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

private val editorBackground: Color
    @Composable get() = if (timetableDark) timetableTop else Color(0xFFF6F6F6)
private val editorPink: Color
    @Composable get() = if (timetableDark) timetableAccent else Color(0xFFD9476D)
private val editorHint: Color
    @Composable get() = if (timetableDark) timetableMuted else Color(0xFFA5A5A5)
private val editorValue: Color
    @Composable get() = if (timetableDark) Color(0xFFC0C2CB) else Color(0xFF606060)
private val editorShape = RoundedCornerShape(13.dp)

private data class CoursePeriod(
    val day: Int?,
    val first: Int?,
    val last: Int?,
    val weeks: String,
    val time: String,
    val room: String,
    val teacher: String,
    val notes: String,
)

private val periodsSaver = listSaver<List<CoursePeriod>, String>(
    save = { periods ->
        periods.flatMap { period ->
            listOf(
                period.day?.toString().orEmpty(), period.first?.toString().orEmpty(),
                period.last?.toString().orEmpty(), period.weeks, period.time, period.room,
                period.teacher, period.notes,
            )
        }
    },
    restore = { saved ->
        saved.chunked(8).map {
            CoursePeriod(it[0].toIntOrNull(), it[1].toIntOrNull(), it[2].toIntOrNull(), it[3], it[4], it[5], it[6], it[7])
        }
    },
)

private enum class PeriodField(val label: String) {
    WEEKS("周数"), SLOTS("节数"), TIME("自定义时间"), ROOM("教室"), TEACHER("老师"), NOTES("备注"),
}

@Composable
internal fun CourseEditor(
    course: CourseSlot?,
    busy: Boolean,
    message: String,
    onDismiss: () -> Unit,
    onSave: (List<CourseSlot>) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(course?.name.orEmpty()) }
    var credits by rememberSaveable { mutableStateOf(course?.credits.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(course?.displayColor()?.toArgb() ?: courseColors[4].toArgb()) }
    var periods by rememberSaveable(stateSaver = periodsSaver) {
        val period = if (course == null) CoursePeriod(1, 1, 2, "1-20", "", "", "", "") else {
            CoursePeriod(
                course.weekday, course.firstPeriod, course.lastPeriod, course.weeks,
                course.customTime, course.room, course.teacher, course.notes,
            )
        }
        mutableStateOf(listOf(period))
    }
    var generalField by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingDeletion by rememberSaveable { mutableStateOf(false) }
    var editPeriod by rememberSaveable { mutableStateOf<Int?>(null) }
    var periodField by rememberSaveable { mutableStateOf<PeriodField?>(null) }

    fun edit(index: Int, field: PeriodField) {
        editPeriod = index
        periodField = field
    }

    fun update(index: Int, value: CoursePeriod) {
        periods = periods.toMutableList().apply { this[index] = value }
        editPeriod = null
        periodField = null
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        val window = (view.parent as DialogWindowProvider).window
        val dark = timetableDark
        SideEffect {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        Column(Modifier.fillMaxSize().background(editorBackground).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.align(Alignment.CenterStart)) {
                    TimetableAction(TableGlyph.CLOSE, "关闭编辑", !busy, onClick = onDismiss)
                }
                TableText(if (course == null) "添加课程" else "编辑课程", 18, bold = true)
                Surface(
                    modifier = Modifier.align(Alignment.CenterEnd).clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = name.isNotBlank() && !busy) {
                            onSave(periods.map { period ->
                                CourseSlot(
                                    name.trim(), period.day, period.first, period.last, period.teacher.trim(),
                                    period.room.trim(), period.weeks.trim(), color, credits.trim(),
                                    period.time.trim(), period.notes.trim(),
                                )
                            })
                        },
                    color = timetableSurface,
                ) {
                    TableText(
                        "保存", 15, if (name.isNotBlank() && !busy) editorPink else editorHint,
                        Modifier.padding(horizontal = 17.dp, vertical = 9.dp), bold = true,
                    )
                }
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Surface(shape = editorShape, color = timetableSurface) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        BasicTextField(
                            value = name, onValueChange = { name = it }, singleLine = true, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 19.dp)
                                .semantics { contentDescription = "课程名称" },
                            textStyle = TextStyle(color = timetableInk, fontSize = 17.sp, fontFamily = timetableFont),
                            cursorBrush = SolidColor(editorPink),
                            decorationBox = { field ->
                                Box {
                                    if (name.isEmpty()) TableText("输入课程名称", 17, editorHint, bold = true)
                                    field()
                                }
                            },
                        )
                        EditorDivider()
                        EditorRow(TableGlyph.COLOR, "颜色", onClick = { generalField = "color" }) {
                            Box(
                                Modifier.size(24.dp).clip(CircleShape).background(
                                    Brush.sweepGradient(courseColors + courseColors.first())
                                ).padding(2.dp).clip(CircleShape).background(Color(color))
                                    .border(1.dp, Color.White, CircleShape),
                            )
                        }
                        EditorDivider()
                        EditorItem(TableGlyph.STAR, "学分", credits, false) { generalField = "credits" }
                    }
                }
                periods.forEachIndexed { index, period ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TableText("时段${index + 1}", 13, editorValue, Modifier.weight(1f))
                        TextButton(
                            onClick = { periods = periods.toMutableList().apply { add(index + 1, period) } },
                            enabled = !busy,
                        ) { TableText("复制", 13, editorPink) }
                        TextButton(
                            onClick = {
                                if (periods.size == 1) confirmingDeletion = true
                                else periods = periods.filterIndexed { position, _ -> index != position }
                            },
                            enabled = !busy && (periods.size > 1 || course != null),
                        ) { TableText("删除", 13, if (periods.size > 1 || course != null) timetableInk else editorHint) }
                    }
                    Surface(shape = editorShape, color = timetableSurface) {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            val weeks = if (period.weeks.isBlank()) "每周" else "第${period.weeks.removeSuffix("周")}周"
                            val slots = period.day?.let { "周${timetableDays[it - 1]}　 第${period.first} - ${period.last}节" }
                                ?: "未指定节次"
                            EditorItem(TableGlyph.CALENDAR, "周数", weeks, true) { edit(index, PeriodField.WEEKS) }
                            EditorDivider()
                            EditorItem(TableGlyph.CLOCK, "节数", slots, true) { edit(index, PeriodField.SLOTS) }
                            EditorDivider()
                            EditorItem(TableGlyph.CUSTOM, "自定义时间", period.time.ifBlank { "关闭" }, true) {
                                edit(index, PeriodField.TIME)
                            }
                            EditorDivider()
                            EditorItem(TableGlyph.ROOM, "教室", period.room, false) { edit(index, PeriodField.ROOM) }
                            EditorDivider()
                            EditorItem(TableGlyph.PERSON, "老师", period.teacher, false) { edit(index, PeriodField.TEACHER) }
                            EditorDivider()
                            EditorItem(TableGlyph.NOTES, "备注", period.notes, false) { edit(index, PeriodField.NOTES) }
                        }
                    }
                }
                if (message.isNotEmpty()) {
                    TableText(message, 13, editorPink, Modifier.padding(12.dp))
                }
            }
        }
    }

    if (confirmingDeletion) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmingDeletion = false },
            title = { Text("删除课程？") },
            text = { Text(course?.name.orEmpty()) },
            confirmButton = { Button(onClick = { onSave(emptyList()) }, enabled = !busy) { Text("删除") } },
            dismissButton = {
                TextButton(onClick = { confirmingDeletion = false }, enabled = !busy) { Text("取消") }
            },
        )
    }

    when (generalField) {
        "color" -> AlertDialog(
            onDismissRequest = { generalField = null },
            title = { Text("颜色") },
            text = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    courseColors.forEachIndexed { index, option ->
                        val tint = option.toArgb()
                        Box(
                            Modifier.size(29.dp).clip(CircleShape).background(option)
                                .semantics { contentDescription = "课程颜色 ${index + 1}" }
                                .clickable { color = tint; generalField = null },
                            contentAlignment = Alignment.Center,
                        ) { if (color == tint) TableIcon(TableGlyph.CHECK, Color.White, 16.dp) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { generalField = null }) { Text("关闭") } },
        )
        "credits" -> EditorValue("学分", credits, "选填", { generalField = null }) {
            credits = it
            generalField = null
        }
    }

    val index = editPeriod
    val period = index?.let(periods::getOrNull)
    val field = periodField
    if (index != null && period != null && field != null) {
        val dismiss = { editPeriod = null; periodField = null }
        if (field == PeriodField.SLOTS) {
            PeriodPicker(period, dismiss) { update(index, it) }
        } else {
            val (value, hint) = when (field) {
                PeriodField.WEEKS -> period.weeks to "例如 1-16、18、1-15单周；留空为每周"
                PeriodField.TIME -> period.time to "例如 18:30–19:15；留空为关闭"
                PeriodField.ROOM -> period.room to "选填"
                PeriodField.TEACHER -> period.teacher to "选填"
                PeriodField.NOTES -> period.notes to "选填"
                PeriodField.SLOTS -> error("Handled by the period picker")
            }
            EditorValue(field.label, value, hint, dismiss) { updated ->
                val changed = when (field) {
                    PeriodField.WEEKS -> period.copy(weeks = updated)
                    PeriodField.TIME -> period.copy(time = updated)
                    PeriodField.ROOM -> period.copy(room = updated)
                    PeriodField.TEACHER -> period.copy(teacher = updated)
                    PeriodField.NOTES -> period.copy(notes = updated)
                    PeriodField.SLOTS -> error("Handled by the period picker")
                }
                update(index, changed)
            }
        }
    }
}

@Composable
private fun EditorDivider() = HorizontalDivider(color = timetableOutline)

@Composable
private fun EditorRow(
    glyph: TableGlyph, label: String, onClick: () -> Unit,
    value: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 51.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TableIcon(glyph, size = 17.dp)
        TableText(label, 15, modifier = Modifier.weight(1f), bold = true)
        value()
    }
}

@Composable
private fun EditorItem(glyph: TableGlyph, label: String, value: String, next: Boolean, onClick: () -> Unit) {
    EditorRow(glyph, label, onClick) {
        TableText(value.ifBlank { "选填" }, 14, if (value.isBlank()) editorHint else editorValue, maxLines = 1)
        if (next) TableIcon(TableGlyph.NEXT, editorValue, size = 15.dp)
    }
}

@Composable
private fun EditorValue(title: String, value: String, hint: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                draft, { draft = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(hint) }, singleLine = title != "备注",
            )
        },
        confirmButton = { Button(onClick = { onSave(draft) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PeriodPicker(period: CoursePeriod, onDismiss: () -> Unit, onSave: (CoursePeriod) -> Unit) {
    var day by rememberSaveable { mutableStateOf(period.day) }
    var first by rememberSaveable { mutableStateOf(period.first?.toString() ?: "1") }
    var last by rememberSaveable { mutableStateOf(period.last?.toString() ?: "2") }
    val start = first.toIntOrNull()
    val end = last.toIntOrNull()
    val valid = day == null || (start != null && end != null && start in 1..end)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("节数") },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    timetableDays.forEachIndexed { index, label ->
                        FilterChip(day == index + 1, { day = index + 1 }, label = { Text("周$label") })
                    }
                }
                FilterChip(day == null, { day = null }, label = { Text("未指定节次") })
                if (day != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            first, { first = it.filter(Char::isDigit) }, Modifier.weight(1f),
                            label = { Text("开始节数") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            last, { last = it.filter(Char::isDigit) }, Modifier.weight(1f),
                            label = { Text("结束节数") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid, onClick = {
                    onSave(period.copy(day = day, first = start.takeIf { day != null }, last = end.takeIf { day != null }))
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
