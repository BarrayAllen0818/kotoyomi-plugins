package org.skepsun.kototoro.parsers.site.en

import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.newParser
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.bitmap.Bitmap
import org.skepsun.kototoro.parsers.util.LinkResolver
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder

class Rule34VideoTest {

    private fun source(): ContentParserSource {
        val source = ContentParserSource.entries.find { it.name == "RULE34VIDEO" }
        assertNotNull(source, "Rule34Video must be registered in the generated source catalogue")
        return requireNotNull(source)
    }

    @Test
    fun `registered source is an adult video source`() {
        assertEquals(ContentType.HENTAI_VIDEO, source().contentType)
    }

    @Test
    fun `browse parses video cards and lazy thumbnails without advertisements`() = runTest {
        val context = OfflineContentLoaderContext(
            mapOf("https://rule34video.com/latest-updates/1/" to "rule34video/list.html"),
        )
        val parser = source().newParser(context)
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        assertEquals(listOf("Example animation", "Second animation"), list.map { it.title })
        assertEquals("/video/1001/example-animation/", list[0].url)
        assertEquals("https://rule34video.com/thumbs/1001.jpg", list[0].coverUrl)
        assertTrue(list.all { it.contentRating == ContentRating.ADULT })
    }

    @Test
    fun `search retains every keyword and requests the next page`() = runTest {
        val url = "https://rule34video.com/search/example-animation/?sort_by=post_date"
        val context = OfflineContentLoaderContext(
            mapOf(url to "rule34video/list.html", "$url&from_videos=2" to "rule34video/list.html"),
        )
        val parser = source().newParser(context)
        val filter = ContentListFilter(query = "Example Animation")
        val first = parser.getList(0, SortOrder.UPDATED, filter)
        parser.getList(first.size, SortOrder.UPDATED, filter)
        assertEquals(listOf(url, "$url&from_videos=2"), context.requests)
    }

    @Test
    fun `detail keeps one stable chapter and separate artist category and tag metadata`() = runTest {
        val context = OfflineContentLoaderContext(mapOf(DETAIL to "rule34video/detail.html"))
        val parser = source().newParser(context)
        val result = parser.getDetails(content())
        assertEquals("Example animation", result.title)
        assertEquals(setOf("Example Artist"), result.authors)
        assertEquals("https://rule34video.com/posters/1001.jpg", result.coverUrl)
        assertEquals(setOf("categories:example-series", "tags:animation"), result.tags.map { it.key }.toSet())
        assertEquals("/video/1001/example-animation/", result.chapters?.single()?.url)
    }

    @Test
    fun `playback resolves the configured quality and attaches referer`() = runTest {
        var referer: String? = null
        val stream = "https://cdn.example.com/1001-1080p.mp4?token=example"
        val context = OfflineContentLoaderContext(
            fixtures = mapOf(DETAIL to "rule34video/detail.html", stream to "rule34video/empty.html"),
            responseTypes = mapOf(stream to "video/mp4"),
            transformBody = { request, body ->
                if (request.url.toString() == stream) referer = request.header("Referer")
                body
            },
        )
        val parser = source().newParser(context)
        val chapter = requireNotNull(parser.getDetails(content()).chapters).single()
        val page = parser.getPages(chapter).single()
        assertEquals(stream, page.url)
        assertEquals(DETAIL, page.headers?.get("Referer"))
        assertEquals(DETAIL, referer)
        assertNotNull(page.headers?.get("User-Agent"))
        assertEquals(2, context.requests.count { it == DETAIL })
    }

    @Test
    fun `category filter uses site route rather than silently ignoring the selection`() = runTest {
        val url = "https://rule34video.com/categories/example-series/?sort_by=rating"
        val context = OfflineContentLoaderContext(mapOf(url to "rule34video/list.html"))
        val parser = source().newParser(context)
        val tag = ContentTag("Example series", "categories:example-series", source())
        parser.getList(0, SortOrder.RATING, ContentListFilter(tags = setOf(tag)))
        assertEquals(listOf(url), context.requests)
    }

    @Test
    fun `legacy and current video paths have the same stable identity`() {
        val parser = Rule34Video(OfflineContentLoaderContext())
        val doc = Jsoup.parse(
            """<div class="item thumb"><a class="th" href="/video/1001/new-title/" title="New"></a></div>""",
        )
        val legacy = Jsoup.parse(
            """<div class="item thumb"><a class="th" href="/videos/1001/old-title/" title="Old"></a></div>""",
        )
        assertEquals(parser.parseList(doc).single().id, parser.parseList(legacy).single().id)
    }

