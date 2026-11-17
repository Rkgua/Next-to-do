package com.nexttodo.app

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
internal fun TimetableControls(
    model: TimetableViewModel,
    today: LocalDate,
    week: Int,
    count: Int,
    onWeek: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val enabled = !model.busy
    Popup(
        alignment = Alignment.BottomCenter,
        offset = IntOffset.Zero,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val shape = RoundedCornerShape(18.dp)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 15.dp)
                .border(1.dp, Color(0xFFCBCAD4), shape),
            shape = shape, color = Color(0xFFF8F6FC).copy(alpha = .96f),
        ) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TableText("周数", 13, modifier = Modifier.align(Alignment.CenterStart))
                    TableText(
                        "第${week}周", 13, Color.White,
                        Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xFF2E3039))
                            .padding(horizontal = 13.dp, vertical = 9.dp),
                    )
                    TextButton(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        onClick = { dialog = "week" }, enabled = enabled,
                    ) { TableText("修改当前周", 12, timetableAccent) }
                }
                WeekSlider(week, count, enabled, onWeek)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TableText("课表", 13, modifier = Modifier.weight(1f))
                    TextButton(onClick = { dialog = "new" }, enabled = enabled) {
                        TableText("新建课表", 12, timetableAccent)
                    }
                    TextButton(onClick = { dialog = "manage" }, enabled = enabled) {
                        TableText("管理", 12, timetableAccent)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    model.tables.forEach { table ->
                        val selected = table.id == model.selectedId
                        Column(
                            Modifier.width(50.dp).clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = enabled) { model.selectTable(table.id) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(
                                    Brush.verticalGradient(
                                        if (selected) listOf(Color(0xFF8F9BBB), Color(0xFF7B89AD))
                                        else listOf(timetableTop, timetableBottom)
                                    )
                                ),
                                contentAlignment = Alignment.Center,
                            ) { if (selected) TableIcon(TableGlyph.CHECK, Color.White, 23.dp) }
                            TableText(
                                table.name, 10, if (selected) timetableAccent else timetableInk,
                                Modifier.padding(top = 7.dp, bottom = 4.dp), bold = selected, maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
    when (val showing = dialog) {
        "week" -> {
            var replacement by rememberSaveable { mutableStateOf(week) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("修改当前周") },
                text = {
                    Column {
                        Text("把今天所在的一周设为第 ${replacement} 周")
                        WeekSlider(replacement, count, enabled) { replacement = it }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = enabled,
                        onClick = { model.changeCurrentWeek(replacement, today) { dialog = null } },
                    ) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } },
            )
        }
        "new" -> TableDetails(
            table = null, today = today, busy = model.busy, onDismiss = { dialog = null },
            onSave = { name, date -> model.createTable(name, date) { dialog = null } },
        )
        "manage" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("管理课表") },
            text = {
                Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState())) {
                    model.tables.forEach { table ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(table.name, Modifier.weight(1f), maxLines = 2)
                            TextButton(enabled = enabled, onClick = { dialog = "edit:${table.id}" }) { Text("编辑") }
                            if (model.tables.size > 1) {
                                TextButton(enabled = enabled, onClick = { dialog = "delete:${table.id}" }) { Text("删除") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("完成") } },
        )
        null -> Unit
        else -> model.tables.find { it.id == showing.substringAfter(':') }?.let { table ->
            if (showing.startsWith("edit:")) {
                TableDetails(
                    table, today, model.busy, { dialog = "manage" },
                    onSave = { name, date -> model.editTable(table, name, date) { dialog = "manage" } },
                )
            } else {
                AlertDialog(
                    onDismissRequest = { dialog = "manage" },
                    title = { Text("删除 ${table.name}？") },
                    text = { Text("这张课表里的课程也会被删除。") },
                    confirmButton = {
                        Button(enabled = enabled, onClick = { model.removeTable(table.id) { dialog = "manage" } }) {
                            Text("删除")
                        }
                    },
                    dismissButton = { TextButton(onClick = { dialog = "manage" }) { Text("取消") } },
                )
            }
        }
    }
}

@Composable
private fun WeekSlider(week: Int, count: Int, enabled: Boolean, onWeek: (Int) -> Unit) {
    Slider(
        value = week.toFloat(), onValueChange = { onWeek(it.roundToInt().coerceIn(1, count)) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        valueRange = 1f..count.toFloat(),
        enabled = enabled,
        colors = SliderDefaults.colors(
            thumbColor = timetableAccent, activeTrackColor = timetableAccent,
            inactiveTrackColor = Color(0xFFDCDEF7),
        ),
    )
}

@Composable
private fun TableDetails(
    table: Timetable?,
    today: LocalDate,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, LocalDate) -> Unit,
) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(table?.name.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(table?.firstDay?.toString()) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (table == null) "新建课表" else "编辑课表") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("课表名称") }, singleLine = true)
                TextButton(
                    enabled = !busy,
                    onClick = {
                        val initial = date?.let(LocalDate::parse) ?: today.monday()
                        DatePickerDialog(context, { _, year, month, day ->
                            date = LocalDate.of(year, month + 1, day).toString()
                        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
                    },
                ) { Text(date?.let { "课表第一天  $it" } ?: "选择课表第一天") }
                Text("所选日期所在的一周为第 1 周。")
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && date != null,
                onClick = { date?.let { onSave(name, LocalDate.parse(it)) } },
            ) { Text(if (table == null) "新建" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}
