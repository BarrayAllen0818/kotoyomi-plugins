package org.skepsun.kototoro.parsers.site.all

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.OfflineContentLoaderContext
import org.skepsun.kototoro.parsers.newParser
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder

class ExHentaiSearchTest {

    @Test
    fun `gallery details expose total pages and namespaced original works and characters`() = runBlocking {
        val target = "https://e-hentai.org/g/123/fixture/"
        val ctx = OfflineContentLoaderContext(mapOf(target to "exhentai/search.html"), transformBody = { _, _ ->
            """<html><body><div class="gm"><div id="gd2"><h1 id="gn">作品</h1></div>
            <div id="taglist"><div class="gt" id="ta_parody:original"><a>original</a></div><div class="gt" id="ta_character:hero"><a>hero</a></div></div>
            <table id="gdd"><tr><td class="gdt1">Length:</td><td class="gdt2">42 pages</td></tr></table>
            </div></body></html>"""
        })
        val parser = ContentParserSource.EXHENTAI.newParser(ctx)
        val content = org.skepsun.kototoro.parsers.model.Content(
            id = 123, title = "作品", altTitles = emptySet(), url = target, publicUrl = target,
            rating = 0f, contentRating = null, coverUrl = null, tags = emptySet(),
            state = null, authors = emptySet(), source = parser.source,
        )
        val payload = org.json.JSONObject(parser.javaClass.getMethod("getDetailsMetadata", org.skepsun.kototoro.parsers.model.Content::class.java)
            .invoke(parser, parser.getDetails(content)) as String)
        assertEquals(42, payload.getInt("total_pages"))
        assertEquals(listOf("original"), payload.getJSONArray("parodies").toList())
        assertEquals(listOf("hero"), payload.getJSONArray("characters").toList())
    }

    @Test
    fun `WNACG album total requires the explicit page count label`() = runBlocking {
        val target = "https://www.wnacg.com/photos-index-aid-42.html"
        var info = "頁數：24"
        val ctx = OfflineContentLoaderContext(mapOf(target to "exhentai/search.html"), transformBody = { _, _ ->
            """<html><body><div class="userwrap"><h2>作品</h2><div class="uwconn"><label>$info</label></div></div></body></html>"""
        })
        val parser = ContentParserSource.WNACG.newParser(ctx)
        val content = org.skepsun.kototoro.parsers.model.Content(
            id = 42, title = "作品", altTitles = emptySet(), url = target, publicUrl = target,
            rating = 0f, contentRating = null, coverUrl = null, tags = emptySet(),
            state = null, authors = emptySet(), source = parser.source,
        )
        val payload = org.json.JSONObject(parser.javaClass.getMethod("getDetailsMetadata", org.skepsun.kototoro.parsers.model.Content::class.java)
            .invoke(parser, parser.getDetails(content)) as String)
        assertEquals(24, payload.getInt("total_pages"))
        info = "相簿列表 999"
        val missing = org.json.JSONObject(parser.javaClass.getMethod("getDetailsMetadata", org.skepsun.kototoro.parsers.model.Content::class.java)
            .invoke(parser, parser.getDetails(content)) as String)
        assertTrue(missing.isNull("total_pages"))
    }

    private fun url(query: String?, retry: Boolean = false): String = "https://e-hentai.org/".toHttpUrl().newBuilder()
        .addEncodedQueryParameter("next", "0")
        .addQueryParameter("f_search", query)
        .apply { if (retry) addQueryParameter("inline_set", "dm_e") }
        .addQueryParameter("advsearch", "1")
        .build().toString()

    private fun context(expected: Set<String?>, inputs: List<String?>): OfflineContentLoaderContext {
        val urls = (inputs + expected).flatMap { listOf(url(it), url(it, retry = true)) }
        return OfflineContentLoaderContext(urls.associateWith { "exhentai/search.html" }, transformBody = { request, html ->
            if (request.url.queryParameter("f_search") in expected) html else "<html><body>No hits found</body></html>"
        })
    }

