package com.nexttodo.app

import android.util.Xml
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
        val rows = readRows(sheet, shared)
        val header = rows.indexOfFirst {
            val labels = it.cells.values.map(String::trim)
            columnNames.take(4).all { label -> label in labels }
        }
        if (header >= 0) {
            val columns = rows[header].cells.entries.associate { it.value.trim() to it.key }
            val courses = rows.drop(header + 1).mapNotNull { row -> readCourse(row, columns) }
            return courses.distinct().also { require(it.isNotEmpty()) { "表格没有课程数据" } }
        }
    }
    throw IllegalArgumentException("表头需要包含课程名称、星期、开始节数和结束节数")
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
    val weekday = wholeNumber(day) ?: weekdays.indexOf(
        day.removePrefix("星期").removePrefix("周").replace("天", "日")
    ).takeIf { it >= 0 }?.plus(1)
    val first = wholeNumber(start)
    val last = wholeNumber(end)
    if (day.isNotEmpty() || start.isNotEmpty() || end.isNotEmpty()) {
        require(weekday != null && weekday in 1..7 && first != null && first > 0 && last != null && last >= first) {
            "第 ${row.number} 行的星期或节次有误"
        }
    }
    return CourseSlot(name, weekday, first, last, value(4), value(5), value(6))
}

private fun wholeNumber(value: String): Int? = value.toDoubleOrNull()?.let { number ->
    number.toInt().takeIf { it.toDouble() == number }
}
