package org.skepsun.kototoro.parsers.site.all

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.SourceConfigMock
import org.skepsun.kototoro.parsers.bitmap.Bitmap
import org.skepsun.kototoro.parsers.config.ContentSourceConfig
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.LinkResolver

/** 显式匿名线上验证；验证码或网络失败是受阻，不能作为图片验证通过。 */
@EnabledIfEnvironmentVariable(named = "NHENTAI_INTEGRATION_TEST", matches = "1")
class NhentaiParserIntegrationTest {
    @Test
    fun `anonymous live list supplies accessible covers`() = runBlocking {
        val context = NhIntegrationContext()
        val parser = NhentaiParser(context).also { context.parser = it }
        val list = parser.getListPage(1, SortOrder.NEWEST, ContentListFilter())
        assertTrue(list.isNotEmpty(), "NH list is empty")
        assertTrue(list.first().coverUrl?.isNotBlank() == true)
        checkImage(context, list.first().coverUrl!!, null)
    }

    @ParameterizedTest
    @ValueSource(strings = ["385440", "686046"])
    fun `anonymous v2 cover first last images are accessible`(id: String) = runBlocking {
        val context = NhIntegrationContext()
        val parser = NhentaiParser(context).also { context.parser = it }
        val card = parser.parseGalleryList(Jsoup.parse(
            "<a class='gallery' href='/g/$id/'><div class='caption'>NH fixture</div></a>",
            "https://nhentai.net/",
        )).single()
        val details = parser.getDetails(card)
        val pages = parser.getPages(details.chapters!!.single())
        assertTrue(pages.isNotEmpty(), "NH $id pages are empty")
        checkImage(context, details.coverUrl!!, null)
        checkImage(context, parser.getPageUrl(pages.first()), pages.first().headers)
        checkImage(context, parser.getPageUrl(pages.last()), pages.last().headers)
        println("NH $id: cover, first, last images verified; pages=${pages.size}")
    }

    @Test
    fun `anonymous character filter loads sorted first and second pages`() = runBlocking {
        val context = NhIntegrationContext()
        val parser = NhentaiParser(context).also { context.parser = it }
        val tag = parser.getFilterOptions().availableTags.single { it.key == "tag:80930" }
        for (page in 1..2) {
            val list = parser.getListPage(page, SortOrder.POPULARITY, ContentListFilter(tags = setOf(tag)))
            assertTrue(list.isNotEmpty(), "NH character page $page is empty")
            checkImage(context, requireNotNull(list.first().coverUrl), null)
        }
    }

    private fun checkImage(context: NhIntegrationContext, url: String, headers: Map<String, String>?) {
        val request = Request.Builder().url(url)
        headers?.forEach { (name, value) -> request.header(name, value) }
        context.httpClient.newCall(request.build()).execute().use {
            assertTrue(it.isSuccessful, "NH image status=${it.code}, url=$url")
            val type = it.body.contentType()?.toString().orEmpty()
            assertTrue(type.startsWith("image/"), "NH image type=$type, url=$url")
            assertTrue(it.body.source().readByteArray(1).isNotEmpty(), "NH image body is empty")
        }
    }
}

/** 仅接受代理环境设置，不加载任何账户、Cookie 文件或 Token。 */
private class NhIntegrationContext : ContentLoaderContext() {
    private val config = SourceConfigMock()
    var parser: NhentaiParser? = null
    override val cookieJar: CookieJar = CookieJar.NO_COOKIES
    override val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .addInterceptor { chain -> parser?.intercept(chain) ?: chain.proceed(chain.request()) }
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
    override fun getDefaultUserAgent(): String = "NH anonymous integration"
    override fun newParserInstance(source: ContentSource): ContentParser = error("Unused")
    override fun newLinkResolver(link: HttpUrl): LinkResolver = error("Unused")
    @Deprecated("Provide a base url")
    override suspend fun evaluateJs(script: String): String? = error("Unused")
    override suspend fun evaluateJs(baseUrl: String, script: String): String? = error("Unused")
    override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = error("Unused")
    override fun createBitmap(width: Int, height: Int): Bitmap = error("Unused")
}
