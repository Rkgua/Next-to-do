package com.nexttodo.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FocusPlansSection(lifecycle: Lifecycle) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plans by remember { mutableStateOf(emptyList<FocusPlan>()) }
    var editing by remember { mutableStateOf<FocusPlan?>(null) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var permissionRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permissionRevision++ }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { plans = withContext(Dispatchers.IO) { FocusPlanScheduler.read(context) } }
    fun save(value: List<FocusPlan>) {
        if (busy) return
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { FocusPlanScheduler.save(context, value) }; plans = value; error = "" }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = "计划暂时无法保存，请重试" }
            finally { busy = false }
        }
    }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRevision++ }
    val notifications = remember(permissionRevision) { context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }
    val precise = remember(permissionRevision) { Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("专注计划", style = MaterialTheme.typography.titleMedium)
                    Text("到点开始计时，给重要的事留个位置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { editing = null; showEditor = true }, enabled = !busy && plans.size < 100) { Text("＋ 添加") }
            }
            if (plans.isEmpty()) Text("还没有计划，按自己的节奏开始也很好。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            plans.forEach { plan ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${plan.timeLabel}  ${plan.title}", style = MaterialTheme.typography.titleSmall)
                        Text("${plan.duration} 分钟 · ${plan.repeatLabel}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(plan.enabled, { enabled -> save(plans.map { if (it.id == plan.id) it.copy(enabled = enabled) else it }) }, enabled = !busy)
                }
                Row {
                    TextButton(onClick = { editing = plan; showEditor = true }, enabled = !busy) { Text("编辑") }
                    TextButton(onClick = { save(plans.filterNot { it.id == plan.id }) }, enabled = !busy) { Text("删除") }
                }
            }
            if (!notifications) TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("开启计划通知") }
            if (!precise && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Text("精确闹钟未开启，计划可能延迟。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())) }) { Text("设置准点提醒") }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showEditor) FocusPlanEditor(editing, onDismiss = { showEditor = false }) { plan ->
        save(plans.filterNot { it.id == plan.id } + plan)
        showEditor = false
    }
}

@Composable
private fun FocusPlanEditor(plan: FocusPlan?, onDismiss: () -> Unit, onSave: (FocusPlan) -> Unit) {
    val context = LocalContext.current
    val now = remember { LocalTime.now().plusMinutes(5) }
    var title by rememberSaveable { mutableStateOf(plan?.title ?: "") }
    var hour by rememberSaveable { mutableIntStateOf(plan?.hour ?: now.hour) }
    var minute by rememberSaveable { mutableIntStateOf(plan?.minute ?: now.minute) }
    var duration by rememberSaveable { mutableStateOf((plan?.duration ?: 25).toString()) }
    var weekdays by rememberSaveable { mutableStateOf(plan?.days?.sorted()?.joinToString(",") ?: "1,2,3,4,5,6,7") }
    val days = weekdays.split(',').mapNotNull(String::toIntOrNull).toSet()
    val minutes = duration.toIntOrNull()?.takeIf { it in 1..180 }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (plan == null) "添加专注计划" else "编辑专注计划") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it.take(32) }, label = { Text("计划名称") }, singleLine = true)
            OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> hour = h; minute = m }, hour, minute, true).show() }) {
                Text("开始时间  %02d:%02d".format(hour, minute))
            }
            OutlinedTextField(duration, { duration = it.filter(Char::isDigit).take(3) }, label = { Text("专注时长（1–180 分钟）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, isError = minutes == null)
            Text("重复日期", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { day -> FilterChip(day in days, {
                    weekdays = (if (day in days) days - day else days + day).sorted().joinToString(",")
                }, label = { Text("一二三四五六日"[day - 1].toString()) }) }
            }
            Text("到点开始计时并提醒；已有计时会继续。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(enabled = minutes != null && days.isNotEmpty(), onClick = {
        onSave(FocusPlan(plan?.id ?: UUID.randomUUID().toString(), title.trim().ifBlank { "定时专注" }, hour, minute, minutes!!, days, plan?.enabled ?: true))
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
