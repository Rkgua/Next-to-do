package com.nexttodo.app

import org.junit.Assert.*
import org.junit.Test

class ProfileDailyPolicyTest {
    private val image = DailyImage("2026-11-20", "风景", "https://www.bing.com/image.jpg", "摄影者", null)
    private val quote = DailyQuote("2026-11-21", "在线获取的测试句子", null, null, "一言", null)
    private val fresh = DailyContent(image, quote, fetchedAt = 10_000)

    @Test fun rejectsCleartextCredentialsAndNonWebLinks() {
        listOf("http://example.com/api", "https://user:password@example.com/api", "file:///tmp/image", "javascript:alert(1)", "https:///missing-host")
            .forEach { assertFalse(it, isDailyHttpsUrl(it)) }
        assertTrue(isDailyHttpsUrl("https://example.com/api?c=d&c=i"))
    }

    @Test fun freshCacheDoesNotTreatBingActualDateAsARefreshFailure() {
        assertFalse(fresh.needsRefresh(20_000, "2026-11-21"))
    }

    @Test fun refreshesAtMidnightAndAfterAnHourOrClockRollback() {
        assertTrue(fresh.needsRefresh(20_000, "2026-11-22"))
        assertTrue(fresh.needsRefresh(3_610_000, "2026-11-21"))
        assertTrue(fresh.needsRefresh(9_999, "2026-11-21"))
    }

    @Test fun incompleteOrStaleResultsAreRetried() {
        assertTrue(fresh.copy(image = null).needsRefresh(20_000, "2026-11-21"))
        assertTrue(fresh.copy(quote = null).needsRefresh(20_000, "2026-11-21"))
        assertTrue(fresh.copy(imageStale = true).needsRefresh(20_000, "2026-11-21"))
        assertTrue(fresh.copy(quoteStale = true).needsRefresh(20_000, "2026-11-21"))
    }

    @Test fun partialResponseKeepsPreviousImageWithoutRelabellingItsDate() {
        val nextQuote = quote.copy(date = "2026-11-22")
        val merged = mergeDailyContent(fresh, DailyContent(quote = nextQuote, fetchedAt = 30_000))
        assertEquals(image, merged.image)
        assertEquals("2026-11-20", merged.image?.date)
        assertEquals(nextQuote, merged.quote)
        assertTrue(merged.imageStale)
        assertFalse(merged.quoteStale)
    }

    @Test fun unavailableQuoteKeepsPreviousQuoteAndItsProvenance() {
        val merged = mergeDailyContent(fresh, DailyContent(image = image, fetchedAt = 30_000))
        assertEquals(quote, merged.quote)
        assertTrue(merged.quoteStale)
        assertFalse(merged.imageStale)
    }

    @Test fun noCacheAndNoOnlineContentProducesNoInventedDailyQuote() {
        val merged = mergeDailyContent(DailyContent(), DailyContent())
        assertNull(merged.image)
        assertNull(merged.quote)
    }

    @Test fun sceneSettlesAtClosestAnchorAndRespondsToSwipeDirection() {
        val anchors = listOf(100f, 450f, 800f)
        assertEquals(0, settleDailyScene(220f, anchors, 0f))
        assertEquals(1, settleDailyScene(390f, anchors, 0f))
        assertEquals(2, settleDailyScene(700f, anchors, 0f))
        assertEquals(1, settleDailyScene(150f, anchors, -1000f))
        assertEquals(2, settleDailyScene(500f, anchors, -1000f))
        assertEquals(1, settleDailyScene(720f, anchors, 1000f))
        assertEquals(0, settleDailyScene(300f, anchors, 1000f))
    }

    @Test fun attributionNeverInventsAnAuthor() {
        assertEquals("", quote.attribution)
        assertEquals("《诗集》", quote.copy(work = "诗集").attribution)
        assertEquals("作者 · 《诗集》", quote.copy(author = "作者", work = "诗集").attribution)
    }
}
