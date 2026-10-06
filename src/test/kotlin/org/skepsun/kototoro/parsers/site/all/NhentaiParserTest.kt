package org.skepsun.kototoro.parsers.site.all

import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import okio.buffer
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.SourceConfigMock
import org.skepsun.kototoro.parsers.bitmap.Bitmap
import org.skepsun.kototoro.parsers.config.ContentSourceConfig
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.LinkResolver
import org.skepsun.kototoro.parsers.util.generateUid

class NhentaiParserTest {
    @Test
    fun `new and legacy list image attributes resolve without empty covers`() {
        val parser = NhentaiParser(NhOfflineContext())
        val attributes = listOf(
            "src='/images/a.webp'" to "https://nhentai.net/images/a.webp",
            "data-src='' src='//t2.nhentai.net/a.jpg'" to "https://t2.nhentai.net/a.jpg",
            "data-src='/old.jpg' src='/new.jpg'" to "https://nhentai.net/old.jpg",
            "data-cfsrc='relative.jpg'" to "https://nhentai.net/relative.jpg",
            "" to null,
        )
        for ((attrs, expected) in attributes) {
            assertEquals(expected, parser.parseGalleryList(listDocument(attrs)).single().coverUrl, attrs)
        }
    }

    @Test
    fun `legacy list identity and language remain unchanged`() {
        val parser = NhentaiParser(NhOfflineContext())
        val items = parser.parseGalleryList(Jsoup.parse(fixture("list.html"), "https://nhentai.net/"))
        assertEquals(2, items.size)
        assertEquals("385440", items[0].url)
        assertEquals("https://t3.nhentai.net/galleries/2045096/cover.jpg", items[0].coverUrl)
        assertEquals("English", items[1].description)
        assertEquals(ContentRating.ADULT, items[0].contentRating)
    }

    @Test
    fun `details and pages use v2 paths and preserve existing identities`() = runBlocking {
        val ctx = NhOfflineContext()
        val parser = NhentaiParser(ctx).also { ctx.parser = it }
        val original = parser.parseGalleryList(listDocument("src='/a.jpg'")).single()
        val details = parser.getDetails(original)
        assertEquals(original.id, details.id)
        assertEquals(original.url, details.url)
        assertEquals(original.publicUrl, details.publicUrl)
        assertEquals("Fixture title", details.title)
        assertEquals(setOf("Fixture artist"), details.authors)
        assertEquals("chinese / translated", details.description)
        assertEquals("https://t1.nhentai.net/galleries/42/cover.webp.webp", details.coverUrl)
        val chapter = details.chapters!!.single()
        assertEquals(parser.generateUid("${original.id}-0"), chapter.id)
        val pages = parser.getPages(chapter)
        assertEquals(listOf("https://i1.nhentai.net/galleries/42/1.jpg",
            "https://i1.nhentai.net/galleries/42/2.webp.webp"), pages.map { it.url })
        assertEquals(parser.generateUid("galleries/42/1.jpg"), pages[0].id)
        assertEquals(pages[0].url, parser.getPageUrl(pages[0]))
        assertEquals(1, ctx.requests.count { it.url.encodedPath == "/api/v2/cdn" })
        assertTrue(ctx.requests.all { it.header("Referer") == "https://nhentai.net/" })
        assertEquals("https://nhentai.net/", pages[0].headers?.get("Referer"))
    }

    @Test
    fun `all advertised sorts preserve page two for every list route`() = runBlocking {
        val ctx = NhOfflineContext()
        val parser = NhentaiParser(ctx).also { ctx.parser = it }
        val filters = listOf(
            ContentListFilter() to "/",
            ContentListFilter(query = "sample query") to "/search/",
            ContentListFilter(tags = setOf(ContentTag("中文", "language:chinese", parser.source))) to "/language/chinese/",
            ContentListFilter(tags = setOf(ContentTag("Sample Tag", "tag:1", parser.source))) to "/tag/sample-tag/",
        )
        for (order in parser.availableSortOrders) {
            for ((filter, path) in filters) {
                parser.getListPage(2, order, filter)
                val url = ctx.requests.last().url
                val expectedPath = if (path == "/" && order != SortOrder.NEWEST) "/search/" else path
                assertEquals(expectedPath, url.encodedPath)
                assertEquals("2", url.queryParameter("page"))
                assertEquals(when (order) {
                    SortOrder.POPULARITY_TODAY -> "popular-today"
                    SortOrder.POPULARITY_WEEK -> "popular-week"
                    SortOrder.POPULARITY_MONTH -> "popular-month"
                    SortOrder.POPULARITY -> "popular"
                    else -> null
                }, url.queryParameter("sort"))
            }
        }
    }

