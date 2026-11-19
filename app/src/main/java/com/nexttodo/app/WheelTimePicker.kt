package com.nexttodo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun WheelTimePicker(
    label: String,
    initial: LocalDateTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val hours = rememberPagerState(initialPage = 24 * 500 + initial.hour) { 24 * 1000 }
    val minutes = rememberPagerState(initialPage = 60 * 500 + initial.minute) { 60 * 1000 }
    val rowHeight = 48.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择$label") },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(initial.toLocalDate().format(DateTimeFormatter.ofPattern("yyyy年M月d日")),
                    style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("小时", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text("分钟", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = rowHeight * 5)) {
                    // Keep the center row visible even in a short landscape window.
                    val visibleRows = if (maxHeight < rowHeight * 5) 3 else 5
                    Box(Modifier.fillMaxWidth().height(rowHeight * visibleRows)) {
                        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(rowHeight)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp)))
                        Row(Modifier.fillMaxSize()) {
                            TimeWheel(hours, 24, "小时", rowHeight, visibleRows, Modifier.weight(1f))
                            TimeWheel(minutes, 60, "分钟", rowHeight, visibleRows, Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !hours.isScrollInProgress && !minutes.isScrollInProgress,
                onClick = { onConfirm(LocalTime.of(hours.currentPage % 24, minutes.currentPage % 60)) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TimeWheel(state: PagerState, count: Int, label: String, rowHeight: Dp, visibleRows: Int, modifier: Modifier) {
    fun step(delta: Int) { state.requestScrollToPage((state.currentPage + delta).coerceIn(0, state.pageCount - 1)) }
    VerticalPager(
        state = state,
        modifier = modifier.fillMaxHeight().semantics {
            contentDescription = "$label 滚轮"
            stateDescription = (state.currentPage % count).toString().padStart(2, '0')
            progressBarRangeInfo = ProgressBarRangeInfo((state.currentPage % count).toFloat(), 0f..(count - 1).toFloat(), count - 2)
            setProgress { value ->
                val next = value.roundToInt().coerceIn(0, count - 1)
                state.requestScrollToPage(state.currentPage - state.currentPage % count + next)
                true
            }
            customActions = listOf(
                CustomAccessibilityAction("上一$label") { step(-1); true },
                CustomAccessibilityAction("下一$label") { step(1); true },
            )
        },
        pageSize = PageSize.Fixed(rowHeight),
        contentPadding = PaddingValues(vertical = rowHeight * ((visibleRows - 1) / 2)),
        flingBehavior = PagerDefaults.flingBehavior(state, pagerSnapDistance = PagerSnapDistance.atMost(8)),
        beyondViewportPageCount = 1,
    ) { page ->
        val distance = abs(state.currentPage - page + state.currentPageOffsetFraction).coerceAtMost(2f)
        val emphasis = (1f - distance).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().clickable { state.requestScrollToPage(page) }.clearAndSetSemantics {},
            contentAlignment = Alignment.Center) {
            Text((page % count).toString().padStart(2, '0'),
                color = if (distance < .5f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontSize = (22 + 4 * emphasis).sp,
                fontWeight = if (distance < .5f) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1, softWrap = false,
                modifier = Modifier.graphicsLayer {
                    alpha = (1f - distance * .35f).coerceAtLeast(.3f)
                    scaleY = 1f - (distance - 1f).coerceAtLeast(0f) * .22f
                })
        }
    }
}
