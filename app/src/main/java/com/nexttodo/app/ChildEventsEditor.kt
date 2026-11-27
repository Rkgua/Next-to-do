package com.nexttodo.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun ChildEventsEditor(parent: Event, children: List<Event>, enabled: Boolean, onChange: (List<Event>) -> Unit) {
    var editing by remember(parent.id) { mutableStateOf<Event?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Text(if (children.isEmpty()) "把一件大事拆成几步，逐个完成。" else "${children.count { it.completed }}/${children.size} 已完成 · 点击可编辑",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    children.forEach { child ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(enabled) { editing = child }.padding(vertical = 8.dp)) {
                Text(child.title, style = MaterialTheme.typography.bodyLarge)
                EventTagBadges(child.tags)
                Text(if (child.followsParentDate) "跟随主事件日期" else child.scheduleLabel(),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onChange(children.filterNot { it.id == child.id }) }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "移除子事件 · ${child.title}" }) { Text("移除") }
        }
    }
    OutlinedButton(onClick = { error = null; editing = parent.newChild() }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("＋ 添加子事件")
    }
    editing?.let { child ->
        Dialog(onDismissRequest = { editing = null }, properties = DialogProperties(usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { editing = null }) { Text("取消") }
                        Text("编辑子事件", style = MaterialTheme.typography.titleLarge)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    EventEditor(child, true, false, onChange = { updated ->
                        error = null
                        editing = if (child.calendarDay != updated.calendarDay || child.time != updated.time)
                            updated.copy(followsParentDate = false) else updated
                    }, onRecognize = {}, onSave = {
                        error = when {
                            child.title.isBlank() -> "请输入子事件名称"
                            child.fields.any { it.name.isBlank() } -> "请输入属性名称"
                            else -> child.validateSchedule()
                        }
                        if (error == null) {
                            val saved = child.copy(title = child.title.trim())
                            onChange(if (children.any { it.id == child.id }) children.map { if (it.id == child.id) saved else it } else children + saved)
                            editing = null
                        }
                    }, onReset = { editing = parent.newChild().copy(id = child.id) },
                        parentTitle = parent.title.ifBlank { "当前事件" }, saveLabel = "完成编辑", showRecognition = false)
                }
            }
        }
    }
}
