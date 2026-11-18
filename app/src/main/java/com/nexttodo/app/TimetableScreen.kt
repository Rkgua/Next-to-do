package com.nexttodo.app

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal val timetableDark: Boolean
    @Composable get() = MaterialTheme.colorScheme.background.luminance() < .5f
internal val timetableTop: Color
    @Composable get() = if (timetableDark) MaterialTheme.colorScheme.background else Color(0xFFE4E5F0)
internal val timetableBottom: Color
    @Composable get() = if (timetableDark) MaterialTheme.colorScheme.background else Color(0xFFC3D1E5)
internal val timetableInk: Color
    @Composable get() = if (timetableDark) Color(0xFFF1F1F4) else Color(0xFF17171C)
internal val timetableAccent: Color
    @Composable get() = if (timetableDark) MaterialTheme.colorScheme.primary else Color(0xFF4A5D87)
internal val timetableMuted: Color
    @Composable get() = if (timetableDark) Color(0xFF96989F) else Color(0xFF56606B)
internal val timetableSurface: Color
    @Composable get() = if (timetableDark) MaterialTheme.colorScheme.surface else Color.White
internal val timetableOutline: Color
    @Composable get() = if (timetableDark) Color(0xFF50525B) else Color(0xFFF0F0F0)
internal val timetableFont = FontFamily.Serif
internal val courseColors = listOf(
    Color(0xFF72B8A5), Color(0xFF73B8D4), Color(0xFF83ADEF),
    Color(0xFF8E9BD5), Color(0xFF70BFC8), Color(0xFF789DBF), Color(0xFFA394CE),
)
internal val timetableDays = listOf("一", "二", "三", "四", "五", "六", "日")
private val periodTimes = listOf(
    "08:00" to "08:45", "08:55" to "09:40", "10:00" to "10:45", "10:55" to "11:40",
    "14:00" to "14:45", "14:55" to "15:40", "16:00" to "16:45", "16:55" to "17:40",
    "19:00" to "19:45", "19:55" to "20:40",
)
private val periodHeight = 64.dp
private val timeWidth = 34.dp
private const val LAST_GRID_PERIOD = 99
private val dateLabel = DateTimeFormatter.ofPattern("yyyy/M/d")

internal fun CourseSlot.displayColor(): Color = color?.let(::Color)
    ?: courseColors[Math.floorMod(name.hashCode(), courseColors.size)]

private fun CourseSlot.inGrid() = weekday != null && firstPeriod != null &&
    lastPeriod != null && firstPeriod in 1..lastPeriod && lastPeriod <= LAST_GRID_PERIOD

