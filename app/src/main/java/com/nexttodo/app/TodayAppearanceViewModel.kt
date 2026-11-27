package com.nexttodo.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal enum class TodaySort(val label: String) {
    MANUAL("自定义顺序"), TIME("时间"), TITLE("名称"), TYPE("事件类型");

    fun sorted(events: List<Event>): List<Event> = when (this) {
        MANUAL -> events
        TIME -> events.sortedWith(compareBy<Event> { it.time.isBlank() }.thenBy { it.time })
        TITLE -> events.sortedBy { it.title }
        TYPE -> events.sortedBy { it.type.ordinal }
    }
}

internal data class TodayBackground(val skin: TimetableSkin, val image: String? = null,
    val headerColor: Int? = null, val dim: Float? = null) {
    fun toJson() = JSONObject().put("skin", skin.name).put("image", image)
        .put("headerColor", headerColor).put("dim", dim)

    companion object {
        fun fromTable(table: Timetable) = TodayBackground(table.skin, table.backgroundImage,
            table.backgroundTextColors[backgroundKey(table.skin, table.backgroundImage)]?.header,
            table.style.backgroundDim)
        fun fromJson(json: JSONObject) = TodayBackground(TimetableSkin.valueOf(json.getString("skin")),
            json.optString("image").takeIf { it.isNotBlank() && it != "null" },
            if (json.isNull("headerColor")) null else json.getInt("headerColor"),
            if (json.isNull("dim")) null else json.getDouble("dim").toFloat().takeIf { it.isFinite() }?.coerceIn(0f, .85f))
    }
}

internal class TodayAppearanceViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("today_appearance", 0)
    var synced by mutableStateOf(preferences.getBoolean("synced", true))
        private set
    var background by mutableStateOf(runCatching {
        preferences.getString("background", null)?.let { TodayBackground.fromJson(JSONObject(it)) }
    }.getOrNull())
        private set
    var images by mutableStateOf(runCatching {
        val json = JSONArray(preferences.getString("images", "[]"))
        (0 until json.length()).map { json.getString(it) }.filter { wallpaperFile(application, it)?.isFile == true }.distinct()
    }.getOrDefault(emptyList()))
        private set
    var sort by mutableStateOf(TodaySort.entries.find { it.name == preferences.getString("sort", null) } ?: TodaySort.MANUAL)
        private set
    var display by mutableStateOf(EventDisplay.entries.find { it.name == preferences.getString("display", null) } ?: EventDisplay.TREE)
        private set
    var filter by mutableStateOf(EventStatusFilter.ALL)
        private set

    fun changeFilter(value: EventStatusFilter) { filter = value }
    fun changeDisplay(value: EventDisplay) {
        if (preferences.edit().putString("display", value.name).commit()) display = value
        else message = "无法保存显示视图，请重试"
    }
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set

    fun effective(table: Timetable) = if (synced) TodayBackground.fromTable(table)
        else background ?: TodayBackground.fromTable(table)

    fun clearMessage() { message = "" }

    fun changeSort(value: TodaySort) {
        if (preferences.edit().putString("sort", value.name).commit()) sort = value
        else message = "无法保存排序方式，请重试"
    }

    fun setSynced(value: Boolean, table: Timetable) = operation {
        if (!value && background == null) {
            var initial = TodayBackground.fromTable(table)
            // Keep a separate copy so deleting a timetable image cannot break Today.
            if (initial.skin == TimetableSkin.CUSTOM && initial.image != null) {
                val original = checkNotNull(wallpaperFile(getApplication(), initial.image))
                val copy = newImage()
                try {
                    withContext(Dispatchers.IO) { original.copyTo(copy) }
                    initial = initial.copy(image = copy.name)
                    persist(value, initial, images + copy.name)
                } catch (exception: Exception) { copy.delete(); throw exception }
            } else persist(value, initial, images)
        } else persist(value, background, images)
    }

    fun select(skin: TimetableSkin, image: String? = null) = operation {
        require(skin != TimetableSkin.CUSTOM || image in images)
        persist(false, TodayBackground(skin, image), images)
    }

    fun saveBackground(bitmap: Bitmap, crop: WallpaperCrop, onSaved: () -> Unit) = operation {
        val file = newImage()
        try {
            withContext(Dispatchers.IO) {
                val cropped = cropWallpaper(bitmap, crop)
                try { file.outputStream().use { check(cropped.compress(Bitmap.CompressFormat.JPEG, 92, it)) } }
                finally { cropped.recycle() }
            }
            persist(false, TodayBackground(TimetableSkin.CUSTOM, file.name), images + file.name)
        } catch (exception: Exception) { file.delete(); throw exception }
        onSaved()
    }

    fun remove(image: String) = operation {
        require(image in images)
        val next = if (background?.image == image) TodayBackground(TimetableSkin.DEFAULT) else background
        persist(synced, next, images - image)
        withContext(Dispatchers.IO) { wallpaperFile(getApplication(), image)?.delete() }
    }

    private fun newImage() = File(getApplication<Application>().filesDir, "wallpaper-${UUID.randomUUID()}.jpg")

    private suspend fun persist(sync: Boolean, next: TodayBackground?, gallery: List<String>) {
        withContext(Dispatchers.IO) {
            check(preferences.edit().putBoolean("synced", sync).putString("background", next?.toJson()?.toString())
                .putString("images", JSONArray(gallery).toString()).commit())
        }
        synced = sync; background = next; images = gallery
    }

    private fun operation(action: suspend () -> Unit) {
        if (busy) return
        busy = true; message = ""
        viewModelScope.launch {
            try { action() }
            catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) {
                android.util.Log.e("NextToDo", "Unable to save Today background", exception)
                message = "无法保存 Today 背景，请重试"
            } finally { busy = false }
        }
    }
}
