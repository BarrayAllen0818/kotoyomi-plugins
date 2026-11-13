package org.skepsun.kototoro.parsers.site.zh

import java.io.IOException
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.SourceConfigMock
import org.skepsun.kototoro.parsers.bitmap.Bitmap
import org.skepsun.kototoro.parsers.config.ContentSourceConfig
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.LinkResolver

class JmComicTest {
    @Test
    fun `details metadata uses album total photos and structured works and actors`() = runTest {
        val context = JmOfflineContext()
        context.albumBody = org.json.JSONObject(context.albumBody).apply {
            put("total", 999)
            put("total_photos", "42")
            put("works", org.json.JSONArray(listOf("原作一", "原作二")))
            put("actors", org.json.JSONArray(listOf("角色甲")))
        }.toString()
        val parser = JmParser(context)
        val card = parser.getList(0, SortOrder.NEWEST, ContentListFilter(query = "123456")).single()
        val details = parser.getDetails(card)
        val payload = org.json.JSONObject(parser.getDetailsMetadata(details)!!)
        assertEquals(42, payload.getInt("total_pages"))
        assertEquals(listOf("原作一", "原作二"), payload.getJSONArray("parodies").toList())
        assertEquals(listOf("角色甲"), payload.getJSONArray("characters").toList())
        assertNull(parser.getDetailsMetadata(card))
    }


    private val parser = JmParser(JmOfflineContext())

    @Test
    fun `cold context uses persisted successful server before querying settings`() = runTest {
        val prefs = JmTestPreferences()
        val first = JmOfflineContext(prefs)
        val parser = JmParser(first)
        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        first.imageHandler = { req -> if (req.url.host == "images.jm.invalid") throw SocketException("reset") }
        first.imageClient(parser).newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute().close()
        assertEquals(1, prefs.writes)
        repeat(3) {
            first.imageClient(parser).newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute().close()
        }
        assertEquals(1, prefs.writes)
        val cold = JmOfflineContext(prefs)
        val restarted = JmParser(cold)
        val next = restarted.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        assertEquals("https://images2.jm.invalid/media/albums/654321_3x4.jpg", next.coverUrl)
        cold.imageClient(restarted).newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute().close()
        assertTrue(cold.settingRequests.isEmpty())
        assertEquals("images2.jm.invalid", cold.imageRequests.single().url.host)
        assertEquals(1, prefs.writes)
    }

    @Test
    fun `persisted host failure recovers and replaces it for the next cold context`() = runTest {
        val prefs = JmTestPreferences()
        val first = JmOfflineContext(prefs)
        val parser = JmParser(first)
        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        first.imageClient(parser).newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute().close()
        val cold = JmOfflineContext(prefs)
        val restarted = JmParser(cold)
        cold.imageHandler = { req -> if (req.url.host == "images.jm.invalid") throw SocketException("reset") }
        cold.imageClient(restarted).newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute().close()
        assertEquals(listOf("2"), cold.settingRequests.map { it.url.queryParameter("app_img_shunt") })
        val next = JmParser(JmOfflineContext(prefs))
            .getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        assertEquals("https://images2.jm.invalid/media/albums/654321_3x4.jpg", next.coverUrl)
    }

    @Test
    fun `unverified settings and failed images are never persisted`() = runTest {
        val prefs = JmTestPreferences()
        val ctx = JmOfflineContext(prefs)
        val parser = JmParser(ctx)
        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        assertEquals(0, prefs.writes)
        ctx.imageStatus = 403
        ctx.imageClient(parser).newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute().close()
        assertEquals(0, prefs.writes)
        val cold = JmOfflineContext(prefs)
        JmParser(cold).getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456"))
        assertEquals(1, cold.settingRequests.size)
    }

