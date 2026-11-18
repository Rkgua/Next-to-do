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
