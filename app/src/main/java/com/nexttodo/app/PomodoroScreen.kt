package com.nexttodo.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

@Composable
internal fun PomodoroScreen(lifecycle: Lifecycle, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val timer = remember(context) { PomodoroTimer(context) }
    var state by remember(timer) { mutableStateOf(timer.refresh()) }
    var showDurations by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(lifecycle, timer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state = timer.restore()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(timer, state.running) {
        while (state.running) {
            delay(200)
            state = timer.refresh()
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("返回我的") }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.phase.label, style = MaterialTheme.typography.titleMedium)
                val seconds = (state.remaining.coerceAtLeast(0) + 999) / 1_000
                Text(
                    "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                LinearProgressIndicator(
                    progress = { (1f - state.remaining.toFloat() / state.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("本轮专注 ${state.completedFocus} / 4", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { state = timer.setRunning(!state.running) },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    when {
                        state.running -> "暂停"
                        state.remaining < state.total -> "继续"
                        state.phase == PomodoroPhase.FOCUS -> "开始专注"
                        else -> "开始休息"
                    }
                )
            }
            OutlinedButton(
                onClick = { state = timer.reset() },
                enabled = state.running || state.remaining < state.total,
            ) { Text("重置") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { showDurations = true },
                enabled = !state.running && state.remaining >= state.total,
            ) { Text("设置时长") }
            if (state.phase != PomodoroPhase.FOCUS) {
                TextButton(onClick = { state = timer.skipBreak() }) { Text("跳过休息") }
            }
        }
    }
    if (showDurations) {
        DurationSettings(
            state.durations,
            onDismiss = { showDurations = false },
            onSave = {
                state = timer.setDurations(it)
                showDurations = false
            },
        )
    }
}

@Composable
private fun DurationSettings(
    durations: PomodoroDurations,
    onDismiss: () -> Unit,
    onSave: (PomodoroDurations) -> Unit,
) {
    var focus by rememberSaveable { mutableStateOf(durations.focus.toString()) }
    var short by rememberSaveable { mutableStateOf(durations.short.toString()) }
    var long by rememberSaveable { mutableStateOf(durations.long.toString()) }
    val values = listOf(focus, short, long).map { it.toIntOrNull()?.takeIf { value -> value in 1..180 } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置时长") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DurationField("专注（分钟）", focus) { focus = it }
                DurationField("短休息（分钟）", short) { short = it }
                DurationField("长休息（分钟）", long) { long = it }
            }
        },
        confirmButton = {
            Button(
                enabled = values.all { it != null },
                onClick = { onSave(PomodoroDurations(values[0]!!, values[1]!!, values[2]!!)) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DurationField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        onValueChange = { onChange(it.filter { character -> character in '0'..'9' }.take(3)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = if (value.toIntOrNull()?.let { it !in 1..180 } == true) {
            { Text("可设置 1–180 分钟") }
        } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