    @Test
    fun `invalid gallery data fails with identity and field context`() = runBlocking {
        val mutations: List<Pair<String, (JSONObject) -> Unit>> = listOf(
            "id" to { it.put("id", 99) },
            "num_pages" to { it.put("num_pages", 0) },
            "num_pages" to { it.put("num_pages", 3) },
            "pages" to { it.put("pages", org.json.JSONArray()) },
            "number" to { it.getJSONArray("pages").getJSONObject(0).put("number", 1) },
            "number" to { it.getJSONArray("pages").getJSONObject(0).put("number", 0) },
            "number" to { it.getJSONArray("pages").getJSONObject(0).put("number", 3) },
            "path" to { it.getJSONArray("pages").getJSONObject(0).put("path", "") },
            "path" to { it.getJSONArray("pages").getJSONObject(0).put("path", "http://invalid.test/1.jpg") },
        )
        for ((field, mutate) in mutations) {
            val ctx = NhOfflineContext()
            val parser = NhentaiParser(ctx)
            val original = parser.parseGalleryList(listDocument("")).single()
            val chapter = ContentChapter(id = 1, title = null, number = 1f, volume = 0, url = "42",
                scanlator = null, uploadDate = 0, branch = null, source = parser.source)
            ctx.gallery = JSONObject(ctx.gallery).also(mutate).toString()
            val e = assertThrows(ParseException::class.java) { runBlocking { parser.getPages(chapter) } }
            assertTrue(e.message.orEmpty().contains("42") && e.message.orEmpty().contains(field), e.message)
            if (field == "id") {
                assertThrows(ParseException::class.java) { runBlocking { parser.getDetails(original) } }
            }
        }
        val ctx = NhOfflineContext()
        val parser = NhentaiParser(ctx)
        ctx.gallery = JSONObject(ctx.gallery).put("cover", JSONObject()).toString()
        assertThrows(ParseException::class.java) {
            runBlocking { parser.getDetails(parser.parseGalleryList(listDocument("")).single()) }
        }
        Unit
    }

    @Test
    fun `absolute paths skip CDN and UID ignores host but preserves query encoding`() = runBlocking {
        val urls = listOf(
            "galleries/42/1.webp?v=2&x=%2F#frag",
            "/galleries/42/1.webp?v=2&x=%2F",
            "https://other.test/galleries/42/1.webp?v=2&x=%2F#frag",
            "https://other.test/galleries/42/1.webp?v=3&x=%2F",
            "https://other.test/galleries/42/1.webp?x=%2F&v=2",
            "https://other.test/galleries/42/A%2Fb.webp?v=2&x=%2F",
            "https://other.test/galleries/42/A/b.webp?v=2&x=%2F",
        )
        val ids = urls.map { path ->
            val ctx = NhOfflineContext()
            val parser = NhentaiParser(ctx)
            val json = JSONObject(ctx.gallery).put("num_pages", 1).put("pages",
                org.json.JSONArray().put(JSONObject().put("number", 1).put("path", path)))
            ctx.gallery = json.toString()
            val chapter = ContentChapter(id = 1, title = null, number = 1f, volume = 0, url = "42",
                scanlator = null, uploadDate = 0, branch = null, source = parser.source)
            val page = parser.getPages(chapter).single()
            assertEquals(if (path.startsWith("https:")) 0 else 1,
                ctx.requests.count { it.url.encodedPath == "/api/v2/cdn" })
            page.id
        }
        assertEquals(ids[0], ids[1])
        assertEquals(ids[0], ids[2])
        assertNotEquals(ids[0], ids[3])
        assertNotEquals(ids[0], ids[4])
        assertNotEquals(ids[5], ids[6])
    }

