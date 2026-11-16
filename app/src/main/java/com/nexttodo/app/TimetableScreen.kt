package com.nexttodo.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class TimetableViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("timetable", Context.MODE_PRIVATE)
    var courses by mutableStateOf<List<CourseSlot>>(emptyList())
        private set
    var preview by mutableStateOf<List<CourseSlot>?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set

    init {
        runOperation("无法读取本地课表") {
            courses = withContext(Dispatchers.IO) {
                val data = JSONArray(preferences.getString("courses", "[]") ?: "[]")
                List(data.length()) { index ->
                    val row = data.getJSONObject(index)
                    CourseSlot(
                        row.getString("name"),
                        row.optInt("day").takeIf { it in 1..7 },
                        row.optInt("first").takeIf { it > 0 },
                        row.optInt("last").takeIf { it > 0 },
                        row.optString("teacher"),
                        row.optString("room"),
                        row.optString("weeks"),
                    )
                }
            }
        }
    }

    fun chooseFile(uri: Uri) = runOperation("无法读取课表，请选择 .xlsx 文件") {
        val imported = withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            checkNotNull(resolver.openInputStream(uri)).use(::readTimetable)
        }
        preview = imported.filterNot { it in courses }.takeIf { it.isNotEmpty() }
        if (preview == null) message = "这份课表已经导入"
    }

    fun closePreview() {
        if (!busy) preview = null
    }

    fun confirmImport() {
        val additions = preview ?: return
        runOperation("无法保存课表，请重试") {
            persist((courses + additions).distinct())
            preview = null
            message = "已导入 ${additions.size} 条课程安排"
        }
    }

    fun saveCourse(previous: CourseSlot?, course: CourseSlot) = runOperation("无法保存课程，请重试") {
        if (course != previous && course in courses) {
            message = "课表中已存在相同的课程安排"
            return@runOperation
        }
        val updated = if (previous == null) courses + course else courses.map {
            if (it == previous) course else it
        }
        persist(updated)
        message = if (previous == null) "已添加课程" else "已更新课程"
    }

    private suspend fun persist(updated: List<CourseSlot>) {
        withContext(Dispatchers.IO) {
            val data = JSONArray(updated.map {
                JSONObject()
                    .put("name", it.name)
                    .put("day", it.weekday)
                    .put("first", it.firstPeriod)
                    .put("last", it.lastPeriod)
                    .put("teacher", it.teacher)
                    .put("room", it.room)
                    .put("weeks", it.weeks)
            })
            check(preferences.edit().putString("courses", data.toString()).commit())
        }
        courses = updated
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

private val excelMimeTypes = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/octet-stream",
)

private val dayLabels = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

