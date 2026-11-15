package com.nexttodo.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val monthFormat = DateTimeFormatter.ofPattern("yyyy年M月")
internal val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
private val calendarStart = LocalDate.of(1900, 1, 1)
private val calendarSize = ChronoUnit.DAYS.between(calendarStart, LocalDate.of(2201, 1, 1)).toInt()
private const val VISIBLE_DAYS = 7

private fun pageFor(day: LocalDate) = ChronoUnit.DAYS.between(calendarStart, day)
    .coerceIn(0L, calendarSize.toLong() - 1).toInt()

private fun dayFor(page: Int) = calendarStart.plusDays(page.toLong())

@Composable
internal fun TodayScreen(
    selectedDay: LocalDate,
    onDayChange: (LocalDate) -> Unit,
    model: EventViewModel,
    onOpen: (Event) -> Unit,
    onAdd: () -> Unit,
) {
    val today = LocalDate.now()
    val eventsByDay = model.events.groupBy { it.calendarDay ?: today }
    Column(Modifier.fillMaxSize()) {
        DateSelector(selectedDay, today, eventsByDay.mapValues { it.value.size }, onDayChange)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                model.loadingFailed -> Button(
                    onClick = model::reload,
                    enabled = !model.busy,
                    modifier = Modifier.align(Alignment.TopStart).padding(20.dp),
                ) { Text("重新读取事件") }
                model.busy && model.events.isEmpty() -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> key(selectedDay) {
                    EventList(
                        events = eventsByDay[selectedDay].orEmpty(),
                        enabled = !model.busy,
                        onOpen = onOpen,
                        onToggle = model::complete,
                        onMove = model::movePending,
                    )
                }
            }
            if (!model.loadingFailed && !model.busy) {
                FloatingActionButton(
                    onClick = onAdd,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
                        .semantics { contentDescription = "添加事件" },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text("+", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
    }
}

@Composable
private fun DateSelector(
    selectedDay: LocalDate,
    today: LocalDate,
    eventCounts: Map<LocalDate, Int>,
    onDayChange: (LocalDate) -> Unit,
) {
    val blue = MaterialTheme.colorScheme.primary
    val foreground = MaterialTheme.colorScheme.onPrimary
    val state = rememberPagerState(initialPage = pageFor(selectedDay)) { calendarSize }
    val changeDay by rememberUpdatedState(onDayChange)
    LaunchedEffect(state) {
        snapshotFlow { state.settledPage }.collect { changeDay(dayFor(it)) }
    }
    Surface(color = blue, contentColor = foreground) {
        Column(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    dayFor(state.currentPage).format(monthFormat),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { state.requestScrollToPage(pageFor(today)) }) {
                    Text("今天", color = foreground)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(116.dp)) {
                val cellWidth = maxWidth / VISIBLE_DAYS
                EventCurve(state, eventCounts, foreground)
                HorizontalPager(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    pageSize = PageSize.Fixed(cellWidth),
                    contentPadding = PaddingValues(horizontal = (maxWidth - cellWidth) / 2),
                    flingBehavior = PagerDefaults.flingBehavior(
                        state = state,
                        pagerSnapDistance = PagerSnapDistance.atMost(VISIBLE_DAYS),
                    ),
                    key = { it },
                ) { page ->
                    val date = dayFor(page)
                    val count = eventCounts[date] ?: 0
                    val active = page == state.currentPage
                    val weekday = weekdays[date.dayOfWeek.value - 1]
                    Column(
                        Modifier.fillMaxSize().padding(top = 58.dp)
                            .semantics {
                                selected = active
                                contentDescription = "${date.format(dayFormat)}，星期$weekday，$count 个事件"
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                    ) {
                        Text("周$weekday", style = MaterialTheme.typography.labelSmall)
                        Surface(
                            color = if (active) foreground else Color.Transparent,
                            contentColor = if (active) blue else foreground,
                            shape = CircleShape,
                        ) {
                            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    date.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (date == today || active) FontWeight.Bold else FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventCurve(state: PagerState, eventCounts: Map<LocalDate, Int>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(55.dp)) {
        val centerPage = state.currentPage
        val pageOffset = state.currentPageOffsetFraction
        val cellWidth = size.width / VISIBLE_DAYS
        val direction = if (layoutDirection == LayoutDirection.Ltr) 1 else -1
        val points = (-4..4).map { relative ->
            val count = eventCounts[dayFor(centerPage + relative)] ?: 0
            val rise = count / (count + 3f)
            Offset(
                size.width / 2f + (relative - pageOffset) * cellWidth * direction,
                size.height - 10.dp.toPx() - (size.height - 19.dp.toPx()) * rise,
            )
        }
        val curve = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.zipWithNext().forEach { (start, end) ->
                val middle = (start.x + end.x) / 2f
                cubicTo(middle, start.y, middle, end.y, end.x, end.y)
            }
        }
        drawPath(curve, color.copy(alpha = 0.78f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        points.forEachIndexed { index, point ->
            drawCircle(color, if (index == 4) 3.25.dp.toPx() else 2.dp.toPx(), point)
        }
    }
}
