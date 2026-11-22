package com.nexttodo.app

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class ProfileDailyViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ProfileDailyRepository(application)
    var sources by mutableStateOf(repository.readSources())
        private set
    var content by mutableStateOf(DailyContent())
        private set
    var bitmap by mutableStateOf<Bitmap?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var notice by mutableStateOf("")
        private set
    private var cacheLoaded = false
    private var lastAttempt = 0L

    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (loading || (!force && now - lastAttempt < 60_000 && lastAttempt > 0)) return
        if (!force && cacheLoaded && !content.needsRefresh(now, dailyDate())) return
        loading = true
        lastAttempt = now
        viewModelScope.launch {
            try {
                if (!cacheLoaded) {
                    val cached = withContext(Dispatchers.IO) { repository.readCache(sources) }
                    content = cached
                    bitmap = withContext(Dispatchers.IO) { repository.cachedBitmap(cached.image) }
                    cacheLoaded = true
                }
                val result = withContext(Dispatchers.IO) { repository.refresh(sources, content) }
                content = result.content
                bitmap = result.bitmap
                notice = result.notice
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = if (content.quote != null || bitmap != null) "暂时无法更新，已保留上次内容" else "每日内容暂不可用，稍后重试"
            } finally { loading = false }
        }
    }

    fun updateSources(value: DailySources) {
        if (loading || sources == value) return
        repository.saveSources(value)
        sources = value
        content = DailyContent()
        bitmap = null
        notice = ""
        cacheLoaded = false
        refresh(force = true)
    }
}

private data class DailyResult(val content: DailyContent, val bitmap: Bitmap?, val notice: String)

private class ProfileDailyRepository(context: Context) {
    private val preferences = context.getSharedPreferences("profile_daily_sources", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "profile-daily").apply { mkdirs() }
    private val cache = AtomicFile(File(directory, "content.json"))

    fun readSources() = DailySources(
        preferences.getBoolean("preferOwnApi", true),
        preferences.getString("dailyApi", null) ?: DEFAULT_DAILY_API,
        preferences.getString("imageApi", null) ?: DEFAULT_BING_API,
        preferences.getString("quoteApi", null) ?: DEFAULT_QUOTE_API,
    )

    fun saveSources(value: DailySources) {
        require(listOf(value.dailyApi, value.imageApi, value.quoteApi).all(::isDailyHttpsUrl))
        preferences.edit {
            putBoolean("preferOwnApi", value.preferOwnApi)
            putString("dailyApi", value.dailyApi)
            putString("imageApi", value.imageApi)
            putString("quoteApi", value.quoteApi)
        }
    }

    fun readCache(sources: DailySources): DailyContent = runCatching {
        val json = JSONObject(cache.readFully().toString(Charsets.UTF_8))
        if (json.getString("sources") != sourceKey(sources)) return@runCatching DailyContent()
        parseDaily(json.getJSONObject("content"), dailyDate()).copy(
            fetchedAt = json.optLong("fetchedAt"), imageStale = true, quoteStale = true)
    }.getOrDefault(DailyContent())

    suspend fun refresh(sources: DailySources, previous: DailyContent): DailyResult = coroutineScope {
        val day = dailyDate()
        var ownFailed = false
        val own = if (sources.preferOwnApi) attempt {
            parseDaily(requestJson(sources.dailyApi), day)
        }.also { ownFailed = it == null } else null
        // Preserve the day's chosen sentence when an image-only refresh is needed.
        val savedTodayQuote = previous.quote?.takeIf { it.date == day }
        val imageTask = async {
            own?.image ?: attempt { parsePublicImage(requestJson(sources.imageApi), day) }
        }
        val quoteTask = async {
            savedTodayQuote ?: own?.quote ?: attempt { parsePublicQuote(requestJson(sources.quoteApi), day) }
        }
        val image = imageTask.await()
        val quote = quoteTask.await()
        val merged = mergeDailyContent(previous, DailyContent(image, quote, System.currentTimeMillis(),
            imageStale = if (own?.image != null) own.imageStale else image == null,
            quoteStale = if (savedTodayQuote != null) false else if (own?.quote != null) own.quoteStale else quote == null))
        var imageUnavailable = false
        var bitmap = merged.image?.let { info ->
            attempt { loadBitmap(info) }.also { imageUnavailable = it == null }
        }
        val retainedImage = if (imageUnavailable) previous.image?.takeIf {
            val cached = cachedBitmap(it)
            if (cached != null) bitmap = cached
            cached != null
        } else null
        val usable = merged.copy(image = retainedImage ?: merged.image,
            imageStale = merged.imageStale || imageUnavailable)
        // A disk failure must not prevent displaying a successful online response.
        attempt { writeCache(sources, usable) }
        val notice = when {
            bitmap == null && quote == null -> "每日内容暂不可用，稍后重试"
            imageUnavailable || image == null -> "图片暂未更新，已保留可用背景"
            quote == null -> "句子暂未更新，已保留上次内容"
            ownFailed -> "自有服务暂不可用，已使用公共来源"
            usable.imageStale || usable.quoteStale -> "正在展示上次可用内容"
            else -> ""
        }
        DailyResult(usable, bitmap, notice)
    }

    fun cachedBitmap(image: DailyImage?): Bitmap? = image?.let { info ->
        attempt { decodeBitmap(imageFile(info.url)) }
    }

