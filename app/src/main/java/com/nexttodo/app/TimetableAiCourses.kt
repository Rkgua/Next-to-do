package com.nexttodo.app

import org.json.JSONArray
import org.json.JSONObject

internal data class AiCourseChange(val before: CourseSlot?, val after: CourseSlot?) {
    val action: String get() = when { before == null -> "新增"; after == null -> "删除"; else -> "修改" }
}

internal data class AiCourseResult(val courses: List<CourseSlot>, val changes: List<AiCourseChange>)

internal fun courseContext(courses: List<CourseSlot>): JSONArray = JSONArray(courses.mapIndexed { index, course ->
    JSONObject().put("id", "course_${index + 1}").put("name", course.name)
        .put("weekday", course.weekday ?: JSONObject.NULL)
        .put("firstPeriod", course.firstPeriod ?: JSONObject.NULL).put("lastPeriod", course.lastPeriod ?: JSONObject.NULL)
        .put("weeks", course.weeks).put("room", course.room).put("teacher", course.teacher)
        .put("credits", course.credits).put("customTime", course.customTime).put("notes", course.notes)
        .put("color", course.color?.let(::colorHex) ?: JSONObject.NULL)
})

internal fun parseAiCourseChanges(original: List<CourseSlot>, operations: JSONArray): AiCourseResult {
    require(operations.length() <= 100) { "一次最多修改 100 项课程安排，请分批处理" }
    if (operations.length() == 0) return AiCourseResult(original, emptyList())
    val updated = original.map { it as CourseSlot? }.toMutableList()
    val additions = mutableListOf<CourseSlot>()
    val touched = mutableSetOf<Int>()
    val changes = mutableListOf<AiCourseChange>()
    for (index in 0 until operations.length()) {
        val operation = operations.getJSONObject(index)
        val action = operation.getString("action")
        val allowed = when (action) {
            "add" -> setOf("action", "course")
            "update" -> setOf("action", "targets", "fields")
            "delete" -> setOf("action", "targets")
            else -> throw IllegalArgumentException("AI 返回了不支持的课程操作，请重新生成")
        }
        require(operation.keys().asSequence().all { it in allowed }) { "AI 返回了不支持的课程操作字段" }
        if (action == "add") {
            val course = editAiCourse(operation.getJSONObject("course"), null)
            additions.add(course)
            changes.add(AiCourseChange(null, course))
            continue
        }
        val targets = operation.getJSONArray("targets")
        require(targets.length() > 0) { "没有找到要修改的课程，请写清课程名称" }
        for (target in 0 until targets.length()) {
            val id = targets.getString(target)
            val courseIndex = Regex("course_([1-9][0-9]*)").matchEntire(id)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
            require(courseIndex != null && courseIndex in original.indices) { "AI 选择了不存在的课程，请重新生成" }
            require(touched.add(courseIndex)) { "同一条课程安排被重复修改，请重新生成" }
            val before = original[courseIndex]
            val after = if (action == "delete") null else editAiCourse(operation.getJSONObject("fields"), before)
            updated[courseIndex] = after
            if (before != after) changes.add(AiCourseChange(before, after))
        }
    }
    val courses = updated.filterNotNull() + additions
    val oldCounts = original.groupingBy { it }.eachCount()
    require(courses.groupingBy { it }.eachCount().all { (course, count) -> count <= maxOf(1, oldCounts[course] ?: 0) }) {
        "修改后存在重复课程安排，请重新描述要求"
    }
    return AiCourseResult(courses, changes)
}

