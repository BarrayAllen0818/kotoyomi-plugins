package org.skepsun.kototoro.parsers.site.zh

import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.HttpUrl
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
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.LinkResolver

class JmComicTest {

    private val parser = JmParser(JmOfflineContext())

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
private class JmOfflineContext : ContentLoaderContext() {
    val config = SourceConfigMock()
    val requests = mutableListOf<Request>()
    val businessRequests: List<Request>
        get() = requests.filter { it.url.encodedPath in setOf("/album", "/search") }
    var albumBody: String = fixture("album.json")
    var albumFailure: (() -> Nothing)? = null
    var albumStatus: Int = 200

    override val cookieJar: CookieJar = CookieJar.NO_COOKIES
    override val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = when (request.url.encodedPath) {
                "/newsvr-2025.txt" -> encrypt("""{"Server":["api.jm.invalid"]}""", "diosfjckwpqpdfjkvnqQjsik")
                "/setting" -> envelope(request, """{"img_host":"https://images.jm.invalid"}""")
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
                .code(200)
                .message("OK")
                .body(body.toResponseBody())
                .build()
        }
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
