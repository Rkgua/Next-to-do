package com.nexttodo.app

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
internal fun EventScheduleEditor(event: Event, enabled: Boolean, onChange: (Event) -> Unit) {
    val context = LocalContext.current
    val dialogTheme = if (timetableDark) R.style.Theme_NextToDo_Dialog_Dark else R.style.Theme_NextToDo_Dialog_Light
    var repeat by rememberSaveable(event.id) { mutableStateOf(false) }
    fun date(initial: LocalDate, callback: (LocalDate) -> Unit) {
        DatePickerDialog(context, dialogTheme, { _, y, m, d -> callback(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }
    if (event.time.isBlank()) OutlinedButton(enabled = enabled, onClick = {
        date(event.startDay()) { onChange(event.copy(day = it)) }
    }) { Text("开始日期 · ${event.startDay()}") }
    val lasting = event.endTime.isNotBlank() || event.endDay != null
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("持续事件", Modifier.weight(1f))
        Switch(lasting, { checked -> onChange(event.copy(endDay = if (checked) event.startDay() else null, endTime = "")) }, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "切换持续事件" })
    }
    if (lasting) {
        OutlinedButton(enabled = enabled, onClick = { date(event.finishDay()) { onChange(event.copy(endDay = it, endTime = "")) } }) {
            Text("结束日期 · ${event.finishDay()}")
        }
        TimeField("结束时间（可选）", event.endTime, enabled, initialDate = event.finishDay()) {
            onChange(event.copy(endTime = it, endDay = event.finishDay()))
        }
        Text("从开始到结束的每一天都会显示。", style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(enabled = enabled, onClick = { repeat = true }) { Text("重复 · ${event.repetition.label}") }
    if (repeat) AlertDialog(onDismissRequest = { repeat = false }, title = { Text("重复方式") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            EventRepeat.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { onChange(event.copy(repetition = option)); repeat = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(event.repetition == option, { onChange(event.copy(repetition = option)); repeat = false })
                    Text(option.label)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { repeat = false }) { Text("完成") } })
    if (event.repetition in listOf(EventRepeat.WEEKLY, EventRepeat.CUSTOM, EventRepeat.BIWEEKLY)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val days = event.repeatDays.ifEmpty { setOf(event.startDay().dayOfWeek.value) }
            (1..7).forEach { day ->
                FilterChip(day in days, { val next = if (day in days) days - day else days + day
                    if (next.isNotEmpty()) onChange(event.copy(repeatDays = next)) },
                    label = { Text(listOf("一", "二", "三", "四", "五", "六", "日")[day - 1]) }, enabled = enabled)
            }
        }
    }
    if (event.repetition == EventRepeat.ROTATION) OutlinedTextField(event.repeatInterval.toString(), {
        it.toIntOrNull()?.takeIf { number -> number in 1..365 }?.let { number -> onChange(event.copy(repeatInterval = number)) }
    }, label = { Text("轮班周期共几天（1—365）") }, enabled = enabled)
    if (event.repetition == EventRepeat.ROTATION) {
        OutlinedTextField(event.repeatWorkDays.toString(), { value -> value.toIntOrNull()?.takeIf { it in 1..event.repeatInterval }?.let { onChange(event.copy(repeatWorkDays = it)) } },
            label = { Text("每个周期前几天工作") }, enabled = enabled)
        Text("开始日期作为周期第一天，其余天数休息。", style = MaterialTheme.typography.bodySmall)
    }
    if (event.repetition == EventRepeat.ALTERNATING) Text("开始日期所在周为大周：周一至周六；下一周为小周：周一至周五。", style = MaterialTheme.typography.bodySmall)
    if (event.repetition in listOf(EventRepeat.WORKDAYS, EventRepeat.HOLIDAYS)) Text("中国大陆调休数据覆盖 2026、2027 年。未收录年份暂不生成这两种重复安排。", style = MaterialTheme.typography.bodySmall)
    event.validateSchedule()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
