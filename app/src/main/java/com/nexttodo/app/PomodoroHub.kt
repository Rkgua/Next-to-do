package com.nexttodo.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

@Composable
internal fun PomodoroHub(lifecycle: Lifecycle, activity: Activity,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
    onImmersiveChange: (Boolean) -> Unit = {}, onBack: () -> Unit) {
    val context = LocalContext.current
    val timer = remember(context) { PomodoroTimer(context.applicationContext) }
    var state by remember { mutableStateOf(timer.refresh()) }
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var durations by rememberSaveable { mutableStateOf(false) }
    var immersive by rememberSaveable { mutableStateOf(false) }
    var confirmRest by remember { mutableStateOf(false) }
    var pinRequested by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(false) }
    var showPinSetup by remember { mutableStateOf(false) }
    val history = rememberFocusHistory(lifecycle)
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    val hostWindow = dialogWindow ?: activity.window
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    SideEffect {
        hostWindow.let { WindowCompat.getInsetsController(it, it.decorView).apply {
            isAppearanceLightStatusBars = !immersive && !dark
            isAppearanceLightNavigationBars = !immersive && !dark
        } }
    }
    fun stopPin() {
        if (pinRequested) runCatching { activity.stopLockTask() }
        pinRequested = false
        timer.trackScreenPin(false)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { owner, _ -> active = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stopPin() }
    }
    DisposableEffect(immersive) {
        onImmersiveChange(immersive)
        if (immersive) hostWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            onImmersiveChange(false)
            hostWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(active) {
        if (active) while (true) {
            state = timer.refresh()
            pinned = context.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
            delay(500)
        }
    }
    BackHandler { if (immersive) { stopPin(); immersive = false } else onBack() }
    fun update(action: () -> PomodoroState) { state = action(); LiveStatus.request(context) }
    val seconds = (state.remaining.coerceAtLeast(0) + 999) / 1000
    val clock = "%02d:%02d".format(seconds / 60, seconds % 60)
    if (immersive) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF142B30), contentColor = Color(0xFFF5F2E9)) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { stopPin(); immersive = false }) { Text("退出沉浸", color = Color.White) }
                    Text("FOCUS", Modifier.padding(top = 16.dp), fontSize = 11.sp, letterSpacing = 3.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(state.phase.label, fontSize = 16.sp, color = Color(0xFFA0C3BB))
                Text(clock, Modifier.padding(vertical = 28.dp), fontFamily = FontFamily.Monospace, fontSize = 60.sp,
                    fontWeight = FontWeight.Light, letterSpacing = (-3).sp)
                LinearProgressIndicator(progress = { (1f - state.remaining.toFloat() / state.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp), color = Color(0xFFA0C3BB), trackColor = Color.White.copy(alpha = .1f))
                Text("本轮已完成 ${state.completedFocus} / 4", Modifier.padding(top = 24.dp), color = Color.White.copy(alpha = .6f), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Button(onClick = { update { timer.setRunning(!state.running) } }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA0C3BB), contentColor = Color(0xFF142B30))) {
                    Text(if (state.running) "暂停" else "继续")
                }
                TextButton(onClick = {
                    if (pinned) { stopPin(); pinned = false }
                    else if (Settings.System.getInt(context.contentResolver, "lock_to_app_enabled", 0) == 0) showPinSetup = true
                    else try {
                        timer.trackScreenPin(true)
                        activity.startLockTask()
                        pinRequested = true
                    }
                    catch (_: Exception) { timer.trackScreenPin(false); Toast.makeText(context, "当前设备暂不支持屏幕固定", Toast.LENGTH_SHORT).show() }
                }) { Text(if (pinned) "解除屏幕固定" else "固定当前屏幕", color = Color.White.copy(alpha = .8f)) }
                Text("由系统确认固定，可通过系统手势退出。", fontSize = 11.sp, color = Color.White.copy(alpha = .45f))
            }
        }
        if (showPinSetup) AlertDialog(onDismissRequest = { showPinSetup = false }, title = { Text("开启屏幕固定") },
            text = { Text("在系统安全设置中开启「屏幕固定」或「应用固定」，再返回这里固定当前屏幕。") },
            confirmButton = { TextButton(onClick = {
                showPinSetup = false
                try { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                catch (_: android.content.ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }) { Text("打开系统设置") } }, dismissButton = { TextButton(onClick = { showPinSetup = false }) { Text("稍后") } })
        return
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(contentInsets)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ 返回") }
                Text("番茄空间", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { durations = true }, enabled = !state.running) { Text("时长") }
            }
            PomodoroSectionTabs(tab) { tab = it }
            if (tab == 0) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FocusSummaryCard(dailyFocus(history.records, java.time.LocalDate.now()), failed = history.failed, loading = history.loading)
                Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFFC96B60), Color(0xFFAD4E47))), RoundedCornerShape(26.dp))) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${state.phase.label} · ${state.total / 60_000} 分钟", Modifier.weight(1f), color = Color.White.copy(alpha = .85f), fontSize = 13.sp)
                            Text("${state.completedFocus} / 4", color = Color.White.copy(alpha = .65f), fontSize = 12.sp)
                        }
                        Text(clock, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 50.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp)
                        if (!state.running && state.remaining >= state.total && state.phase == PomodoroPhase.FOCUS) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 25, 45).forEach { minutes ->
                                OutlinedButton(onClick = { update { timer.setDurations(state.durations.copy(focus = minutes)) } },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                                    Text("${minutes}分", fontSize = 11.sp)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { update { timer.setRunning(!state.running) } }, modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFAD4E47))) {
                                Text(when { state.running -> "暂停"; state.remaining < state.total -> "继续"; state.phase == PomodoroPhase.FOCUS -> "开始专注"; else -> "开始休息" })
                            }
                            TextButton(onClick = { update(timer::reset) }, enabled = state.running || state.remaining < state.total) { Text("重置", color = Color.White.copy(alpha = .8f)) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FocusActionCard("小憩", "5 分钟，换一口呼吸", FocusTeal, Modifier.weight(1f)) {
                        if (state.running) confirmRest = true else update { timer.startRest() }
                    }
                    FocusActionCard("沉浸专注", "留在当下这一件事", FocusBlue, Modifier.weight(1f)) {
                        if (state.phase != PomodoroPhase.FOCUS) update { timer.startFocus(state.durations.focus) }
                        else if (!state.running) update { timer.setRunning(true) }
                        immersive = true
                    }
                }
                if (state.phase != PomodoroPhase.FOCUS) TextButton(onClick = { update(timer::skipBreak) }) { Text("跳过休息，回到专注") }
                FocusPlansSection(lifecycle)
            } else FocusStatistics(history) { tab = 0 }
        }
    }
    if (durations) DurationSettings(state.durations, onDismiss = { durations = false }, onSave = { update { timer.setDurations(it) }; durations = false })
    if (confirmRest) AlertDialog(onDismissRequest = { confirmRest = false }, title = { Text("开始 5 分钟小憩？") },
        text = { Text("当前计时会结束，已经投入的时间会保留在统计中。") },
        confirmButton = { TextButton(onClick = { update { timer.startRest() }; confirmRest = false }) { Text("开始小憩") } },
        dismissButton = { TextButton(onClick = { confirmRest = false }) { Text("继续当前计时") } })
}

@Composable
private fun PomodoroSectionTabs(selected: Int, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.widthIn(max = 280.dp).fillMaxWidth().height(44.dp)
            .clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            val tabWidth = (maxWidth - 6.dp) / 2
            val indicatorLeft by animateDpAsState(tabWidth * selected,
                spring(dampingRatio = 1f, stiffness = 500f), label = "focus tab indicator")
            Box(Modifier.fillMaxSize().padding(3.dp)) {
                Surface(Modifier.offset { IntOffset(indicatorLeft.roundToPx(), 0) }.width(tabWidth).fillMaxHeight(),
                    shape = RoundedCornerShape(19.dp), color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 1.dp) {}
                Row(Modifier.fillMaxSize().selectableGroup()) {
                    listOf("专注", "统计").forEachIndexed { index, label ->
                        val active = selected == index
                        val color by animateColorAsState(if (active) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f),
                            tween(160), label = "focus tab text")
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(19.dp)).selectable(active,
                            interactionSource = null, indication = null, role = Role.Tab, onClick = { onSelect(index) }),
                            contentAlignment = Alignment.Center) {
                            Text(label, color = color, fontSize = 15.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusActionCard(title: String, detail: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(22.dp), color = color.copy(alpha = .12f)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
        }
    }
}