    @Test
    fun `direct video search accepts only this site and supports ID syntax`() {
        val parser = Rule34Video(OfflineContentLoaderContext())
        assertEquals(DETAIL, parser.buildListUrl(1, SortOrder.UPDATED, ContentListFilter(query = DETAIL)))
        assertEquals(
            "https://rule34video.com/video/1001/",
            parser.buildListUrl(1, SortOrder.UPDATED, ContentListFilter(query = "slug:1001")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            parser.buildListUrl(1, SortOrder.UPDATED, ContentListFilter(query = "https://foreign.example/video/1001/"))
        }
    }

    @Test
    fun `unsupported combinations fail instead of ignoring the query`() {
        val parser = Rule34Video(OfflineContentLoaderContext())
        val tag = ContentTag("Animation", "tags:animation", source())
        assertThrows(IllegalArgumentException::class.java) {
            parser.buildListUrl(1, SortOrder.UPDATED, ContentListFilter(query = "example", tags = setOf(tag)))
        }
    }

    @Test
    fun `download sheet is a fallback and strips only download parameters`() {
        val parser = Rule34Video(OfflineContentLoaderContext())
        val doc = Jsoup.parse("""<div class="vp-sheet" data-sheet="download">
            <a class="tag_item_download"
            href="/get_file/1/test.mp4?download=1&amp;download_filename=test&amp;token=keep">720p MP4</a>
            </div>""", DETAIL)
        assertEquals(
            listOf("https://rule34video.com/get_file/1/test.mp4?token=keep"),
            parser.streamCandidates(doc).map { it.toString() },
        )
    }

    @Test
    fun `HTML video fallback accepts relative sources and ignores iframe advertisements`() {
        val parser = Rule34Video(OfflineContentLoaderContext())
        val doc = Jsoup.parse("""<iframe src="https://advert.example/video.mp4"></iframe>
            <video><source src="/media/test.webm" label="480p"></video>""", DETAIL)
        assertEquals(
            listOf("https://rule34video.com/media/test.webm"),
            parser.streamCandidates(doc).map { it.toString() },
        )
    }

    @Test
    fun `KVS player URL uses the existing project decoder`() {
        val parser = Rule34Video(OfflineContentLoaderContext())
        val raw = "function/0/https://rule34video.com/get_file/1/0123456789abcdef0123456789abcdef/1001/test.mp4/"
        val doc = Jsoup.parse("""<script>var flashvars = {
            license_code: '${'$'}62417872059274', video_url: '$raw', video_url_text: '1080p' };</script>""", DETAIL)
        assertEquals(kvsDecodeUrl(raw, "${'$'}62417872059274"), parser.streamCandidates(doc).single().toString())
    }

    @Test
    fun `missing stream produces no fabricated playable candidate`() {
        val parser = Rule34Video(OfflineContentLoaderContext())
        assertTrue(parser.streamCandidates(Jsoup.parse("<html>Unavailable</html>")).isEmpty())
    }

    @Test
    fun `numeric video IDs are not interpreted as a resolution`() {
        val parser = Rule34Video(OfflineContentLoaderContext())
        val doc = Jsoup.parse("""<video>
            <source src="/1001/clip.mp4"><source src="/1001/clip-720p.mp4">
            </video>""", DETAIL)
        assertEquals("https://rule34video.com/1001/clip-720p.mp4", parser.streamCandidates(doc).first().toString())
    }

    @Test
    fun `redirect resolves to media and never forwards site credentials to the CDN`() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val cookies = listOf(
            Cookie.Builder().name("XSRF-TOKEN").value("example%3D").hostOnlyDomain("rule34video.com").build(),
        )
        val jar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
        }
        val client = OkHttpClient.Builder().cookieJar(jar).addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("OK")
            when (request.url.host) {
                "cdn.example.com" -> builder.code(200).header("Content-Type", "video/mp4")
                    .body("".toResponseBody()).build()
                else -> if (request.url.encodedPath.startsWith("/get_file/")) {
                    builder.code(302).header("Location", "https://cdn.example.com/final.mp4")
                        .body("".toResponseBody()).build()
                } else {
                    builder.code(200).header("Content-Type", "text/html").body("""<script>var flashvars = {
                        video_url: 'https://rule34video.com/get_file/1/example.mp4/',
                        video_url_text: '720p' };</script>""".toResponseBody()).build()
                }
            }
        }.build()
        val context = testContext(client, jar)
        val parser = Rule34Video(context)
        val detailParser = Rule34Video(OfflineContentLoaderContext(mapOf(DETAIL to "rule34video/detail.html")))
        val chapter = requireNotNull(detailParser.getDetails(content()).chapters).single()
        val page = parser.getPages(chapter).single()
        assertEquals("https://cdn.example.com/final.mp4", page.url)
        assertNull(page.headers?.get("Cookie"))
        assertNull(page.headers?.get("X-XSRF-TOKEN"))
        assertNull(requests.last().header("Cookie"))
        assertNull(requests.last().header("X-XSRF-TOKEN"))
        assertEquals("example=", requests[1].header("X-XSRF-TOKEN"))
    }

    private fun testContext(client: OkHttpClient, jar: CookieJar): ContentLoaderContext {
        val delegate = OfflineContentLoaderContext()
        return object : ContentLoaderContext() {
            override val httpClient = client
            override val cookieJar = jar
            override fun getConfig(source: ContentSource) = delegate.getConfig(source)
            override fun getDefaultUserAgent() = "Rule34Video offline test"
            override fun newParserInstance(source: ContentSource): ContentParser =
                (source as ContentParserSource).newParser(this)
            override fun newLinkResolver(link: HttpUrl): LinkResolver = error("Unused")
            @Deprecated("Provide a base url")
            override suspend fun evaluateJs(script: String): String? = error("Unused")
            override suspend fun evaluateJs(baseUrl: String, script: String): String? = error("Unused")
            override fun createBitmap(width: Int, height: Int): Bitmap = error("Unused")
            override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = error("Unused")
        }
    }

    private fun content() = Content(
        id = 1L,
        title = "Example animation",
        altTitles = emptySet(),
        url = "/video/1001/example-animation/",
        publicUrl = DETAIL,
        rating = RATING_UNKNOWN,
        contentRating = ContentRating.ADULT,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = source(),
    )

    companion object {
        private const val DETAIL = "https://rule34video.com/video/1001/example-animation/"
    }
}
