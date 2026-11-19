package com.nexttodo.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.AlarmClock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDateTime
import java.util.Calendar

internal fun systemAlarmIntent(event: Event): Intent {
    val time = LocalDateTime.parse(event.time)
    val weekdays = when (event.repetition) {
        EventRepeat.DAILY -> (1..7).toList()
        EventRepeat.WEEKLY, EventRepeat.CUSTOM -> event.repeatDays.ifEmpty { setOf(time.dayOfWeek.value) }.sorted()
        EventRepeat.WEEKDAYS -> (1..5).toList()
        EventRepeat.WEEKENDS -> listOf(6, 7)
        EventRepeat.SUNDAY_OFF -> (1..6).toList()
        else -> emptyList()
    }
    return Intent(AlarmClock.ACTION_SET_ALARM).apply {
        putExtra(AlarmClock.EXTRA_HOUR, time.hour)
        putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
        putExtra(AlarmClock.EXTRA_MESSAGE, event.title.ifBlank { "NextTodo 事件" })
        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        putExtra(AlarmClock.EXTRA_VIBRATE, true)
        if (weekdays.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS,
            ArrayList(weekdays.map { if (it == 7) Calendar.SUNDAY else it + 1 }))
    }
}

@Composable
internal fun SystemAlarmButton(event: Event, enabled: Boolean = true) {
    if (event.time.isBlank()) return
    val context = LocalContext.current
    var confirming by remember(event.id) { mutableStateOf(false) }
    var error by remember(event.id) { mutableStateOf<String?>(null) }
    OutlinedButton(onClick = { confirming = true }, enabled = enabled) { Text("添加到系统闹钟") }
    error?.let { Text(it) }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("创建系统闹钟") },
        text = { Text("${event.scheduleLabel()}\n\n将打开手机时钟，并填写名称、时刻及支持的每周重复规则。系统接口不支持指定日期、每月、调休及轮班规则，请在时钟中核对日期和重复设置。\n\n系统闹钟独立保存，之后修改或删除事件，也需要到时钟中修改或删除闹钟。") },
        confirmButton = { TextButton(onClick = {
            confirming = false
            error = null
            try { context.startActivity(systemAlarmIntent(event)) }
            catch (_: ActivityNotFoundException) { error = "未找到支持创建闹钟的时钟应用" }
            catch (_: SecurityException) { error = "系统未允许创建闹钟，请在手机时钟中手动添加" }
        }) { Text("打开时钟创建") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("取消") } },
    )
}
