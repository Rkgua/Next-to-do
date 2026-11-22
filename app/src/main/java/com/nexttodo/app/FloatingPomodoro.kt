package com.nexttodo.app

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val TomatoAccent = Color(0xFFE56C64)

@Composable
internal fun FloatingPomodoro(lifecycle: Lifecycle, expanded: Boolean, onExpanded: (Boolean) -> Unit,
    initialPosition: Offset? = null, onOpenHub: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val timer = remember(context) { PomodoroTimer(context) }
    var state by remember(timer) { mutableStateOf(timer.refresh()) }
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var showDurations by rememberSaveable { mutableStateOf(false) }
    var positionX by rememberSaveable { mutableStateOf<Float?>(null) }
    var positionY by rememberSaveable { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val bubbleScale by animateFloatAsState(if (dragging) 1.1f else 1f, label = "tomato bubble scale")
    LaunchedEffect(expanded, active) { if (expanded || !active) dragging = false }
    DisposableEffect(lifecycle, timer) {
        val observer = LifecycleEventObserver { owner, event ->
            active = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (event == Lifecycle.Event.ON_RESUME) state = timer.restore()
        }
        lifecycle.addObserver(observer)
        val preferences = context.getSharedPreferences("pomodoro", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state = timer.refresh() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { lifecycle.removeObserver(observer); preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(active, timer) {
        if (active) while (true) { state = timer.refresh(); delay(500) }
    }
    fun applyTimer(action: () -> PomodoroState) {
        state = action()
        LiveStatus.request(context)
    }
    val seconds = (state.remaining.coerceAtLeast(0) + 999) / 1000
    val clock = "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val bubbleSize = 56.dp
        val edge = 8.dp
        val horizontalTravel = (maxWidth - bubbleSize - edge * 2).coerceAtLeast(1.dp)
        val verticalTravel = (maxHeight - bubbleSize - edge * 2).coerceAtLeast(1.dp)
        val horizontalPixels = with(density) { horizontalTravel.toPx() }
        val verticalPixels = with(density) { verticalTravel.toPx() }
        val edgePixels = with(density) { edge.toPx() }
        val defaultX = ((initialPosition?.x ?: with(density) { 106.dp.toPx() }) - edgePixels).div(horizontalPixels).coerceIn(0f, 1f)
        val defaultY = ((initialPosition?.y ?: with(density) { 18.dp.toPx() }) - edgePixels).div(verticalPixels).coerceIn(0f, 1f)
        if (!expanded) {
            Box(Modifier.offset {
                IntOffset((edgePixels + horizontalPixels * (positionX ?: defaultX)).roundToInt(),
                    (edgePixels + verticalPixels * (positionY ?: defaultY)).roundToInt())
            }.size(bubbleSize)
                .pointerInput(horizontalPixels, verticalPixels) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, movement ->
                        change.consume()
                        positionX = ((positionX ?: defaultX) + movement.x / horizontalPixels).coerceIn(0f, 1f)
                        positionY = ((positionY ?: defaultY) + movement.y / verticalPixels).coerceIn(0f, 1f)
                    }
                }
                .combinedClickable(onClick = { onExpanded(true) }, onDoubleClick = onOpenHub)
                .semantics {
                    contentDescription = "番茄钟，${state.phase.label}，剩余 $clock。单击快捷控制，双击专注与统计，可自由拖动"
                    customActions = listOf(
                        CustomAccessibilityAction("打开专注与统计") { onOpenHub(); true },
                        CustomAccessibilityAction("移到左侧") { positionX = 0f; true },
                        CustomAccessibilityAction("移到右侧") { positionX = 1f; true },
                        CustomAccessibilityAction("移到上方") { positionY = .15f; true },
                        CustomAccessibilityAction("移到下方") { positionY = .75f; true })
                }, contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.profile_tomato), null,
                    Modifier.fillMaxSize().padding(3.dp).graphicsLayer {
                        scaleX = bubbleScale
                        scaleY = bubbleScale
                    })
            }
            if (dragging) {
                val labelWidth = 86.dp
                val labelHeight = 34.dp
                val bubblePixels = with(density) { bubbleSize.toPx() }
                val labelPixels = with(density) { labelWidth.toPx() }
                val labelHeightPixels = with(density) { labelHeight.toPx() }
                val maxLabelX = with(density) { (maxWidth - labelWidth - edge).toPx() }.coerceAtLeast(edgePixels)
                val maxLabelY = with(density) { (maxHeight - labelHeight - edge).toPx() }.coerceAtLeast(edgePixels)
                Surface(Modifier.offset {
                    val bubbleX = edgePixels + horizontalPixels * (positionX ?: defaultX)
                    val bubbleY = edgePixels + verticalPixels * (positionY ?: defaultY)
                    val labelX = if ((positionX ?: defaultX) > .5f) bubbleX - labelPixels - edgePixels
                        else bubbleX + bubblePixels + edgePixels
                    IntOffset(labelX.coerceIn(edgePixels, maxLabelX).roundToInt(),
                        (bubbleY + (bubblePixels - labelHeightPixels) / 2).coerceIn(edgePixels, maxLabelY).roundToInt())
                }.size(labelWidth, labelHeight), shape = RoundedCornerShape(17.dp),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = .94f),
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 6.dp) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(clock, fontFamily = FontFamily.Monospace, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        AnimatedVisibility(expanded, Modifier.align(Alignment.CenterEnd).padding(16.dp),
            enter = fadeIn() + scaleIn(initialScale = .94f), exit = fadeOut() + scaleOut(targetScale = .94f)) {
            Surface(Modifier.widthIn(max = 310.dp).fillMaxWidth(.9f), shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface, shadowElevation = 18.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))) {
                Column(Modifier.heightIn(max = (maxHeight - 32.dp).coerceAtLeast(100.dp))
                    .verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.profile_tomato), null, Modifier.size(30.dp))
                        Text("专注片刻", Modifier.weight(1f).padding(start = 10.dp),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        TextButton(onClick = { onExpanded(false) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)) { Text("收起", fontSize = 12.sp) }
                    }
                    Text(state.phase.label, color = TomatoAccent, style = MaterialTheme.typography.labelLarge)
                    Text(clock, fontFamily = FontFamily.Monospace, fontSize = 44.sp, letterSpacing = (-2).sp,
                        fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.CenterHorizontally))
                    LinearProgressIndicator(progress = { (1f - state.remaining.toFloat() / state.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp), color = TomatoAccent,
                        trackColor = TomatoAccent.copy(alpha = .12f))
                    Text("本轮专注  ${state.completedFocus} / 4", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TodayFocusSummary(lifecycle, compact = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { applyTimer { timer.setRunning(!state.running) } }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = TomatoAccent, contentColor = Color.White)) {
                            Text(when { state.running -> "暂停"; state.remaining < state.total -> "继续";
                                state.phase == PomodoroPhase.FOCUS -> "开始专注"; else -> "开始休息" })
                        }
                        OutlinedButton(onClick = { applyTimer(timer::reset) }, enabled = state.running || state.remaining < state.total) { Text("重置") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onOpenHub, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("专注与统计", fontSize = 12.sp) }
                        TextButton(onClick = { showDurations = true }, enabled = !state.running && state.remaining >= state.total,
                            contentPadding = PaddingValues(horizontal = 6.dp)) { Text("调整时长", fontSize = 12.sp) }
                        if (state.phase != PomodoroPhase.FOCUS) TextButton(onClick = { applyTimer(timer::skipBreak) },
                            contentPadding = PaddingValues(horizontal = 6.dp)) { Text("跳过休息", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
    if (showDurations) DurationSettings(state.durations, onDismiss = { showDurations = false }, onSave = {
        applyTimer { timer.setDurations(it) }
        showDurations = false
    })
}
