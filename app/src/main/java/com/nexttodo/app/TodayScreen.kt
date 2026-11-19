package com.nexttodo.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import android.app.DatePickerDialog
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val monthFormat = DateTimeFormatter.ofPattern("yyyy年M月")
internal val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
internal val todayHeaderBackground: Color
    @Composable get() = if (MaterialTheme.colorScheme.background.luminance() < .5f) {
        MaterialTheme.colorScheme.surface
    } else MaterialTheme.colorScheme.primary
private val todayHeaderForeground: Color
    @Composable get() = if (MaterialTheme.colorScheme.background.luminance() < .5f) {
        MaterialTheme.colorScheme.onSurface
    } else MaterialTheme.colorScheme.onPrimary
private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
private const val VISIBLE_DAYS = 7

@Composable
internal fun TodayScreen(
    selectedDay: LocalDate,
    onDayChange: (LocalDate) -> Unit,
    model: EventViewModel,
    onOpen: (Event) -> Unit,
    onAdd: () -> Unit,
    onEnterFocus: () -> Unit,
) {
    val now = rememberEventNow(model.events)
    val today = now.toLocalDate()
    val ongoing = remember(model.events, now) { activeLongEvents(model.events, now) }
    var period by rememberSaveable { mutableStateOf(TodayPeriod.DAY) }
    val start = period.start(selectedDay)
    val end = period.end(selectedDay)
    val displayed = remember(model.events, start, end) { periodEvents(model.events, start, end) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        key(period) { DateSelector(selectedDay, today, model.events, onDayChange, period) { period = it } }
        if (period != TodayPeriod.DAY) Text("${period.label} · $start — $end", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        val enterFocus by rememberUpdatedState(onEnterFocus)
        Box(Modifier.weight(1f).fillMaxWidth()
            .semantics { customActions = listOf(CustomAccessibilityAction("打开 Today 全屏") { enterFocus(); true }) }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { enterFocus() }) }
            .pointerInput(Unit) {
                var distance = 0f
                var opened = false
                detectVerticalDragGestures(onDragStart = { distance = 0f; opened = false },
                    onVerticalDrag = { change, amount ->
                        distance += amount
                        if (!opened && distance < -56.dp.toPx()) { opened = true; enterFocus() }
                        change.consume()
                    })
            }) {
            when {
                model.loadingFailed -> Button(
                    onClick = model::reload,
                    enabled = !model.busy,
                    modifier = Modifier.align(Alignment.TopStart).padding(20.dp),
                ) { Text("重新读取事件") }
                model.busy && model.events.isEmpty() -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> key(selectedDay, period) {
                    EventList(
                        events = displayed,
                        enabled = !model.busy,
                        onOpen = onOpen,
                        onToggle = model::complete,
                        onMove = model::movePending,
                        onPin = model::pin, onDelete = model::delete,
                        allowReorder = period == TodayPeriod.DAY,
                        ongoingEvents = ongoing,
                        emptyLabel = when (period) {
                            TodayPeriod.DAY -> "这天暂无待办"
                            TodayPeriod.WEEK -> "本周暂无待办"
                            TodayPeriod.MONTH -> "本月暂无待办"
                        },
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
    events: List<Event>,
    onDayChange: (LocalDate) -> Unit,
    period: TodayPeriod,
    onPeriodChange: (TodayPeriod) -> Unit,
) {
    val blue = todayHeaderBackground
    val foreground = todayHeaderForeground
    val state = rememberPagerState(initialPage = period.pageFor(selectedDay)) { period.pageCount }
    val scope = rememberCoroutineScope()
    var navigation by remember { mutableStateOf<Job?>(null) }
    var navigationToken by remember { mutableStateOf<Any?>(null) }
    var selectionAnchor by remember { mutableStateOf(selectedDay) }
    val changeDay by rememberUpdatedState(onDayChange)
    fun selectDate(date: LocalDate) {
        navigation?.cancel()
        val token = Any()
        navigationToken = token
        navigation = scope.launch {
            try {
                state.animateScrollToPage(period.pageFor(date), animationSpec = tween(500, easing = FastOutSlowInEasing))
                selectionAnchor = date
                changeDay(date)
            } finally {
                if (navigationToken === token) navigationToken = null
            }
        }
    }
    val eventCounts = remember(events, period, state.currentPage) {
        (-5..5).map { state.currentPage + it }.filter { it in 0 until period.pageCount }.associateWith { page ->
            val date = period.dateFor(page)
            periodEvents(events, date, period.end(date)).size
        }
    }
    val context = LocalContext.current
    val dialogTheme = if (timetableDark) R.style.Theme_NextToDo_Dialog_Dark else R.style.Theme_NextToDo_Dialog_Light
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(selectedDay) {
        selectionAnchor = selectedDay
        if (navigationToken == null && !state.isScrollInProgress && state.currentPage != period.pageFor(selectedDay)) state.requestScrollToPage(period.pageFor(selectedDay))
    }
    val relativeDayLabel = period.relativeLabel(today, period.dateFor(state.currentPage))
    LaunchedEffect(state) {
        snapshotFlow { if (navigationToken == null && !state.isScrollInProgress) state.settledPage else null }.collect {
            if (it != null && it != period.pageFor(selectionAnchor)) {
                selectionAnchor = period.dateFor(it)
                changeDay(selectionAnchor)
            }
        }
    }
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) { navigation?.cancel(); navigation = null; navigationToken = null }
        }
    }
    Surface(color = blue, contentColor = foreground) {
        Column(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape,
                    modifier = Modifier.clickable { menu = true }.semantics { contentDescription = "切换待办范围" }) {
                    Image(
                        painter = painterResource(R.drawable.nexttodo_logo),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).padding(4.dp),
                    )
                }
                DropdownMenu(menu, { menu = false }) {
                    TodayPeriod.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label + if (period == option) " ✓" else "") },
                        onClick = { onPeriodChange(option); menu = false }) }
                }
                }
                Text(
                    period.dateFor(state.currentPage).format(monthFormat),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).clickable {
                        DatePickerDialog(context, dialogTheme, { _, y, m, d ->
                            val date = LocalDate.of(y, m + 1, d)
                            selectDate(date)
                        },
                            selectedDay.year, selectedDay.monthValue - 1, selectedDay.dayOfMonth).show()
                    },
                )
                TextButton(onClick = { selectDate(today) },
                    modifier = Modifier.semantics { contentDescription = "回到今天" }) {
                    Text(relativeDayLabel, color = foreground)
                }
            }
            val extraHeight = 24.dp * (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
            BoxWithConstraints(Modifier.fillMaxWidth().height(128.dp + extraHeight)) {
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
                    val date = period.dateFor(page)
                    val count = eventCounts[page] ?: 0
                    val active = page == state.currentPage
                    val weekday = weekdays[date.dayOfWeek.value - 1]
                    Column(
                        Modifier.fillMaxSize().padding(top = 58.dp).clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClick = { selectDate(date) })
                            .semantics {
                                selected = active
                                contentDescription = when (period) {
                                    TodayPeriod.DAY -> "${date.format(dayFormat)}，星期$weekday，$count 个事件"
                                    TodayPeriod.WEEK -> "${date}至${period.end(date)}，$count 个事件"
                                    TodayPeriod.MONTH -> "${date.year}年${date.monthValue}月，$count 个事件"
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                    ) {
                        Text(when (period) {
                            TodayPeriod.DAY -> "周$weekday"
                            TodayPeriod.WEEK -> "${date.monthValue}月"
                            TodayPeriod.MONTH -> "${date.year}年"
                        }, style = MaterialTheme.typography.labelSmall,
                            fontSize = (if (period == TodayPeriod.MONTH) 10 else 11).sp,
                            maxLines = 1, softWrap = false)
                        Surface(
                            color = if (active) foreground else Color.Transparent,
                            contentColor = if (active) blue else foreground,
                            shape = CircleShape,
                        ) {
                            Box(Modifier.width(if (period == TodayPeriod.DAY) 34.dp else cellWidth - 6.dp)
                                .heightIn(min = 36.dp).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    when (period) {
                                        TodayPeriod.DAY -> date.dayOfMonth.toString()
                                        TodayPeriod.WEEK -> "${date.dayOfMonth}–${period.end(date).dayOfMonth}"
                                        TodayPeriod.MONTH -> "${date.monthValue}月"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontSize = when (period) { TodayPeriod.WEEK -> 12.sp; TodayPeriod.MONTH -> 14.sp; TodayPeriod.DAY -> 16.sp },
                                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    fontWeight = if (period.pageFor(date) == period.pageFor(today) || active) FontWeight.Bold else FontWeight.Medium,
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
private fun EventCurve(state: PagerState, eventCounts: Map<Int, Int>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(55.dp)) {
        val centerPage = state.currentPage
        val pageOffset = state.currentPageOffsetFraction
        val cellWidth = size.width / VISIBLE_DAYS
        val direction = if (layoutDirection == LayoutDirection.Ltr) 1 else -1
        val points = (-4..4).map { relative ->
            val count = eventCounts[centerPage + relative] ?: 0
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
