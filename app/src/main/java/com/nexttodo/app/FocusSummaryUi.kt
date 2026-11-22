package com.nexttodo.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal val FocusCoral = Color(0xFFCA675E)
internal val FocusTeal = Color(0xFF329989)
internal val FocusBlue = Color(0xFF648CD0)
internal data class FocusHistorySnapshot(val records: List<FocusSegment> = emptyList(), val loading: Boolean = true, val failed: Boolean = false)

@Composable
internal fun rememberFocusHistory(lifecycle: Lifecycle): FocusHistorySnapshot {
    val context = LocalContext.current.applicationContext
    val timer = remember(context) { PomodoroTimer(context) }
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { owner, _ -> active = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val snapshot by produceState(FocusHistorySnapshot(), active, context) {
        if (active) while (true) {
            try {
                val records = withContext(Dispatchers.IO) {
                    timer.refresh()
                    val saved = FocusHistory.get(context).read()
                    saved + listOfNotNull(timer.liveSegment())
                }
                value = FocusHistorySnapshot(records, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { value = value.copy(loading = false, failed = true) }
            delay(2_000)
        }
    }
    return snapshot
}

@Composable
internal fun TodayFocusSummary(lifecycle: Lifecycle, compact: Boolean = false) {
    val snapshot = rememberFocusHistory(lifecycle)
    val today = dailyFocus(snapshot.records, LocalDate.now())
    FocusSummaryCard(today, compact, snapshot.failed, snapshot.loading)
}

@Composable
internal fun FocusSummaryCard(totals: FocusTotals, compact: Boolean = false, failed: Boolean = false, loading: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if (compact) 12.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("今日时间", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(if (loading) "读取中" else "${totals.sessions} 次专注", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (failed) Text("暂时无法读取记录，正在重试", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp)) {
                FocusDonut(totals, Modifier.size(if (compact) 88.dp else 122.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 9.dp else 12.dp)) {
                    TimeLegend("番茄专注", totals.focus, FocusCoral)
                    TimeLegend("休息", totals.rest, FocusTeal)
                    if (totals.locked > 0) TimeLegend("屏幕固定", totals.locked, FocusBlue)
                    if (!compact && totals.total == 0L) Text("开始一段专注，时间会在这里累积", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun TimeLegend(label: String, milliseconds: Long, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(label, Modifier.weight(1f).padding(start = 7.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(focusDurationLabel(milliseconds), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FocusDonut(totals: FocusTotals, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f)
    Box(modifier.semantics { contentDescription = "今日累计 ${focusDurationLabel(totals.total)}，专注 ${focusDurationLabel(totals.focus)}，休息 ${focusDurationLabel(totals.rest)}，屏幕固定 ${focusDurationLabel(totals.locked)}" },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * .085f
            val topLeft = Offset(stroke / 2, stroke / 2)
            val ringSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, topLeft, ringSize, style = Stroke(stroke))
            var angle = -90f
            if (totals.total > 0) listOf(totals.focus to FocusCoral, totals.rest to FocusTeal, totals.locked to FocusBlue)
                .filter { it.first > 0 }.forEach { (duration, color) ->
                    val sweep = duration.toFloat() / totals.total * 360
                    val gap = minOf(4f, sweep * .15f)
                    drawArc(color, angle + gap / 2, sweep - gap, false, topLeft, ringSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    angle += sweep
                }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (totals.total in 1..59_999) "<1" else (totals.total / 60_000).toString(), fontSize = 25.sp, fontWeight = FontWeight.Light)
            Text("分钟", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
