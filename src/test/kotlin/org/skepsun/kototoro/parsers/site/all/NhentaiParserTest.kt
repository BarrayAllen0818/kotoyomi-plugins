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
import org.json.JSONArray
import okio.buffer
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.newParser
import org.skepsun.kototoro.parsers.SourceConfigMock
import org.skepsun.kototoro.parsers.bitmap.Bitmap
import org.skepsun.kototoro.parsers.config.ContentSourceConfig
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.LinkResolver
import org.skepsun.kototoro.parsers.util.generateUid

class NhentaiParserTest {
    @Test
    fun `429 headers pause a new factory instance and cancellation sends no request`() = runBlocking {
        val ctx = NhOfflineContext().apply {
            status = 429
            responseHeaders = okhttp3.Headers.headersOf("Retry-After", "1")
        }
        ctx.parser = NhentaiParser(ctx)
        val first = ContentParserSource.NHENTAI.newParser(ctx)
        val filter = ContentListFilter(query = "fixture")
        val error = assertThrows(org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions::class.java) {
            runBlocking { first.getList(0, SortOrder.NEWEST, filter) }
        }
        assertTrue(error.getRetryDelay() in 1..1000)
        assertTrue(ctx.toasts.isEmpty())
        ctx.status = 200
        val second = ContentParserSource.NHENTAI.newParser(ctx)
        assertNull(withTimeoutOrNull(100) { second.getList(0, SortOrder.NEWEST, filter) })
        assertEquals(1, ctx.requests.size)
        assertEquals(1, ctx.toasts.size)
        assertEquals("NH 请求正在等待，约剩 1 秒", ctx.toasts.single())
        delay(1000)
        assertTrue(second.getList(0, SortOrder.NEWEST, filter).isNotEmpty())
        assertEquals(2, ctx.requests.count { it.url.encodedPath == "/api/v2/search" })
        assertEquals(1, ctx.toasts.size)
    }

    @Test
    fun `related works from the public factory use one related request without title searches`() = runBlocking {
        val ctx = NhOfflineContext()
        val parser = ContentParserSource.NHENTAI.newParser(ctx)
        val seed = NhentaiParser(ctx).parseGalleryList(listDocument("src='/cover.jpg'")).single().copy(
            title = (1..45).joinToString(" ") { "word$it" }, altTitles = setOf("another title"),
        )
        ctx.related = """{"result":[
            {"id":42,"english_title":"Seed","thumbnail":"https://thumb.test/42.jpg"},
            {"id":43,"english_title":"Related","thumbnail":"https://thumb.test/43.jpg"},
            {"id":43,"english_title":"Related","thumbnail":"https://thumb.test/43.jpg"}
        ]}"""

        val items = parser.getRelatedContent(seed)
        assertEquals(listOf("43"), items.map { it.url })
        assertEquals("Related", items.single().title)
        assertEquals("https://thumb.test/43.jpg", items.single().coverUrl)
        assertEquals(listOf("/api/v2/galleries/42/related"), ctx.requests.map { it.url.encodedPath })
    }

    @Test
    fun `related works only return empty for a successful empty result and propagate errors`() = runBlocking {
        for (body in listOf("{\"result\":[]}", "{}", "broken", "{\"result\":null}",
            "{\"error\":\"failed\",\"result\":[]}")) {
            val ctx = NhOfflineContext().apply { related = body }
            val parser = ContentParserSource.NHENTAI.newParser(ctx)
            val seed = NhentaiParser(ctx).parseGalleryList(listDocument("src='/cover.jpg'")).single()
            if (body == "{\"result\":[]}") assertTrue(parser.getRelatedContent(seed).isEmpty())
            else assertThrows(ParseException::class.java) { runBlocking { parser.getRelatedContent(seed) } }
            assertEquals(listOf("/api/v2/galleries/42/related"), ctx.requests.map { it.url.encodedPath })
        }
        val ctx = NhOfflineContext().apply { status = 429 }
        val parser = ContentParserSource.NHENTAI.newParser(ctx)
        val seed = NhentaiParser(ctx).parseGalleryList(listDocument("src='/cover.jpg'")).single()
        assertThrows(IOException::class.java) { runBlocking { parser.getRelatedContent(seed) } }
        assertEquals(1, ctx.requests.size)
    }

    @Test
    fun `login headers and authenticated refresh requests use the host browser identity`() = runBlocking {
        val ctx = NhAuthContext()
        val parser = NhentaiParser(ctx).also { ctx.parser = it }
        val browserUserAgent = ctx.getDefaultUserAgent()
        assertEquals(browserUserAgent, parser.getRequestHeaders()["User-Agent"])
        ctx.setAccess("expired")
        ctx.setRefresh()
        ctx.respond = { request -> when {
            request.url.encodedPath == "/auth/refresh" -> {
                ctx.setAccess("replacement"); 200 to "{}"
            }
            request.header("Authorization") == "User expired" -> 401 to "{}"
            else -> 200 to "{\"username\":\"Fixture User\"}"
        } }

        assertEquals("Fixture User", requireNotNull(parser.authorizationProvider).getUsername())
        assertEquals(listOf("/api/v2/user", "/auth/refresh", "/api/v2/user"),
            ctx.requests.map { it.url.encodedPath })
        assertTrue(ctx.requests.all { it.header("User-Agent") == browserUserAgent })
        assertEquals("User replacement", ctx.requests.last().header("Authorization"))
    }