private fun editAiCourse(fields: JSONObject, before: CourseSlot?): CourseSlot {
    val supported = setOf("name", "weekday", "firstPeriod", "lastPeriod", "weeks", "room", "teacher", "credits", "customTime", "notes", "color")
    require(fields.keys().asSequence().all { it in supported }) { "AI 返回了不支持的课程信息" }
    fun text(name: String, old: String, limit: Int = 200): String {
        if (!fields.has(name)) return old
        val value = if (fields.isNull(name)) "" else fields.getString(name).trim()
        require(value.length <= limit) { "课程信息过长，请简化后重试" }
        return value
    }
    fun integer(name: String, old: Int?, min: Int, max: Int): Int? {
        if (!fields.has(name)) return old
        if (fields.isNull(name)) return null
        val raw = (fields.get(name) as? Number)?.toDouble()
        require(raw != null && raw.isFinite() && raw == raw.toInt().toDouble() && raw in min.toDouble()..max.toDouble()) {
            "AI 返回了无效的星期或节次，请重新生成"
        }
        return raw.toInt()
    }
    val course = CourseSlot(
        name = text("name", before?.name.orEmpty(), 120),
        weekday = integer("weekday", before?.weekday, 1, 7),
        firstPeriod = integer("firstPeriod", before?.firstPeriod, 1, 99),
        lastPeriod = integer("lastPeriod", before?.lastPeriod, 1, 99),
        teacher = text("teacher", before?.teacher.orEmpty()), room = text("room", before?.room.orEmpty()),
        weeks = text("weeks", before?.weeks.orEmpty()),
        color = if (!fields.has("color")) before?.color else if (fields.isNull("color")) null else
            requireNotNull(parseTextColor(fields.getString("color"))) { "AI 返回了无效的课程颜色" },
        credits = text("credits", before?.credits.orEmpty(), 30),
        customTime = text("customTime", before?.customTime.orEmpty()), notes = text("notes", before?.notes.orEmpty(), 2000),
    )
    require(course.name.isNotBlank()) { "请说明要添加的课程名称" }
    require((course.firstPeriod == null) == (course.lastPeriod == null) &&
        (course.firstPeriod == null || course.firstPeriod <= course.lastPeriod!!)) { "开始和结束节次需要完整且顺序正确" }
    if (before == null || fields.has("weeks")) {
        validateAiWeeks(course.weeks)
        require((1..MAX_TIMETABLE_WEEKS).any { course.takesPlaceIn(it) }) { "单双周与周数范围不匹配，请重新说明周数" }
    }
    return course
}

private fun validateAiWeeks(value: String) {
    val text = value.replace("第", "").replace("周", "").replace(" ", "")
        .replace(Regex("[－—–~～至]"), "-")
    if (value.isBlank() || text == "单" || text == "双") return
    val token = "[0-9]+(?:-[0-9]+)?[单双]?"
    require(text.matches(Regex("$token(?:[,，、;；]$token)*"))) { "周数需要是 1-16、5、1-15单周 等格式" }
    Regex("([0-9]+)(?:-([0-9]+))?").findAll(text).forEach { match ->
        val first = match.groupValues[1].toIntOrNull()
        val last = match.groupValues[2].ifEmpty { match.groupValues[1] }.toIntOrNull()
        require(first != null && last != null && first in 1..MAX_TIMETABLE_WEEKS && last in first..MAX_TIMETABLE_WEEKS) {
            "周数需要在 1 到 $MAX_TIMETABLE_WEEKS 周内，且起止顺序正确"
        }
    }
}

internal fun aiCourseDescription(course: CourseSlot): String {
    val timing = listOfNotNull(course.weekday?.let { "周${timetableDays[it - 1]}" },
        course.firstPeriod?.let { "第${it}–${course.lastPeriod}节" }).joinToString(" ")
    return listOfNotNull(course.name, "周数：${course.weeks.ifBlank { "每周" }}",
        "时间：${timing.ifBlank { "未指定星期和节次" }}",
        course.room.takeIf(String::isNotBlank)?.let { "教室：$it" },
        course.teacher.takeIf(String::isNotBlank)?.let { "老师：$it" },
        course.credits.takeIf(String::isNotBlank)?.let { "学分：$it" },
        course.customTime.takeIf(String::isNotBlank)?.let { "自定义时间：$it" },
        course.notes.takeIf(String::isNotBlank)?.let { "备注：$it" },
        course.color?.let { "颜色：${colorHex(it)}" }).joinToString("\n")
}

internal fun TimetableAiDraft.coursePreviewWeek(): Int? {
    if (courseChanges.isEmpty()) return null
    val current = original.currentWeek().coerceIn(1, MAX_TIMETABLE_WEEKS)
    val changed = courseChanges.mapNotNull { it.after }.ifEmpty { courseChanges.mapNotNull { it.before } }
    return (current..MAX_TIMETABLE_WEEKS).firstOrNull { week -> changed.any { it.takesPlaceIn(week) } }
        ?: (1 until current).firstOrNull { week -> changed.any { it.takesPlaceIn(week) } } ?: current
}