    @Test
    fun `HTTP and IO failures propagate without webpage fallback`() = runBlocking {
        for (status in listOf(403, 404, 429, 500)) {
            for (details in listOf(true, false)) {
                val ctx = NhOfflineContext().also { it.status = status }
                val parser = NhentaiParser(ctx)
                val original = parser.parseGalleryList(listDocument("")).single()
                val chapter = ContentChapter(id = 1, title = null, number = 1f, volume = 0, url = "42",
                    scanlator = null, uploadDate = 0, branch = null, source = parser.source)
                assertThrows(IOException::class.java) { runBlocking {
                    if (details) parser.getDetails(original) else parser.getPages(chapter)
                } }
                assertEquals(listOf("/api/v2/galleries/42"), ctx.requests.map { it.url.encodedPath })
            }
        }
        val error = IOException("fixture failure")
        val ctx = NhOfflineContext().also { it.failure = error }
        val parser = NhentaiParser(ctx)
        val actual = assertThrows(IOException::class.java) { runBlocking {
            parser.getDetails(parser.parseGalleryList(listDocument("")).single())
        } }
        assertEquals(error.message, actual.message)
        assertEquals(1, ctx.requests.size)
    }

    @Test
    fun `domain changes during gallery response retain operation snapshot and explicit referer`() = runBlocking {
        val ctx = NhOfflineContext()
        val parser = NhentaiParser(ctx).also { ctx.parser = it }
        ctx.onRequest = { request ->
            if (request.url.encodedPath == "/api/v2/galleries/42") ctx.config.set(parser.configKeyDomain, "new.nhentai.net")
        }
        val original = parser.parseGalleryList(listDocument("")).single()
        val details = parser.getDetails(original)
        val pages = parser.getPages(details.chapters!!.single())
        assertEquals(listOf("nhentai.net", "nhentai.net", "new.nhentai.net", "new.nhentai.net"),
            ctx.requests.map { it.url.host })
        assertEquals(listOf("https://nhentai.net/", "https://nhentai.net/",
            "https://new.nhentai.net/", "https://new.nhentai.net/"), ctx.requests.map { it.header("Referer") })
        assertEquals("https://new.nhentai.net/", pages[0].headers?.get("Referer"))
    }

    @Test
    fun `page operation keeps old domain referer after settings change`() = runBlocking {
        val ctx = NhOfflineContext()
        val parser = NhentaiParser(ctx).also { ctx.parser = it }
        val card = parser.parseGalleryList(listDocument("")).single()
        val chapter = parser.getDetails(card).chapters!!.single()
        ctx.requests.clear()
        ctx.onRequest = { request ->
            if (request.url.host == "nhentai.net" && request.url.encodedPath == "/api/v2/galleries/42") {
                ctx.config.set(parser.configKeyDomain, "new.nhentai.net")
            }
        }
        val oldPages = parser.getPages(chapter)
        assertTrue(ctx.requests.all { it.url.host == "nhentai.net" })
        assertTrue(ctx.requests.all { it.header("Referer") == "https://nhentai.net/" })
        assertTrue(oldPages.all { it.headers?.get("Referer") == "https://nhentai.net/" })
        parser.getPages(chapter)
        assertEquals("new.nhentai.net", ctx.requests.last().url.host)
        assertEquals("https://new.nhentai.net/", ctx.requests.last().header("Referer"))
    }

    @Test
    fun `list public link belongs to entry domain snapshot`() = runBlocking {
        val ctx = NhOfflineContext()
        val parser = NhentaiParser(ctx).also { ctx.parser = it }
        ctx.onRequest = { ctx.config.set(parser.configKeyDomain, "new.nhentai.net") }
        assertEquals("https://nhentai.net/g/42",
            parser.getListPage(1, SortOrder.NEWEST, ContentListFilter()).single().publicUrl)
        assertEquals("https://new.nhentai.net/g/42",
            parser.getListPage(1, SortOrder.NEWEST, ContentListFilter()).single().publicUrl)
    }

