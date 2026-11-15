package com.nexttodo.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun EventEditor(
    event: Event,
    enabled: Boolean,
    onChange: (Event) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EventType.entries.forEach { type ->
                FilterChip(
                    selected = event.type == type,
                    onClick = { onChange(event.withType(type)) },
                    enabled = enabled,
                    label = { Text(type.label) },
                )
            }
        }
        EditorText("事件名称", event.title, enabled) { onChange(event.copy(title = it)) }
        EditorText("描述", event.description, enabled, singleLine = false) {
            onChange(event.copy(description = it))
        }
        TimeField("事件时间", event.time, enabled, initialDate = event.day) {
            onChange(event.copy(time = it))
        }
        AttachmentField("原图", event.originalImage, FieldType.IMAGE, enabled) {
            onChange(event.copy(originalImage = it))
        }
        event.fields.forEachIndexed { index, field ->
            key(event.id, field.id, field.type) {
                val preset = field.presetFor != null
                FieldEditor(
                    field = field,
                    enabled = enabled,
                    preset = preset,
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
        ) { Text("+ 添加属性") }
        Button(onClick = onSave, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("保存事件")
        }
        TextButton(onClick = onReset, enabled = enabled) {
            Text("新建空白事件")
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
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
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
private fun TimeField(
    label: String,
    value: String,
    enabled: Boolean,
    initialDate: LocalDate? = null,
    onChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val dialogTheme = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        R.style.Theme_NextToDo_Dialog_Dark
    } else {
        R.style.Theme_NextToDo_Dialog_Light
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { pickDateTime(context, dialogTheme, value, initialDate, onChange) },
            enabled = enabled,
        ) {
            Text(
                when {
                    value.isNotBlank() -> "$label · ${eventTimeLabel(value)}"
                    initialDate != null -> "选择$label · ${initialDate.format(dayFormat)}"
                    else -> "选择$label"
                }
            )
        }
        if (value.isNotBlank()) {
            TextButton(onClick = { onChange("") }, enabled = enabled) { Text("清除") }
        }
    }
}

private fun pickDateTime(
    context: Context,
    dialogTheme: Int,
    value: String,
    initialDate: LocalDate?,
    onChange: (String) -> Unit,
) {
    val now = LocalDateTime.now()
    val initial = when {
        value.isNotBlank() -> LocalDateTime.parse(value)
        initialDate != null -> LocalDateTime.of(initialDate, now.toLocalTime())
        else -> now
    }
    DatePickerDialog(context, dialogTheme, { _, year, month, day ->
        TimePickerDialog(context, dialogTheme, { _, hour, minute ->
            onChange(LocalDateTime.of(year, month + 1, day, hour, minute).toString())
        }, initial.hour, initial.minute, true).show()
    }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}
