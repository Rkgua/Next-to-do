package com.nexttodo.app

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** Soften only the list's top edge as cards scroll underneath the photo header. */
@Composable
internal fun Modifier.profileSettingsEdge(): Modifier {
    val contentLayer = rememberGraphicsLayer()
    val blurredEdge = rememberGraphicsLayer()
    return drawWithContent {
        val edge = 16.dp.toPx().coerceAtMost(size.height)
        if (edge <= 0f || size.width <= 0f) return@drawWithContent
        contentLayer.record { this@drawWithContent.drawContent() }

        // Fade the sharp content into the existing background transition.
        val canvas = drawContext.canvas
        canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint())
        drawLayer(contentLayer)
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White), endY = edge),
            blendMode = BlendMode.DstIn)
        canvas.restore()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val radius = 2.5.dp.toPx()
            blurredEdge.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
            // Record a narrow strip, including extra pixels for blur sampling.
            blurredEdge.record(size = IntSize(ceil(size.width).toInt(), ceil(edge + radius * 3).toInt())) {
                drawLayer(contentLayer)
            }
            canvas.saveLayer(Rect(0f, 0f, size.width, edge), Paint())
            drawLayer(blurredEdge)
            drawRect(Brush.verticalGradient(0f to Color.Transparent,
                .35f to Color.White.copy(alpha = .55f), 1f to Color.Transparent, endY = edge),
                blendMode = BlendMode.DstIn)
            canvas.restore()
        }
    }
}
