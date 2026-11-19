package com.nexttodo.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import org.json.JSONArray
import org.json.JSONObject

internal const val DEFAULT_WEEK_COUNT = 20
internal const val MAX_TIMETABLE_WEEKS = 100

internal enum class TimetableFormat(vararg val mimeTypes: String) {
    EXCEL("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream"),
    HTML("text/html", "application/xhtml+xml", "text/plain", "application/octet-stream"),
}

internal fun LocalDate.monday(): LocalDate = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

internal data class Timetable(
    val id: String,
    val name: String,
    val firstDay: LocalDate,
    val courses: List<CourseSlot>,
    val skin: TimetableSkin = TimetableSkin.DEFAULT,
    val backgroundImage: String? = null,
    val backgroundImages: List<String> = emptyList(),
    val backgroundTextColors: Map<String, BackgroundTextColors> = emptyMap(),
    val style: TimetableStyle = TimetableStyle(),
) {
    fun currentWeek(today: LocalDate = LocalDate.now()): Int =
        ChronoUnit.WEEKS.between(firstDay.monday(), today.monday()).toInt() + 1

    fun dateOfWeek(week: Int): LocalDate = firstDay.monday().plusWeeks(week.toLong() - 1)

    fun weekCount(today: LocalDate): Int = maxOf(
        DEFAULT_WEEK_COUNT,
        currentWeek(today),
        courses.maxOfOrNull { course ->
            weekRanges.findAll(course.weeks).maxOfOrNull {
                (it.groupValues[2].ifEmpty { it.groupValues[1] }).toIntOrNull() ?: 0
            } ?: 0
        } ?: 0,
    ).coerceAtMost(MAX_TIMETABLE_WEEKS)
}

private val weekRanges = Regex("""(\d+)(?:\s*[-－—–~～至]\s*(\d+))?\s*周?\s*[（(]?\s*([单双])?""")

internal fun CourseSlot.takesPlaceIn(week: Int): Boolean {
    val ranges = weekRanges.findAll(weeks).toList()
    val parity = when {
        "单周" in weeks && "双周" !in weeks -> "单"
        "双周" in weeks && "单周" !in weeks -> "双"
        else -> ""
    }
    fun matchesParity(value: String) = when (value) {
        "单" -> week % 2 == 1
        "双" -> week % 2 == 0
        else -> true
    }
    if (ranges.isEmpty()) return matchesParity(parity)
    val globalParity = parity.takeIf { ranges.none { it.groupValues[3].isNotEmpty() } }.orEmpty()
    return ranges.any { match ->
        val first = match.groupValues[1].toIntOrNull() ?: return@any false
        val last = match.groupValues[2].toIntOrNull() ?: first
        week in first..last && matchesParity(match.groupValues[3].ifEmpty { globalParity })
    }
}

internal fun CourseSlot.hasFutureOccurrence(week: Int): Boolean =
    ((week + 1)..MAX_TIMETABLE_WEEKS).any { takesPlaceIn(it) }