    @Test
    fun `web login exposes verified account and anonymous browsing remains available`() = runBlocking {
        val ctx = NhAuthContext()
        ctx.parser = NhentaiParser(ctx)
        val parser = ContentParserSource.NHENTAI.newParser(ctx)
        val auth = requireNotNull(parser.authorizationProvider)
        assertEquals("https://nhentai.net/login", auth.authUrl)
        assertFalse(auth.isAuthorized())
        assertTrue(ctx.requests.isEmpty())
        parser.getList(0, SortOrder.NEWEST, ContentListFilter(query = "fixture"))
        assertNull(ctx.requests.single().header("Authorization"))
        ctx.setAccess("fixture-access")
        assertTrue(auth.isAuthorized())
        assertEquals("Fixture User", auth.getUsername())
        assertEquals("User fixture-access", ctx.requests.last().header("Authorization"))
    }

    @Test
    fun `cookie credential only authenticates same origin API and reads latest browser login`() {
        val ctx = NhAuthContext()
        ctx.setAccess("first-access")
        val parser = NhentaiParser(ctx).also { ctx.parser = it }
        for ((url, expected) in listOf(
            "https://nhentai.net/api/v2/search" to "User first-access",
            "https://thumb.test/api/v2/search" to null,
            "https://nhentai.net:8443/api/v2/search" to null,
            "http://nhentai.net/api/v2/search" to null,
            "https://nhentai.net/login" to null,
            "https://t1.nhentai.net/galleries/1/thumb.jpg" to null,
        )) {
            ctx.httpClient.newCall(Request.Builder().url(url).build()).execute().close()
            assertEquals(expected, ctx.requests.last().header("Authorization"), url)
        }
        ctx.setAccess("new-browser-access")
        ctx.httpClient.newCall(Request.Builder().url("https://nhentai.net/api/v2/user").build()).execute().close()
        assertEquals("User new-browser-access", ctx.requests.last().header("Authorization"))
        assertNull(parser.getRequestHeaders()["Authorization"])
    }

    @Test
    fun `expired web cookie refreshes once and retries original request with replacement`() = runBlocking {
        val ctx = NhAuthContext()
        ctx.setAccess("expired")
        ctx.setRefresh()
        val parser = NhentaiParser(ctx).also { ctx.parser = it }
        ctx.respond = { request -> when {
            request.url.encodedPath == "/auth/refresh" -> {
                ctx.setAccess("replacement"); 200 to "{}"
            }
            request.header("Authorization") == "User expired" -> 401 to "{}"
            else -> 200 to "{\"result\":[],\"num_pages\":0}"
        } }
        parser.getListPage(1, SortOrder.NEWEST, ContentListFilter(query = "fixture"))
        assertEquals(listOf("/api/v2/search", "/auth/refresh", "/api/v2/search"),
            ctx.requests.map { it.url.encodedPath })
        assertEquals("POST", ctx.requests[1].method)
        assertNull(ctx.requests[1].header("Authorization"))
        assertEquals("User replacement", ctx.requests.last().header("Authorization"))
    }

    @Test
    fun `rejected refresh asks for login without repeatedly refreshing the same session`() = runBlocking {
        val ctx = NhAuthContext()
        ctx.setAccess("expired"); ctx.setRefresh()
        val parser = NhentaiParser(ctx).also { ctx.parser = it }
        ctx.respond = { 401 to "{\"error\":\"Expired\"}" }
        repeat(2) {
            assertThrows(org.skepsun.kototoro.parsers.exception.AuthRequiredException::class.java) { runBlocking {
                parser.getListPage(1, SortOrder.NEWEST, ContentListFilter(query = "fixture"))
            } }
        }
        assertEquals(1, ctx.requests.count { it.url.encodedPath == "/auth/refresh" })
        ctx.setAccess("fresh-login")
        ctx.respond = { 200 to "{\"result\":[],\"num_pages\":0}" }
        parser.getListPage(1, SortOrder.NEWEST, ContentListFilter(query = "fixture"))
        assertEquals("User fresh-login", ctx.requests.last().header("Authorization"))
    }

    @Test
    fun `two parser instances share one refresh for concurrent expired requests`() = runBlocking {
        val ctx = NhAuthContext()
        ctx.setAccess("expired"); ctx.setRefresh()
        val first = NhentaiParser(ctx)
        val second = NhentaiParser(ctx)
        val arrived = java.util.concurrent.CountDownLatch(2)
        val refreshes = java.util.concurrent.atomic.AtomicInteger()
        ctx.respond = { request -> when {
            request.url.encodedPath == "/auth/refresh" -> {
                refreshes.incrementAndGet(); ctx.setAccess("replacement"); 200 to "{}"
            }
            request.header("Authorization") == "User expired" -> {
                arrived.countDown()
                check(arrived.await(5, java.util.concurrent.TimeUnit.SECONDS))
                401 to "{}"
            }
            else -> 200 to "{}"
        } }
        val request = Request.Builder().url("https://nhentai.net/api/v2/user").build()
        val clients = listOf(first, second).map { parser ->
            OkHttpClient.Builder().addInterceptor(parser).addInterceptor(ctx.terminal).build()
        }
        clients.map { client -> async(Dispatchers.IO) {
            client.newCall(request).execute().use { assertEquals(200, it.code) }
        } }.awaitAll()
        assertEquals(1, refreshes.get())
        assertEquals(2, ctx.requests.count { it.header("Authorization") == "User replacement" })
    }

    @Test
    fun `missing access cookie can renew from browser refresh session`() = runBlocking {
        val ctx = NhAuthContext()
        ctx.setRefresh()
        val parser = NhentaiParser(ctx).also { ctx.parser = it }
        ctx.respond = { request ->
            if (request.url.encodedPath == "/auth/refresh") {
                ctx.setAccess("renewed"); 200 to "{}"
            } else 200 to "{\"username\":\"Renewed User\"}"
        }
        assertEquals("Renewed User", parser.getUsername())
        assertEquals(listOf("/auth/refresh", "/api/v2/user"), ctx.requests.map { it.url.encodedPath })
        assertEquals("User renewed", ctx.requests.last().header("Authorization"))
    }