    private fun loadBitmap(image: DailyImage): Bitmap {
        val file = imageFile(image.url)
        cachedBitmap(image)?.let { return it }
        val bytes = requestBytes(image.url, 12 * 1024 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..16_384 && bounds.outHeight in 1..16_384)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (exception: Exception) { atomic.failWrite(output); throw exception }
        val decoded = checkNotNull(decodeBitmap(file))
        directory.listFiles()?.filter { it.name.startsWith("image-") && it.name.endsWith(".cache") && it != file }
            ?.forEach { it.delete() }
        return decoded
    }

    private fun decodeBitmap(file: File): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth !in 1..16_384 || bounds.outHeight !in 1..16_384) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun imageFile(url: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        return File(directory, "image-$hash.cache")
    }

    private fun sourceKey(value: DailySources) = listOf(value.preferOwnApi, value.dailyApi, value.imageApi, value.quoteApi).joinToString("\n")

    private fun writeCache(sources: DailySources, content: DailyContent) {
        val json = JSONObject().put("sources", sourceKey(sources)).put("fetchedAt", content.fetchedAt)
            .put("content", JSONObject().apply {
                content.image?.let { image -> put("image", JSONObject().put("date", image.date).put("title", image.title)
                    .put("url", image.url).put("copyright", image.copyright).put("copyrightUrl", image.copyrightUrl)) }
                content.quote?.let { quote -> put("quote", JSONObject().put("date", quote.date).put("text", quote.text)
                    .put("author", quote.author).put("work", quote.work).put("source", quote.source).put("sourceUrl", quote.sourceUrl)) }
            })
        val output = cache.startWrite()
        try { output.write(json.toString().toByteArray()); cache.finishWrite(output) }
        catch (exception: Exception) { cache.failWrite(output); throw exception }
    }
}

private inline fun <T> attempt(block: () -> T): T? = try { block() }
catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { null }

private fun JSONObject.text(key: String, max: Int = 600): String? =
    optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }?.take(max)

private fun JSONObject.https(key: String): String? = text(key, 4096)?.takeIf(::isDailyHttpsUrl)
private fun validDay(value: String?, fallback: String): String = attempt { LocalDate.parse(value).toString() } ?: fallback

private fun parseDaily(json: JSONObject, day: String): DailyContent {
    val image = json.optJSONObject("image")?.let { attempt { parseImage(it, day) } }
    val quote = json.optJSONObject("quote")?.let { attempt { parseQuote(it, day) } }
    require(image != null || quote != null)
    return DailyContent(image, quote, imageStale = json.optJSONObject("meta")?.optJSONObject("image")?.optBoolean("stale") == true,
        quoteStale = json.optJSONObject("meta")?.optJSONObject("quote")?.optBoolean("stale") == true)
}

private fun parseImage(json: JSONObject, day: String): DailyImage = DailyImage(
    validDay(json.text("date"), day), json.text("title", 160).orEmpty(),
    requireNotNull(json.https("url")), json.text("copyright").orEmpty(), json.https("copyrightUrl"))

private fun parseQuote(json: JSONObject, day: String): DailyQuote {
    val source = when (val name = json.text("source", 80)) {
        "curated", null -> "每日一句"
        "hitokoto" -> "一言"
        else -> name
    }
    return DailyQuote(validDay(json.text("date"), day), requireNotNull(json.text("text", 240)),
        json.text("author", 80), json.text("work", 120), source, json.https("sourceUrl"))
}

private fun parsePublicImage(json: JSONObject, day: String): DailyImage {
    val bing = json.optJSONArray("images")?.optJSONObject(0)
    if (bing == null) return parseImage(json.optJSONObject("data") ?: json, day)
    val url = URI("https://www.bing.com").resolve(requireNotNull(bing.text("url", 4096))).toString()
    require(isDailyHttpsUrl(url))
    val date = attempt { LocalDate.parse(bing.text("startdate"), DateTimeFormatter.BASIC_ISO_DATE).toString() } ?: day
    return DailyImage(date, bing.text("title", 160).orEmpty(), url,
        bing.text("copyright").orEmpty(), bing.https("copyrightlink"))
}

private fun parsePublicQuote(json: JSONObject, day: String): DailyQuote {
    if (json.text("hitokoto", 240) == null) return parseQuote(json.optJSONObject("data") ?: json, day)
    val uuid = json.text("uuid", 80)?.takeIf { it.matches(Regex("[a-fA-F0-9-]+")) }
    return DailyQuote(day, requireNotNull(json.text("hitokoto", 240)), json.text("from_who", 80),
        json.text("from", 120), "一言", uuid?.let { "https://hitokoto.cn?uuid=$it" } ?: "https://hitokoto.cn")
}

private fun requestJson(url: String): JSONObject = JSONObject(requestBytes(url, 256 * 1024).toString(Charsets.UTF_8))

private fun requestBytes(url: String, limit: Int, redirects: Int = 0): ByteArray {
    require(isDailyHttpsUrl(url) && redirects <= 4)
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 5_000
        connection.readTimeout = 7_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "NextTodo-Android/1.0")
        val status = connection.responseCode
        if (status in listOf(301, 302, 303, 307, 308)) {
            val next = URI(url).resolve(requireNotNull(connection.getHeaderField("Location"))).toString()
            return requestBytes(next, limit, redirects + 1)
        }
        check(status == 200)
        require(connection.contentLengthLong <= limit)
        return connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= limit)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } finally { connection.disconnect() }
}
