package com.nexttodo.app

import android.app.DatePickerDialog
import android.app.NotificationManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun EventEditor(
    event: Event,
    enabled: Boolean,
    recognizing: Boolean,
    onChange: (Event) -> Unit,
    onRecognize: (Attachment) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    children: List<Event> = emptyList(),
    onChildrenChange: ((List<Event>) -> Unit)? = null,
    parentTitle: String? = null,
    saveLabel: String = "保存事件",
    showRecognition: Boolean = true,
) {
    val context = LocalContext.current
    val recognitionPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onRecognize(selectedAttachment(context, it, "原图")) }
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (showRecognition) Surface(
                onClick = {
                    recognitionPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)) {
                        Icon(painterResource(R.drawable.editor_sparkles), null,
                            Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (recognizing) "正在识别图片…" else "AI 识别图片",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("所选图片发送至 DeepSeek，识别后可修改", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                    }
                    Icon(painterResource(R.drawable.settings_chevron), null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
            EditorSection("事件内容", R.drawable.tab_add) {
                parentTitle?.let { Text("子事件 · $it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary) }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EventType.entries.forEach { type ->
                        FilterChip(
                            selected = event.type == type,
                            onClick = { onChange(event.withType(type)) },
                            enabled = enabled,
                            label = { Text(type.label, modifier = Modifier.padding(horizontal = 4.dp)) },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(0.dp, Color.Transparent),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
                EditorText("事件名称", event.title, enabled) { onChange(event.copy(title = it)) }
                EditorText("描述（选填）", event.description, enabled, singleLine = false) {
                    onChange(event.copy(description = it))
                }
                event.fields.forEachIndexed { index, field ->
                    if (field.presetFor != null) key(event.id, field.id, field.type) {
                        FieldEditor(field, enabled, true, { updated ->
                            onChange(event.copy(fields = event.fields.toMutableList().also { it[index] = updated }))
                        }, {})
                    }
                }
            }
            if (event.parentId == null && onChildrenChange != null) {
                EditorSection("子事件", R.drawable.tab_add) {
                    ChildEventsEditor(event, children, enabled, onChildrenChange)
                }
            }
            EditorSection("添加标签", R.drawable.editor_tags) {
                EventTagEditor(event.tags, enabled) { onChange(event.copy(tags = it)) }
            }
            EditorSection("时间与提醒", R.drawable.editor_calendar) {
                EventScheduleEditor(event, enabled, onChange)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                val reminderTime = event.timeInMillis()
                val future = reminderTime != null && reminderTime > System.currentTimeMillis()
                val notifications = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
                val hint = when {
                    reminderTime == null -> "请先设置事件时间，再开启到点提醒"
                    !future && event.repetition == EventRepeat.NONE -> if (event.isDuration) "开始时间已过，无法设置开始提醒" else "事件时间已过"
                    event.reminderEnabled && !notifications -> "请在「My → 系统权限」开启通知"
                    event.reminderEnabled && android.os.Build.VERSION.SDK_INT >= 31 &&
                        !context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms() ->
                        "未开启精确闹钟，休眠时提醒可能延迟；请在「My → 系统权限」开启"
                    event.reminderEnabled && !AlarmAlerts.fullScreenAllowed(context) ->
                        "锁屏弹出尚未允许；请在「My → 系统权限」开启锁屏全屏提醒"
                    else -> null
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (event.isDuration) "开始时提醒" else "到点提醒", style = MaterialTheme.typography.bodyLarge)
                        Text(hint ?: if (event.reminderEnabled) "在设定时间响铃提醒" else "开启后不错过重要安排",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = event.reminderEnabled,
                        onCheckedChange = { onChange(event.copy(reminderEnabled = it)) },
                        enabled = enabled && reminderTime != null &&
                            (future || event.reminderEnabled || event.repetition != EventRepeat.NONE),
                        modifier = Modifier.testTag("event-reminder-switch"),
                    )
                }
                SystemAlarmButton(event, enabled)
            }
            EditorSection("附件与属性", R.drawable.editor_attachment) {
                Text("补充图片、文件或其他信息（选填）", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                AttachmentField("原图", event.originalImage, FieldType.IMAGE, enabled) {
                    onChange(event.copy(originalImage = it))
                }
                event.fields.forEachIndexed { index, field ->
                    if (field.presetFor == null) key(event.id, field.id, field.type) {
                        FieldEditor(
                            field = field,
                            enabled = enabled,
                            preset = false,
                            onChange = { updated ->
                                val fields = event.fields.toMutableList()
                                fields[index] = updated
                                onChange(event.copy(fields = fields))
                            },
                            onRemove = {
                                val fields = event.fields.toMutableList()
                                fields.removeAt(index)
                                onChange(event.copy(fields = fields))
                            },
                        )
                    }
                }
                OutlinedButton(
                    onClick = { onChange(event.copy(fields = event.fields + EventField())) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("＋ 添加属性") }
            }
        }
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onReset, enabled = enabled) { Text("清空") }
                Button(onClick = onSave, enabled = enabled,
                    modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)) {
                    Text(saveLabel, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun EditorSection(title: String, icon: Int, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(icon), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun FieldEditor(
    field: EventField,
    enabled: Boolean,
    preset: Boolean,
    onChange: (EventField) -> Unit,
    onRemove: () -> Unit,
) {
    if (preset) {
        EditorText(field.name, field.value, enabled) { onChange(field.copy(value = it)) }
        return
    }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EditorText("属性名称", field.name, enabled) { onChange(field.copy(name = it)) }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FieldType.entries.forEach { type ->
                    FilterChip(
                        selected = type == field.type,
                        onClick = {
                            if (type != field.type) {
                                onChange(field.copy(type = type, value = "", attachment = null))
                            }
                        },
                        enabled = enabled,
                        label = { Text(type.label) },
                    )
                }
            }
            when (field.type) {
                FieldType.TEXT -> EditorText("内容", field.value, enabled) {
                    onChange(field.copy(value = it))
                }
                FieldType.TIME -> TimeField("时间", field.value, enabled) {
                    onChange(field.copy(value = it))
                }
                FieldType.IMAGE, FieldType.FILE ->
                    AttachmentField(field.type.label, field.attachment, field.type, enabled) {
                        onChange(field.copy(attachment = it))
                    }
            }
            TextButton(onClick = onRemove, enabled = enabled) { Text("删除属性") }
        }
    }
}

@Composable
private fun EditorText(
    label: String,
    value: String,
    enabled: Boolean,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    TextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().testTag("event-field-$label"),
        enabled = enabled,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            unfocusedIndicatorColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun AttachmentField(
    label: String,
    attachment: Attachment?,
    type: FieldType,
    enabled: Boolean,
    onChange: (Attachment?) -> Unit,
) {
    val context = LocalContext.current
    val launchPicker: () -> Unit = if (type == FieldType.IMAGE) {
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { onChange(selectedAttachment(context, it, "图片")) }
        }
        val launch: () -> Unit = {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        launch
    } else {
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onChange(selectedAttachment(context, it, "文件")) }
        }
        val launch: () -> Unit = { picker.launch(arrayOf("*/*")) }
        launch
    }
    Column {
        if (attachment != null && type == FieldType.IMAGE) {
            ImagePreview(attachment)
        }
        if (attachment != null) Text(attachment.name, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = launchPicker, enabled = enabled) {
                Text(if (attachment == null) "选择$label" else "更换$label")
            }
            if (attachment != null) {
                TextButton(onClick = { onChange(null) }, enabled = enabled) { Text("移除") }
            }
        }
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

internal fun eventTimeLabel(value: String): String =
    LocalDateTime.parse(value).format(dateFormat)

@Composable
internal fun TimeField(
    label: String,
    value: String,
    enabled: Boolean,
    initialDate: LocalDate? = null,
    allowClear: Boolean = true,
    format: DateTimeFormatter = dateFormat,
    onChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val dialogTheme = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        R.style.Theme_NextToDo_Dialog_Dark
    } else {
        R.style.Theme_NextToDo_Dialog_Light
    }
    var pickingTime by remember(value, initialDate) { mutableStateOf<LocalDateTime?>(null) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { pickEventDate(context, dialogTheme, value, initialDate) { pickingTime = it } },
            enabled = enabled,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
            border = null,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Text(
                when {
                    value.isNotBlank() -> "$label · ${LocalDateTime.parse(value).format(format)}"
                    initialDate != null -> "选择$label · ${initialDate.format(dayFormat)}"
                    else -> "选择$label"
                }
            )
        }
        if (value.isNotBlank() && allowClear) {
            TextButton(onClick = { onChange("") }, enabled = enabled) { Text("清除") }
        }
    }
    pickingTime?.let { initial ->
        WheelTimePicker(label, initial, onDismiss = { pickingTime = null }, onConfirm = { time ->
            pickingTime = null
            onChange(initial.toLocalDate().atTime(time).toString())
        })
    }
}

private fun pickEventDate(
    context: Context,
    dialogTheme: Int,
    value: String,
    initialDate: LocalDate?,
    onPick: (LocalDateTime) -> Unit,
) {
    val now = LocalDateTime.now()
    val initial = when {
        value.isNotBlank() -> LocalDateTime.parse(value)
        initialDate != null -> LocalDateTime.of(initialDate, now.toLocalTime())
        else -> now
    }
    DatePickerDialog(context, dialogTheme, { _, year, month, day ->
        onPick(LocalDateTime.of(year, month + 1, day, initial.hour, initial.minute))
    }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}
