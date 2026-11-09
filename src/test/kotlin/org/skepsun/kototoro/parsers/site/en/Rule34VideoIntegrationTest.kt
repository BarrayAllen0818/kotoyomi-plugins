package org.skepsun.kototoro.parsers.site.en

import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.skepsun.kototoro.parsers.ContentLoaderContextMock
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.await
import kotlin.time.Duration.Companion.minutes

@EnabledIfEnvironmentVariable(named = "RULE34VIDEO_INTEGRATION_TEST", matches = "1")
class Rule34VideoIntegrationTest {
    @Test
    fun `browse search metadata and playable media respond through actual parser`() = runTest(timeout = 3.minutes) {
        val parser = Rule34Video(ContentLoaderContextMock)
        val list = parser.getListPage(1, SortOrder.UPDATED, ContentListFilter())
        assertFalse(list.isEmpty())
        for (item in listOf(list.first(), list.last())) {
            val cover = requireNotNull(item.coverUrl)
            val coverRequest = Request.Builder().url(cover).headers(parser.getRequestHeaders()).build()
            ContentLoaderContextMock.httpClient.newCall(coverRequest).await().use {
                assertTrue(it.isSuccessful, "Cover HTTP ${it.code}")
                assertTrue(it.header("Content-Type").orEmpty().startsWith("image/"))
            }
        }
        val details = parser.getDetails(list.first())
        assertTrue(details.title.isNotBlank())
        val page = parser.getPages(requireNotNull(details.chapters).single()).single()
        val request = Request.Builder().url(page.url).header("Range", "bytes=0-1023").apply {
            page.headers?.forEach { (name, value) -> header(name, value) }
        }.build()
        ContentLoaderContextMock.httpClient.newCall(request).await().use {
            assertTrue(it.isSuccessful, "Media HTTP ${it.code}")
            assertTrue(it.header("Content-Type").orEmpty().startsWith("video/"))
            assertTrue(it.body.source().readByteArray(32).isNotEmpty())
        }
        val keyword = details.title.split(' ').first { it.length >= 3 }
        assertFalse(parser.getListPage(1, SortOrder.RELEVANCE, ContentListFilter(query = keyword)).isEmpty())
        assertFalse(parser.getFilterOptions().availableTags.isEmpty())
        println("RULE34VIDEO live checks: browse, first/last covers, details, media bytes, search, categories passed")
    }
}
