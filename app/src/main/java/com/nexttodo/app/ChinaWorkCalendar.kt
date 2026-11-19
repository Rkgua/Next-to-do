package com.nexttodo.app

import java.time.LocalDate

internal object ChinaWorkCalendar {
    // Sources: State Council notices, reproduced by Beijing.gov.cn and Xinhua.
    // 2026: https://www.beijing.gov.cn/zhengce/zhengcefagui/202511/t20251104_4258873.html
    // 2027: https://www.news.cn/politics/20261105/f90cd6ad4df94ab4833b141e52ccfc13/c.html
    private val holidays = ranges(2026, "01-01/01-03 02-15/02-23 04-04/04-06 05-01/05-05 06-19/06-21 09-25/09-27 10-01/10-07") +
        ranges(2027, "01-01/01-03 02-05/02-13 04-03/04-05 05-01/05-05 06-09/06-09 09-15/09-15 10-01/10-07")
    private val adjusted = listOf("2026-01-04", "2026-02-14", "2026-02-28", "2026-05-09", "2026-09-20", "2026-10-10",
        "2027-01-31", "2027-02-14", "2027-05-08", "2027-09-26", "2027-10-09").map(LocalDate::parse).toSet()
    fun isWorkday(day: LocalDate): Boolean? = when {
        day.year !in 2026..2027 -> null
        day in adjusted -> true
        day in holidays -> false
        else -> day.dayOfWeek.value <= 5
    }
    private fun ranges(year: Int, source: String): Set<LocalDate> = source.split(' ').flatMap { range ->
        val (first, last) = range.split('/')
        val start = LocalDate.parse("$year-$first")
        val end = LocalDate.parse("$year-$last")
        generateSequence(start) { it.plusDays(1) }.takeWhile { it <= end }.toList()
    }.toSet()
}
