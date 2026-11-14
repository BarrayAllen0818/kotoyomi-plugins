package org.skepsun.kototoro.parsers.site.zh

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.SourceConfigMock
import org.skepsun.kototoro.parsers.bitmap.Bitmap
import org.skepsun.kototoro.parsers.config.ContentSourceConfig
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.newParser
import org.skepsun.kototoro.parsers.util.LinkResolver

/** 匿名详情验证；沿用默认 HTTP 协议协商，不加载账户或 Cookie 文件。 */
@EnabledIfEnvironmentVariable(named = "HANIME1_INTEGRATION_TEST", matches = "1")
class Hanime1IntegrationTest {

    @Test
    fun `formal factory extracts metadata from current anime and animation pages`() = runBlocking {
        val context = Hanime1IntegrationContext()
        try {
            val parser = ContentParserSource.HANIME1.newParser(context)
            var checkedBrands = 0
            var checkedSubtitles = 0
            for (id in listOf(409178, 409177, 408317, 408319)) {
                val url = "https://${parser.domain}/watch?v=$id"
                val seed = Content(
                    id = id.toLong(), title = "Online metadata check", altTitles = emptySet(),
                    url = "/watch?v=$id", publicUrl = url, rating = 0f, contentRating = null,
                    coverUrl = null, tags = emptySet(), state = null, authors = emptySet(), source = parser.source,
                )
                val requestCount = context.responses.size
                val details = parser.getDetails(seed)
                assertEquals(requestCount + 1, context.responses.size, "Details must use one request")
                val (html, protocol) = context.responses.last()
                val page = Jsoup.parse(html, url)
                val payload = JSONObject(
                    parser.javaClass.getMethod("getDetailsMetadata", Content::class.java)
                        .invoke(parser, details) as String,
                )
                val title = requireNotNull(page.getElementById("shareBtn-title")) { "Hanime1: title missing" }.text()
                val credit = requireNotNull(page.getElementById("video-artist-name")) { "Hanime1: creator missing" }
                assertTrue(title.isNotBlank())
                assertEquals(title, details.title)
                assertTrue(credit.text().isNotBlank())
                assertEquals(setOf(credit.text().trim()), details.authors)
                assertEquals(
                    listOf(requireNotNull(credit.nextElementSibling()).text()),
                    payload.getJSONArray("types").toList(),
                )
                val duration = requireNotNull(page.selectFirst("meta[property=og:video:duration]"))
                    .attr("content").toLong()
                assertTrue(duration > 0)
                assertEquals(duration, payload.getLong("duration_seconds"))
                assertFalse(details.description.isNullOrBlank())
                assertEquals(1, details.chapters.orEmpty().size)

                // 网页简介以双语行标注品牌；独立按行取得预期值，不复用产品匹配表达式。
                val description = requireNotNull(details.description)
                    .replace(Regex("""(?i)<br\s*/?>"""), "\n")
                val brand = description.lineSequence().map(String::trim)
                    .firstOrNull { it.startsWith("Brand / ブランド:") }
                    ?.substringAfter(':')?.trim()?.takeIf(String::isNotEmpty)
                if (brand != null) {
                    assertEquals(listOf(brand), payload.getJSONArray("producers").toList())
                    checkedBrands++
                } else {
                    assertFalse(payload.has("producers"))
                }
                val attributes = page.select(".single-video-tag:not([data-toggle])").map {
                    it.text().removePrefix("# ").substringBefore(" (").trim()
                }
                for ((label, field) in listOf("中文字幕" to "subtitle_languages", "中文配音" to "audio_languages")) {
                    if (label in attributes) {
                        assertEquals(listOf("中文"), payload.getJSONArray(field).toList())
                        if (field == "subtitle_languages") checkedSubtitles++
                    } else {
                        assertFalse(payload.has(field))
                    }
                }
                val related = payload.optJSONArray("related_works")
                if (credit.nextElementSibling()?.text() in setOf("裏番", "泡麵番")) {
                    val links = page.select("#playlist-scroll .thumb-container a[href]")
                        .map { it.absUrl("href") }.filter { it != url }.distinct()
                    assertEquals(links.size, related?.length() ?: 0)
                    for (index in 0 until (related?.length() ?: 0)) {
                        val work = requireNotNull(related).getJSONObject(index)
                        assertEquals(parser.source.name, work.getString("source"))
                        assertTrue(work.getString("url") in links)
                        assertTrue(work.getString("title").isNotBlank())
                    }
                } else {
                    assertFalse(payload.has("related_works"))
                }
                assertFalse(payload.has("parodies"))
                assertFalse(payload.has("characters"))
                println("HANIME1 details $id: HTTP 200, $protocol, metadata checked, brand=$brand")
            }
            assertTrue(checkedBrands > 0, "Current samples must exercise the bilingual brand field")
            assertTrue(checkedSubtitles > 0, "Current samples must exercise subtitle language")
        } finally {
            context.httpClient.dispatcher.executorService.shutdownNow()
            context.httpClient.connectionPool.evictAll()
        }
    }
}

private class Hanime1IntegrationContext : ContentLoaderContext() {
    val responses = mutableListOf<Pair<String, String>>()
    private val config = SourceConfigMock()
    override val cookieJar: CookieJar = CookieJar.NO_COOKIES
    override val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (!response.isSuccessful) {
                val status = response.code
                response.close()
                error("Hanime1 details: HTTP $status")
            }
            responses += response.peekBody(2_000_000).string() to response.protocol.toString()
            response
        }
        .apply {
            val raw = listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy")
                .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
            if (raw != null) {
                val uri = URI(if (raw.contains("://")) raw else "http://$raw")
                val type = if (uri.scheme.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP
                proxy(Proxy(type, InetSocketAddress(uri.host, if (uri.port >= 0) uri.port else 80)))
            }
        }.build()

    override fun getConfig(source: ContentSource): ContentSourceConfig = config
    override fun getDefaultUserAgent(): String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    override fun newParserInstance(source: ContentSource): ContentParser =
        (source as ContentParserSource).newParser(this)
    override fun newLinkResolver(link: HttpUrl): LinkResolver =
        error("Browser unavailable in anonymous integration check")
    @Deprecated("Provide a base url")
    override suspend fun evaluateJs(script: String): String? = error("JS unavailable in metadata check")
    override suspend fun evaluateJs(baseUrl: String, script: String): String? =
        error("JS unavailable in metadata check")
    override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = error("Unused")
    override fun createBitmap(width: Int, height: Int): Bitmap = error("Unused")
}