    @Test
    fun `malformed gallery and CDN JSON preserve parse context and close responses`() = runBlocking {
        for (endpoint in listOf("/api/v2/galleries/42", "/api/v2/cdn")) {
            val ctx = NhOfflineContext()
            val parser = NhentaiParser(ctx)
            val original = parser.parseGalleryList(listDocument("")).single()
            var closed = false
            ctx.bodyTransform = { request, body ->
                if (request.url.encodedPath != endpoint) body.toResponseBody()
                else object : okhttp3.ResponseBody() {
                    private val buffer = object : okio.ForwardingSource(okio.Buffer().writeUtf8("invalid JSON")) {
                        override fun close() { closed = true; super.close() }
                    }.buffer()
                    override fun contentType(): okhttp3.MediaType? = null
                    override fun contentLength(): Long = -1L
                    override fun source(): okio.BufferedSource = buffer
                }
            }
            val e = assertThrows(ParseException::class.java) { runBlocking { parser.getDetails(original) } }
            assertTrue(e.message.orEmpty().contains("NH") && e.message.orEmpty().contains("JSON"))
            assertTrue(closed)
            ctx.bodyTransform = null
            parser.getDetails(original)
        }
    }

    @Test
    fun `CDN fields require usable HTTPS hosts and failures are not cached`() = runBlocking {
        for (field in listOf("image_servers", "thumb_servers")) {
            val ctx = NhOfflineContext()
            val parser = NhentaiParser(ctx)
            val original = parser.parseGalleryList(listDocument("")).single()
            ctx.cdn = JSONObject(ctx.cdn).put(field, org.json.JSONArray().put("http://invalid.test/")).toString()
            repeat(2) {
                val e = assertThrows(ParseException::class.java) { runBlocking { parser.getDetails(original) } }
                assertTrue(e.message.orEmpty().contains(field))
            }
            assertEquals(2, ctx.requests.count { it.url.encodedPath == "/api/v2/cdn" })
        }
    }

