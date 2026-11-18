package com.nexttodo.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

internal enum class TimetableSkin(val label: String, val drawable: Int? = null) {
    DEFAULT("默认"),
    AURORA("极光", R.drawable.timetable_aurora),
    STARS("星空", R.drawable.timetable_stars),
    STARS_2("星空 2", R.drawable.timetable_stars_2),
    SWIRL("漩涡", R.drawable.timetable_swirl),
    CUSTOM("自定义图片"),
}

internal fun wallpaperFile(context: Context, name: String): File? =
    name.takeIf { it.matches(Regex("wallpaper-[a-f0-9-]+\\.jpg")) }?.let { File(context.filesDir, it) }

@Composable
internal fun TimetableWallpaper(skin: TimetableSkin, image: String?, modifier: Modifier = Modifier) {
    val dark = timetableDark
    val top = timetableTop
    val bottom = timetableBottom
    val context = LocalContext.current
    val imageKey = image.takeIf { skin == TimetableSkin.CUSTOM }
    val bitmap by produceState<Bitmap?>(null, imageKey) {
        value = null
        value = withContext(Dispatchers.IO) {
            imageKey?.let { wallpaperFile(context, it) }?.let { file ->
                runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
            }
        }
    }
    Box(modifier.background(Brush.verticalGradient(listOf(top, bottom)))) {
        skin.drawable?.let { drawable ->
            Image(painterResource(drawable), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (skin == TimetableSkin.CUSTOM && bitmap != null) {
            Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (dark && skin != TimetableSkin.DEFAULT) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .38f)))
        }
    }
}

internal fun readWallpaper(context: Context, uri: Uri): Bitmap {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片，请选择其他图片" }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    val bitmap = checkNotNull(resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }) { "无法读取这张图片" }
    val orientation = runCatching {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull()
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    if (matrix.isIdentity) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
        if (it !== bitmap) bitmap.recycle()
    }
}

internal data class WallpaperCrop(val width: Int, val height: Int, val zoom: Float, val pan: Offset)

internal fun cropBounds(bitmap: Bitmap, crop: WallpaperCrop): android.graphics.RectF {
    require(crop.width > 0 && crop.height > 0)
    val scale = max(crop.width.toFloat() / bitmap.width, crop.height.toFloat() / bitmap.height) * crop.zoom.coerceIn(1f, 5f)
    val w = max(crop.width.toFloat(), bitmap.width * scale)
    val h = max(crop.height.toFloat(), bitmap.height * scale)
    val x = (crop.width - w) / 2 + crop.pan.x.coerceIn(-(w - crop.width) / 2, (w - crop.width) / 2)
    val y = (crop.height - h) / 2 + crop.pan.y.coerceIn(-(h - crop.height) / 2, (h - crop.height) / 2)
    return android.graphics.RectF(x, y, x + w, y + h)
}

internal fun cropWallpaper(bitmap: Bitmap, crop: WallpaperCrop): Bitmap {
    val factor = min(1f, 1600f / max(crop.width, crop.height))
    val output = Bitmap.createBitmap(max(1, (crop.width * factor).toInt()), max(1, (crop.height * factor).toInt()), Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(output).apply {
        scale(output.width.toFloat() / crop.width, output.height.toFloat() / crop.height)
        drawBitmap(bitmap, null, cropBounds(bitmap, crop), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    }
    return output
}