    @Test
    fun `persisted server is isolated between automatic and manual choices`() = runTest {
        val prefs = JmTestPreferences()
        val ctx = JmOfflineContext(prefs)
        val parser = JmParser(ctx)
        val key = mutableListOf<ConfigKey<*>>().also(parser::onCreateConfig)
            .filterIsInstance<ConfigKey.PreferredImageServer>().single()
        ctx.config.set(key, "3")
        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        ctx.imageClient(parser).newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute().close()
        val automatic = JmOfflineContext(prefs)
        val autoCard = JmParser(automatic).getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        assertTrue(requireNotNull(autoCard.coverUrl).startsWith("https://images.jm.invalid/"))
        val manual = JmOfflineContext(prefs)
        manual.config.set(key, "3")
        val manualCard = JmParser(manual).getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        assertTrue(requireNotNull(manualCard.coverUrl).startsWith("https://images3.jm.invalid/"))
        assertTrue(manual.settingRequests.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["not JSON", "{\"img_host\":\"http://images.jm.invalid\",\"shunt\":1}",
        "{\"img_host\":\"https://user:password@images.jm.invalid\",\"shunt\":1}"])
    fun `invalid persisted records fall back to the site settings`(record: String) = runTest {
        val prefs = JmTestPreferences()
        val first = JmOfflineContext(prefs)
        val parser = JmParser(first)
        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        first.imageClient(parser).newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute().close()
        prefs.values[prefs.values.keys.single()] = record
        val cold = JmOfflineContext(prefs)
        val next = JmParser(cold).getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        assertEquals("https://images.jm.invalid/media/albums/654321_3x4.jpg", next.coverUrl)
        assertEquals(1, cold.settingRequests.size)
    }

    @Test
    fun `storage failures do not turn a successful image into an image error`() = runTest {
        val prefs = JmTestPreferences().apply { unavailable = true }
        val ctx = JmOfflineContext(prefs)
        val parser = JmParser(ctx)
        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        ctx.imageClient(parser).newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute().use {
            assertEquals(200, it.code)
        }
        assertEquals(1, ctx.settingRequests.size)
    }

    @Test
    fun `image settings offer automatic and four site shunts with valid query parameters`() = runTest {
        val ctx = JmOfflineContext()
        val parser = JmParser(ctx)
        val keys = mutableListOf<ConfigKey<*>>()
        parser.onCreateConfig(keys)
        val key = keys.filterIsInstance<ConfigKey.PreferredImageServer>().single()
        assertNull(key.defaultValue)
        assertEquals(setOf(null, "1", "2", "3", "4"), key.presetValues.keys)
        parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456"))
        assertEquals("1", ctx.settingRequests.single().url.queryParameter("app_img_shunt"))
        assertEquals("", ctx.settingRequests.single().url.queryParameter("express"))
    }

    @Test
    fun `successful covers do not probe and initialization is shared across parser instances`() = runTest {
        val ctx = JmOfflineContext()
        val card = JmParser(ctx).getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        val second = JmParser(ctx)
        second.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456"))
        ctx.imageClient(second).newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute().use {
            assertEquals(200, it.code)
        }
        assertEquals(1, ctx.settingRequests.size)
        assertEquals(1, ctx.imageRequests.size)
    }

    @Test
    fun `failed cover switches shunt preserving path and next cards reuse the successful server`() = runTest {
        val ctx = JmOfflineContext()
        ctx.imageHandler = { req -> if (req.url.host == "images.jm.invalid") throw SocketException("Connection reset") }
        val parser = JmParser(ctx)
        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        val client = ctx.imageClient(JmParser(ctx))
        client.newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute().use { assertEquals(200, it.code) }
        assertEquals(listOf("1", "2"), ctx.settingRequests.map { it.url.queryParameter("app_img_shunt") })
        assertEquals(listOf("images.jm.invalid", "images2.jm.invalid"), ctx.imageRequests.map { it.url.host })
        assertTrue(ctx.imageRequests.all { it.url.encodedPath == "/media/albums/654321_3x4.jpg" })
        client.newCall(Request.Builder().url("https://images.jm.invalid/media/albums/777777_3x4.jpg").build())
            .execute().close()
        assertEquals("images2.jm.invalid", ctx.imageRequests.last().url.host)
        assertEquals(2, ctx.settingRequests.size)
        val next = JmParser(ctx).getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        assertEquals("https://images2.jm.invalid/media/albums/654321_3x4.jpg", next.coverUrl)
    }

    @Test
    fun `automatic recovery is bounded and exhausted group is not repeated within a minute`() = runTest {
        val ctx = JmOfflineContext()
        ctx.imageHandler = { throw SocketException("Connection reset") }
        val parser = JmParser(ctx)
        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        val client = ctx.imageClient(parser)
        repeat(2) {
            assertInstanceOf(SocketException::class.java, runCatching {
                client.newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute()
            }.exceptionOrNull())
        }
        assertEquals(listOf("1", "2", "3", "4"), ctx.settingRequests.map { it.url.queryParameter("app_img_shunt") })
        assertEquals(5, ctx.imageRequests.size)
    }

    @Test
    fun `manual shunt is respected on already generated cards and never automatically switches`() = runTest {
        val ctx = JmOfflineContext()
        val parser = JmParser(ctx)
        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        val keys = mutableListOf<ConfigKey<*>>().also(parser::onCreateConfig)
        ctx.config.set(keys.filterIsInstance<ConfigKey.PreferredImageServer>().single(), "3")
        ctx.imageHandler = { throw SocketException("Connection reset") }
        assertInstanceOf(SocketException::class.java, runCatching {
            ctx.imageClient(parser).newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute()
        }.exceptionOrNull())
        assertEquals(listOf("1", "3"), ctx.settingRequests.map { it.url.queryParameter("app_img_shunt") })
        assertEquals(listOf("images3.jm.invalid"), ctx.imageRequests.map { it.url.host })
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403, 404, 429, 503])
    fun `HTTP errors do not trigger image shunt recovery`(code: Int) = runTest {
        val ctx = JmOfflineContext().apply { imageStatus = code }
        val parser = JmParser(ctx)
        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        ctx.imageClient(parser).newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute().use {
            assertEquals(code, it.code)
        }
        assertEquals(1, ctx.settingRequests.size)
        assertEquals(1, ctx.imageRequests.size)
    }