    @Test
    fun `namespaced multiword search returns matching galleries`() = runBlocking {
        val queries = mapOf(
            "other:ai generated" to "other:\"ai generated\"",
            "o:ai generated$" to "o:\"ai generated$\"",
            "female:big breasts" to "female:\"big breasts\"",
            "char:john smith" to "char:\"john smith\"",
        )
        for ((raw, expected) in queries) {
            val ctx = context(setOf(expected), listOf(raw))
            val result = ExHentaiParser(ctx).getListPage(0, SortOrder.NEWEST, ContentListFilter(query = raw))
            assertEquals(1, result.size)
            assertEquals(expected, ctx.requests.first().toHttpUrl().queryParameter("f_search"))
        }
    }

    @Test
    fun `gallery tag preserves namespace and searches as a single exact term`() = runBlocking {
        val expected = "other:\"ai generated$\""
        val ctx = context(setOf(null, expected), listOf("tag:\"ai generated\"$", "other:\"ai generated\"$"))
        val parser = ExHentaiParser(ctx)
        val gallery = parser.getListPage(0, SortOrder.NEWEST, ContentListFilter.EMPTY).single()
        assertEquals("other:ai generated", gallery.tags.single().key)
        val result = parser.getListPage(0, SortOrder.NEWEST, ContentListFilter(tags = gallery.tags))
        assertEquals(1, result.size)
        assertEquals(expected, ctx.requests.last().toHttpUrl().queryParameter("f_search"))
    }

    @Test
    fun `selected namespaced tag sends one exact term`() = runBlocking {
        val expected = "other:\"ai generated$\""
        val current = "other:\"ai generated\"$"
        val ctx = context(setOf(expected), listOf(current))
        val parser = ExHentaiParser(ctx)
        val tag = parser.getFilterOptions().availableTags.single { it.key == "other:ai generated" }
        val result = parser.getListPage(0, SortOrder.NEWEST, ContentListFilter(tags = setOf(tag)))
        assertEquals(1, result.size)
        assertEquals(expected, ctx.requests.first().toHttpUrl().queryParameter("f_search"))
    }

    @Test
    fun `filter options expose ai generated and retain the namespace`() = runBlocking {
        val parser = ExHentaiParser(context(emptySet(), emptyList()))
        val options = parser.getFilterOptions()
        assertTrue(options.availableTags.any { it.key == "other:ai generated" })
        assertTrue(options.tagGroups.any { group -> group.tags.any { it.key == "other:ai generated" } })
    }

    @Test
    fun `already quoted and compound queries keep their existing syntax`() = runBlocking {
        val queries = listOf("other:\"ai generated$\"", "other:ai generated -female:foo", "ordinary title")
        for (query in queries) {
            val ctx = context(setOf(query), listOf(query))
            assertEquals(1, ExHentaiParser(ctx).getListPage(0, SortOrder.NEWEST, ContentListFilter(query = query)).size)
            assertEquals(query, ctx.requests.single().toHttpUrl().queryParameter("f_search"))
        }
    }

    @Test
    fun `details id preserves namespace even when identical tag names occur in different namespaces`() = runBlocking {
        val ctx = OfflineContentLoaderContext(mapOf(url(null) to "exhentai/search.html"), transformBody = { _, html ->
            html.replace("<div class=\"gt\" title=\"other:ai generated\">ai generated</div>",
                "<a id=\"ta_other:ai_generated\">ai generated</a>" +
                    "<a id=\"ta_female:ai_generated\">ai generated</a>")
        })
        val gallery = ExHentaiParser(ctx).getListPage(0, SortOrder.NEWEST, ContentListFilter.EMPTY).single()
        assertEquals(setOf("other:ai generated", "female:ai generated"), gallery.tags.map { it.key }.toSet())
    }

    @Test
    fun `excluded tags and locale remain separate exact search terms`() = runBlocking {
        val expected = "-other:\"ai generated$\" language:\"english$\""
        val ctx = context(setOf(expected), emptyList())
        val tag = ContentTag("AI generated", "other:ai generated", ContentParserSource.EXHENTAI)
        val filter = ContentListFilter(tagsExclude = setOf(tag), locale = java.util.Locale.ENGLISH)
        assertEquals(1, ExHentaiParser(ctx).getListPage(0, SortOrder.NEWEST, filter).size)
        assertEquals(expected, ctx.requests.single().toHttpUrl().queryParameter("f_search"))
    }
}
