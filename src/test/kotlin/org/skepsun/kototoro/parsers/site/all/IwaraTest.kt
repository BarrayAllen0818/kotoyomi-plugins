package org.skepsun.kototoro.parsers.site.all

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.SourceConfigMock
import org.skepsun.kototoro.parsers.bitmap.Bitmap
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.newParser
import org.skepsun.kototoro.parsers.util.LinkResolver

class IwaraTest {
    @Test
    fun `factory metadata keeps category and duration on final detail copy`() = runTest {
        val context = OfflineContentLoaderContext(mapOf(DETAIL to "iwara/detail.json"), transformBody = { _, body ->
            JSONObject(body).apply {
                getJSONObject("user").put("name", "")
                getJSONObject("file").put("duration", 244)
                getJSONArray("tags").put(JSONObject("""{"id":"koikatsu","type":"category"}"""))
            }.toString()
        })
        val parser = parser(context)
        val details = parser.getDetails(content().copy(id = 987))
        assertEquals(setOf("example"), details.authors)
        val metadata = JSONObject(parser.javaClass.getMethod("getDetailsMetadata", Content::class.java)
            .invoke(parser, details) as String)
        assertEquals(244L, metadata.getLong("duration_seconds"))
        assertEquals(listOf("koikatsu"), metadata.getJSONArray("types").toList())
        assertTrue(!metadata.has("parodies"))
        assertTrue(!metadata.has("characters"))
        assertEquals(listOf(DETAIL), context.requests)
    }

    @Test
    fun `factory metadata keeps explicit null duration distinct from a missing field`() = runTest {
        val ctx = OfflineContentLoaderContext(mapOf(DETAIL to "iwara/detail.json"), transformBody = { _, body ->
            JSONObject(body).apply { getJSONObject("file").put("duration", JSONObject.NULL) }.toString()
        })
        val parser = parser(ctx)
        val details = parser.getDetails(content())
        val value = JSONObject(parser.javaClass.getMethod("getDetailsMetadata", Content::class.java)
            .invoke(parser, details) as String)
        assertTrue(value.has("duration_seconds"))
        assertTrue(value.isNull("duration_seconds"))
        assertTrue(!value.has("subtitle_languages"))
        assertTrue(!value.has("audio_languages"))
    }

    @Test
    fun `registered source is a video source`() {
        val source = ContentParserSource.entries.find { it.name == "IWARA" }
        assertNotNull(source, "Iwara must be registered in the generated source catalogue")
        assertEquals(ContentType.HENTAI_VIDEO, requireNotNull(source).contentType)
    }

    @Test
    fun `browse keeps native IDs selected thumbnails and content ratings`() = runTest {
        val context = OfflineContentLoaderContext(mapOf(LIST to "iwara/list.json"))
        val list = parser(context).getList(0, SortOrder.UPDATED, ContentListFilter())
        assertEquals(listOf("Example animation", "Second animation"), list.map { it.title })
        assertEquals("/video/vid0001", list[0].url)
        assertEquals("https://iwara.tv/video/vid0001", list[0].publicUrl)
        assertEquals("https://files.iwara.tv/image/thumbnail/file-001/thumbnail-03.jpg", list[0].coverUrl)
        assertEquals("https://files.iwara.tv/image/thumbnail/cover-002/custom.jpg", list[1].coverUrl)
        assertEquals(ContentRating.ADULT, list[0].contentRating)
        assertEquals(ContentRating.SAFE, list[1].contentRating)
    }

    @Test
    fun `pagination starts at zero and advances after a short server page`() = runTest {
        val next = LIST.replace("page=0", "page=1")
        val context = OfflineContentLoaderContext(mapOf(LIST to "iwara/list.json", next to "iwara/list.json"))
        val parser = parser(context)
        val first = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        parser.getList(first.size, SortOrder.UPDATED, ContentListFilter())
        assertEquals(listOf(LIST, next), context.requests)
    }

    @Test
    fun `keyword search uses search endpoint with every keyword`() = runTest {
        val url = "$API/search?page=0&limit=32&type=videos&query=dance%20test"
        val context = OfflineContentLoaderContext(mapOf(url to "iwara/list.json"))
        assertEquals(2, parser(context).getList(0, SortOrder.RELEVANCE, ContentListFilter(query = "dance test")).size)
        assertEquals(listOf(url), context.requests)
    }

    @Test
    fun `tag catalogue and tag browse use server tags`() = runTest {
        val url = "$API/videos?page=0&limit=32&sort=views&tags=animation"
        val context = OfflineContentLoaderContext(mapOf(
            "$API/tags?page=0&limit=100" to "iwara/tags.json",
            url to "iwara/list.json",
        ))
        val parser = parser(context)
        val tag = parser.getFilterOptions().availableTags.first { it.key == "animation" }
        assertEquals("animation", tag.title)
        assertEquals(2, parser.getList(0, SortOrder.POPULARITY, ContentListFilter(tags = setOf(tag))).size)
        assertEquals(url, context.requests.last())
    }

