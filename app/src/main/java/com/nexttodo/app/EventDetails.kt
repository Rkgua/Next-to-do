package com.nexttodo.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun EventDetails(
    event: Event,
    editable: Boolean,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val time = event.scheduleLabel().takeIf { it.isNotBlank() }
    val attributes = event.fields.filter {
        (it.type == FieldType.TEXT || it.type == FieldType.TIME) && it.value.isNotBlank()
    }
    val attachments = event.fields.filter {
        (it.type == FieldType.IMAGE || it.type == FieldType.FILE) && it.attachment != null
    }

    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart).semantics { contentDescription = "返回" },
            ) {
                Canvas(Modifier.size(22.dp)) {
                    val left = Offset(size.width * .22f, size.height * .5f)
                    val right = Offset(size.width * .82f, size.height * .5f)
                    val stroke = 2.dp.toPx()
                    drawLine(colors.onSurface, left, right, stroke, StrokeCap.Round)
                    drawLine(colors.onSurface, left, Offset(size.width * .49f, size.height * .23f), stroke, StrokeCap.Round)
                    drawLine(colors.onSurface, left, Offset(size.width * .49f, size.height * .77f), stroke, StrokeCap.Round)
                }
            }
            Text("事件详情", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FilledTonalButton(
                onClick = onEdit, enabled = editable,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) { Text("编辑") }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge)
                    .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.secondaryContainer)))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        event.type.label, style = MaterialTheme.typography.labelLarge,
                        color = colors.onPrimaryContainer,
                    )
                    Surface(
                        color = if (event.completed) colors.primary else colors.surface.copy(alpha = .9f),
                        contentColor = if (event.completed) colors.onPrimary else colors.onSurfaceVariant,
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            if (event.completed) "已完成" else "待完成",
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                SelectionContainer {
                    Text(
                        event.title, modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (time != null || (event.reminderEnabled && !event.completed)) {
                DetailCard {
                    if (time != null) DetailValue(if (event.time.isBlank()) "日期" else "事件时间", time)
                    SystemAlarmButton(event, editable)
                    if (event.reminderEnabled && !event.completed) {
                        if (time != null) HorizontalDivider(color = colors.outlineVariant)
                        DetailValue("到点提醒", "已开启")
                    }
                }
            }
            if (event.description.isNotBlank()) {
                DetailCard { DetailValue("描述", event.description) }
            }
            if (attributes.isNotEmpty()) {
                DetailCard {
                    attributes.forEachIndexed { index, field ->
                        if (index > 0) HorizontalDivider(color = colors.outlineVariant)
                        val value = if (field.type == FieldType.TIME) eventTimeLabel(field.value) else field.value
                        DetailValue(field.name.ifBlank { field.type.label }, value)
                    }
                }
            }
            if (attachments.isNotEmpty() || event.originalImage != null) {
                DetailCard {
                    Text("附件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    attachments.forEach { field ->
                        val attachment = checkNotNull(field.attachment)
                        Text(field.name.ifBlank { field.type.label }, style = MaterialTheme.typography.labelLarge)
                        if (field.type == FieldType.IMAGE) ImagePreview(attachment) else OpenAttachment(attachment)
                    }
                    event.originalImage?.let { attachment ->
                        Text("原图", style = MaterialTheme.typography.labelLarge)
                        ImagePreview(attachment)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun DetailValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun OpenAttachment(attachment: Attachment) {
    val context = LocalContext.current
    var failed by remember(attachment.uri) { mutableStateOf(false) }
    OutlinedButton(onClick = {
        val uri = Uri.parse(attachment.uri)
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, context.contentResolver.getType(uri) ?: "application/octet-stream")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
            failed = false
        } catch (_: ActivityNotFoundException) {
            failed = true
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("打开 ${attachment.name}") }
    if (failed) Text("没有可打开此文件的应用")
}

@Composable
internal fun ImagePreview(attachment: Attachment) {
    val context = LocalContext.current
    var expanded by remember(attachment.uri) { mutableStateOf(false) }
    var failed by remember(attachment.uri) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, attachment.uri) {
        value = null
        value = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val uri = Uri.parse(attachment.uri)
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                var sampleSize = 1
                while (maxOf(options.outWidth, options.outHeight) / sampleSize > 2400) sampleSize *= 2
                options.inJustDecodeBounds = false
                options.inSampleSize = sampleSize
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?.asImageBitmap()
            }.getOrNull()
        }
        failed = value == null
    }
    val image = bitmap
    if (image != null) {
        Image(
            image,
            "查看 ${attachment.name}",
            Modifier.fillMaxWidth().heightIn(max = 200.dp)
                .clip(MaterialTheme.shapes.medium).clickable { expanded = true },
        )
        if (expanded) {
            Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(12.dp)) {
                        Image(image, attachment.name, Modifier.fillMaxSize().padding(top = 48.dp))
                        TextButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.TopEnd)) {
                            Text("关闭")
                        }
                    }
                }
            }
        }
    } else if (failed) {
        Text("无法预览图片")
    }
}