@Composable
internal fun TimetableScreen(model: TimetableViewModel) {
    val today = LocalDate.now()
    val table = model.selected
    val currentWeek = table.currentWeek(today)
    val count = table.weekCount(today)
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val excel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { uri -> model.chooseFile(uri, TimetableFormat.EXCEL) }
    }
    val html = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { uri -> model.chooseFile(uri, TimetableFormat.HTML) }
    }
    var importing by remember { mutableStateOf(false) }
    var controls by rememberSaveable { mutableStateOf(false) }
    var appearance by rememberSaveable(table.id) { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var editIndex by rememberSaveable(table.id) { mutableStateOf<Int?>(null) }
    var overlappingIndices by rememberSaveable(table.id) { mutableStateOf<IntArray?>(null) }

    LaunchedEffect(model.message, editIndex, appearance) {
        if (model.message.isNotEmpty() && editIndex == null && !appearance) {
            snackbar.showSnackbar(model.message)
            model.clearMessage()
        }
    }

    val pager = key(table.id, table.firstDay, model.ready) {
        rememberPagerState(initialPage = currentWeek.coerceIn(1, count) - 1) { count }
    }
    val gridScroll = key(table.id) { rememberScrollState() }
    val week = pager.currentPage + 1
    val monday = table.dateOfWeek(week)
    val periodCount = maxOf(12, table.courses.filter { it.inGrid() }.maxOfOrNull { it.lastPeriod!! } ?: 0)
    val enabled = model.ready && !model.busy && model.preview == null
    val textColors = table.backgroundTextColors[backgroundKey(table.skin, table.backgroundImage)]
        ?: defaultBackgroundText(table.skin, timetableDark)
    Box(
        Modifier.fillMaxSize().background(timetableTop),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    val day = timetableDays[today.dayOfWeek.value - 1]
                    Text(
                        if (week == currentWeek) "第${week}周 周$day" else "第${week}周(非本周)",
                        color = timetableInk, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        fontFamily = timetableFont, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(today.format(dateLabel), color = timetableInk, fontFamily = timetableFont, fontSize = 12.sp)
                }
                TimetableAction(TableGlyph.ADD, "添加课程", enabled) {
                    model.clearMessage()
                    editIndex = -1
                }
                Box {
                    TimetableAction(TableGlyph.IMPORT, "导入课表", enabled) { importing = true }
                    DropdownMenu(
                        expanded = importing,
                        onDismissRequest = { importing = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = timetableSurface,
                        modifier = Modifier.width(184.dp),
                    ) {
                        DropdownMenuItem(
                            text = { TableText("Excel导入", 15) },
                            leadingIcon = { TableIcon(TableGlyph.EXCEL) },
                            onClick = {
                                importing = false
                                excel.launch(TimetableFormat.EXCEL.mimeTypes.toList().toTypedArray())
                            },
                        )
                        HorizontalDivider(color = timetableOutline)
                        DropdownMenuItem(
                            text = { TableText("HTML导入", 15) },
                            leadingIcon = { TableIcon(TableGlyph.HTML) },
                            onClick = {
                                importing = false
                                html.launch(TimetableFormat.HTML.mimeTypes.toList().toTypedArray())
                            },
                        )
                    }
                }
                TimetableAction(TableGlyph.SHARE, "分享课表", enabled) {
                    val text = table.courses.joinToString("\n", prefix = "${table.name}\n") { course ->
                        "${course.name}　${courseSummary(course)}"
                    }
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(share, "分享课表"))
                }
                TimetableAction(TableGlyph.MORE, "课表与周数", enabled) { controls = true }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { viewport = it }) {
                TimetableWallpaper(table.skin, table.backgroundImage, Modifier.matchParentSize())
                Column(Modifier.fillMaxSize()) {
                    WeekDates(monday, today, Color(textColors.header))
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(gridScroll)) {
                        HorizontalPager(
                            state = pager,
                            modifier = Modifier.fillMaxWidth(),
                            beyondViewportPageCount = 0,
                        ) { page ->
                            WeekGrid(table.courses, page + 1, periodCount, enabled, Color(textColors.time)) {
                                model.clearMessage()
                                if (it.size == 1) editIndex = table.courses.indexOf(it.single())
                                else overlappingIndices = it.map(table.courses::indexOf).toIntArray()
                            }
                        }
                    }
                }
            }
        }
        if (controls) {
            TimetableControls(
                model = model, today = today, week = week, count = count,
                onWeek = { requested -> pager.requestScrollToPage(requested - 1) },
                onDismiss = { controls = false },
                onAppearance = { controls = false; appearance = true; model.clearMessage() },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (appearance) {
        TimetableAppearance(model,
            if (viewport.height > 0) viewport.width.toFloat() / viewport.height else .5f,
            onDismiss = { appearance = false; model.clearMessage() })
    }

    overlappingIndices?.let { indices ->
        AlertDialog(
            onDismissRequest = { overlappingIndices = null },
            title = { Text("重叠的课程") },
            text = {
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(indices.toList()) { index ->
                        table.courses.getOrNull(index)?.let { course ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    overlappingIndices = null
                                    editIndex = index
                                }.padding(vertical = 12.dp),
                            ) {
                                Text(course.name, fontWeight = FontWeight.SemiBold)
                                Text(courseSummary(course), fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { overlappingIndices = null }) { Text("关闭") } },
        )
    }
    if (model.ready) {
        editIndex?.let { index ->
            val previous = table.courses.getOrNull(index)
            CourseEditor(
                course = previous,
                busy = model.busy,
                message = model.message,
                onDismiss = { editIndex = null; model.clearMessage() },
                onSave = { changes -> model.saveCourses(previous, changes) { editIndex = null } },
            )
        }
    }
    model.preview?.let { courses ->
        AlertDialog(
            onDismissRequest = model::closePreview,
            title = { Text("导入到 ${table.name}") },
            text = {
                LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Text("共 ${courses.size} 条课程安排") }
                    items(courses) {
                        Column {
                            Text(it.name, fontWeight = FontWeight.SemiBold)
                            Text(courseSummary(it), fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = model::confirmImport, enabled = !model.busy) { Text("确认导入") } },
            dismissButton = { TextButton(onClick = model::closePreview, enabled = !model.busy) { Text("取消") } },
        )
    }
}

@Composable
private fun WeekDates(monday: LocalDate, today: LocalDate, headerColor: Color) {
    Row(Modifier.fillMaxWidth().height(43.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(timeWidth), horizontalAlignment = Alignment.CenterHorizontally) {
            TableText("${monday.monthValue}", 10, headerColor, bold = true)
            TableText("月", 10, headerColor, bold = true)
        }
        timetableDays.forEachIndexed { index, label ->
            val date = monday.plusDays(index.toLong())
            val selected = date == today
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val color = if (selected) headerColor else headerColor.copy(alpha = .85f)
                TableText(label, 10, color)
                Box(
                    Modifier.size(20.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (selected) {
                            if (timetableDark) Color(0xFFD0D1D7) else Color(0xFF2A2D32)
                        } else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    TableText("${date.dayOfMonth}", 10,
                        if (selected) { if (timetableDark) Color(0xFF1D1E22) else Color.White } else color)
                }
            }
        }
    }
}

@Composable
private fun WeekGrid(
    courses: List<CourseSlot>,
    week: Int,
    periods: Int,
    enabled: Boolean,
    timeColor: Color,
    onCourses: (List<CourseSlot>) -> Unit,
) {
    val (scheduled, unscheduled) = remember(courses) { courses.partition { it.inGrid() } }
    val days = remember(scheduled) { scheduled.groupBy { it.weekday } }
    Column {
        Row(Modifier.fillMaxWidth().height(periodHeight * periods)) {
            Column(Modifier.width(timeWidth)) {
                repeat(periods) { index ->
                    Column(
                        Modifier.fillMaxWidth().height(periodHeight).padding(top = 11.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        TableText("${index + 1}", 12, timeColor, bold = true)
                        periodTimes.getOrNull(index)?.let { (start, end) ->
                            TableText(start, 8, timeColor, Modifier.padding(top = 3.dp))
                            TableText(end, 8, timeColor)
                        }
                    }
                }
            }
            (1..7).forEach { day ->
                val groups = remember(days, day, week) { groupOverlappingCourses(days[day].orEmpty(), week) }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    groups.forEach { group ->
                        val course = group.courses.first()
                        CourseBlock(
                            course = course, active = course.takesPlaceIn(week), enabled = enabled,
                            courseCount = group.courses.size,
                            modifier = Modifier.offset(y = periodHeight * (group.first - 1)).fillMaxWidth()
                                .height(periodHeight * (group.last - group.first + 1)),
                            onClick = { onCourses(group.courses) },
                        )
                    }
                }
            }
        }
        if (unscheduled.isNotEmpty()) {
            TableText("未指定节次", 14, modifier = Modifier.padding(horizontal = 12.dp), bold = true)
            unscheduled.forEach { course ->
                TableText(
                    course.name, 14,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onCourses(listOf(course)) }.padding(12.dp),
                )
            }
        }
    }
}

private data class CourseGroup(val first: Int, val last: Int, val courses: List<CourseSlot>)

private fun groupOverlappingCourses(courses: List<CourseSlot>, week: Int): List<CourseGroup> {
    val (active, inactive) = courses.partition { it.takesPlaceIn(week) }
    val visible = active + inactive.filter { other ->
        other.hasFutureOccurrence(week) &&
            active.none { it.firstPeriod!! <= other.lastPeriod!! && other.firstPeriod!! <= it.lastPeriod!! }
    }
    return buildList {
        val group = mutableListOf<CourseSlot>()
        var end = 0
        fun finishGroup() {
            if (group.isNotEmpty()) add(CourseGroup(group.first().firstPeriod!!, end, group.toList()))
            group.clear()
            end = 0
        }
        for (course in visible.sortedWith(compareBy<CourseSlot> { it.firstPeriod }.thenByDescending { it.lastPeriod })) {
            if (course.firstPeriod!! > end) finishGroup()
            group.add(course)
            end = maxOf(end, course.lastPeriod!!)
        }
        finishGroup()
    }
}

@Composable
private fun CourseBlock(
    course: CourseSlot, active: Boolean, enabled: Boolean, courseCount: Int, modifier: Modifier, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(5.dp)
    val base = course.displayColor()
    val nightFill = lerp(base, Color(0xFF0C1428), .55f)
    val fill = (if (timetableDark) nightFill else base).copy(alpha = if (active) 1f else .40f)
    Box(
        modifier.padding(1.25.dp).clip(shape).background(fill)
            .border(1.dp, Color.White.copy(alpha = if (active) .7f else .38f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        val color = Color.White.copy(alpha = if (active) .99f else .67f)
        val footer = when {
            active && courseCount > 1 -> "重叠 $courseCount 门"
            !active && courseCount > 1 -> "非本周 · $courseCount 门"
            !active -> "非本周"
            else -> null
        }
        Column(Modifier.padding(bottom = if (footer == null) 0.dp else 12.dp)) {
            Text(
                course.name, fontFamily = timetableFont, fontWeight = FontWeight.Bold,
                fontSize = 11.sp, lineHeight = 13.5.sp, maxLines = 6, overflow = TextOverflow.Ellipsis, color = color,
            )
            val details = listOfNotNull(course.room.takeIf(String::isNotBlank)?.let { "@$it" },
                course.customTime.ifBlank { null }).joinToString("\n")
            if (details.isNotEmpty()) {
                Text(
                    details, modifier = Modifier.padding(top = 4.dp), fontSize = 10.5.sp, lineHeight = 13.sp,
                    fontFamily = timetableFont, color = color, maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (footer != null) TableText(footer, 8, color, Modifier.align(Alignment.BottomCenter), maxLines = 1)
    }
}

internal fun courseSummary(course: CourseSlot): String = listOfNotNull(
    course.weekday?.let { "周${timetableDays[it - 1]}　第${course.firstPeriod}–${course.lastPeriod}节" },
    course.room.ifBlank { null }, course.teacher.ifBlank { null }, course.weeks.ifBlank { null },
).joinToString(" · ")

@Composable
internal fun TableText(
    text: String, size: Int, color: Color = timetableInk, modifier: Modifier = Modifier,
    bold: Boolean = false, maxLines: Int = Int.MAX_VALUE,
) = Text(
    text, modifier, color = color, fontSize = size.sp, lineHeight = (size + 3).sp,
    fontFamily = timetableFont, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    maxLines = maxLines, overflow = TextOverflow.Ellipsis,
)

internal enum class TableGlyph(val path: String) {
    ADD("M12 3v18M3 12h18"),
    IMPORT("M10 4H5a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h13a2 2 0 0 0 2-2V9M21 4c-5 0-9 3-9 11m-4-4 4 4 4-4"),
    SHARE("M13 4 22 12 13 19v-5c-5 0-8 2-11 5 0-7 4-11 11-11z"),
    MORE("M4 12h.1M12 12h.1M20 12h.1"),
    EXCEL("M4 4h16v16H4zM4 9h16M4 15h16M9 9v11M15 9v11"),
    HTML("M7 6 1 12l6 6m10-12 6 6-6 6m-3-15-4 18"),
    CLOSE("M5 5l14 14M19 5 5 19"),
    CHECK("m3 12 6 6L21 6"),
    COLOR("M12 3a9 9 0 1 0 1 18c3 0 0-5 4-5h2c4 0 2-13-7-13zM7 12h.1M10 7h.1M15 7h.1M17 11h.1"),
    STAR("M12 9l1 2 3 .5-2 2 .3 2.5-2.3-1.2L9.7 16l.3-2.5-2-2 3-.5zM12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20z"),
    CALENDAR("M3 5h18v16H3zM3 10h18M8 3v4M16 3v4"),
    CLOCK("M12 6v6l4 3M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20z"),
    CUSTOM("M12 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-6m-8 0 8-8"),
    ROOM("M12 22s8-7 8-12a8 8 0 0 0-16 0c0 5 8 12 8 12zM12 7a3 3 0 1 0 0 6 3 3 0 1 0 0-6z"),
    PERSON("M16 21l5-9M3 21v-3a3 3 0 0 1 3-3h8M10 3a4 4 0 1 0 0 8 4 4 0 1 0 0-8z"),
    NOTES("M4 3h16v18H4zM8 8h8m-8 4h6"),
    NEXT("m9 5 7 7-7 7"),
}

@Composable
internal fun TableIcon(glyph: TableGlyph, color: Color = timetableInk, size: Dp = 20.dp) {
    val path = remember(glyph) { PathParser().parsePathString(glyph.path).toPath() }
    Canvas(Modifier.size(size)) {
        scale(this.size.width / 24f, this.size.height / 24f, androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(
                path, color, style = Stroke(
                    width = if (glyph == TableGlyph.MORE) 2.8f else 1.85f,
                    cap = StrokeCap.Round, join = StrokeJoin.Round,
                ),
            )
        }
    }
}

@Composable
internal fun TimetableAction(glyph: TableGlyph, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.size(39.dp).semantics { contentDescription = label },
    ) { TableIcon(glyph, timetableInk.copy(alpha = if (enabled) 1f else .4f)) }
}
