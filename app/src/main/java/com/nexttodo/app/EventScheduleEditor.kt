package com.nexttodo.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val durationTimeFormat = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")

@Composable
internal fun EventScheduleEditor(event: Event, enabled: Boolean, onChange: (Event) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("持续事件", style = MaterialTheme.typography.bodyLarge)
            Text("适合跨时段或多日的安排", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(event.isDuration, { onChange(event.withDuration(it, LocalDateTime.now())) }, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "切换持续事件" })
    }
    if (event.isDuration) {
        TimeField("开始时间", event.durationStart().toString(), enabled, allowClear = false, format = durationTimeFormat) {
            val start = LocalDateTime.parse(it)
            onChange(event.copy(time = it, day = start.toLocalDate(), repetition = EventRepeat.NONE,
                endTime = if (event.durationEnd() <= start) start.plusHours(1).toString() else event.endTime,
                endDay = if (event.durationEnd() <= start) start.plusHours(1).toLocalDate() else event.endDay))
        }
        TimeField("结束时间", event.durationEnd().toString(), enabled, allowClear = false, format = durationTimeFormat) {
            onChange(event.copy(endTime = it, endDay = LocalDateTime.parse(it).toLocalDate(), repetition = EventRepeat.NONE))
        }
        Text("从开始到结束的每一天都会显示，进行中时显示在 Today 的「长期事件」中。", style = MaterialTheme.typography.bodySmall)
    } else {
        TimeField("事件时间", event.time, enabled, initialDate = event.day) {
            onChange(event.copy(time = it, day = it.takeIf(String::isNotBlank)?.let { value -> LocalDateTime.parse(value).toLocalDate() } ?: event.day,
                reminderEnabled = it.isNotBlank() && event.reminderEnabled))
        }
    }
    event.validateSchedule()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
