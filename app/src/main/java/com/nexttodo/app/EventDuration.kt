package com.nexttodo.app

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

internal val Event.isDuration: Boolean get() = endTime.isNotBlank() || endDay != null
internal fun Event.durationStart(): LocalDateTime =
    time.takeIf(String::isNotBlank)?.let(LocalDateTime::parse) ?: startDay().atStartOfDay()

// Legacy date-only events include the entire last day.
internal fun Event.durationEnd(): LocalDateTime =
    endTime.takeIf(String::isNotBlank)?.let(LocalDateTime::parse) ?: finishDay().plusDays(1).atStartOfDay()

internal fun Event.withDuration(enabled: Boolean, now: LocalDateTime): Event {
    if (!enabled) return copy(endTime = "", endDay = null, repetition = EventRepeat.NONE,
        repeatDays = emptySet(), completedDates = emptySet())
    val start = time.takeIf(String::isNotBlank)?.let(LocalDateTime::parse)
        ?: now.with(startDay(now.toLocalDate())).truncatedTo(ChronoUnit.MINUTES)
    val end = start.plusHours(1)
    return copy(time = start.toString(), day = start.toLocalDate(), endTime = end.toString(), endDay = end.toLocalDate(),
        repetition = EventRepeat.NONE, repeatDays = emptySet(), completedDates = emptySet())
}

internal fun activeLongEvents(events: List<Event>, now: LocalDateTime,
    rangeStart: LocalDate = now.toLocalDate(), rangeEnd: LocalDate = rangeStart): List<Event> = events.mapNotNull { event ->
    if (!event.isDuration) return@mapNotNull null
    val displayed = event.onDate(now.toLocalDate()) ?: return@mapNotNull null
    val shift = ChronoUnit.DAYS.between(event.startDay(), displayed.occurrenceDate)
    val start = event.durationStart().plusDays(shift)
    val end = event.durationEnd().plusDays(shift)
    // An active event belongs only to viewed days that overlap this occurrence.
    // The end is exclusive, so a midnight finish does not occupy the next day.
    displayed.takeIf { now >= start && now < end &&
        rangeStart.atStartOfDay() < end && rangeEnd.plusDays(1).atStartOfDay() > start }
}

@Composable
internal fun rememberEventNow(events: List<Event>): LocalDateTime {
    val lifecycle = (LocalContext.current as? LifecycleOwner)?.lifecycle
    var active by remember { mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { owner, _ -> active = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(active, events) {
        if (active) while (true) {
            now = LocalDateTime.now()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    return now
}