    @Test
    fun `cancelled image and non transport failures never start shunt queries`() = runTest {
        val ctx = JmOfflineContext()
        val parser = JmParser(ctx)
        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        val client = ctx.imageClient(parser)
        val call = client.newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build())
        ctx.imageHandler = { call.cancel(); throw SocketException("Socket closed") }
        runCatching { call.execute() }
        ctx.imageHandler = { throw IOException("unrelated IO failure") }
        runCatching { client.newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute() }
        assertEquals(1, ctx.settingRequests.size)
    }

    @Test
    fun `failed initialization can retry without marking a default server as initialized`() = runTest {
        val ctx = JmOfflineContext().apply { settingBody = { "{}" } }
        val parser = JmParser(ctx)
        parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456"))
        ctx.settingBody = ctx::defaultSetting
        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        assertEquals("https://images.jm.invalid/media/albums/654321_3x4.jpg", card.coverUrl)
        assertEquals(2, ctx.settingRequests.size)
    }

    @Test
    fun `concurrent parser instances share one recovery rather than querying for each card`() = runTest {
        val ctx = JmOfflineContext()
        val parser = JmParser(ctx)
        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        val firstAttempts = CountDownLatch(4)
        ctx.imageHandler = { req ->
            if (req.url.host == "images.jm.invalid") {
                firstAttempts.countDown()
                check(firstAttempts.await(5, TimeUnit.SECONDS))
                throw SocketException("Connection reset")
            }
        }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map {
                executor.submit<Int> {
                    ctx.imageClient(JmParser(ctx)).newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build())
                        .execute().use { it.code }
                }
            }
            assertEquals(listOf(200, 200, 200, 200), futures.map { it.get(8, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }
        assertEquals(2, ctx.settingRequests.size)
        assertEquals(4, ctx.imageRequests.count { it.url.host == "images2.jm.invalid" })
    }

    @Test
    fun `duplicate hosts are not requested again and a third fallback may succeed`() = runTest {
        val ctx = JmOfflineContext()
        val parser = JmParser(ctx)
        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        ctx.settingBody = { req ->
            if (req.url.queryParameter("app_img_shunt") == "2") """{"img_host":"https://images.jm.invalid"}"""
            else ctx.defaultSetting(req)
        }
        ctx.imageHandler = { req -> if (req.url.host != "images4.jm.invalid") throw SocketException("reset") }
        ctx.imageClient(parser).newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute().use {
            assertEquals(200, it.code)
        }
        assertEquals(listOf("1", "2", "3", "4"), ctx.settingRequests.map { it.url.queryParameter("app_img_shunt") })
        assertEquals(listOf("images.jm.invalid", "images3.jm.invalid", "images4.jm.invalid"),
            ctx.imageRequests.map { it.url.host })
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403, 429])
    fun `settings permission and rate limit errors stop and share the pause`(status: Int) = runTest {
        val ctx = JmOfflineContext()
        val parser = JmParser(ctx)
        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        ctx.settingStatus = status
        ctx.imageHandler = { throw SocketException("reset") }
        repeat(2) {
            val error = runCatching {
                ctx.imageClient(JmParser(ctx)).newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute()
            }.exceptionOrNull()
            assertInstanceOf(IOException::class.java, error)
            if (it == 0) {
                if (status == 401) assertInstanceOf(AuthRequiredException::class.java, error)
                else assertEquals(status, assertInstanceOf(org.jsoup.HttpStatusException::class.java, error).statusCode)
            }
        }
        assertEquals(2, ctx.settingRequests.size)
        assertEquals(2, ctx.imageRequests.size)
    }

    @Test
    fun `cancellation during recovery does not start another shunt or poison later recovery`() = runTest {
        val ctx = JmOfflineContext()
        val parser = JmParser(ctx)
        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        val client = ctx.imageClient(parser)
        val call = client.newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build())
        ctx.imageHandler = { req -> if (req.url.host == "images.jm.invalid") throw SocketException("reset") }
        ctx.settingBody = { req -> call.cancel(); ctx.defaultSetting(req) }
        assertInstanceOf(IOException::class.java, runCatching { call.execute() }.exceptionOrNull())
        assertEquals(2, ctx.settingRequests.size)
        assertEquals(1, ctx.imageRequests.size)
        ctx.settingBody = ctx::defaultSetting
        client.newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute().close()
        assertEquals(3, ctx.settingRequests.size)
        assertEquals("images2.jm.invalid", ctx.imageRequests.last().url.host)
    }

    @Test
    fun `invalid image settings are skipped within the same bounded recovery`() = runTest {
        val ctx = JmOfflineContext()
        val parser = JmParser(ctx)
        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()
        ctx.imageHandler = { req -> if (req.url.host == "images.jm.invalid") throw SocketException("reset") }
        ctx.settingBody = { req -> when (req.url.queryParameter("app_img_shunt")) {
            "2" -> """{"img_host":"https://user:password@example.invalid/"}"""
            "3" -> "not JSON"
            else -> ctx.defaultSetting(req)
        } }
        ctx.imageClient(parser).newCall(Request.Builder().url(requireNotNull(card.coverUrl)).build()).execute().close()
        assertEquals(4, ctx.settingRequests.size)
        assertEquals(listOf("images.jm.invalid", "images4.jm.invalid"), ctx.imageRequests.map { it.url.host })
    }

    @Test
    fun `ordinary search keeps keywords tags sorting and list mapping`() = runTest {
        val context = JmOfflineContext()
        val parser = JmParser(context)
        val filter = ContentListFilter(
            query = "故事",
            tags = setOf(
                ContentTag("全彩", "全彩", parser.source),
                ContentTag("同人", "c:doujin", parser.source),
            ),
        )
        val result = parser.getList(0, SortOrder.POPULARITY, filter).single()

        assertEquals("繁體標題 123456", result.title)
        assertEquals(setOf("作者甲"), result.authors)
        assertEquals(setOf("同人", "單本"), result.tags.map { it.title }.toSet())
        assertEquals("故事 全彩", context.businessRequests.single().url.queryParameter("search_query"))
        assertEquals("mv", context.businessRequests.single().url.queryParameter("o"))
        assertEquals("/search", context.businessRequests.single().url.encodedPath)
        assertEquals(1, context.requests.count { it.url.encodedPath == "/newsvr-2025.txt" })
        assertEquals(1, context.requests.count { it.url.encodedPath == "/setting" })
    }

    @ParameterizedTest
    @ValueSource(strings = ["1", " 123456 ", "\t123456\n", "9223372036854775808123456"])
    fun `positive decimal query uses album without converting to a machine integer`(query: String) = runTest {
        val context = JmOfflineContext()
        val parser = JmParser(context)

        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = query)).single()

        assertEquals("/album", context.businessRequests.single().url.encodedPath)
        assertEquals(query.trim(), context.businessRequests.single().url.queryParameter("id"))
        assertEquals(parser.generateUid("jm:654321"), card.id)
    }

    @Test
    fun `number overrides site filters and returned identity controls card and details`() = runTest {
        val context = JmOfflineContext()
        val parser = JmParser(context)
        val filter = ContentListFilter(
            query = " 123456 ",
            tags = setOf(
                ContentTag("全彩", "全彩", parser.source),
                ContentTag("同人", "c:doujin", parser.source),
                ContentTag("每週必看", "w:257", parser.source),
            ),
        )
        val originalFilter = filter.copy()
        val card = parser.getList(0, SortOrder.POPULARITY_MONTH, filter).single()

        assertEquals(originalFilter, filter)
        assertEquals(parser.generateUid("jm:654321"), card.id)
        assertEquals("繁體標題 123456", card.title)
        assertEquals(setOf("作者甲"), card.authors)
        assertEquals("合成作品描述", card.description)
        assertEquals(setOf("劇情向", "全彩"), card.tags.map { it.title }.toSet())
        assertEquals("https://images.jm.invalid/media/albums/654321_3x4.jpg", card.coverUrl)
        assertEquals(card.coverUrl, card.largeCoverUrl)
        assertTrue(card.url.endsWith("/album?id=654321"))
        assertEquals(card.url, card.publicUrl)
        assertNull(card.chapters)
        assertEquals(setOf("id"), context.businessRequests.single().url.queryParameterNames)

        val details = parser.getDetails(card)
        assertEquals("654321", context.businessRequests.last().url.queryParameter("id"))
        assertEquals(card.id, details.id)
        assertEquals(card.url, details.url)
        assertEquals(card.coverUrl, details.coverUrl)
        assertEquals(setOf("作者甲"), details.authors)
        assertEquals(1, requireNotNull(details.chapters).size)
        assertTrue(context.businessRequests.all { it.url.encodedPath == "/album" })
    }

    @Test
    fun `public offsets do not repeat lookup and a new query starts normally`() = runTest {
        val context = JmOfflineContext()
        val parser = JmParser(context)
        val filter = ContentListFilter(query = "123456")

        assertEquals(1, parser.getList(0, SortOrder.POPULARITY, filter).size)
        assertTrue(parser.getList(1, SortOrder.POPULARITY, filter).isEmpty())
        assertTrue(parser.getList(2, SortOrder.POPULARITY, filter).isEmpty())
        assertEquals(1, context.businessRequests.size)
        assertEquals(1, parser.getList(0, SortOrder.POPULARITY, filter.copy(query = "777777")).size)
        assertEquals(listOf("123456", "777777"), context.businessRequests.map { it.url.queryParameter("id") })
    }

    @Test
    fun `later number page on a fresh parser needs no initialization or business requests`() = runTest {
        val context = JmOfflineContext()
        val parser = JmParser(context)

        assertTrue(parser.getList(80, SortOrder.POPULARITY, ContentListFilter(query = "123456")).isEmpty())
        assertTrue(context.requests.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["00123", "0", "-1", "+123", "JM123456", "123故事", "123 456", "１２３", "123.0",
        "https://example.invalid/album?id=123456"])
    fun `non number query keeps the keyword search path`(query: String) = runTest {
        val context = JmOfflineContext()
        val parser = JmParser(context)

        assertEquals(1, parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = query)).size)
        assertEquals("/search", context.businessRequests.single().url.encodedPath)
        assertEquals(query, context.businessRequests.single().url.queryParameter("search_query"))
    }

    @Test
    fun `numeric site tag alone remains a keyword search`() = runTest {
        val context = JmOfflineContext()
        val parser = JmParser(context)
        val filter = ContentListFilter(tags = setOf(ContentTag("123456", "123456", parser.source)))

        assertEquals(1, parser.getList(0, SortOrder.POPULARITY, filter).size)
        assertEquals("/search", context.businessRequests.single().url.encodedPath)
    }

    @Test
    fun `returned identity matches ordinary lists and is independent of configured domain`() = runTest {
        val ids = listOf("one.jm.invalid", "two.jm.invalid").map { domain ->
            val context = JmOfflineContext()
            val parser = JmParser(context)
            context.config.set(parser.configKeyDomain, domain)
            val ordinary = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "故事")).single()
            val numbered = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()

            assertEquals(ordinary.id, numbered.id)
            assertEquals(ordinary.url, numbered.url)
            assertEquals(ordinary.coverUrl, numbered.coverUrl)
            assertTrue(numbered.url.startsWith("https://$domain/"))
            numbered.id
        }
        assertEquals(ids.first(), ids.last())
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "{}",
        """{"id":null,"name":"作品"}""",
        """{"id":"","name":"作品"}""",
        """{"id":"0","name":"作品"}""",
        """{"id":"abc","name":"作品"}""",
        """{"id":"654321"}""",
        """{"id":"654321","name":null}""",
        """{"id":"654321","name":"  "}""",
        """{"id":"654321","name":[]}""",
        "null",
        "[]",
    ])
    fun `invalid album fails with JM query context without search fallback`(album: String) = runTest {
        val context = JmOfflineContext().apply { albumBody = album }
        val parser = JmParser(context)

        val failure = assertInstanceOf(ParseException::class.java, runCatching {
            parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456"))
        }.exceptionOrNull())
        assertTrue(failure.message.orEmpty().contains("JM"))
        assertTrue(failure.message.orEmpty().contains("123456"))
        assertEquals(listOf("/album"), context.businessRequests.map { it.url.encodedPath })
    }

    @Test
    fun `optional JSON nulls are not displayed as strings`() = runTest {
        val context = JmOfflineContext().apply {
            albumBody = """{"id":654321,"name":"作品","author":[null],"description":null,"tags":[null,"","全彩"]}"""
        }
        val card = JmParser(context).getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()

        assertTrue(card.authors.isEmpty())
        assertTrue(card.description.isNullOrEmpty())
        assertEquals(setOf("全彩"), card.tags.map { it.title }.toSet())
    }

    @Test
    fun `missing optional fields and album id alias use existing list identity`() = runTest {
        val context = JmOfflineContext().apply {
            albumBody = """{"album_id":"654321","name":"作品"}"""
        }
        val parser = JmParser(context)
        val card = parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).single()

        assertEquals(parser.generateUid("jm:654321"), card.id)
        assertTrue(card.authors.isEmpty())
        assertTrue(card.tags.isEmpty())
    }

    @Test
    fun `network failure retains exception type message and retry path without fallback`() = runTest {
        val failure = IOException("offline transport failure")
        val context = JmOfflineContext().apply { albumFailure = { throw failure } }
        val parser = JmParser(context)

        val actual = assertInstanceOf(IOException::class.java, runCatching {
            parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456"))
        }.exceptionOrNull())
        assertEquals(failure.message, actual.message)
        assertEquals(listOf("/album", "/album", "/album"), context.businessRequests.map { it.url.encodedPath })
    }

    @Test
    fun `authentication failure retains existing auth error without fallback`() = runTest {
        val context = JmOfflineContext().apply { albumStatus = 401 }
        val parser = JmParser(context)

        assertInstanceOf(AuthRequiredException::class.java, runCatching {
            parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456"))
        }.exceptionOrNull())
        assertEquals(listOf("/album", "/album", "/album"), context.businessRequests.map { it.url.encodedPath })
    }

    @Test
    fun `failed lookup can be retried from the first public offset`() = runTest {
        val context = JmOfflineContext()
        val validAlbum = context.albumBody
        val parser = JmParser(context)
        context.albumBody = "{}"
        assertInstanceOf(ParseException::class.java, runCatching {
            parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456"))
        }.exceptionOrNull())
        context.albumBody = validAlbum

        assertEquals(1, parser.getList(0, SortOrder.POPULARITY, ContentListFilter(query = "123456")).size)
        assertEquals(listOf("/album", "/album"), context.businessRequests.map { it.url.encodedPath })
    }

    @Test
    fun `weekly tags are sorted by time descending (newest issue first)`() {
        // 数据形态取自 /week 接口：id 为期数序号（越大越新），time 为展示字符串
        val tags = listOf(
            ContentTag("2026第255期09.04 - 08.28", "w:256", parser.source),
            ContentTag("2021第1期10.21 - 10.14", "w:1", parser.source),
            ContentTag("2026第256期09.11 - 09.04", "w:257", parser.source),
            ContentTag("2025第219期12.26 - 12.19", "w:220", parser.source),
            ContentTag("2026第254期08.28 - 08.21", "w:255", parser.source),
        )

        val sorted = with(parser) { tags.sortedWeeklyByTimeDesc() }

        assertEquals(listOf("w:257", "w:256", "w:255", "w:220", "w:1"), sorted.map { it.key })
    }
}

