package com.nexttodo.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProfileSettings(
    settings: AppSettings,
    lifecycle: Lifecycle,
    theme: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
    onOpenPomodoro: () -> Unit,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(onClick = onOpenPomodoro, modifier = Modifier.fillMaxWidth()) {
            Text("番茄钟")
        }
        SettingsSection("DeepSeek") { DeepSeekSettings(settings) }
        SettingsSection("权限") { PermissionSettings(lifecycle) }
        SettingsSection("外观") {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppTheme.entries.forEach { option ->
                    FilterChip(
                        selected = theme == option,
                        onClick = { onThemeChange(option) },
                        label = { Text(option.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DeepSeekSettings(settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var savedKey by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        try {
            savedKey = withContext(Dispatchers.IO) { settings.readDeepSeekKey() }
            draft = savedKey.orEmpty()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            hasError = true
            message = "无法读取已保存的密钥，请重新填写"
        } finally {
            busy = false
        }
    }

    fun updateKey(key: String?) {
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (key == null) settings.clearDeepSeekKey()
                    else settings.saveDeepSeekKey(key)
                }
                savedKey = key
                draft = key.orEmpty()
                showKey = false
                hasError = false
                message = if (key == null) "密钥已清除" else "密钥已保存"
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                hasError = true
                message = if (key == null) "无法清除密钥" else "无法保存密钥"
            } finally {
                busy = false
            }
        }
    }

    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = it
            message = ""
        },
        label = { Text("API 密钥") },
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { showKey = !showKey }, enabled = !busy && draft.isNotEmpty()) {
                Text(if (showKey) "隐藏" else "显示")
            }
        },
        isError = hasError && message.isNotEmpty(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { updateKey(draft.trim()) },
            enabled = !busy && draft.isNotBlank() && draft.trim() != savedKey,
        ) { Text("保存密钥") }
        TextButton(onClick = { updateKey(null) }, enabled = !busy && savedKey != null) {
            Text("清除密钥")
        }
    }
    if (message.isNotEmpty()) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class Permissions(
    val notifications: Boolean,
    val exactAlarms: Boolean,
    val overlay: Boolean,
)

private fun readPermissions(context: Context) = Permissions(
    notifications = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
    exactAlarms = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
    overlay = Settings.canDrawOverlays(context),
)

@Composable
private fun PermissionSettings(lifecycle: Lifecycle) {
    val context = LocalContext.current
    val appPackage = Uri.fromParts("package", context.packageName, null)
    var permissions by remember(context) { mutableStateOf(readPermissions(context)) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissions = readPermissions(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    PermissionRow("通知栏通知", permissions.notifications) {
        openSettings(
            context,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            appPackage,
        )
    }
    HorizontalDivider()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PermissionRow("精确闹钟", permissions.exactAlarms) {
            openSettings(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, appPackage), appPackage)
        }
    } else {
        PermissionRow("精确闹钟", permissions.exactAlarms, onManage = null)
    }
    HorizontalDivider()
    PermissionRow("悬浮窗", permissions.overlay) {
        openSettings(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, appPackage), appPackage)
    }
}

private fun openSettings(context: Context, intent: Intent, appPackage: Uri) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appPackage))
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onManage: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (granted) "已允许" else "未允许",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onManage != null) {
            TextButton(onClick = onManage) { Text("系统设置") }
        }
    }
}