    @Test
    fun `details expose account author escape text and keep one stable chapter`() = runTest {
        val parser = parser(OfflineContentLoaderContext(mapOf(DETAIL to "iwara/detail.json")))
        val details = parser.getDetails(content())
        assertEquals("Example animation", details.title)
        assertEquals(setOf("Example Uploader"), details.authors)
        val doc = Jsoup.parse(requireNotNull(details.description))
        assertTrue(!doc.text().contains("上传者："))
        assertTrue(doc.text().contains("First line"))
        assertTrue(doc.select("script").isEmpty())
        assertEquals(setOf("animation", "mmd"), details.tags.map { it.key }.toSet())
        val chapter = requireNotNull(details.chapters).single()
        assertEquals("/video/vid0001", chapter.url)
        assertEquals(1767323045000L, chapter.uploadDate)
        val renamed = parser.getDetails(content().copy(url = "/video/vid0001/renamed-title"))
        assertEquals(details.id, renamed.id)
        assertEquals(chapter.id, requireNotNull(renamed.chapters).single().id)
    }

    @Test
    fun `detail preserves the saved identity and URL required by the host`() = runTest {
        val parser = parser(OfflineContentLoaderContext(mapOf(DETAIL to "iwara/detail.json")))
        val original = content().copy(id = 987L, url = "/video/vid0001/old-title")
        val details = parser.getDetails(original)
        assertEquals(original.id, details.id)
        assertEquals(original.url, details.url)
        assertEquals(original.source, details.source)
        assertEquals("/video/vid0001", requireNotNull(details.chapters).single().url)
    }

    @Test
    fun `malformed list items report Iwara context`() {
        val context = OfflineContentLoaderContext(
            mapOf(LIST to "iwara/list.json"), transformBody = { _, _ -> "{\"results\":[null]}" },
        )
        val error = assertThrows(ParseException::class.java) {
            runTest { parser(context).getList(0, SortOrder.UPDATED, ContentListFilter()) }
        }
        assertTrue(error.message.orEmpty().contains("Iwara"))
    }

    @Test
    fun `playback refreshes file URLs signs file requests and keeps signing headers off media`() = runTest {
        val signatures = mutableListOf<String?>()
        var refresh = 0
        val fresh = FILE.replace("hash=example", "hash=fresh")
        val context = OfflineContentLoaderContext(
            mapOf(DETAIL to "iwara/detail.json", FILE to "iwara/sources.json", fresh to "iwara/sources.json"),
            transformBody = { request, body ->
                if (request.url.encodedPath.startsWith("/file/")) signatures += request.header("X-Version")
                if (request.url.toString() == DETAIL && ++refresh > 1) {
                    body.replace("hash=example", "hash=fresh")
                } else body
            },
        )
        val parser = parser(context)
        val chapter = requireNotNull(parser.getDetails(content()).chapters).single()
        repeat(2) {
            val page = parser.getPages(chapter).single()
            assertEquals("https://media.example.com/1080.mp4?token=example", page.url)
            assertEquals("https://iwara.tv/", page.headers?.get("Referer"))
            assertNotNull(page.headers?.get("User-Agent"))
            assertNull(page.headers?.get("X-Version"))
            assertNull(page.headers?.get("Authorization"))
            assertNull(page.headers?.get("Cookie"))
        }
        assertEquals(3, context.requests.count { it == DETAIL })
        assertEquals(2, context.requests.count { it == fresh })
        assertEquals(List(2) { "3e2c3fe2d46a714457e8c494ac4fcc81d97e8e04" }, signatures)
    }

    @ParameterizedTest
    @CsvSource("1080,1080", "720,540", "100,360", "2160,1080")
    fun `quality uses highest known height below preference or lowest above it`(preferred: String, selected: String) =
        runTest {
            val context = OfflineContentLoaderContext(
                mapOf(DETAIL to "iwara/detail.json", FILE to "iwara/sources.json"),
            )
            val config = SourceConfigMock().apply { set(ConfigKey.Text("preferred_quality", "", "1080"), preferred) }
            val parser = parser(withConfig(context, config))
            val chapter = requireNotNull(parser.getDetails(content()).chapters).single()
            assertEquals("https://media.example.com/$selected.mp4?token=example", parser.getPages(chapter).single().url)
        }

    @Test
    fun `original is usable when no numbered resolution exists`() = runTest {
        val context = OfflineContentLoaderContext(
            mapOf(DETAIL to "iwara/detail.json", FILE to "iwara/sources.json"),
            transformBody = { request, body ->
                if (request.url.toString() == FILE) {
                    JSONArray().put(JSONArray(body).getJSONObject(0)).toString()
                } else body
            },
        )
        val parser = parser(context)
        val chapter = requireNotNull(parser.getDetails(content()).chapters).single()
        assertEquals("https://media.example.com/source.mp4?token=example", parser.getPages(chapter).single().url)
    }

