package org.skepsun.kototoro.parsers.site.all

import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await
import kotlin.time.Duration.Companion.minutes

@EnabledIfEnvironmentVariable(named = "IWARA_INTEGRATION_TEST", matches = "1")
class IwaraIntegrationTest {
    @Test
    fun `public browse covers details search tags and media respond through parser`() = runTest(timeout = 3.minutes) {
        val parser = Iwara(ContentLoaderContextMock)
        val list = parser.getList(0, SortOrder.UPDATED, ContentListFilter())
        assertFalse(list.isEmpty())
        for (item in listOf(list.first(), list.last())) {
            val request = Request.Builder().url(requireNotNull(item.coverUrl))
                .headers(parser.getRequestHeaders()).build()
            ContentLoaderContextMock.httpClient.newCall(request).await().use { response ->
                assertTrue(response.isSuccessful, "Cover HTTP ${response.code}")
                assertTrue(response.header("Content-Type").orEmpty().startsWith("image/"))
            }
        }
        var selected: ContentPage? = null
        var tag: ContentTag? = null
        for (item in list.take(6)) {
            val details = parser.getDetails(item)
            assertTrue(details.title.isNotBlank())
            assertTrue(details.authors.isNotEmpty(), "Iwara author uses the published account name")
            try {
                selected = parser.getPages(requireNotNull(details.chapters).single()).single()
                tag = details.tags.firstOrNull()
                break
            } catch (e: ParseException) {
                // A recent upload can still be processing, private, or hosted on an external website.
                if (listOf("fileUrl not found", "此视频由外部网站", "需要账号权限")
                        .none { e.shortMessage.orEmpty().contains(it) }) throw e
            }
        }
        assertNotNull(selected, "No playable public upload found in the inspected items")
        val page = requireNotNull(selected)
        val request = Request.Builder().url(page.url).header("Range", "bytes=0-1023").apply {
            page.headers?.forEach { (name, value) -> header(name, value) }
        }.build()
        ContentLoaderContextMock.httpClient.newCall(request).await().use { response ->
            assertTrue(response.isSuccessful, "Media HTTP ${response.code}")
            assertTrue(response.header("Content-Type").orEmpty().startsWith("video/"))
            assertTrue(response.body.source().readByteArray(32).isNotEmpty())
        }
        val keyword = tag?.title ?: "mmd"
        assertFalse(parser.getList(0, SortOrder.RELEVANCE, ContentListFilter(query = keyword)).isEmpty())
        val availableTag = tag ?: parser.getFilterOptions().availableTags.first()
        assertFalse(parser.getList(0, SortOrder.UPDATED, ContentListFilter(tags = setOf(availableTag))).isEmpty())
        println("IWARA live checks: browse, first/last covers, metadata, media bytes, search and tags passed")
    }
}
