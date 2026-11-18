package com.nexttodo.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.max

@Composable
internal fun TimetableAiImportScreen(model: TimetableViewModel, onDismiss: () -> Unit) {
    var paste by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var previewGrid by rememberSaveable { mutableStateOf(false) }
    var pickerError by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { previewGrid = false; model.chooseAiImportFile(it) }
    }
    val gallery = rememberLauncherForActivityResult(GalleryPicker()) { uri ->
        uri?.let { previewGrid = false; model.chooseAiImportFile(it) }
    }
    val draft = model.aiImportDraft
    val source = model.aiImportSource
    val busy = model.busy
    val canClose = !busy || model.aiImportStage.isNotEmpty()
    fun close() { model.closeAiImport(); onDismiss() }
    AppearancePage(if (draft == null) "AI 解析导入" else "AI 导入预览", canClose, ::close) {
        if (draft == null) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("导入到：${model.selected.name}", style = MaterialTheme.typography.titleMedium)
                Text("无需固定表头，支持 Excel、HTML、CSV/文本、PDF、Word 和课表截图。",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    pickerError = ""; model.clearMessage()
                    runCatching { picker.launch(arrayOf("*/*")) }.onFailure { pickerError = "无法打开文件选择器，请重试" }
                }) { Text("选择课表文件") }
                OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    pickerError = ""; model.clearMessage()
                    runCatching { gallery.launch(Unit) }.onFailure { pickerError = "无法打开相册，请检查相册应用是否已启用" }
                }) { Text("从相册选择课表截图") }
                OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { paste = true }) {
                    Text("粘贴课表文本")
                }
                if (source != null) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(source.name, style = MaterialTheme.typography.titleSmall)
                        Text("已选择 · ${max(1, source.data.size / 1024)} KB", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("使用 My 中保存的 DeepSeek 密钥。开始解析会将所选文件或文本发送给 DeepSeek；结果先预览，确认后才加入课表。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (model.message.isNotEmpty()) Text(model.message, color = MaterialTheme.colorScheme.error)
                if (pickerError.isNotEmpty()) Text(pickerError, color = MaterialTheme.colorScheme.error)
                Button(enabled = !busy && source != null, onClick = model::generateAiImport, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) model.aiImportStage.ifBlank { "处理中…" } else "开始 AI 解析")
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = ::close) { Text("取消解析并返回") }
                }
            }
        } else {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!previewGrid, { previewGrid = false }, label = { Text("课程清单") })
                FilterChip(previewGrid, { previewGrid = true }, label = { Text("课表预览") })
            }
            Text("${draft.sourceName} · 新增 ${draft.courses.size} 条" +
                if (draft.skipped > 0) " · 已过滤 ${draft.skipped} 条重复安排" else "",
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            if (previewGrid) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    TimetableScreen(model, previewTable = model.selected.copy(courses = model.selected.courses + draft.courses), isPreview = true)
                }
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (draft.notes.isNotBlank()) item {
                    Text(draft.notes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                items(draft.courses) { course ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(aiCourseDescription(course), style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider()
                    }
                }
            }
            if (model.message.isNotEmpty()) Text(model.message, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = !busy, onClick = { model.discardAiImportPreview(); previewGrid = false }) { Text("重新解析") }
                Button(enabled = !busy && draft.tableId == model.selected.id, modifier = Modifier.weight(1f),
                    onClick = { model.confirmAiImport(onDismiss) }) { Text(if (busy) "导入中…" else "确认导入") }
            }
        }
    }
    if (paste) AlertDialog(onDismissRequest = { paste = false }, title = { Text("粘贴课表文本") }, text = {
        OutlinedTextField(text, { if (it.length <= 100_000) text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 340.dp),
            label = { Text("课程、星期、节次、周数等") }, placeholder = { Text("例如：高等数学，周一1-2节，第1-16周，A101教室") })
    }, confirmButton = {
        TextButton(enabled = text.isNotBlank(), onClick = { model.useAiImportText(text); previewGrid = false; paste = false }) { Text("使用文本") }
    }, dismissButton = { TextButton(onClick = { paste = false }) { Text("取消") } })
}