    @Test
    fun `invalid media URLs and non video formats are not playable`() = runTest {
        for (replacement in listOf(
            """[{"name":"1080","type":"text/html","src":{"view":"https://example.com/page"}}]""",
            """[{"name":"1080","type":"video/mp4","src":{"view":"javascript:alert(1)"}}]""",
            """[{"name":"1080","type":"video/mp4","src":{"view":"https://user:password@example.com/video.mp4"}}]""",
            "[]",
            "[null]",
        )) {
            val context = OfflineContentLoaderContext(
                mapOf(DETAIL to "iwara/detail.json", FILE to "iwara/sources.json"),
                transformBody = { request, body -> if (request.url.toString() == FILE) replacement else body },
            )
            val parser = parser(context)
            val chapter = requireNotNull(parser.getDetails(content()).chapters).single()
            assertThrows(ParseException::class.java) { runBlocking { parser.getPages(chapter) } }
        }
    }

    @Test
    fun `API failures invalid responses and validation pages are not empty lists`() {
        for (body in listOf("{}", "{\"message\":\"errors.privateVideo\"}", "<html>Just a moment</html>")) {
            val context = OfflineContentLoaderContext(
                mapOf(LIST to "iwara/list.json"), transformBody = { _, _ -> body },
            )
            val error = assertThrows(ParseException::class.java) {
                runTest { parser(context).getList(0, SortOrder.UPDATED, ContentListFilter()) }
            }
            assertTrue(error.message.orEmpty().contains("Iwara"))
        }
        val context = OfflineContentLoaderContext(
            mapOf(LIST to "iwara/list.json"), transformBody = { _, _ -> "{\"results\":[]}" },
        )
        runTest { assertTrue(parser(context).getList(0, SortOrder.UPDATED, ContentListFilter()).isEmpty()) }
    }

    @Test
    fun `external videos and foreign file endpoints fail without following them`() = runTest {
        for (external in listOf(false, true)) {
            val context = OfflineContentLoaderContext(
                mapOf(DETAIL to "iwara/detail.json"),
                transformBody = { _, body -> JSONObject(body).apply {
                    if (external) {
                        remove("fileUrl")
                        put("embedUrl", "https://example.com/embed/video")
                    } else put("fileUrl", "https://foreign.example.com/file/id?expires=2000000000")
                }.toString() },
            )
            val parser = parser(context)
            val chapter = requireNotNull(parser.getDetails(content()).chapters).single()
            assertThrows(ParseException::class.java) { runBlocking { parser.getPages(chapter) } }
            assertTrue(context.requests.all { it == DETAIL })
        }
    }

    @Test
    fun `unsupported filter combinations fail before any request`() {
        val context = OfflineContentLoaderContext()
        val parser = parser(context)
        val tag = ContentTag("animation", "animation", source())
        for (filter in listOf(
            ContentListFilter(query = "dance", tags = setOf(tag)),
            ContentListFilter(tagsExclude = setOf(tag)),
            ContentListFilter(tags = setOf(tag, tag.copy(key = "mmd"))),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                runTest { parser.getList(0, SortOrder.UPDATED, filter) }
            }
        }
        assertTrue(context.requests.isEmpty())
    }

    @Test
    fun `direct video URLs resolve only their stable ID and reject foreign sites`() = runTest {
        val context = OfflineContentLoaderContext(mapOf(DETAIL to "iwara/detail.json"))
        val parser = parser(context)
        val list = parser.getList(0, SortOrder.UPDATED,
            ContentListFilter(query = "https://www.iwara.tv/video/vid0001/example-title"))
        assertEquals("/video/vid0001", list.single().url)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                parser.getList(0, SortOrder.UPDATED, ContentListFilter(query = "https://foreign.example/video/id"))
            }
        }
    }

    private fun source() = requireNotNull(ContentParserSource.entries.find { it.name == "IWARA" })
    private fun parser(context: ContentLoaderContext) = source().newParser(context)

    private fun content() = Content(
        id = 1L, title = "Example", altTitles = emptySet(), url = "/video/vid0001",
        publicUrl = "https://iwara.tv/video/vid0001", rating = RATING_UNKNOWN,
        contentRating = ContentRating.ADULT, coverUrl = null, tags = emptySet(), state = null,
        authors = emptySet(), source = source(),
    )

    private fun withConfig(delegate: OfflineContentLoaderContext, config: SourceConfigMock) =
        object : ContentLoaderContext() {
            override val httpClient = delegate.httpClient
            override val cookieJar = delegate.cookieJar
            override fun getConfig(source: ContentSource) = config
            override fun getDefaultUserAgent() = delegate.getDefaultUserAgent()
            override fun newParserInstance(source: ContentSource): ContentParser =
                (source as ContentParserSource).newParser(this)
            override fun newLinkResolver(link: HttpUrl): LinkResolver = error("Unused")
            @Deprecated("Provide a base url")
            override suspend fun evaluateJs(script: String): String? = error("Unused")
            override suspend fun evaluateJs(baseUrl: String, script: String): String? = error("Unused")
            override fun createBitmap(width: Int, height: Int): Bitmap = error("Unused")
            override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = error("Unused")
        }

    companion object {
        private const val API = "https://api.iwara.tv"
        private const val LIST = "$API/videos?page=0&limit=32&sort=date"
        private const val DETAIL = "$API/video/vid0001"
        private const val FILE = "https://filesq.iwara.tv/file/file-001?expires=2000000000&hash=example"
    }
}
