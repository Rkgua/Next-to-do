package com.nexttodo.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NumberBubbleSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    val blue = Color(0xFF245FE5)
    val grey = if (timetableDark) Color(0xFF737780) else Color(0xFFBEC4CE)
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val steps = (range.endInclusive - range.start).roundToInt() - 1
    BoxWithConstraints(modifier.height(43.2.dp).padding(horizontal = 8.4.dp).semantics(mergeDescendants = true) {
        contentDescription = label
        stateDescription = value.roundToInt().toString()
        progressBarRangeInfo = ProgressBarRangeInfo(value, range, steps)
        setProgress { onChange(it.coerceIn(range).roundToInt().toFloat()); true }
    }) {
        val center = 5.4.dp + (maxWidth - 10.8.dp) * fraction
        Box(Modifier.offset(x = center - 10.2.dp).width(20.4.dp).height(21.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val pointerHeight = 4.2.dp.toPx()
                drawRoundRect(blue, size = Size(size.width, size.height - pointerHeight), cornerRadius = CornerRadius(3.6.dp.toPx()))
                drawPath(Path().apply {
                    moveTo(size.width / 2 - 4.8.dp.toPx(), size.height - pointerHeight - 1f)
                    lineTo(size.width / 2, size.height)
                    lineTo(size.width / 2 + 4.8.dp.toPx(), size.height - pointerHeight - 1f)
                    close()
                }, blue)
            }
            Box(Modifier.fillMaxWidth().height(16.8.dp), contentAlignment = Alignment.Center) {
                Text(value.roundToInt().toString(), color = Color.White, fontSize = 9.6.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.clearAndSetSemantics {})
            }
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Slider(value, { onChange(it.roundToInt().toFloat()) },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp).clearAndSetSemantics {},
            valueRange = range, steps = steps,
            thumb = { Box(Modifier.size(10.8.dp).background(blue, CircleShape)) },
            track = {
                Canvas(Modifier.fillMaxWidth().height(1.8.dp)) {
                    drawLine(grey, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.8.dp.toPx(), StrokeCap.Round)
                    drawLine(blue, Offset(0f, size.height / 2), Offset(size.width * fraction, size.height / 2), 1.8.dp.toPx(), StrokeCap.Round)
                }
            })
        }
    }
}
