package com.nexttodo.app

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    var tilt by remember { mutableFloatStateOf(0f) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motionScope = rememberCoroutineScope()
    var settling by remember { mutableStateOf<Job?>(null) }
    val bubbleScale by animateFloatAsState(when { dragging -> 1.1f; pressed -> .96f; else -> 1f },
        animationSpec = spring(dampingRatio = .7f, stiffness = 650f), label = "tomato bubble scale")
    val bubbleTilt by animateFloatAsState(tilt, spring(dampingRatio = .75f, stiffness = 550f), label = "tomato bubble tilt")
    LaunchedEffect(pressed) { if (pressed) settling?.cancel() }
    LaunchedEffect(expanded, active) {
        if (expanded || !active) { settling?.cancel(); dragging = false; tilt = 0f }
    }
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
        val currentDefault by rememberUpdatedState(Offset(defaultX, defaultY))
        DisposableEffect(horizontalPixels, verticalPixels) {
            onDispose { settling?.cancel() }
        }
        if (!expanded) {
            Box(Modifier.graphicsLayer {
                translationX = edgePixels + horizontalPixels * (positionX ?: defaultX)
                translationY = edgePixels + verticalPixels * (positionY ?: defaultY)
            }.size(bubbleSize)
                .pointerInput(horizontalPixels, verticalPixels) {
                    val velocity = VelocityTracker()
                    var distance = Offset.Zero
                    var firstSample = true
                    detectDragGestures(
                        onDragStart = {
                            settling?.cancel()
                            velocity.resetTracking()
                            distance = Offset.Zero
                            firstSample = true
                            dragging = true
                        },
                        onDragEnd = {
                            dragging = false
                            tilt = 0f
                            velocity.addPosition(android.os.SystemClock.uptimeMillis(), distance)
                            val speed = velocity.calculateVelocity()
                            val maxSpeed = with(density) { 350.dp.toPx() }
                            val releaseVelocity = Offset(speed.x.coerceIn(-maxSpeed, maxSpeed), speed.y.coerceIn(-maxSpeed, maxSpeed))
                            val start = Offset((positionX ?: currentDefault.x) * horizontalPixels,
                                (positionY ?: currentDefault.y) * verticalPixels)
                            val drift = releaseVelocity * .045f
                            val maxDrift = with(density) { 18.dp.toPx() }
                            val limited = drift * (maxDrift / drift.getDistance().coerceAtLeast(maxDrift))
                            val target = Offset((start.x + limited.x).coerceIn(0f, horizontalPixels),
                                (start.y + limited.y).coerceIn(0f, verticalPixels))
                            if ((target - start).getDistance() > 1f) settling = motionScope.launch {
                                animate(typeConverter = Offset.VectorConverter, initialValue = start, targetValue = target,
                                    initialVelocity = releaseVelocity, animationSpec = spring(dampingRatio = .9f, stiffness = 500f)) { value, _ ->
                                    positionX = (value.x / horizontalPixels).coerceIn(0f, 1f)
                                    positionY = (value.y / verticalPixels).coerceIn(0f, 1f)
                                }
                            }
                        },
                        onDragCancel = { dragging = false; tilt = 0f },
                    ) { change, movement ->
                        change.consume()
                        // Track cumulative movement: local pointer coordinates move with the bubble itself.
                        if (firstSample) { velocity.addPosition(change.uptimeMillis - 16, Offset.Zero); firstSample = false }
                        distance += movement
                        velocity.addPosition(change.uptimeMillis, distance)
                        positionX = ((positionX ?: currentDefault.x) + movement.x / horizontalPixels).coerceIn(0f, 1f)
                        positionY = ((positionY ?: currentDefault.y) + movement.y / verticalPixels).coerceIn(0f, 1f)
                        tilt = (movement.x / density.density * .35f).coerceIn(-5f, 5f)
                    }
                }
                .combinedClickable(interactionSource = interaction, indication = null,
                    onClick = { onExpanded(true) }, onDoubleClick = onOpenHub)
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
                        rotationZ = bubbleTilt
                    })
            }
            val labelWidth = 74.dp
            val labelHeight = 30.dp
            val bubblePixels = with(density) { bubbleSize.toPx() }
            val labelPixels = with(density) { labelWidth.toPx() }
            val labelHeightPixels = with(density) { labelHeight.toPx() }
            val maxLabelX = with(density) { (maxWidth - labelWidth - edge).toPx() }.coerceAtLeast(edgePixels)
            val maxLabelY = with(density) { (maxHeight - labelHeight - edge).toPx() }.coerceAtLeast(edgePixels)
            AnimatedVisibility(dragging, modifier = Modifier.offset {
                    val bubbleX = edgePixels + horizontalPixels * (positionX ?: defaultX)
                    val bubbleY = edgePixels + verticalPixels * (positionY ?: defaultY)
                    val labelX = bubbleX + (bubblePixels - labelPixels) / 2
                    val labelY = if (bubbleY >= labelHeightPixels + edgePixels * 2) bubbleY - labelHeightPixels - edgePixels
                        else bubbleY + bubblePixels + edgePixels
                    IntOffset(labelX.coerceIn(edgePixels, maxLabelX).roundToInt(),
                        labelY.coerceIn(edgePixels, maxLabelY).roundToInt())
                }, enter = fadeIn(tween(100)) + scaleIn(tween(140), initialScale = .9f),
                exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = .95f)) {
                Surface(Modifier.size(labelWidth, labelHeight), shape = RoundedCornerShape(17.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
                    contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = 2.dp,
                    border = androidx.compose.foundation.BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(clock, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        if (expanded) Box(Modifier.matchParentSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = "收起番茄钟") { onExpanded(false) }
            .semantics { contentDescription = "收起番茄钟" })
        AnimatedVisibility(expanded, Modifier.align(Alignment.CenterEnd).padding(16.dp),
            enter = fadeIn() + scaleIn(initialScale = .94f), exit = fadeOut() + scaleOut(targetScale = .94f)) {
            Surface(Modifier.widthIn(max = 310.dp).fillMaxWidth(.9f)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }, shape = RoundedCornerShape(28.dp),
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