@Composable
internal fun TimetableScreen(model: TimetableViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(model::chooseFile)
    }
    var appImport by rememberSaveable { mutableStateOf(false) }
    var editCourseIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { picker.launch(excelMimeTypes) },
            enabled = !model.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("导入 Excel") }
        OutlinedButton(
            onClick = { appImport = true },
            enabled = !model.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("从课表 App 导入") }
        OutlinedButton(
            onClick = { editCourseIndex = -1 },
            enabled = !model.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("手动添加课程") }
        if (model.message.isNotEmpty()) {
            Text(model.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (model.courses.isEmpty() && !model.busy) {
            Text("暂无课表", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val days = model.courses.groupBy { it.weekday ?: 8 }.toSortedMap()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEach { (day, courses) ->
                item {
                    Text(
                        dayLabels.getOrNull(day - 1) ?: "未指定节次",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(courses.sortedBy { it.firstPeriod ?: Int.MAX_VALUE }) { course ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !model.busy) {
                            editCourseIndex = model.courses.indexOf(course)
                        },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        CourseDescription(course, Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
    editCourseIndex?.let { index ->
        val course = model.courses.getOrNull(index)
        CourseEditor(
            course = course,
            onDismiss = { editCourseIndex = null },
            onSave = {
                model.saveCourse(course, it)
                editCourseIndex = null
            },
        )
    }
    if (appImport) {
        AlertDialog(
            onDismissRequest = { appImport = false },
            title = { Text("从课表 App 导入") },
            text = { Text("在原课表 App 导出 .xlsx 课表，再选择该文件。需要包含课程名称、星期、开始节数和结束节数。") },
            confirmButton = {
                Button(onClick = { appImport = false; picker.launch(excelMimeTypes) }) {
                    Text("选择导出的文件")
                }
            },
            dismissButton = { TextButton(onClick = { appImport = false }) { Text("取消") } },
        )
    }
    model.preview?.let { courses ->
        AlertDialog(
            onDismissRequest = model::closePreview,
            title = { Text("导入 ${courses.size} 条课程安排") },
            text = {
                LazyColumn(
                    Modifier.heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { items(courses) { CourseDescription(it) } }
            },
            confirmButton = {
                Button(onClick = model::confirmImport, enabled = !model.busy) { Text("确认导入") }
            },
            dismissButton = {
                TextButton(onClick = model::closePreview, enabled = !model.busy) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CourseEditor(
    course: CourseSlot?,
    onDismiss: () -> Unit,
    onSave: (CourseSlot) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(course?.name.orEmpty()) }
    var day by rememberSaveable { mutableStateOf(course?.weekday) }
    var first by rememberSaveable { mutableStateOf(course?.firstPeriod?.toString().orEmpty()) }
    var last by rememberSaveable { mutableStateOf(course?.lastPeriod?.toString().orEmpty()) }
    var teacher by rememberSaveable { mutableStateOf(course?.teacher.orEmpty()) }
    var room by rememberSaveable { mutableStateOf(course?.room.orEmpty()) }
    var weeks by rememberSaveable { mutableStateOf(course?.weeks.orEmpty()) }
    val firstPeriod = first.toIntOrNull()
    val lastPeriod = last.toIntOrNull()
    val validPeriods = if (day == null) first.isBlank() && last.isBlank() else {
        firstPeriod != null && firstPeriod > 0 && lastPeriod != null && lastPeriod >= firstPeriod
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (course == null) "手动添加课程" else "编辑课程") },
        text = {
            Column(
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CourseField("课程名称", name) { name = it }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = day == null,
                        onClick = { day = null; first = ""; last = "" },
                        label = { Text("未指定节次") },
                    )
                    dayLabels.forEachIndexed { index, label ->
                        FilterChip(
                            selected = day == index + 1,
                            onClick = { day = index + 1 },
                            label = { Text(label) },
                        )
                    }
                }
                if (day != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CourseField("开始节数", first, Modifier.weight(1f), number = true) { first = it }
                        CourseField("结束节数", last, Modifier.weight(1f), number = true) { last = it }
                    }
                }
                CourseField("老师", teacher) { teacher = it }
                CourseField("地点", room) { room = it }
                CourseField("周数", weeks) { weeks = it }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && validPeriods,
                onClick = {
                    onSave(CourseSlot(name.trim(), day, firstPeriod, lastPeriod, teacher.trim(), room.trim(), weeks.trim()))
                },
            ) { Text("保存课程") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CourseField(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    number: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (!number || it.all { digit -> digit in '0'..'9' }) onChange(it) },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
    )
}

@Composable
private fun CourseDescription(course: CourseSlot, modifier: Modifier = Modifier) {
    val period = course.firstPeriod?.let { first ->
        val last = course.lastPeriod
        if (last == null || first == last) "第 $first 节" else "第 $first–$last 节"
    }
    Column(modifier) {
        Text(course.name, style = MaterialTheme.typography.titleSmall)
        val description = listOfNotNull(
            period, course.room.ifBlank { null }, course.teacher.ifBlank { null }, course.weeks.ifBlank { null },
        ).joinToString(" · ")
        if (description.isNotEmpty()) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
