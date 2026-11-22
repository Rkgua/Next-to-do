package com.nexttodo.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ProfileSettingDetail(val title: String, val hint: String) {
    APPEARANCE("外观与背景", "浅色、深色与跟随系统"),
    LIVE("实时提醒", "课程倒计时、待办和番茄钟"),
    PERMISSIONS("系统权限", "管理通知、精确闹钟和悬浮窗权限"),
    DEEPSEEK("DeepSeek", "管理图片识别与课表助手的 API 密钥"),
    UPDATE("版本更新", "检查新版本并查看发布记录"),
}

@Composable
internal fun profileSettingsBackground(): androidx.compose.ui.graphics.Color =
    if (MaterialTheme.colorScheme.background.luminance() < .5f) androidx.compose.ui.graphics.Color(0xFF111214)
    else androidx.compose.ui.graphics.Color(0xFFF1F1F4)

@Composable
private fun settingsCardColor(): androidx.compose.ui.graphics.Color =
    if (MaterialTheme.colorScheme.background.luminance() < .5f) androidx.compose.ui.graphics.Color(0xFF1E1F22)
    else androidx.compose.ui.graphics.Color.White

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileSettings(
    settings: AppSettings,
    lifecycle: Lifecycle,
    theme: AppTheme,
    updates: AppUpdateViewModel,
    onThemeChange: (AppTheme) -> Unit,
    todayAppearance: TodayAppearanceViewModel,
    timetable: TimetableViewModel,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 20.dp,
    scrollState: ScrollState? = null,
) {
    val context = LocalContext.current
    val scroll = scrollState ?: rememberScrollState()
    var detailName by rememberSaveable { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val liveEnabled = remember(revision, detailName) {
        context.getSharedPreferences("live_status", 0).getBoolean("enabled", false)
    }
    Column(modifier.verticalScroll(scroll).padding(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsGroup("功能") {
            SettingsEntry(ProfileSettingDetail.APPEARANCE, R.drawable.profile_settings_appearance, theme.label) { detailName = it.name }
            SettingsDivider()
            SettingsEntry(ProfileSettingDetail.LIVE, R.drawable.profile_settings_bell, if (liveEnabled) "已开启" else "未开启") { detailName = it.name }
        }
        SettingsGroup("自定义选项") {
            SettingsToggle("隐藏非本周课程", "只显示所查看周的课程",
                timetable.hideNonCurrentCourses, onCheckedChange = timetable::updateHideNonCurrentCourses)
            SettingsDivider(inset = 16.dp)
            SettingsToggle("课表与 Today 背景同步",
                if (todayAppearance.synced) "Today 跟随当前课表背景" else "Today 单独设置背景",
                todayAppearance.synced, enabled = !todayAppearance.busy && !timetable.busy && timetable.ready,
                onCheckedChange = { todayAppearance.setSynced(it, timetable.selected) })
            if (todayAppearance.message.isNotEmpty()) Text(todayAppearance.message,
                Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
        SettingsGroup("隐私与权限") {
            SettingsEntry(ProfileSettingDetail.PERMISSIONS, R.drawable.profile_settings_shield) { detailName = it.name }
            SettingsDivider()
            SettingsEntry(ProfileSettingDetail.DEEPSEEK, R.drawable.profile_settings_key) { detailName = it.name }
        }
        SettingsGroup("关于") {
            SettingsEntry(ProfileSettingDetail.UPDATE, R.drawable.profile_settings_version, updates.currentVersion) { detailName = it.name }
        }
    }
    val detail = ProfileSettingDetail.entries.firstOrNull { it.name == detailName }
    if (detail != null) ModalBottomSheet(onDismissRequest = { detailName = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = settingsCardColor()) {
        MaterialTheme(typography = MaterialTheme.typography.copy(
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            bodySmall = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 17.sp))) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
                .padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(detail.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(detail.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                when (detail) {
                    ProfileSettingDetail.APPEARANCE -> AppearanceSettings(theme, onThemeChange)
                    ProfileSettingDetail.LIVE -> LiveStatusSettings()
                    ProfileSettingDetail.PERMISSIONS -> PermissionSettings(lifecycle)
                    ProfileSettingDetail.DEEPSEEK -> DeepSeekSettings(settings)
                    ProfileSettingDetail.UPDATE -> UpdateSettings(updates)
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, Modifier.padding(start = 14.dp, top = 4.dp), fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = settingsCardColor()) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingsEntry(detail: ProfileSettingDetail, icon: Int? = null, value: String = "", onClick: (ProfileSettingDetail) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick(detail) }.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) {
            // Compensate for transparent margins in the supplied originals, keeping visible height at 22 dp.
            val iconScale = when (icon) {
                R.drawable.profile_settings_bell -> 200f / 146f
                R.drawable.profile_settings_shield -> 200f / 186f
                R.drawable.profile_settings_version -> 200f / 161f
                else -> 1f
            }
            Icon(painterResource(icon), null, Modifier.size(22.dp).graphicsLayer {
                scaleX = iconScale
                scaleY = iconScale
            }, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .85f))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(detail.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(detail.hint, fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .78f))
        }
        if (value.isNotEmpty()) Text(value, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .78f))
        Icon(painterResource(R.drawable.settings_chevron), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline.copy(alpha = .65f))
    }
}

@Composable
private fun SettingsDivider(inset: Dp = 50.dp) {
    HorizontalDivider(Modifier.padding(start = inset, end = 16.dp), thickness = .5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))
}

@Composable
private fun AppearanceSettings(theme: AppTheme, onThemeChange: (AppTheme) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppTheme.entries.forEach { option -> FilterChip(theme == option, { onThemeChange(option) }, label = { Text(option.label) }) }
    }
}

@Composable
private fun SettingsToggle(title: String, hint: String, checked: Boolean, enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch,
        onValueChange = onCheckedChange).heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(hint, fontSize = 11.sp, lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .78f))
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
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
