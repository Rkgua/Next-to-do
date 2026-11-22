package com.nexttodo.app

import android.app.DatePickerDialog
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

@Composable
internal fun FocusStatistics(history: FocusHistorySnapshot, onStart: () -> Unit) {
    val context = LocalContext.current
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var period by rememberSaveable { mutableIntStateOf(0) }
    var showRecords by rememberSaveable { mutableStateOf(false) }
    val selected = LocalDate.parse(date)
    val all = focusTotals(history.records)
    val today = dailyFocus(history.records, selected)
    val activeDays = focusActiveDays(history.records)
    val month = YearMonth.from(selected)
    val rangeStart = when (period) { 1 -> selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); 2 -> month.atDay(1); else -> selected }
    val values = when (period) {
        0 -> hourlyFocus(history.records, selected)
        1 -> (0..6).map { dailyFocus(history.records, rangeStart.plusDays(it.toLong())).focused }
        else -> (1..month.lengthOfMonth()).map { dailyFocus(history.records, month.atDay(it)).focused }
    }
    fun move(direction: Long) { date = when (period) { 1 -> selected.plusWeeks(direction); 2 -> selected.plusMonths(direction); else -> selected.plusDays(direction) }.toString() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (history.failed) Text("记录暂时无法读取，正在重试", color = MaterialTheme.colorScheme.error)
        StatsCard("累计专注") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusMetric("次数", "${all.sessions}", Modifier.weight(1f))
                FocusMetric("时长", focusDurationLabel(all.focused), Modifier.weight(1.5f))
                FocusMetric("活跃日均", focusDurationLabel(if (activeDays == 0) 0 else all.focused / activeDays), Modifier.weight(1.5f))
            }
            Text("完成 ${all.completed} 轮 · 活跃 $activeDays 天", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatsCard("当日专注") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(date, Modifier.weight(1f).clickable {
                    DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d).toString() }, selected.year, selected.monthValue - 1, selected.dayOfMonth).show()
                }, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { date = selected.minusDays(1).toString() }) { Text("‹") }
                TextButton(onClick = { date = selected.plusDays(1).toString() }, enabled = selected < LocalDate.now()) { Text("›") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FocusMetric("专注", focusDurationLabel(today.focused), Modifier.weight(1f))
                FocusMetric("休息", focusDurationLabel(today.rest), Modifier.weight(1f))
                FocusMetric("次数", "${today.sessions}", Modifier.weight(1f))
            }
            if (today.locked > 0) Text("其中屏幕固定 ${focusDurationLabel(today.locked)}", style = MaterialTheme.typography.bodySmall, color = FocusBlue)
        }
        StatsCard("专注时长分布") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("日", "周", "月").forEachIndexed { index, title -> FilterChip(period == index, { period = index }, label = { Text(title) }) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { move(-1) }) { Text("‹") }
                TextButton(onClick = { move(1) }, enabled = rangeStart < LocalDate.now()) { Text("›") }
            }
            Text(when (period) { 0 -> date; 1 -> "$rangeStart — ${rangeStart.plusDays(6)}"; else -> month.toString() },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (values.sum() == 0L) Column(Modifier.fillMaxWidth().height(170.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("这段时间还没有专注记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onStart) { Text("开始一段专注") }
            } else FocusBarChart(values, period)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { showRecords = true }) { Text("查看专注记录") }
                TextButton(onClick = {
                    val body = "Next to do · 专注统计\n累计专注 ${all.sessions} 次，${focusDurationLabel(all.focused)}\n$date 专注 ${focusDurationLabel(today.focused)}，休息 ${focusDurationLabel(today.rest)}"
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), "分享专注统计"))
                }) { Text("分享") }
            }
        }
        StatsCard("${month.year} 年 ${month.monthValue} 月") {
            Text("点选日期查看当日统计", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row { "一二三四五六日".forEach { Text(it.toString(), Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            val offset = month.atDay(1).dayOfWeek.value - 1
            val maximum = (1..month.lengthOfMonth()).maxOf { dailyFocus(history.records, month.atDay(it)).focused }.coerceAtLeast(1)
            repeat((offset + month.lengthOfMonth() + 6) / 7) { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(7) { weekday ->
                        val day = week * 7 + weekday - offset + 1
                        if (day !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).aspectRatio(1f))
                        else {
                            val value = dailyFocus(history.records, month.atDay(day)).focused
                            val tint = if (value == 0L) MaterialTheme.colorScheme.surfaceContainerHigh else FocusBlue.copy(alpha = .2f + .8f * value.toFloat() / maximum)
                            Box(Modifier.weight(1f).aspectRatio(1f).background(tint, RoundedCornerShape(7.dp))
                                .clickable { date = month.atDay(day).toString(); period = 0 }
                                .semantics { contentDescription = "${month.atDay(day)}，专注 ${focusDurationLabel(value)}" }, contentAlignment = Alignment.Center) {
                                Text(day.toString(), fontSize = 11.sp, color = if (value > maximum / 2) Color.White else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
    if (showRecords) FocusRecordsDialog(history.records) { showRecords = false }
}

@Composable
private fun StatsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun FocusMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 23.sp)
    }
}

@Composable
private fun FocusBarChart(values: List<Long>, period: Int) {
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f)
    val peak = values.max().coerceAtLeast(60_000)
    Column {
        Text("最高 ${focusDurationLabel(values.max())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(160.dp).padding(top = 14.dp)
            .semantics { contentDescription = "专注分布，总计 ${focusDurationLabel(values.sum())}" }) {
            repeat(4) { line -> val y = size.height * line / 3; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
            val step = size.width / values.size
            val width = step * .62f
            values.forEachIndexed { index, value ->
                if (value > 0) {
                    val height = (size.height * value.toFloat() / peak).coerceAtLeast(2.dp.toPx())
                    drawRoundRect(FocusBlue, Offset(index * step + (step - width) / 2, size.height - height),
                        Size(width, height), CornerRadius(minOf(4.dp.toPx(), width / 2)))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val ticks = if (period == 0) listOf("0时", "6时", "12时", "18时", "23时")
                else if (period == 1) listOf("周一", "周三", "周五", "周日") else listOf("1日", "${values.size / 2}日", "${values.size}日")
            ticks.forEach { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun FocusRecordsDialog(records: List<FocusSegment>, onDismiss: () -> Unit) {
    val groups = records.distinctBy { it.id }.groupBy { it.session }.values.sortedByDescending { group -> group.maxOf { it.end } }
    val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
    AlertDialog(onDismissRequest = onDismiss, title = { Text("专注记录") }, text = {
        if (groups.isEmpty()) Text("完成或暂停一次计时后，实际投入的时间会保存在这里。")
        else LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(groups, key = { it.first().session }) { group ->
                val total = focusTotals(group)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (group.all { it.kind == FocusKind.REST }) "休息" else "番茄专注", fontWeight = FontWeight.Medium)
                    Text(formatter.format(Instant.ofEpochMilli(group.minOf { it.start })), style = MaterialTheme.typography.bodySmall)
                    Text("${focusDurationLabel(total.total)} · ${if (group.any { it.completed }) "已完成" else "已记录"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
