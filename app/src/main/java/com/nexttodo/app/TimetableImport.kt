package com.nexttodo.app

import android.util.Xml
import android.text.Html
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

internal data class CourseSlot(
    val name: String,
    val weekday: Int?,
    val firstPeriod: Int?,
    val lastPeriod: Int?,
    val teacher: String,
    val room: String,
    val weeks: String,
    val color: Int? = null,
    val credits: String = "",
    val customTime: String = "",
    val notes: String = "",
)

private val columnNames = listOf("课程名称", "星期", "开始节数", "结束节数", "老师", "地点", "周数")
private val sheetPath = Regex("xl/worksheets/[^/]+\\.xml")
private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")

internal fun readTimetable(input: InputStream): List<CourseSlot> {
    val sheets = mutableListOf<ByteArray>()
    var shared = emptyList<String>()
    var totalBytes = 0
    ZipInputStream(input.buffered()).use { archive ->
        val buffer = ByteArray(8192)
        while (true) {
            val entry = archive.nextEntry ?: break
            val isShared = entry.name == "xl/sharedStrings.xml"
            val isSheet = sheetPath.matches(entry.name)
            val output = if (isShared || isSheet) ByteArrayOutputStream() else null
            while (true) {
                val count = archive.read(buffer)
                if (count < 0) break
                totalBytes += count
                require(totalBytes <= 24 * 1024 * 1024) { "课表文件太大" }
                output?.write(buffer, 0, count)
            }
            if (isShared) shared = readSharedStrings(checkNotNull(output).toByteArray())
            if (isSheet) sheets.add(checkNotNull(output).toByteArray())
        }
    }
    for (sheet in sheets) {
        readCourseRows(readRows(sheet, shared))?.let { return it }
    }
    throw IllegalArgumentException("表头需要包含课程名称、星期、开始节数和结束节数")
}

private val htmlTable = Regex("<table\\b[^>]*>(.*?)</table\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val htmlRow = Regex("<tr\\b[^>]*>(.*?)(?=<tr\\b|</tr\\s*>|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val htmlCell = Regex("<t[dh]\\b([^>]*)>(.*?)(?=<t[dh]\\b|</t[dh]\\s*>|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val htmlIgnored = Regex("<!--.*?-->|<(script|style)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val htmlSpan = Regex("\\b(rowspan|colspan)\\s*=\\s*['\"]?(\\d+)", RegexOption.IGNORE_CASE)
private val gridPeriod = Regex("^第?\\s*(\\d{1,2})(?:\\s*[-—－–~～至]\\s*(\\d{1,2}))?\\s*(?:节(?:次)?)?$")
private val courseDetail = Regex("^(?:教师|老师|任课教师|地点|教室|教学地点|周数|周次)\\s*[:：]\\s*")

private data class HtmlCourseCell(val text: String, val rowspan: Int, val colspan: Int)

internal fun readHtmlTimetable(input: InputStream): List<CourseSlot> {
    val limit = 4 * 1024 * 1024
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count == -1) break
        require(bytes.size() + count <= limit) { "课表文件太大" }
        bytes.write(buffer, 0, count)
    }
    val data = bytes.toByteArray()
    val utf8 = data.toString(Charsets.UTF_8)
    val charset = Regex("charset\\s*=\\s*['\"]?([a-z0-9_-]+)", RegexOption.IGNORE_CASE)
        .find(utf8.take(4096))?.groupValues?.get(1)
    val bom = if (data.size < 2) 0 else (data[0].toInt() and 0xFF) * 256 + (data[1].toInt() and 0xFF)
    val decoded = when {
        bom == 0xFFFE || bom == 0xFEFF -> data.toString(Charsets.UTF_16)
        charset != null -> runCatching { data.toString(Charset.forName(charset)) }.getOrDefault(utf8)
        else -> utf8
    }
    for (table in htmlTable.findAll(htmlIgnored.replace(decoded, ""))) {
        val cells = htmlRow.findAll(table.groupValues[1]).map { row ->
            htmlCell.findAll(row.groupValues[1]).map { cell ->
                val spans = htmlSpan.findAll(cell.groupValues[1]).associate { match ->
                    match.groupValues[1].lowercase() to (match.groupValues[2].toIntOrNull() ?: 1).coerceIn(1, 100)
                }
                val value = Html.fromHtml(cell.groupValues[2], Html.FROM_HTML_MODE_COMPACT)
                    .toString().replace('\u00a0', ' ').trim()
                HtmlCourseCell(value, spans["rowspan"] ?: 1, spans["colspan"] ?: 1)
            }.toList()
        }.toList()
        val rows = cells.mapIndexed { index, row ->
            SheetRow(index + 1, row.mapIndexed { column, cell -> column to cell.text }.toMap())
        }
        readCourseRows(rows)?.let { return it }
        readHtmlGrid(cells).takeIf { it.isNotEmpty() }?.let { return it }
    }
    throw IllegalArgumentException("HTML 需要包含课程明细表，或带星期列和节次行的课表")
}

private fun readHtmlGrid(rows: List<List<HtmlCourseCell>>): List<CourseSlot> {
    val occupiedUntil = mutableMapOf<Int, Int>()
    val grid = rows.mapIndexed { index, row ->
        var column = 0
        row.associateBy { cell ->
            while ((column until column + cell.colspan).any { (occupiedUntil[it] ?: 0) > index }) column++
            val start = column
            repeat(cell.colspan) { occupiedUntil[column++] = index + cell.rowspan }
            start
        }
    }
    val header = grid.indexOfFirst { row -> row.values.count { chineseWeekday(it.text) != null } >= 5 }
    if (header < 0) return emptyList()
    val days = grid[header].mapNotNull { (column, cell) -> chineseWeekday(cell.text)?.let { column to it } }.toMap()
    val periodColumn = days.keys.min() - 1
    if (periodColumn < 0) return emptyList()
    val periods = grid.map { row ->
        val label = row[periodColumn]?.text?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        gridPeriod.matchEntire(label)?.let {
            val first = it.groupValues[1].toInt()
            first to (it.groupValues[2].toIntOrNull() ?: first)
        }
    }
    return buildList {
        for (index in header + 1 until grid.size) {
            val (first, last) = periods[index] ?: continue
            if (first <= 0 || last < first) continue
            for ((column, cell) in grid[index]) {
                val day = days[column] ?: continue
                val lines = cell.text.lines().map(String::trim).filter(String::isNotEmpty)
                val name = lines.firstOrNull()?.takeUnless { it in listOf("-", "—", "/") } ?: continue
                val end = periods.getOrNull(index + cell.rowspan - 1)?.second ?: last
                val teacher = lines.drop(1).firstOrNull { it.startsWith("老师") || "教师" in it }?.replace(courseDetail, "").orEmpty()
                val room = lines.drop(1).firstOrNull { it.startsWith("教室") || "地点" in it || it.startsWith("@") }
                    ?.replace(courseDetail, "")?.removePrefix("@").orEmpty()
                val weeks = lines.drop(1).firstOrNull { "周" in it }?.replace(courseDetail, "").orEmpty()
                add(CourseSlot(name, day, first, maxOf(first, last, end), teacher, room, weeks))
            }
        }
    }.distinct()
}

private fun readCourseRows(rows: List<SheetRow>): List<CourseSlot>? {
    val required = columnNames.take(4)
    val header = rows.indexOfFirst { row -> row.cells.values.map(String::trim).containsAll(required) }
    if (header < 0) return null
    val columns = rows[header].cells.entries.associate { it.value.trim() to it.key }
    return rows.drop(header + 1).mapNotNull { readCourse(it, columns) }.distinct().also {
        require(it.isNotEmpty()) { "表格没有课程数据" }
    }
}

private data class SheetRow(val number: Int, val cells: Map<Int, String>)

private fun parser(data: ByteArray): XmlPullParser = Xml.newPullParser().apply {
    setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
    setInput(data.inputStream(), null)
}

private fun readSharedStrings(data: ByteArray): List<String> {
    val xml = parser(data)
    return buildList {
        var current = StringBuilder()
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            when {
                xml.eventType == XmlPullParser.START_TAG && xml.name == "si" -> current = StringBuilder()
                xml.eventType == XmlPullParser.START_TAG && xml.name == "t" -> current.append(xml.nextText())
                xml.eventType == XmlPullParser.END_TAG && xml.name == "si" -> add(current.toString())
            }
        }
    }
}