    @Test
    fun `second unauthorized response requests login and never loops renewal`() = runBlocking {
        val ctx = NhAuthContext()
        ctx.setAccess("expired"); ctx.setRefresh()
        val parser = NhentaiParser(ctx).also { ctx.parser = it }
        ctx.respond = { request ->
            if (request.url.encodedPath == "/auth/refresh") {
                ctx.setAccess("still-invalid"); 200 to "{}"
            } else 401 to "{}"
        }
        assertFalse(parser.isAuthorized())
        assertEquals(3, ctx.requests.size)
        assertFalse(parser.isAuthorized())
        assertEquals(3, ctx.requests.size)
    }

    @Test
    fun `refresh transport failure is retriable and retains server errors`() = runBlocking {
        val ctx = NhAuthContext()
        ctx.setAccess("expired"); ctx.setRefresh()
        val parser = NhentaiParser(ctx).also { ctx.parser = it }
        ctx.responseHeaders = okhttp3.Headers.headersOf("Retry-After", "0")
        for (status in listOf(429, 503)) {
            ctx.respond = { request ->
                (if (request.url.encodedPath == "/auth/refresh") status else 401) to "{}"
            }
            ctx.httpClient.newCall(Request.Builder().url("https://nhentai.net/api/v2/user").build())
                .execute().use { assertEquals(status, it.code) }
        }
        ctx.respond = { request ->
            if (request.url.encodedPath == "/auth/refresh") {
                ctx.setAccess("recovered"); 200 to "{}"
            } else if (request.header("Authorization") == "User expired") 401 to "{}"
            else 200 to "{\"username\":\"Recovered\"}"
        }
        assertEquals("Recovered", parser.getUsername())
    }

    @Test
    fun `browser login replacing a rejected refresh remains authenticated`() = runBlocking {
        val ctx = NhAuthContext()
        ctx.setAccess("expired"); ctx.setRefresh()
        val parser = NhentaiParser(ctx).also { ctx.parser = it }
        ctx.respond = { request -> when {
            request.url.encodedPath == "/auth/refresh" -> {
                ctx.setAccess("browser-login"); 401 to "{}"
            }
            request.header("Authorization") == "User expired" -> 401 to "{}"
            else -> 200 to "{\"username\":\"Browser User\"}"
        } }
        assertEquals("Browser User", parser.getUsername())
        assertTrue(parser.isAuthorized())
        assertEquals(1, ctx.requests.count { it.url.encodedPath == "/auth/refresh" })
    }