internal class TimetableViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("timetable", Context.MODE_PRIVATE)
    private val initial = Timetable("default", "未命名", LocalDate.now().monday(), emptyList())
    var tables by mutableStateOf(listOf(initial))
        private set
    var selectedId by mutableStateOf(initial.id)
        private set
    val selected: Timetable get() = tables.first { it.id == selectedId }

    var preview by mutableStateOf<List<CourseSlot>?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var ready by mutableStateOf(false)
        private set
    var aiDraft by mutableStateOf<TimetableAiDraft?>(null)
        private set
    var aiImportSource by mutableStateOf<AiTimetableSource?>(null)
        private set
    var aiImportDraft by mutableStateOf<AiTimetableImportDraft?>(null)
        private set
    var aiImportStage by mutableStateOf("")
        private set
    private var aiImportJob: Job? = null
    private var aiImportService: TimetableAiImportService? = null

    fun setAiPreview(draft: TimetableAiDraft) { aiDraft = draft }
    fun clearAiPreview() { aiDraft = null }

    override fun onCleared() {
        aiImportService?.cancel()
        super.onCleared()
    }

    fun chooseAiImportFile(uri: Uri) = aiImportOperation("无法读取文件，请重新选择") {
        aiImportStage = "正在读取文件…"
        val source = withContext(Dispatchers.IO) { readAiTimetableSource(getApplication(), uri) }
        aiImportSource = source; aiImportDraft = null
    }

    fun useAiImportText(text: String) {
        if (busy) return
        require(text.length <= 100_000)
        aiImportSource = AiTimetableSource("粘贴的课表文本", text.trim().toByteArray(Charsets.UTF_8), "user_data", "timetable.txt")
        aiImportDraft = null; message = ""
    }

    fun generateAiImport() = aiImportOperation("AI 解析失败，请检查网络后重试") {
        val source = requireNotNull(aiImportSource) { "请先选择课表文件或粘贴课表文本" }
        require(source.data.isNotEmpty()) { "课表内容为空，请重新选择或填写" }
        val table = selected
        aiImportStage = "正在上传并解析课表…"
        val service = TimetableAiImportService()
        aiImportService = service
        val result = withContext(Dispatchers.IO) {
            val key = AppSettings(getApplication()).readDeepSeekKey()
            require(!key.isNullOrBlank()) { "请先在 My 中填写并保存 DeepSeek API 密钥" }
            service.parse(source, key)
        }
        require(selected.id == table.id) { "当前课表已切换，请重新解析后导入" }
        val existing = table.courses.mapTo(hashSetOf()) { it.importFields() }
        val additions = result.courses.filterNot { it.importFields() in existing }
        require(additions.isNotEmpty()) { "识别到的课程都已存在，无需重复导入" }
        aiImportDraft = AiTimetableImportDraft(table.id, source.name, additions,
            result.courses.size - additions.size, result.notes)
    }

    fun confirmAiImport(onSaved: () -> Unit) = runOperation("无法导入课程，请重试") {
        val draft = requireNotNull(aiImportDraft) { "请先解析并核对课程" }
        require(selected.id == draft.tableId) { "当前课表已切换，请重新解析后导入" }
        val existing = selected.courses.mapTo(hashSetOf()) { it.importFields() }
        val additions = draft.courses.filterNot { it.importFields() in existing }
        require(additions.isNotEmpty()) { "这些课程都已存在，无需重复导入" }
        update(selected.copy(courses = selected.courses + additions))
        aiImportDraft = null; aiImportSource = null
        message = "已导入 ${additions.size} 条课程安排"
        onSaved()
    }

    fun discardAiImportPreview() { if (!busy) { aiImportDraft = null; message = "" } }

    fun closeAiImport() {
        if (busy && aiImportJob == null) return
        aiImportJob?.let { job ->
            job.cancel()
            aiImportService?.cancel()
            aiImportJob = null; aiImportService = null; busy = false
        }
        aiImportStage = ""; aiImportSource = null; aiImportDraft = null; message = ""
    }

    private fun aiImportOperation(failure: String, action: suspend () -> Unit) {
        if (busy || !ready) return
        busy = true; message = ""
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { action() }
            catch (exception: CancellationException) { throw exception }
            catch (exception: IllegalArgumentException) { message = exception.message ?: failure }
            catch (_: org.json.JSONException) { message = "AI 返回的课程格式有误，请重新解析" }
            catch (_: Exception) { message = failure }
            finally {
                if (aiImportJob == coroutineContext[Job]) {
                    aiImportJob = null; aiImportService = null; aiImportStage = ""; busy = false
                }
            }
        }
        aiImportJob = job
        job.start()
    }

    init {
        runOperation("无法读取本地课表") {
            val (loaded, selectedTable) = withContext(Dispatchers.IO) {
                val saved = preferences.getString("tables", null)
                val loaded = if (saved == null) {
                    val previous = JSONArray(preferences.getString("courses", "[]") ?: "[]")
                    listOf(initial.copy(courses = previous.readCourses())).also { writeTables(it, initial.id) }
                } else {
                    JSONArray(saved).objects().map {
                        Timetable(
                            it.getString("id"),
                            it.getString("name"),
                            LocalDate.parse(it.getString("firstDay")),
                            it.getJSONArray("courses").readCourses(),
                            TimetableSkin.entries.firstOrNull { skin -> skin.name == it.optString("skin") }
                                ?: TimetableSkin.DEFAULT,
                            it.optString("backgroundImage").takeIf(String::isNotBlank),
                            it.optJSONArray("backgroundImages")?.let { images ->
                                List(images.length()) { index -> images.getString(index) }.distinct()
                            } ?: listOfNotNull(it.optString("backgroundImage").takeIf(String::isNotBlank)),
                            it.optJSONObject("backgroundTextColors")?.let { colors ->
                                colors.keys().asSequence().associateWith { key ->
                                    val saved = colors.getJSONObject(key)
                                    BackgroundTextColors.fromJson(saved)
                                }
                            }.orEmpty(),
                            runCatching { TimetableStyle.fromJson(it.optJSONObject("style")) }.getOrDefault(TimetableStyle()),
                        )
                    }
                }
                check(loaded.isNotEmpty())
                val id = preferences.getString("selectedTable", null)
                loaded to (loaded.find { it.id == id } ?: loaded.first()).id
            }
            selectedId = selectedTable
            tables = loaded
            ready = true
        }
    }

    fun chooseFile(uri: Uri, format: TimetableFormat) = runOperation("无法读取课表，请检查文件格式") {
        val imported = withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            checkNotNull(resolver.openInputStream(uri)).use {
                when (format) {
                    TimetableFormat.EXCEL -> readTimetable(it)
                    TimetableFormat.HTML -> readHtmlTimetable(it)
                }
            }
        }
        val existing = selected.courses.mapTo(hashSetOf()) { it.importFields() }
        preview = imported.filterNot { it.importFields() in existing }.takeIf { it.isNotEmpty() }
        if (preview == null) message = "这份课表已经导入"
    }

    fun clearMessage() {
        message = ""
    }

    fun changeSkin(skin: TimetableSkin) = runOperation("无法保存课表外观，请重试") {
        require(skin != TimetableSkin.CUSTOM || selected.backgroundImage != null) { "请先导入背景图片" }
        update(selected.copy(skin = skin))
    }

    fun selectBackground(image: String) = runOperation("无法保存课表背景，请重试") {
        require(image in selected.backgroundImages)
        update(selected.copy(skin = TimetableSkin.CUSTOM, backgroundImage = image))
    }

    fun saveBackgroundTextColors(key: String, colors: BackgroundTextColors?, onSaved: () -> Unit) =
        runOperation("无法保存文字颜色，请重试") {
            val updated = if (colors == null) selected.backgroundTextColors - key
                else selected.backgroundTextColors + (key to colors)
            update(selected.copy(backgroundTextColors = updated))
            onSaved()
        }

    fun applyAiDraft(draft: TimetableAiDraft, onSaved: () -> Unit) = runOperation("无法保存 AI 修改，请重试") {
        val current = selected
        val original = draft.original
        require(current.id == original.id && current.style == original.style && current.skin == original.skin &&
            current.backgroundImage == original.backgroundImage && current.backgroundTextColors == original.backgroundTextColors) {
            "课表外观已发生变化，请重新生成预览"
        }
        val changesCourses = draft.preview.courses != original.courses
        require(!changesCourses || current.courses == original.courses) { "课程已发生变化，请重新生成预览，避免覆盖新的修改" }
        update(current.copy(style = draft.preview.style, skin = draft.preview.skin,
            backgroundTextColors = draft.preview.backgroundTextColors,
            courses = if (changesCourses) draft.preview.courses else current.courses))
        onSaved()
    }

    fun saveBackground(bitmap: Bitmap, crop: WallpaperCrop, onSaved: () -> Unit) =
        runOperation("无法保存背景图片，请重试") {
            val table = selected
            val file = File(getApplication<Application>().filesDir, "wallpaper-${UUID.randomUUID()}.jpg")
            try {
                withContext(Dispatchers.IO) {
                    val output = cropWallpaper(bitmap, crop)
                    try {
                        file.outputStream().use { check(output.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
                    } finally { output.recycle() }
                }
                update(table.copy(skin = TimetableSkin.CUSTOM, backgroundImage = file.name,
                    backgroundImages = table.backgroundImages + file.name))
            } catch (exception: Exception) {
                file.delete()
                throw exception
            }
            onSaved()
        }

    fun removeBackground(image: String) = runOperation("无法移除背景图片，请重试") {
        val table = selected
        require(image in table.backgroundImages)
        update(table.copy(backgroundImages = table.backgroundImages - image,
            backgroundTextColors = table.backgroundTextColors - backgroundKey(TimetableSkin.CUSTOM, image),
            backgroundImage = table.backgroundImage.takeUnless { it == image },
            skin = if (table.skin == TimetableSkin.CUSTOM && table.backgroundImage == image) TimetableSkin.DEFAULT else table.skin))
        withContext(Dispatchers.IO) { wallpaperFile(getApplication(), image)?.delete() }
    }

    fun closePreview() {
        if (!busy) preview = null
    }

    fun confirmImport() {
        val additions = preview ?: return
        runOperation("无法保存课表，请重试") {
            update(selected.copy(courses = selected.courses + additions))
            preview = null
            message = "已导入 ${additions.size} 条课程安排"
        }
    }

    fun saveCourses(previous: CourseSlot?, slots: List<CourseSlot>, onSaved: () -> Unit) =
        runOperation("无法保存课程，请重试") {
            val remaining = selected.courses.toMutableList().apply { if (previous != null) remove(previous) }
            require(slots.distinct().size == slots.size && slots.none { it in remaining }) {
                "课表中已存在相同的课程安排"
            }
            update(selected.copy(courses = remaining + slots))
            onSaved()
        }

    fun createTable(name: String, firstDay: LocalDate, onCreated: () -> Unit) =
        runOperation("无法新建课表，请重试") {
            val table = Timetable(UUID.randomUUID().toString(), name.trim().ifEmpty { "未命名" }, firstDay, emptyList())
            persist(tables + table, table.id)
            onCreated()
        }

    fun editTable(table: Timetable, name: String, firstDay: LocalDate, onSaved: () -> Unit) =
        runOperation("无法保存课表，请重试") {
            update(table.copy(name = name.trim().ifEmpty { "未命名" }, firstDay = firstDay))
            onSaved()
        }

    fun changeCurrentWeek(week: Int, today: LocalDate, onSaved: () -> Unit) =
        runOperation("无法修改当前周，请重试") {
            val shift = selected.currentWeek(today).toLong() - week
            update(selected.copy(firstDay = selected.firstDay.plusWeeks(shift)))
            onSaved()
        }

    fun selectTable(id: String) = runOperation("无法切换课表，请重试") {
        if (id != selectedId && preview == null && tables.any { it.id == id }) persist(tables, id)
    }

    fun removeTable(id: String, onRemoved: () -> Unit) = runOperation("无法删除课表，请重试") {
        require(tables.size > 1) { "请至少保留一张课表" }
        val images = tables.find { it.id == id }?.backgroundImages.orEmpty()
        val remaining = tables.filterNot { it.id == id }
        persist(remaining, if (selectedId == id) remaining.first().id else selectedId)
        withContext(Dispatchers.IO) {
            images.forEach { wallpaperFile(getApplication(), it)?.delete() }
        }
        onRemoved()
    }

    private suspend fun update(updated: Timetable) = persist(
        tables.map { if (it.id == updated.id) updated else it },
        selectedId,
    )

    private suspend fun persist(updated: List<Timetable>, id: String) {
        withContext(Dispatchers.IO) { writeTables(updated, id) }
        tables = updated
        selectedId = id
    }

    private fun writeTables(updated: List<Timetable>, id: String) {
        val data = JSONArray(updated.map { table ->
            JSONObject().put("id", table.id).put("name", table.name)
                .put("firstDay", table.firstDay.toString())
                .put("courses", JSONArray(table.courses.map(CourseSlot::toJson)))
                .put("skin", table.skin.name).put("backgroundImage", table.backgroundImage.orEmpty())
                .put("backgroundImages", JSONArray(table.backgroundImages))
                .put("style", table.style.toJson())
                .put("backgroundTextColors", JSONObject(table.backgroundTextColors.mapValues { (_, colors) ->
                    colors.toJson()
                }))
        })
        check(preferences.edit().putString("tables", data.toString()).putString("selectedTable", id).commit())
    }

    private fun runOperation(failure: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = ""
        viewModelScope.launch {
            try {
                action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                message = exception.message ?: failure
            } catch (_: Exception) {
                message = failure
            } finally {
                busy = false
            }
        }
    }
}

private fun CourseSlot.importFields() = copy(color = null, credits = "", customTime = "", notes = "")

private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

internal fun JSONArray.readCourses() = objects().map { row ->
    CourseSlot(
        row.getString("name"),
        row.optInt("day").takeIf { it in 1..7 },
        row.optInt("first").takeIf { it > 0 },
        row.optInt("last").takeIf { it > 0 },
        row.optString("teacher"),
        row.optString("room"),
        row.optString("weeks"),
        row.optInt("color").takeUnless { row.isNull("color") },
        row.optString("credits"),
        row.optString("customTime"),
        row.optString("notes"),
    )
}

private fun CourseSlot.toJson() = JSONObject()
    .put("name", name).put("day", weekday).put("first", firstPeriod).put("last", lastPeriod)
    .put("teacher", teacher).put("room", room).put("weeks", weeks)
    .put("color", color).put("credits", credits).put("customTime", customTime).put("notes", notes)
