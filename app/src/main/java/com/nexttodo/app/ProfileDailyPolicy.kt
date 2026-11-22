package com.nexttodo.app

import java.net.URI
import java.time.LocalDate
import java.time.ZoneId

internal const val DEFAULT_DAILY_API = "https://api.rkgua.cn/api/daily"
internal const val DEFAULT_BING_API = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN"
internal const val DEFAULT_QUOTE_API = "https://v1.hitokoto.cn/?c=d&c=e&c=i&c=k&min_length=8&max_length=40&encode=json"
internal val dailyZone: ZoneId = ZoneId.of("Asia/Shanghai")
internal fun dailyDate(): String = LocalDate.now(dailyZone).toString()

internal data class DailySources(
    val preferOwnApi: Boolean = true,
    val dailyApi: String = DEFAULT_DAILY_API,
    val imageApi: String = DEFAULT_BING_API,
    val quoteApi: String = DEFAULT_QUOTE_API,
)

internal fun isDailyHttpsUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
}.getOrDefault(false)

internal data class DailyImage(
    val date: String,
    val title: String,
    val url: String,
    val copyright: String,
    val copyrightUrl: String?,
)

internal data class DailyQuote(
    val date: String,
    val text: String,
    val author: String?,
    val work: String?,
    val source: String,
    val sourceUrl: String?,
) {
    val attribution: String get() = listOfNotNull(author, work?.let { "《$it》" }).joinToString(" · ")
}

internal data class DailyContent(
    val image: DailyImage? = null,
    val quote: DailyQuote? = null,
    val fetchedAt: Long = 0,
    val imageStale: Boolean = false,
    val quoteStale: Boolean = false,
) {
    fun needsRefresh(now: Long, day: String): Boolean =
        image == null || quote == null || imageStale || quoteStale || quote.date != day ||
            now < fetchedAt || now - fetchedAt >= 3_600_000
}

internal fun mergeDailyContent(previous: DailyContent, incoming: DailyContent): DailyContent = incoming.copy(
    image = incoming.image ?: previous.image,
    quote = incoming.quote ?: previous.quote,
    imageStale = incoming.image == null || incoming.imageStale,
    quoteStale = incoming.quote == null || incoming.quoteStale,
)

/** Upward velocity expands; a slow release settles at the nearest visible height. */
internal fun settleDailyScene(height: Float, anchors: List<Float>, velocity: Float): Int {
    require(anchors.size == 3 && anchors.zipWithNext().all { it.first <= it.second })
    return when {
        velocity < -650f -> anchors.indexOfFirst { it > height + 24f }.takeIf { it >= 0 } ?: 2
        velocity > 650f -> anchors.indexOfLast { it < height - 24f }.coerceAtLeast(0)
        else -> anchors.indices.minBy { kotlin.math.abs(anchors[it] - height) }
    }
}