    @Test
    fun `cancel during synchronous CDN parsing closes body and does not publish`() = runBlocking {
        supervisorScope {
            val ctx = NhOfflineContext()
            val parser = NhentaiParser(ctx)
            val original = parser.parseGalleryList(listDocument("")).single()
            var closed = false
            lateinit var pending: Deferred<Content>
            ctx.bodyTransform = { request, body ->
                if (request.url.encodedPath != "/api/v2/cdn") body.toResponseBody()
                else object : okhttp3.ResponseBody() {
                    private val buffer = object : okio.ForwardingSource(okio.Buffer().writeUtf8(body)) {
                        override fun read(sink: okio.Buffer, byteCount: Long): Long {
                            val result = super.read(sink, byteCount)
                            pending.cancel()
                            return result
                        }
                        override fun close() { closed = true; super.close() }
                    }.buffer()
                    override fun contentType(): okhttp3.MediaType? = null
                    override fun contentLength(): Long = body.toByteArray().size.toLong()
                    override fun source(): okio.BufferedSource = buffer
                }
            }
            pending = async(start = CoroutineStart.LAZY) { parser.getDetails(original) }
            pending.start()
            try { pending.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
            assertTrue(closed)
            ctx.bodyTransform = null
            parser.getDetails(original)
            parser.getDetails(original)
            assertEquals(2, ctx.requests.count { it.url.encodedPath == "/api/v2/cdn" })
        }
    }

    @Test
    fun `saved character tag resolves its actual category and preserves all sorts and pages`() = runBlocking {
        val ctx = NhOfflineContext().also {
            it.tagMetadata = """[{"id":80930,"type":"character","url":"/character/abigail-williams/"}]"""
            it.tagListPath = "/character/abigail-williams/"
        }
        val parser = NhentaiParser(ctx).also { ctx.parser = it }
        val tag = parser.getFilterOptions().availableTags.single { it.key == "tag:80930" }
        assertEquals("abigail williams", tag.title)
        for (order in parser.availableSortOrders) {
            for (page in 1..2) {
                ctx.requests.clear()
                val cards = parser.getListPage(page, order, ContentListFilter(tags = setOf(tag)))
                assertEquals("42", cards.single().url)
                assertEquals(listOf("/api/v2/tags/ids", "/character/abigail-williams/"),
                    ctx.requests.map { it.url.encodedPath })
                assertEquals("80930", ctx.requests.first().url.queryParameter("ids"))
                assertEquals(page.toString(), ctx.requests.last().url.queryParameter("page"))
                assertEquals(when (order) {
                    SortOrder.POPULARITY_TODAY -> "popular-today"
                    SortOrder.POPULARITY_WEEK -> "popular-week"
                    SortOrder.POPULARITY_MONTH -> "popular-month"
                    SortOrder.POPULARITY -> "popular"
                    else -> null
                }, ctx.requests.last().url.queryParameter("sort"))
            }
        }
    }

    @Test
    fun `tag routing uses returned path rather than display title for each category`() = runBlocking {
        for (type in listOf("tag", "character", "artist", "group", "parody", "category", "language")) {
            val ctx = NhOfflineContext().also {
                it.tagMetadata = """[{"id":42,"type":"$type","url":"/$type/canonical-slug/"}]"""
                it.tagListPath = "/$type/canonical-slug/"
            }
            val parser = NhentaiParser(ctx)
            parser.getListPage(2, SortOrder.POPULARITY, ContentListFilter(
                tags = setOf(ContentTag("Different display title", "tag:42", parser.source)),
            ))
            assertEquals("/$type/canonical-slug/", ctx.requests.last().url.encodedPath)
            assertEquals("42", ctx.requests.first().url.queryParameter("ids"))
        }
    }

    @Test
    fun `invalid tag identity or path fails before requesting a list`() = runBlocking {
        val badResponses = listOf(
            "[]", "[null]", """[{"id":99,"url":"/tag/sample-tag/"}]""",
            """[{"id":1,"url":"/tag/sample-tag/"},{"id":1,"url":"/tag/sample-tag/"}]""",
            """[{"id":1}]""",
            """[{"id":1,"url":"https://other.test/tag/sample-tag/"}]""",
            """[{"id":1,"url":"//other.test/tag/sample-tag/"}]""",
            """[{"id":1,"url":"/tag/sample-tag/?page=99"}]""",
            """[{"id":1,"url":"/tag/sample-tag/#fragment"}]""",
            """[{"id":1,"url":"/api/v2/galleries/42"}]""",
        )
        for (body in badResponses) {
            val ctx = NhOfflineContext().also { it.tagMetadata = body }
            val parser = NhentaiParser(ctx)
            val error = assertThrows(ParseException::class.java) { runBlocking {
                parser.getListPage(1, SortOrder.NEWEST, ContentListFilter(
                    tags = setOf(ContentTag("Sample Tag", "tag:1", parser.source)),
                ))
            } }
            assertTrue(error.message.orEmpty().contains("NH") && error.message.orEmpty().contains("tag 1"))
            assertEquals(listOf("/api/v2/tags/ids"), ctx.requests.map { it.url.encodedPath }, body)
        }
        for (key in listOf("tag:", "tag:no-id", "tag:0", "tag:-1", "tag:1&ids=2")) {
            val ctx = NhOfflineContext()
            val parser = NhentaiParser(ctx)
            assertThrows(ParseException::class.java) { runBlocking {
                parser.getListPage(1, SortOrder.NEWEST,
                    ContentListFilter(tags = setOf(ContentTag("Sample Tag", key, parser.source))))
            } }
            assertTrue(ctx.requests.isEmpty(), key)
        }
    }

    @Test
    fun `tag metadata failures propagate without guessed path fallback`() = runBlocking {
        for (status in listOf(403, 404, 429, 500)) {
            val ctx = NhOfflineContext().also { it.status = status }
            val parser = NhentaiParser(ctx)
            assertThrows(IOException::class.java) { runBlocking {
                parser.getListPage(1, SortOrder.NEWEST, ContentListFilter(
                    tags = setOf(ContentTag("Sample Tag", "tag:1", parser.source)),
                ))
            } }
            assertEquals(listOf("/api/v2/tags/ids"), ctx.requests.map { it.url.encodedPath })
        }
    }

    @Test
    fun `tag lookup and list keep operation domain snapshot and referer`() = runBlocking {
        val ctx = NhOfflineContext()
        val parser = NhentaiParser(ctx).also { ctx.parser = it }
        ctx.onRequest = { request ->
            if (request.url.encodedPath == "/api/v2/tags/ids") ctx.config.set(parser.configKeyDomain, "new.nhentai.net")
        }
        val filter = ContentListFilter(tags = setOf(ContentTag("Sample Tag", "tag:1", parser.source)))
        val card = parser.getListPage(2, SortOrder.NEWEST, filter).single()
        assertEquals(2, ctx.requests.size)
        assertTrue(ctx.requests.all { it.url.host == "nhentai.net" && it.header("Referer") == "https://nhentai.net/" })
        assertEquals("https://nhentai.net/g/42", card.publicUrl)
        ctx.requests.clear()
        parser.getListPage(2, SortOrder.NEWEST, filter)
        assertTrue(ctx.requests.all { it.url.host == "new.nhentai.net" &&
            it.header("Referer") == "https://new.nhentai.net/" })
    }

    @Test
    fun `tag metadata parsing closes responses on malformed JSON and cancellation`() = runBlocking {
        supervisorScope {
            for (cancel in listOf(false, true)) {
                val ctx = NhOfflineContext()
                val parser = NhentaiParser(ctx)
                val filter = ContentListFilter(tags = setOf(ContentTag("Sample Tag", "tag:1", parser.source)))
                var closed = false
                lateinit var pending: Deferred<List<Content>>
                ctx.bodyTransform = { _, body ->
                    object : okhttp3.ResponseBody() {
                        private val buffer = object : okio.ForwardingSource(
                            okio.Buffer().writeUtf8(if (cancel) body else "invalid JSON"),
                        ) {
                            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                                val result = super.read(sink, byteCount)
                                if (cancel) pending.cancel()
                                return result
                            }
                            override fun close() { closed = true; super.close() }
                        }.buffer()
                        override fun contentType(): okhttp3.MediaType? = null
                        override fun contentLength(): Long = -1L
                        override fun source(): okio.BufferedSource = buffer
                    }
                }
                if (cancel) {
                    pending = async(start = CoroutineStart.LAZY) { parser.getListPage(1, SortOrder.NEWEST, filter) }
                    pending.start()
                    try { pending.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
                } else {
                    val error = assertThrows(ParseException::class.java) { runBlocking {
                        parser.getListPage(1, SortOrder.NEWEST, filter)
                    } }
                    assertTrue(error.message.orEmpty().contains("tag 1") && error.message.orEmpty().contains("JSON"))
                }
                assertTrue(closed)
                assertEquals(listOf("/api/v2/tags/ids"), ctx.requests.map { it.url.encodedPath })
            }
        }
    }

    private fun listDocument(attrs: String) = Jsoup.parse(
        "<a class='gallery' href='/g/42/'><img $attrs><div class='caption'>Fixture</div></a>",
        "https://nhentai.net/",
    )

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/nhentai/$name")).readText()
}

/** Synthetic terminal interceptor: no network, local cookies or authentication state. */
private class NhOfflineContext : ContentLoaderContext() {
    val config = SourceConfigMock()
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    var gallery = requireNotNull(javaClass.getResource("/fixtures/nhentai/gallery-v2.json")).readText()
    var cdn = requireNotNull(javaClass.getResource("/fixtures/nhentai/cdn.json")).readText()
    var tagMetadata = """[{"id":1,"type":"tag","url":"/tag/sample-tag/"}]"""
    var tagListPath = "/tag/sample-tag/"
    var status = 200
    var failure: IOException? = null
    var onRequest: ((Request) -> Unit)? = null
    var bodyTransform: ((Request, String) -> okhttp3.ResponseBody)? = null
    var parser: NhentaiParser? = null
    override val cookieJar: CookieJar = CookieJar.NO_COOKIES
    override val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor { chain -> parser?.intercept(chain) ?: chain.proceed(chain.request()) }
        .addInterceptor { chain ->
            val request = chain.request()
            requests += request
            onRequest?.invoke(request)
            failure?.let { throw it }
            val body = when (request.url.encodedPath) {
                "/api/v2/galleries/42" -> gallery
                "/api/v2/cdn" -> cdn
                "/api/v2/tags/ids" -> tagMetadata
                tagListPath -> "<a class='gallery' href='/g/42/'><img src='/a.jpg'><div class='caption'>Fixture</div></a>"
                "/tag/abigail-williams/" -> "Tag not found"
                "/", "/search/", "/language/chinese/", "/tag/sample-tag/" ->
                    "<a class='gallery' href='/g/42/'><img src='/a.jpg'><div class='caption'>Fixture</div></a>"
                else -> error("Unexpected offline NH request: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (request.url.encodedPath == "/tag/abigail-williams/") 404 else status)
                .message("Fixture").body(bodyTransform?.invoke(request, body) ?: body.toResponseBody()).build()
        }.build()
    override fun getConfig(source: ContentSource): ContentSourceConfig = config
    override fun getDefaultUserAgent(): String = "NH offline"
    override fun newParserInstance(source: ContentSource): ContentParser = error("Unused")
    override fun newLinkResolver(link: HttpUrl): LinkResolver = error("Unused")
    @Deprecated("Provide a base url")
    override suspend fun evaluateJs(script: String): String? = error("Unused")
    override suspend fun evaluateJs(baseUrl: String, script: String): String? = error("Unused")
    override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = error("Unused")
    override fun createBitmap(width: Int, height: Int): Bitmap = error("Unused")
}

class NhentaiCdnCacheTest {
    private fun value(host: String) = NhentaiCdnConfig(
        "https://i.$host/".toHttpUrl(), "https://t.$host/".toHttpUrl(),
    )

    @Test
    fun `stable domain concurrent initialization merges and expiry starts at validation`() = runTest {
        var now = 0L
        var count = 0
        val cache = NhentaiCdnCache { now }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val load: suspend () -> NhentaiCdnConfig = {
            count++; entered.complete(Unit); release.await()
            now = 100L
            value("a.test")
        }
        val first = async { cache.get("a", { "a" }, load) }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { cache.get("a", { "a" }, load) }
        release.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, count)
        now = 600_000_000_099L
        cache.get("a", { "a" }) { count++; value("b.test") }
        assertEquals(1, count)
        now++
        cache.get("a", { "a" }) { count++; value("b.test") }
        assertEquals(2, count)
    }

    @Test
    fun `queued old operations may each load and cannot replace new domain cache`() = runTest {
        var domain = "a"
        val cache = NhentaiCdnCache { 0L }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loads = mutableListOf<String>()
        val a1 = async {
            cache.get("a", { domain }) {
                loads += "a1"; entered.complete(Unit); release.await(); value("a.test")
            }
        }
        entered.await()
        val a2 = async(start = CoroutineStart.UNDISPATCHED) {
            cache.get("a", { domain }) { loads += "a2"; value("a.test") }
        }
        val a3 = async(start = CoroutineStart.UNDISPATCHED) {
            cache.get("a", { domain }) { loads += "a3"; value("a.test") }
        }
        domain = "b"
        val b1 = async(start = CoroutineStart.UNDISPATCHED) {
            cache.get("b", { domain }) { loads += "b1"; value("b.test") }
        }
        release.complete(Unit)
        assertEquals(value("a.test"), a1.await())
        a2.await(); a3.await()
        assertEquals(value("b.test"), b1.await())
        assertEquals(listOf("a1", "a2", "a3", "b1"), loads)
        assertEquals(value("a.test"), cache.get("a", { domain }) { loads += "late-a"; value("a.test") })
        assertEquals(value("b.test"), cache.get("b", { domain }) { error("Must retain B cache") })
    }

    @Test
    fun `cancelled waiter does not cancel holder and cancellation before publish frees lock`() = runTest {
        val cache = NhentaiCdnCache { 0L }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async {
            cache.get("a", { "a" }) { entered.complete(Unit); release.await(); value("a.test") }
        }
        entered.await()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            cache.get("a", { "a" }) { error("Waiter must not load") }
        }
        waiter.cancelAndJoin()
        release.complete(Unit)
        assertEquals(value("a.test"), holder.await())
        val cancelled = async {
            cache.get("b", { "b" }) {
                currentCoroutineContext().cancel()
                value("b.test")
            }
        }
        try { cancelled.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(value("c.test"), cache.get("b", { "b" }) { value("c.test") })
    }

    @Test
    fun `cancellation after synchronous commit leaves valid cache`() = runTest {
        val cache = NhentaiCdnCache { 0L }
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            cache.get("a", { "a" }) { value("a.test") }
            committed.complete(Unit)
            release.await()
        }
        committed.await()
        job.cancelAndJoin()
        assertEquals(value("a.test"), cache.get("a", { "a" }) { error("Valid cache was lost") })
    }

    @Test
    fun `failed refresh does not serve stale value and subsequent operation can retry`() = runTest {
        var now = 0L
        val cache = NhentaiCdnCache { now }
        cache.get("a", { "a" }) { value("a.test") }
        now = 600_000_000_000L
        val e = IOException("refresh failed")
        try { cache.get("a", { "a" }) { throw e }; fail("Expected failure") }
        catch (actual: IOException) { assertSame(e, actual) }
        assertEquals(value("b.test"), cache.get("a", { "a" }) { value("b.test") })
    }
}
