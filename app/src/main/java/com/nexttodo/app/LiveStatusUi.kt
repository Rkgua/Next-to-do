package com.nexttodo.app

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

@Composable
internal fun LiveStatusSettings() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("live_status", 0) }
    var revision by remember { mutableIntStateOf(0) }
    var permissionDenied by remember { mutableStateOf(false) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        prefs.edit().putBoolean("enabled", granted).apply()
        LiveStatus.request(context)
    }
    key(revision) {
        val enabled = prefs.getBoolean("enabled", false)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("实时提醒", Modifier.weight(1f))
            Switch(enabled, { value ->
                if (value && Build.VERSION.SDK_INT >= 33 && !context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else prefs.edit().putBoolean("enabled", value).apply()
            }, modifier = Modifier.semantics { contentDescription = "开启实时提醒" })
        }
        listOf("courses" to "课程倒计时", "tasks" to "今日待办统计", "timer" to "番茄钟状态").forEach { (name, title) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f))
                Switch(prefs.getBoolean(name, true), { prefs.edit().putBoolean(name, it).apply() }, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = title })
            }
        }
        Text("开启后，在通知栏和应用内查看实时状态。系统胶囊的展示由手机系统决定。", style = MaterialTheme.typography.bodySmall)
        if (permissionDenied) Text("通知权限尚未开启，可在 My → 权限中设置。", style = MaterialTheme.typography.bodySmall)
        if (enabled && !context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
            Text("请在 My → 权限中开启通知。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun LiveStatusCapsule(onOpen: (String) -> Unit) {
    val state = LiveStatus.snapshot ?: return
    val context = LocalContext.current
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    var active by remember { mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { owner, _ -> active = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(state.deadline, active) {
        if (active && state.deadline != null) while (true) {
            now = System.currentTimeMillis()
            if (now >= state.deadline) LiveStatus.request(context)
            delay(1000)
        }
    }
    val remaining = state.deadline?.let { ((it - now).coerceAtLeast(0) + 999) / 1000 }
    val label = remaining?.let { "${state.title} ${it / 60}:${(it % 60).toString().padStart(2, '0')}" } ?: state.title
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = RoundedCornerShape(28.dp), modifier = Modifier.clickable { expanded = true }) {
            Text(label, Modifier.padding(horizontal = 18.dp, vertical = 9.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text(label) }, text = { Text(state.detail) },
        confirmButton = { TextButton(onClick = { expanded = false; onOpen(state.target) }) { Text("查看详情") } },
        dismissButton = { TextButton(onClick = { expanded = false }) { Text("关闭") } })
}