/** All responses are synthetic; this context never proceeds to the network or loads local credentials. */
private class JmOfflineContext(private val preferences: JmTestPreferences = JmTestPreferences()) : ContentLoaderContext() {
    @Suppress("UNUSED_PARAMETER")
    fun getSourcePreferences(sourceName: String): JmTestPreferenceApi = preferences
    val config = SourceConfigMock()
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    val settingRequests: List<Request>
        get() = synchronized(requests) { requests.filter { it.url.encodedPath == "/setting" } }
    val imageRequests: List<Request>
        get() = synchronized(requests) { requests.filter { it.url.encodedPath.startsWith("/media/albums/") } }
    var settingBody: (Request) -> String = ::defaultSetting
    var imageHandler: (Request) -> Unit = {}
    var imageStatus: Int = 200
    var settingStatus: Int = 200

    fun defaultSetting(request: Request): String {
        val shunt = request.url.queryParameter("app_img_shunt")?.toIntOrNull() ?: 1
        val host = if (shunt == 1) "images.jm.invalid" else "images$shunt.jm.invalid"
        return JSONObject().put("img_host", "https://$host")
            .put("app_shunts", org.json.JSONArray((1..4).map { JSONObject().put("key", it).put("title", "图源$it") }))
            .toString()
    }

    fun imageClient(parser: JmParser): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(parser)
        .addInterceptor(responder)
        .build()
    val businessRequests: List<Request>
        get() = requests.filter { it.url.encodedPath in setOf("/album", "/search") }
    var albumBody: String = fixture("album.json")
    var albumFailure: (() -> Nothing)? = null
    var albumStatus: Int = 200