private fun readRows(data: ByteArray, shared: List<String>): List<SheetRow> {
    val xml = parser(data)
    return buildList {
        var number = 0
        var column = 0
        var cells = mutableMapOf<Int, String>()
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            when {
                xml.eventType == XmlPullParser.START_TAG && xml.name == "row" -> {
                    number = xml.getAttributeValue(null, "r")?.toIntOrNull() ?: number + 1
                    column = 0
                    cells = mutableMapOf()
                }
                xml.eventType == XmlPullParser.START_TAG && xml.name == "c" -> {
                    val reference = xml.getAttributeValue(null, "r")
                    val letters = reference.orEmpty().takeWhile { it in 'A'..'Z' }
                    if (letters.isNotEmpty()) {
                        column = letters.fold(0) { n, letter -> n * 26 + (letter - 'A') + 1 } - 1
                    }
                    val value = readCell(xml, shared)
                    if (value.isNotBlank()) cells[column] = value
                    column += 1
                }
                xml.eventType == XmlPullParser.END_TAG && xml.name == "row" && cells.isNotEmpty() -> {
                    add(SheetRow(number, cells))
                }
            }
        }
    }
}

private fun readCell(xml: XmlPullParser, shared: List<String>): String {
    val type = xml.getAttributeValue(null, "t")
    val value = StringBuilder()
    while (xml.next() != XmlPullParser.END_DOCUMENT) {
        if (xml.eventType == XmlPullParser.END_TAG && xml.name == "c") break
        if (xml.eventType == XmlPullParser.START_TAG && (xml.name == "v" || xml.name == "t")) {
            value.append(xml.nextText())
        }
    }
    val text = value.toString()
    return if (type == "s") text.toIntOrNull()?.let(shared::getOrNull).orEmpty() else text
}

private fun readCourse(row: SheetRow, columns: Map<String, Int>): CourseSlot? {
    fun value(index: Int) = columns[columnNames[index]]?.let(row.cells::get)?.trim().orEmpty()
    val name = value(0)
    if (name.isEmpty()) return null
    val day = value(1)
    val start = value(2)
    val end = value(3)
    val weekday = wholeNumber(day) ?: chineseWeekday(day)
    val first = wholeNumber(start)
    val last = wholeNumber(end)
    if (day.isNotEmpty() || start.isNotEmpty() || end.isNotEmpty()) {
        require(weekday != null && weekday in 1..7 && first != null && first > 0 && last != null && last >= first) {
            "第 ${row.number} 行的星期或节次有误"
        }
    }
    return CourseSlot(name, weekday, first, last, value(4), value(5), value(6))
}

private fun chineseWeekday(day: String): Int? = weekdays.indexOf(
    day.trim().removePrefix("星期").removePrefix("周").replace("天", "日")
).takeIf { it >= 0 }?.plus(1)

private fun wholeNumber(value: String): Int? = value.toDoubleOrNull()?.let { number ->
    number.toInt().takeIf { it.toDouble() == number }
}
