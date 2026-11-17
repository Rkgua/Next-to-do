package com.nexttodo.app

import android.app.Application
import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        val remaining = tables.filterNot { it.id == id }
        persist(remaining, if (selectedId == id) remaining.first().id else selectedId)
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

private fun JSONArray.readCourses() = objects().map { row ->
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