    override val cookieJar: CookieJar = CookieJar.NO_COOKIES
    private val responder = Interceptor { chain ->
            val request = chain.request()
            requests += request
            if (request.url.encodedPath.startsWith("/media/albums/")) {
                imageHandler(request)
                return@Interceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(imageStatus).message("offline image")
                    .header("Content-Type", "image/jpeg")
                    .body(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
                        .toResponseBody("image/jpeg".toMediaType())).build()
            }
            val body = when (request.url.encodedPath) {
                "/newsvr-2025.txt" -> encrypt("""{"Server":["api.jm.invalid"]}""", "diosfjckwpqpdfjkvnqQjsik")
                "/setting" -> envelope(request, settingBody(request))
                "/search" -> envelope(request, fixture("search.json"))
                "/album" -> {
                    albumFailure?.invoke()
                    envelope(request, albumBody, albumStatus)
                }
                else -> error("Unexpected offline JM request: ${request.url}")
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (request.url.encodedPath == "/setting") settingStatus else 200)
                .message("OK")
                .body(body.toResponseBody())
                .build()
        }
    override val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(responder)
        .build()

    override fun getConfig(source: ContentSource): ContentSourceConfig = config
    override fun getDefaultUserAgent(): String = "JM offline test"
    override fun newParserInstance(source: ContentSource): ContentParser = error("Not used")
    override fun newLinkResolver(link: HttpUrl): LinkResolver = error("Not used")
    @Deprecated("Provide a base url")
    override suspend fun evaluateJs(script: String): String? = error("Not used")
    override suspend fun evaluateJs(baseUrl: String, script: String): String? = error("Not used")
    override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = error("Not used")
    override fun createBitmap(width: Int, height: Int): Bitmap = error("Not used")

