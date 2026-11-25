package com.nexttodo.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal enum class ReminderSettingsDestination { REQUESTED, APP_DETAILS, SYSTEM_SETTINGS, UNAVAILABLE }

internal fun openReminderSettings(context: Context, requested: List<Intent>): ReminderSettingsDestination {
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null))
    val candidates = requested.map { it to ReminderSettingsDestination.REQUESTED } + listOf(
        appDetails to ReminderSettingsDestination.APP_DETAILS,
        Intent(Settings.ACTION_SETTINGS) to ReminderSettingsDestination.SYSTEM_SETTINGS)
    for ((original, destination) in candidates) {
        val intent = Intent(original)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            val activity = context.packageManager.resolveActivity(intent, 0)?.activityInfo ?: continue
            if (!activity.enabled || !activity.applicationInfo.enabled || !activity.exported) continue
            if (activity.permission != null && context.checkSelfPermission(activity.permission) != PackageManager.PERMISSION_GRANTED) continue
            context.startActivity(intent)
            if (destination != ReminderSettingsDestination.REQUESTED) {
                val text = if (requested.isEmpty()) "已打开系统设置，请按指引检查启动与后台管理"
                    else "专用入口不可用，已打开系统设置，请手动检查后台与通知设置"
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
            return destination
        } catch (_: ActivityNotFoundException) {
            // OEM pages can disappear or change between resolving and launching.
        } catch (_: SecurityException) {
            // Never require privileged access to an OEM settings activity.
        }
    }
    Toast.makeText(context, "无法打开设置，请在手机系统设置中搜索本应用", Toast.LENGTH_LONG).show()
    return ReminderSettingsDestination.UNAVAILABLE
}

@Composable
internal fun ReminderDeviceSettings() {
    val context = LocalContext.current
    val profile = reminderDeviceProfile(Build.MANUFACTURER, Build.BRAND)
    val appPackage = Uri.fromParts("package", context.packageName, null)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
            style = MaterialTheme.typography.bodySmall)
        Text("${profile.label}：启动与后台管理（需手动检查）", style = MaterialTheme.typography.titleSmall)
        Text(profile.guidance, style = MaterialTheme.typography.bodySmall)
        Text("厂商开关没有统一的授权或读取接口，无法自动确认是否开启。入口不可用时会回到应用详情或系统设置；请按实际菜单检查。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            val pages = profile.startupPages.map { page ->
                Intent().setComponent(ComponentName(page.packageName, page.className))
                    .putExtra("package_name", context.packageName).putExtra("packageName", context.packageName)
                    .putExtra("extra_pkgname", context.packageName)
            }
            openReminderSettings(context, pages)
        }) { Text("检查启动与后台管理") }
        TextButton(onClick = {
            openReminderSettings(context, listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appPackage)))
        }) { Text("打开应用详情，检查电池与后台限制") }
        TextButton(onClick = {
            openReminderSettings(context, listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)))
        }) { Text("检查锁屏、横幅与弹出通知") }
        Text("建议分别保持应用前台、切到其他应用、锁屏后运行下面的五秒测试。系统强制停止应用后需重新打开，重新授权后也应再测试。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