    @Test
    fun `alternative tag queries merge newest results without duplicates and reject incomparable sorting before requests`() = runBlocking {
        val ctx = NhOfflineContext()
        ctx.onRequest = { request ->
            if (request.url.encodedPath == "/api/v2/search") {
                val ids = if (request.url.queryParameter("query")!!.contains("Alpha")) listOf(9, 7) else listOf(9, 8)
                ctx.search = JSONObject().put("num_pages", 1).put("result", JSONArray(ids.map { id ->
                    JSONObject().put("id", id).put("english_title", "Fixture")
                        .put("thumbnail", "https://thumb.test/$id.jpg").put("tag_ids", JSONArray())
                })).toString()
            }
        }
        val parser = org.skepsun.kototoro.parsers.model.ContentParserSource.NHENTAI.newParser(ctx)
            as org.skepsun.kototoro.parsers.core.ContentParserWrapper
        val expression = """{"type":"any","children":[{"type":"term","field":"tag","value":"Alpha"},{"type":"term","field":"tag","value":"Beta"}]}"""
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            parser.getCombinedSearchPageJson(0, "POPULARITY", "", expression) { true }
        } }
        assertTrue(ctx.requests.isEmpty())
        assertEquals(listOf("9", "8", "7"), parser.getCombinedSearchPageJson(0, "NEWEST", "", expression) { true }.map { it.url })
        assertEquals(setOf("tag:\"Alpha\"", "tag:\"Beta\""), ctx.requests.map { it.url.queryParameter("query") }.toSet())
    }

    @Test
    fun `factory combined search retains complete list facts and skips filtered pages without gallery requests`() = runBlocking {
        val ctx = NhOfflineContext()
        ctx.tagMetadata = """[{"id":29963,"name":"chinese","type":"language"},{"id":2,"name":"full color","type":"tag"}]"""
        ctx.onRequest = { request ->
            if (request.url.encodedPath == "/api/v2/search") {
                val page = request.url.queryParameter("page")!!.toInt()
                ctx.search = """{"result":[{"id":${43 - page},"english_title":"Full color Fixture","japanese_title":"別名","thumbnail":"https://thumb.test/a.jpg","num_pages":42,"tag_ids":[29963,2]}],"num_pages":2}"""
            }
        }
        val parser = org.skepsun.kototoro.parsers.model.ContentParserSource.NHENTAI.newParser(ctx)
            as org.skepsun.kototoro.parsers.core.ContentParserWrapper
        val expression = """{"type":"all","children":[{"type":"term","field":"language","value":"chinese"},{"type":"term","field":"tag","value":"full color"},{"type":"not","child":{"type":"term","field":"tag","value":"netorare"}}]}"""
        val result = parser.getCombinedSearchPageJson(0, "NEWEST", "", expression) { it.url == "41" }
        assertEquals(listOf("41"), result.map { it.url })
        assertEquals(setOf("chinese", "full color"), result.single().tags.map { it.title }.toSet())
        assertEquals(setOf("別名"), result.single().altTitles)
        assertEquals(42, JSONObject(parser.getDetailsMetadata(result.single())!!).getInt("total_pages"))
        assertEquals(2, ctx.requests.count { it.url.encodedPath == "/api/v2/search" })
        assertEquals(1, ctx.requests.count { it.url.encodedPath == "/api/v2/tags/ids" })
        assertTrue(ctx.requests.none { it.url.encodedPath.contains("galleries/") })
        assertEquals("language:\"chinese\" tag:\"full color\" -tag:\"netorare\"",
            ctx.requests.first().url.queryParameter("query"))
    }

    @Test
    fun `missing batch tag metadata is a real failure instead of accepting unknown exclusions`() = runBlocking {
        val ctx = NhOfflineContext()
        ctx.search = """{"result":[{"id":42,"english_title":"Fixture","thumbnail":"https://thumb.test/a.jpg","tag_ids":[2]}],"num_pages":1}"""
        ctx.tagMetadata = "[]"
        val parser = org.skepsun.kototoro.parsers.model.ContentParserSource.NHENTAI.newParser(ctx)
            as org.skepsun.kototoro.parsers.core.ContentParserWrapper
        assertThrows(ParseException::class.java) { runBlocking {
            parser.getCombinedSearchPageJson(0, "NEWEST", "", """{"type":"term","field":"tag","value":"A"}""") { true }
        } }
    }
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
        ctx.gallery = JSONObject(ctx.gallery).apply {
            getJSONArray("tags").put(JSONObject("""{"id":5,"name":"Fixture group","type":"group"}"""))
                .put(JSONObject("""{"id":6,"name":"doujinshi","type":"category"}"""))
        }.toString()
        val parser = NhentaiParser(ctx).also { ctx.parser = it }
        val original = parser.parseGalleryList(listDocument("src='/a.jpg'")).single()
        val factoryParser = org.skepsun.kototoro.parsers.model.ContentParserSource.NHENTAI.newParser(ctx)
        val details = factoryParser.getDetails(original)
        assertEquals(original.id, details.id)
        assertEquals(original.url, details.url)
        assertEquals(original.publicUrl, details.publicUrl)
        assertEquals("Fixture title", details.title)
        assertEquals(setOf("Fixture artist"), details.authors)
        val metadata = org.json.JSONObject(factoryParser.javaClass.getMethod("getDetailsMetadata", Content::class.java).invoke(factoryParser, details) as String)
        assertEquals(2, metadata.getInt("total_pages"))
        assertEquals(setOf("Fixture alternate"), details.altTitles)
        assertEquals(listOf("Fixture group"), metadata.getJSONArray("groups").toList())
        assertEquals(listOf("doujinshi"), metadata.getJSONArray("types").toList())
        assertEquals(listOf("chinese"), metadata.getJSONArray("languages").toList())
        assertTrue(!metadata.has("chapter_count"))
        assertEquals(listOf("Fixture original"), metadata.getJSONArray("parodies").toList())
        assertEquals(listOf("Fixture character"), metadata.getJSONArray("characters").toList())
        assertEquals("chinese / translated", details.description)
        assertEquals("https://t1.nhentai.net/galleries/42/cover.webp.webp", details.coverUrl)
        val chapter = details.chapters!!.single()
        assertEquals(parser.generateUid("${original.id}-0"), chapter.id)
        val pages = factoryParser.getPages(chapter)
        assertEquals(listOf("https://i1.nhentai.net/galleries/42/1.jpg",
            "https://i1.nhentai.net/galleries/42/2.webp.webp"), pages.map { it.url })
        assertEquals(parser.generateUid("galleries/42/1.jpg"), pages[0].id)
        assertEquals(pages[0].url, factoryParser.getPageUrl(pages[0]))
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
            ContentListFilter(query = "sample query") to "/api/v2/search",
            ContentListFilter(tags = setOf(ContentTag("中文", "language:chinese", parser.source))) to "/language/chinese/",
            ContentListFilter(tags = setOf(ContentTag("Sample Tag", "tag:1", parser.source))) to "/tag/sample-tag/",
        )
        for (order in parser.availableSortOrders) {
            for ((filter, path) in filters) {
                parser.getListPage(2, order, filter)
                val url = ctx.requests.last { it.url.encodedPath != "/api/v2/cdn" }.url
                val expectedPath = if (path == "/" && order != SortOrder.NEWEST) "/search/" else path
                assertEquals(expectedPath, url.encodedPath)
                assertEquals("2", url.queryParameter("page"))
                assertEquals(when (order) {
                    SortOrder.POPULARITY_TODAY -> "popular-today"
                    SortOrder.POPULARITY_WEEK -> "popular-week"
                    SortOrder.POPULARITY_MONTH -> "popular-month"
                    SortOrder.POPULARITY -> "popular"
                    else -> if (filter.query != null) "date" else null
                }, url.queryParameter("sort"))
            }
        }
    }

    @Test
    fun `keyword search uses v2 data and retains identities titles thumbnails and languages`() = runBlocking {
        val ctx = NhOfflineContext()
        val parser = NhentaiParser(ctx).also { ctx.parser = it }
        val filter = ContentListFilter(
            query = "全彩 +&猫",
            tags = setOf(ContentTag("中文", "language:chinese", parser.source)),
        )
        val items = parser.getList(0, SortOrder.NEWEST, filter)
        val request = ctx.requests.first()
        assertEquals("/api/v2/search", request.url.encodedPath)
        assertEquals("全彩 +&猫", request.url.queryParameter("query"))
        assertEquals("1", request.url.queryParameter("page"))
        assertEquals("date", request.url.queryParameter("sort"))
        assertEquals("https://nhentai.net/", request.header("Referer"))
        assertEquals(listOf("42", "43", "44"), items.map { it.url })
        assertEquals(listOf("42", "43", "44").map { parser.generateUid(it) }, items.map { it.id })
        assertEquals(listOf("全彩 Fixture", "日本語 Fixture", "English Fixture"), items.map { it.title })
        assertEquals(listOf("中文", "日本語", "English"), items.map { it.description })
        assertEquals("https://t1.nhentai.net/galleries/42/thumb.webp", items[0].coverUrl)
        assertEquals("https://thumb.test/43.webp", items[1].coverUrl)
        assertTrue(items.all { it.publicUrl == "https://nhentai.net/g/${it.url}" && it.source == parser.source })
        assertEquals(listOf("/api/v2/search", "/api/v2/cdn"), ctx.requests.map { it.url.encodedPath })
        parser.getList(items.size, SortOrder.NEWEST, filter)
        assertEquals("2", ctx.requests.last().url.queryParameter("page"))
    }

    @Test
    fun `successful empty search avoids CDN but broken responses never become empty results`() = runBlocking {
        val ctx = NhOfflineContext()
        val parser = NhentaiParser(ctx)
        val filter = ContentListFilter(query = "全彩")
        ctx.search = """{"result":[],"num_pages":0,"per_page":25,"total":0}"""
        assertTrue(parser.getListPage(1, SortOrder.NEWEST, filter).isEmpty())
        assertEquals(listOf("/api/v2/search"), ctx.requests.map { it.url.encodedPath })
        for (body in listOf("invalid JSON", "{}", """{"result":null}""", """{"result":{}}""",
            """{"error":"Search failed","result":[]}""")) {
            ctx.search = body
            val error = assertThrows(ParseException::class.java) { runBlocking {
                parser.getListPage(1, SortOrder.NEWEST, filter)
            } }
            assertTrue(error.message.orEmpty().contains("NH search"))
            assertEquals("/api/v2/search", ctx.requests.last().url.encodedPath)
        }
        assertTrue(ctx.requests.none { it.url.encodedPath != "/api/v2/search" })
    }

    @Test
    fun `malformed search entries fail rather than silently dropping works`() = runBlocking {
        val base = """{"id":42,"english_title":"Fixture","thumbnail":"https://thumb.test/42.webp","tag_ids":[]}"""
        val variants = listOf(
            JSONObject(base).put("id", 0),
            JSONObject(base).put("id", 1.5),
            JSONObject(base).put("id", "42x"),
            JSONObject(base).put("english_title", JSONObject.NULL),
            JSONObject(base).put("thumbnail", ""),
            JSONObject(base).put("thumbnail", "http://thumb.test/42.webp"),
        ).map { it.toString() } + listOf("null", "42")
        for (entry in variants) {
            val ctx = NhOfflineContext().apply { search = """{"result":[$entry]}""" }
            val parser = NhentaiParser(ctx)
            val error = assertThrows(ParseException::class.java) { runBlocking {
                parser.getListPage(1, SortOrder.NEWEST, ContentListFilter(query = "全彩"))
            } }
            assertTrue(error.message.orEmpty().contains("NH search"))
            assertEquals(listOf("/api/v2/search"), ctx.requests.map { it.url.encodedPath })
        }
    }

    @Test
    fun `valid empty titles use the gallery number like the website`() = runBlocking {
        val ctx = NhOfflineContext().apply {
            search = """
                {"result":[{"id":42,"english_title":"","japanese_title":null,
                "thumbnail":"https://thumb.test/42.webp","tag_ids":[]}]}
            """.trimIndent()
        }
        val parser = NhentaiParser(ctx)
        val item = parser.getListPage(1, SortOrder.NEWEST, ContentListFilter(query = "全彩")).single()
        assertEquals("#42", item.title)
        assertEquals("https://thumb.test/42.webp", item.coverUrl)
        assertEquals("", item.description)
        assertEquals(listOf("/api/v2/search"), ctx.requests.map { it.url.encodedPath })
    }

    @Test
    fun `search network and HTTP failures propagate without fallback or retry`() = runBlocking {
        for (status in listOf(403, 429, 500)) {
            val ctx = NhOfflineContext().apply { this.status = status }
            val parser = NhentaiParser(ctx)
            assertThrows(IOException::class.java) { runBlocking {
                parser.getListPage(1, SortOrder.NEWEST, ContentListFilter(query = "全彩"))
            } }
            assertEquals(listOf("/api/v2/search"), ctx.requests.map { it.url.encodedPath })
        }
        val ctx = NhOfflineContext().apply { failure = IOException("offline") }
        val parser = NhentaiParser(ctx)
        assertThrows(IOException::class.java) { runBlocking {
            parser.getListPage(1, SortOrder.NEWEST, ContentListFilter(query = "全彩"))
        } }
        assertEquals(listOf("/api/v2/search"), ctx.requests.map { it.url.encodedPath })
    }

    @Test
    fun `search and thumbnail configuration retain the operation domain snapshot`() = runBlocking {
        val ctx = NhOfflineContext()
        val parser = NhentaiParser(ctx).also { ctx.parser = it }
        ctx.config.set(parser.configKeyDomain, "old.test")
        ctx.onRequest = { request ->
            if (request.url.encodedPath == "/api/v2/search") ctx.config.set(parser.configKeyDomain, "new.test")
        }
        val items = parser.getListPage(1, SortOrder.NEWEST, ContentListFilter(query = "全彩"))
        assertTrue(items.all { it.publicUrl.startsWith("https://old.test/") })
        assertEquals(listOf("/api/v2/search", "/api/v2/cdn"), ctx.requests.map { it.url.encodedPath })
        assertTrue(ctx.requests.all { it.url.host == "old.test" && it.header("Referer") == "https://old.test/" })
    }

    @Test
    fun `search parsing closes responses and cancellation prevents thumbnail requests`() = runBlocking {
        supervisorScope {
            for (cancel in listOf(false, true)) {
                val ctx = NhOfflineContext()
                val parser = NhentaiParser(ctx)
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
                val filter = ContentListFilter(query = "全彩")
                if (cancel) {
                    pending = async(start = CoroutineStart.LAZY) { parser.getListPage(1, SortOrder.NEWEST, filter) }
                    pending.start()
                    try { pending.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
                } else {
                    val error = assertThrows(ParseException::class.java) { runBlocking {
                        parser.getListPage(1, SortOrder.NEWEST, filter)
                    } }
                    assertTrue(error.message.orEmpty().contains("NH search"))
                }
                assertTrue(closed)
                assertEquals(listOf("/api/v2/search"), ctx.requests.map { it.url.encodedPath })
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
private class NhAuthContext : ContentLoaderContext() {
    private val cookies = java.util.concurrent.CopyOnWriteArrayList<okhttp3.Cookie>()
    val requests = Collections.synchronizedList(mutableListOf<Request>())
    var parser: NhentaiParser? = null
    var responseHeaders = okhttp3.Headers.Builder().build()
    @Volatile var respond: (Request) -> Pair<Int, String> = { request ->
        200 to if (request.url.encodedPath == "/api/v2/user") "{\"username\":\"Fixture User\"}"
        else "{\"result\":[],\"num_pages\":0}"
    }
    fun setAccess(value: String) = setCookie("access_token", value, "/")
    fun setRefresh() = setCookie("refresh_token", "fixture-refresh", "/auth")
    private fun setCookie(name: String, value: String, path: String) {
        cookieJar.saveFromResponse("https://nhentai.net/".toHttpUrl(), listOf(okhttp3.Cookie.Builder()
            .name(name).value(value).hostOnlyDomain("nhentai.net").path(path).secure().build()))
    }
    override val cookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl) = cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
        override fun saveFromResponse(url: HttpUrl, incoming: List<okhttp3.Cookie>) {
            incoming.forEach { cookie ->
                cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                cookies.add(cookie)
            }
        }
    }
    val terminal = okhttp3.Interceptor { chain ->
        val request = chain.request(); requests.add(request)
        val (status, body) = respond(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
            .headers(responseHeaders).body(body.toResponseBody()).build()
    }
    override val httpClient = OkHttpClient.Builder().cookieJar(cookieJar)
        .addInterceptor { chain -> parser?.intercept(chain) ?: chain.proceed(chain.request()) }
        .addInterceptor(terminal).build()
    override fun getConfig(source: ContentSource) = SourceConfigMock()
    override fun getDefaultUserAgent() = "NH auth fixture"
    override fun newParserInstance(source: ContentSource): ContentParser = error("Unused")
    override fun newLinkResolver(link: HttpUrl): LinkResolver = error("Unused")
    @Deprecated("Provide a base url")
    override suspend fun evaluateJs(script: String): String? = error("Unused")
    override suspend fun evaluateJs(baseUrl: String, script: String): String? = error("Unused")
    override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = error("Unused")
    override fun createBitmap(width: Int, height: Int): Bitmap = error("Unused")
}

private class NhOfflineContext : ContentLoaderContext() {
    val toasts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    override fun showToast(message: String, isLong: Boolean) { toasts.add(message) }
    val config = SourceConfigMock()
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    var gallery = requireNotNull(javaClass.getResource("/fixtures/nhentai/gallery-v2.json")).readText()
    var cdn = requireNotNull(javaClass.getResource("/fixtures/nhentai/cdn.json")).readText()
    var search = """
        {"result":[
            {"id":42,"english_title":"全彩 Fixture","japanese_title":"日本語",
            "thumbnail":"galleries/42/thumb.webp","tag_ids":[29963]},
            {"id":43,"english_title":"","japanese_title":"日本語 Fixture",
            "thumbnail":"https://thumb.test/43.webp","tag_ids":[6346]},
            {"id":44,"english_title":"English Fixture","thumbnail":"galleries/44/thumb.jpg","tag_ids":[12227]}
        ],"num_pages":2,"per_page":25,"total":28}
    """.trimIndent()
    var tagMetadata = """[{"id":1,"type":"tag","url":"/tag/sample-tag/"}]"""
    var related = """{"result":[]}"""
    var responseHeaders = okhttp3.Headers.Builder().build()
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
                "/api/v2/search" -> search
                "/api/v2/galleries/42/related" -> related
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
                .headers(responseHeaders).message("Fixture")
                .body(bodyTransform?.invoke(request, body) ?: body.toResponseBody()).build()
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

class NhentaiRateLimiterTest {
    private fun response(url: String, code: Int = 200, retryAfter: String? = null): Response = Response.Builder()
        .request(Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
        .apply { if (retryAfter != null) header("Retry-After", retryAfter) }.body("{}".toResponseBody()).build()

    @Test
    fun `quota waiting reports decreasing remaining time without delaying dispatch`() = runTest {
        val url = "https://nhentai.net/api/v2/search"
        val limiter = NhentaiRateLimiter(now = { testScheduler.currentTime })
        val notices = mutableListOf<Pair<Long, Long>>()
        val onWait: (Long) -> Unit = { notices.add(testScheduler.currentTime to it) }
        repeat(10) { limiter.execute(url.toHttpUrl(), { false }, onWait) { response(url) }.close() }
        assertTrue(notices.isEmpty())
        limiter.execute(url.toHttpUrl(), { false }, onWait) { response(url) }.close()
        assertEquals(listOf(0L to 60_000L, 15_000L to 45_000L, 30_000L to 30_000L, 45_000L to 15_000L), notices)
        assertEquals(60_000L, testScheduler.currentTime)
    }

    @Test
    fun `concurrent endpoints and queued callers merge wait notices for one client`() = runTest {
        val limiter = NhentaiRateLimiter(now = { testScheduler.currentTime })
        val search = "https://nhentai.net/api/v2/search"
        val detail = "https://nhentai.net/api/v2/galleries/42"
        limiter.observeLimit(search.toHttpUrl(), "60")
        limiter.observeLimit(detail.toHttpUrl(), "30")
        val notices = mutableListOf<Long>()
        var sent = 0
        listOf(search, search, search, detail).map { url ->
            async {
                limiter.execute(url.toHttpUrl(), { false }, onWait = { notices.add(testScheduler.currentTime) }) {
                    sent++; response(url)
                }.close()
            }
        }.awaitAll()
        assertEquals(listOf(0L, 15_000L, 30_000L, 45_000L), notices)
        assertEquals(4, sent)
        assertEquals(60_000L, testScheduler.currentTime)
    }

    @Test
    fun `canceling a wait stops all subsequent notices and sends no request`() = runTest {
        val url = "https://nhentai.net/api/v2/search"
        val limiter = NhentaiRateLimiter(now = { testScheduler.currentTime })
        limiter.observeLimit(url.toHttpUrl(), "60")
        val notices = mutableListOf<Long>()
        var sent = 0
        val pending = launch {
            limiter.execute(url.toHttpUrl(), { false }, onWait = { notices.add(testScheduler.currentTime) }) {
                sent++; response(url)
            }.close()
        }
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(20_000)
        pending.cancelAndJoin()
        assertEquals(listOf(0L, 15_000L), notices)
        testScheduler.advanceUntilIdle()
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertEquals(listOf(0L, 15_000L), notices)
        assertEquals(0, sent)
        limiter.execute(url.toHttpUrl(), { false }, onWait = { fail("No wait remains") }) { response(url) }.close()
    }

    @Test
    fun `wait notices recompute an extended server pause and stop at the exact deadline`() = runTest {
        val url = "https://nhentai.net/api/v2/search"
        val limiter = NhentaiRateLimiter(now = { testScheduler.currentTime })
        limiter.observeLimit(url.toHttpUrl(), "35")
        val notices = mutableListOf<Pair<Long, Long>>()
        val pending = async {
            limiter.execute(url.toHttpUrl(), { false }, onWait = { notices.add(testScheduler.currentTime to it) }) {
                response(url)
            }.close()
        }
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(10_000)
        limiter.observeLimit(url.toHttpUrl(), "40")
        pending.await()
        assertEquals(listOf(0L to 35_000L, 15_000L to 35_000L, 30_000L to 20_000L, 45_000L to 5_000L), notices)
        assertEquals(50_000L, testScheduler.currentTime)
    }

    @Test
    fun `anonymous and authenticated search respect separate minute capacities under concurrency`() = runTest {
        val url = "https://nhentai.net/api/v2/search"
        for ((authenticated, capacity) in listOf(false to 10, true to 20)) {
            val limiter = NhentaiRateLimiter(now = { testScheduler.currentTime })
            val start = testScheduler.currentTime
            val sent = mutableListOf<Long>()
            (0..capacity).map {
                async { limiter.execute(url.toHttpUrl(), { authenticated }) {
                    sent.add(testScheduler.currentTime - start); response(url)
                }.close() }
            }.awaitAll()
            assertEquals(List(capacity) { 0L } + 60_000L, sent)
        }
    }

    @Test
    fun `detail and related ids share endpoint quotas while tags keep their own quota`() = runTest {
        for ((path, anonymous, authenticated) in listOf(
            Triple("galleries/{id}", 20, 45), Triple("galleries/{id}/related", 12, 30),
            Triple("tags/ids?ids={id}", 15, 15),
        )) {
            for ((signedIn, capacity) in listOf(false to anonymous, true to authenticated)) {
                val limiter = NhentaiRateLimiter(now = { testScheduler.currentTime })
                val start = testScheduler.currentTime
                val times = mutableListOf<Long>()
                repeat(capacity + 1) { index ->
                    val url = "https://nhentai.net/api/v2/" + path.replace("{id}", (index + 1).toString())
                    limiter.execute(url.toHttpUrl(), { signedIn }) {
                        times.add(testScheduler.currentTime - start); response(url)
                    }.close()
                }
                assertEquals(List(capacity) { 0L } + 60_000L, times, "$path signedIn=$signedIn")
            }
        }
    }

    @Test
    fun `quota waits only for oldest remaining request and counts from response arrival`() = runTest {
        val url = "https://nhentai.net/api/v2/search"
        val limiter = NhentaiRateLimiter(now = { testScheduler.currentTime })
        repeat(10) {
            limiter.execute(url.toHttpUrl(), { false }) { delay(100); response(url) }.close()
            delay(900)
        }
        assertEquals(10_000L, testScheduler.currentTime)
        limiter.execute(url.toHttpUrl(), { false }) { response(url) }.close()
        assertEquals(60_100L, testScheduler.currentTime)
        limiter.execute(url.toHttpUrl(), { false }) { response(url) }.close()
        assertEquals(61_100L, testScheduler.currentTime)
    }

    @Test
    fun `429 closes response waits advertised seconds and does not automatically retry`() = runTest {
        val url = "https://nhentai.net/api/v2/search"
        val limiter = NhentaiRateLimiter(now = { testScheduler.currentTime })
        var calls = 0
        var closed = false
        val body = object : okhttp3.ResponseBody() {
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength() = 0L
            override fun source(): okio.BufferedSource = okio.Buffer()
            override fun close() { closed = true }
        }
        try {
            limiter.execute(url.toHttpUrl(), { false }) {
                calls++; response(url, 429, "7").newBuilder().body(body).build()
            }
            fail<Unit>("429 was accepted")
        } catch (_: org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions) { }
        assertTrue(closed)
        assertEquals(1, calls)
        limiter.execute(url.toHttpUrl(), { false }) { calls++; response(url) }.close()
        assertEquals(7_000L, testScheduler.currentTime)
        assertEquals(2, calls)
    }

    @Test
    fun `429 HTTP dates missing and malformed delays have explicit fallback`() = runTest {
        val url = "https://nhentai.net/api/v2/search"
        val wall = java.time.Instant.parse("2026-11-17T10:00:00Z").toEpochMilli()
        val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.Instant.ofEpochMilli(wall + 9000).atZone(java.time.ZoneOffset.UTC),
        )
        for ((header, expected) in listOf(date to 9000L, null to 60_000L, "bad" to 60_000L, "-1" to 60_000L)) {
            val limiter = NhentaiRateLimiter(now = { testScheduler.currentTime }, wallTime = { wall })
            val start = testScheduler.currentTime
            try {
                limiter.execute(url.toHttpUrl(), { false }) { response(url, 429, header) }
                fail<Unit>("429 was accepted")
            } catch (_: org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions) { }
            limiter.execute(url.toHttpUrl(), { false }) { response(url) }.close()
            assertEquals(expected, testScheduler.currentTime - start, "$header")
        }
    }

    @Test
    fun `host translated 429 preserves cooldown and canceled waiter reserves nothing`() = runTest {
        val url = "https://nhentai.net/api/v2/search"
        val limiter = NhentaiRateLimiter(now = { testScheduler.currentTime })
        try {
            limiter.execute(url.toHttpUrl(), { false }) {
                limiter.observeLimit(url.toHttpUrl(), "8")
                throw org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions(url, 8000)
            }
            fail<Unit>("429 was accepted")
        } catch (_: org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions) { }
        var sent = 0
        val waiting = launch { limiter.execute(url.toHttpUrl(), { false }) { sent++; response(url) }.close() }
        testScheduler.runCurrent()
        assertEquals(0, sent)
        waiting.cancelAndJoin()
        repeat(10) { limiter.execute(url.toHttpUrl(), { false }) { sent++; response(url) }.close() }
        assertEquals(8000L, testScheduler.currentTime)
        assertEquals(10, sent)
    }

    @Test
    fun `cooldown only blocks its endpoint and origin while image and HTML remain unqueued`() = runTest {
        val limiter = NhentaiRateLimiter(now = { testScheduler.currentTime })
        limiter.observeLimit("https://nhentai.net/api/v2/search".toHttpUrl(), "60")
        for (url in listOf("https://nhentai.net/api/v2/tags/ids?ids=1",
            "https://nhentai.net/api/v2/galleries/1", "https://nhentai.net/api/v2/galleries/1/related",
            "https://nhentai.net/api/v2/cdn", "https://nhentai.net/", "https://thumb.test/cover.jpg",
            "https://other.test/api/v2/search", "https://nhentai.net:8443/api/v2/search")) {
            limiter.execute(url.toHttpUrl(), { false }) { response(url) }.close()
            assertEquals(0L, testScheduler.currentTime, url)
        }
    }

    @Test
    fun `login increases capacity and logout retains already used requests`() = runTest {
        val url = "https://nhentai.net/api/v2/search"
        val limiter = NhentaiRateLimiter(now = { testScheduler.currentTime })
        repeat(10) { limiter.execute(url.toHttpUrl(), { false }) { response(url) }.close() }
        repeat(10) { limiter.execute(url.toHttpUrl(), { true }) { response(url) }.close() }
        assertEquals(0L, testScheduler.currentTime)
        limiter.execute(url.toHttpUrl(), { false }) { response(url) }.close()
        assertEquals(60_000L, testScheduler.currentTime)
    }

    @Test
    fun `all parser instances with one network client share quota and other clients are isolated`() = runTest {
        val first = OkHttpClient()
        val url = "https://nhentai.net/api/v2/search"
        val limiter = NhentaiRateLimiter.forClient(first)
        limiter.observeLimit(url.toHttpUrl(), "60")
        var blockedSent = false
        val pending = launch {
            NhentaiRateLimiter.forClient(first).execute(url.toHttpUrl(), { false }) {
                blockedSent = true; response(url)
            }.close()
        }
        testScheduler.runCurrent()
        NhentaiRateLimiter.forClient(OkHttpClient()).execute(url.toHttpUrl(), { false }) { response(url) }.close()
        assertFalse(blockedSent)
        pending.cancelAndJoin()
    }

    @Test
    fun `canceled dispatched request still counts but canceled queued request does not`() = runTest {
        val url = "https://nhentai.net/api/v2/search"
        val limiter = NhentaiRateLimiter(now = { testScheduler.currentTime })
        val sent = launch { limiter.execute(url.toHttpUrl(), { false }) { delay(1000); response(url) }.close() }
        testScheduler.runCurrent()
        sent.cancelAndJoin()
        repeat(9) { limiter.execute(url.toHttpUrl(), { false }) { response(url) }.close() }
        assertEquals(0L, testScheduler.currentTime)
        limiter.execute(url.toHttpUrl(), { false }) { response(url) }.close()
        assertEquals(60_000L, testScheduler.currentTime)
    }

    @Test
    fun `expired cooldown does not suppress a fresh host retry delay`() = runTest {
        val url = "https://nhentai.net/api/v2/search"
        val limiter = NhentaiRateLimiter(now = { testScheduler.currentTime })
        limiter.observeLimit(url.toHttpUrl(), "0")
        try {
            limiter.execute(url.toHttpUrl(), { false }) {
                throw org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions(url, 10_000)
            }
            fail<Unit>("429 was accepted")
        } catch (_: org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions) { }
        limiter.execute(url.toHttpUrl(), { false }) { response(url) }.close()
        assertTrue(testScheduler.currentTime in 9000L..10_000L)
    }
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