    private fun envelope(request: Request, data: String, status: Int = 200): String {
        val time = requireNotNull(request.header("tokenparam")).substringBefore(',')
        return JSONObject()
            .put("status", status)
            .put("data", encrypt(data, "${time}185Hcomic3PAPP7R"))
            .toString()
    }

    private fun encrypt(data: String, secret: String): String {
        val key = MessageDigest.getInstance("MD5").digest(secret.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .toByteArray()
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return encodeBase64(cipher.doFinal(data.toByteArray()))
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/jm/$name")).readText()
}

interface JmTestPreferenceApi {
    fun getString(key: String, default: String?): String?
    fun edit(): JmTestPreferenceEditor
}

interface JmTestPreferenceEditor {
    fun putString(key: String, value: String?): JmTestPreferenceEditor
    fun apply()
}

private class JmTestPreferences : JmTestPreferenceApi {
    val values = mutableMapOf<String, String?>()
    var writes = 0
    var unavailable = false
    override fun getString(key: String, default: String?): String? {
        check(!unavailable) { "test storage unavailable" }
        return values[key] ?: default
    }
    override fun edit(): JmTestPreferenceEditor = object : JmTestPreferenceEditor {
        private val pending = mutableMapOf<String, String?>()
        override fun putString(key: String, value: String?): JmTestPreferenceEditor = apply { pending[key] = value }
        override fun apply() {
            check(!unavailable) { "test storage unavailable" }
            values.putAll(pending)
            writes++
        }
    }
}
