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

    private fun metadataContent(source: ContentParserSource, url: String) =
        org.skepsun.kototoro.parsers.model.Content(
            id = 42, title = "作品", altTitles = emptySet(), url = url, publicUrl = url,
            rating = 0f, contentRating = null, coverUrl = null, tags = emptySet(),
            state = null, authors = emptySet(), source = source,
        )

    private fun metadata(parser: org.skepsun.kototoro.parsers.ContentParser,
        content: org.skepsun.kototoro.parsers.model.Content,
    ) = org.json.JSONObject(parser.javaClass.getMethod("getDetailsMetadata",
        org.skepsun.kototoro.parsers.model.Content::class.java).invoke(parser, content) as String)

    @Test
    fun `metadata updates preserve absent fields and allow explicit clearing without ABI changes`() {
        val content = metadataContent(ContentParserSource.EXHENTAI, "https://e-hentai.org/g/42/test/")
        val records = org.skepsun.kototoro.parsers.model.WebsiteWorkMetadata
        records.attachFields(content, groups = listOf(" A ", "A", ""), durationSeconds = 123)
        records.attach(content, 42, listOf("原作"), listOf("人物"))
        val first = org.json.JSONObject(records.get(content)!!)
        assertEquals(listOf("A"), first.getJSONArray("groups").toList())
        assertEquals(123L, first.getLong("duration_seconds"))
        assertTrue(!first.has("languages"))
        assertEquals(null, records.get(content.copy()))
        val details = records.inherit(content, content.copy(title = "Fresh title"))
        records.attachFields(details, groups = emptyList(), durationSeconds = null)
        val cleared = org.json.JSONObject(records.get(details)!!)
        assertEquals(0, cleared.getJSONArray("groups").length())
        assertTrue(cleared.isNull("duration_seconds"))
        assertEquals(42, cleared.getInt("total_pages"))
        assertEquals(15, org.skepsun.kototoro.parsers.model.Content::class.java.declaredMethods
            .single { it.name == "copy" }.parameterCount)
    }

    @Test
    fun `Hitomi factory details keep separate authors aliases and complete gallery metadata`() = runBlocking {
        val gallery = """var galleryinfo = {"title":"Gallery","japanese_title":"別名","galleryurl":"/galleries/42.html",
            "files":[{"hash":"abc123"},{"hash":"abc456"}],"artists":[{"artist":"first","url":"/artist/first.html"},
            {"artist":"second","url":"/artist/second.html"}],"groups":[{"group":"studio","url":"/group/studio.html"}],
            "parodys":[{"parody":"original","url":"/parody/original.html"}],
            "characters":[{"character":"hero","url":"/character/hero.html"}],"tags":[],
            "type":"doujinshi","language":"japanese","language_localname":"日本語","date":"2026-01-01 00:00:00-00"}"""
        val fixtures = object : HashMap<String, String>() {
            override fun get(key: String): String? = "exhentai/search.html"
        }
        val ctx = OfflineContentLoaderContext(fixtures, transformBody = { request, _ ->
            when (request.url.encodedPath) {
                "/galleries/42.js" -> gallery
                "/gg.js" -> "var o = 0; case 123: o = 1; break; b: '1/'"
                else -> error("Unexpected metadata request: ${request.url}")
            }
        })
        val parser = ContentParserSource.HITOMILA.newParser(ctx)
        val details = parser.getDetails(metadataContent(ContentParserSource.HITOMILA, "42"))
        val payload = metadata(parser, details)
        assertEquals(setOf("First", "Second"), details.authors)
        assertEquals(setOf("別名"), details.altTitles)
        assertEquals(2, payload.getInt("total_pages"))
        assertEquals(listOf("studio"), payload.getJSONArray("groups").toList())
        assertEquals(listOf("original"), payload.getJSONArray("parodies").toList())
        assertEquals(listOf("hero"), payload.getJSONArray("characters").toList())
        assertEquals(listOf("doujinshi"), payload.getJSONArray("types").toList())
        assertEquals(listOf("日本語"), payload.getJSONArray("languages").toList())
        assertTrue(!payload.has("chapter_count"))
        assertEquals(2, ctx.requests.size)
    }

    @Test
    fun `Hanime1 factory preserves creator credits regardless of video type and only uses explicit brands`() = runBlocking {
        val url = "https://hanime1.me/watch?v=42"
        var type = "裏番"
        var credit = "Alice Creator"
        var description = "Brand: Explicit Brand"
        val ctx = OfflineContentLoaderContext(mapOf(url to "exhentai/search.html"), transformBody = { _, _ ->
            """<html><head><meta name="author" content="Hanime1">
            <meta property="og:video:duration" content="471"><meta name="description" content="$description">
            </head><body><a id="video-artist-name">$credit</a><a>$type</a>
            <div id="shareBtn-title">Actual title</div><span class="single-video-tag">中文字幕 (3)</span>
            <span class="single-video-tag">中文配音</span><span class="single-video-tag"># Plain Character</span>
            <div id="playlist-scroll"><div><div class="thumb-container"><a href="/watch?v=43"></a></div>
            <div class="video-title">Another work</div></div></div></body></html>"""
        })
        val parser = ContentParserSource.HANIME1.newParser(ctx)
        val anime = parser.getDetails(metadataContent(ContentParserSource.HANIME1, url))
        val animeMetadata = metadata(parser, anime)
        assertEquals(setOf("Alice Creator"), anime.authors)
        assertEquals(listOf("Explicit Brand"), animeMetadata.getJSONArray("producers").toList())
        assertTrue(!animeMetadata.has("groups"))
        assertTrue(!animeMetadata.has("series"))
        assertEquals(listOf("裏番"), animeMetadata.getJSONArray("types").toList())
        assertEquals(listOf("中文"), animeMetadata.getJSONArray("subtitle_languages").toList())
        assertEquals(listOf("中文"), animeMetadata.getJSONArray("audio_languages").toList())
        assertEquals(471L, animeMetadata.getLong("duration_seconds"))
        val related = animeMetadata.getJSONArray("related_works").getJSONObject(0)
        assertEquals("HANIME1", related.getString("source"))
        assertEquals("https://hanime1.me/watch?v=43", related.getString("url"))
        assertEquals("Another work", related.getString("title"))
        type = "2D動畫"
        credit = "Studio Credit"
        val other = parser.getDetails(metadataContent(ContentParserSource.HANIME1, url))
        val otherMetadata = metadata(parser, other)
        assertEquals(setOf("Studio Credit"), other.authors)
        assertEquals(listOf("Explicit Brand"), otherMetadata.getJSONArray("producers").toList())
        assertTrue(!otherMetadata.has("related_works"))
        assertTrue(!otherMetadata.has("series"))
        assertTrue(!otherMetadata.has("parodies"))
        assertTrue(!otherMetadata.has("characters"))
        type = "泡麵番"
        credit = "Bob Creator"
        description = "No explicitly labelled brand"
        val shortAnime = parser.getDetails(metadataContent(ContentParserSource.HANIME1, url))
        val shortAnimeMetadata = metadata(parser, shortAnime)
        assertEquals(setOf("Bob Creator"), shortAnime.authors)
        assertTrue(!shortAnimeMetadata.has("producers"))
        assertTrue(!shortAnimeMetadata.has("groups"))
        assertTrue(!shortAnimeMetadata.has("series"))
        assertEquals(3, ctx.requests.size)
    }

    @Test
    fun `gallery details expose total pages and namespaced original works and characters`() = runBlocking {
        val target = "https://e-hentai.org/g/123/fixture/"
        val ctx = OfflineContentLoaderContext(mapOf(target to "exhentai/search.html"), transformBody = { _, _ ->
            """<html><body><div class="gm"><div id="gd2"><h1 id="gn">作品</h1></div>
            <div id="taglist"><div class="gt" id="ta_parody:original"><a>original</a></div><div class="gt" id="ta_character:hero"><a>hero</a></div>
            <div class="gt" id="ta_artist:artist"><a>artist</a></div><div class="gt" id="ta_group:group"><a>group</a></div>
            <div class="gt" id="ta_language:english"><a>english</a></div></div><div id="gdc"><div class="cs">Doujinshi</div></div>
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
        assertEquals(listOf("group"), payload.getJSONArray("groups").toList())
        assertEquals(listOf("english"), payload.getJSONArray("languages").toList())
        assertEquals(listOf("Doujinshi"), payload.getJSONArray("types").toList())
        assertEquals(1, ctx.requests.size)
    }

    @Test
    fun `WNACG factory counts actual collection directory without inventing album metadata`() = runBlocking {
        val target = "https://www.wnacg.com/photos-index-aid-42.html"
        val ctx = OfflineContentLoaderContext(mapOf(target to "exhentai/search.html"), transformBody = { _, _ ->
            """<html><body><div class="userwrap"><h2>合集</h2><div class="uwconn">頁數：999</div></div>
            <div id="sr_pub" data-total="2" data-per="12"></div>
            <a class="tagshow" data-chid="51">第1話</a><a class="tagshow" data-chid="52">第2話</a>
            <div class="addtags"><a class="tagshow">普通人物名称</a></div></body></html>"""
        })
        val parser = ContentParserSource.WNACG.newParser(ctx)
        val details = parser.getDetails(metadataContent(ContentParserSource.WNACG, target))
        val payload = metadata(parser, details)
        assertEquals(2, details.chapters!!.size)
        assertEquals(2, payload.getInt("chapter_count"))
        assertTrue(!payload.has("total_pages"))
        assertTrue(!payload.has("parodies"))
        assertTrue(!payload.has("characters"))
        assertTrue(details.authors.isEmpty())
        assertEquals(listOf(target), ctx.requests)
    }

    @Test
    fun `WNACG list category survives final detail without copying unrelated navigation`() = runBlocking {
        val listUrl = "https://www.wnacg.com/albums.html"
        val detailUrl = "https://www.wnacg.com/photos-index-page-1-aid-42.html"
        val ctx = OfflineContentLoaderContext(mapOf(listUrl to "exhentai/search.html", detailUrl to "exhentai/search.html"),
            transformBody = { request, _ ->
                if (request.url.toString() == listUrl) {
                    """<div class="gallary_wrap"><ul class="cc"><li><div class="pic_box cate-5">
                    <a href="/photos-index-aid-42.html"><img src="/cover.jpg"></a></div>
                    <div class="info"><div class="title">List title</div><div class="info_col">分類：同人誌</div></div>
                    </li></ul></div>"""
                } else {
                    """<html><body><nav><a href="/albums-index-cate-999.html">Unrelated navigation</a></nav>
                    <div class="userwrap"><h2>Detail title</h2><div class="uwconn">頁數：24</div></div></body></html>"""
                }
            })
        val parser = ContentParserSource.WNACG.newParser(ctx)
        val card = parser.getList(0, SortOrder.NEWEST, ContentListFilter()).single()
        val listTypes = metadata(parser, card).getJSONArray("types").toList()
        assertTrue(listTypes.contains("同人誌"))
        val details = parser.getDetails(card)
        assertEquals(listTypes, metadata(parser, details).getJSONArray("types").toList())
        assertTrue(!metadata(parser, details).has("chapter_count"))
        assertEquals(listOf(listUrl, detailUrl), ctx.requests)
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
        val missing = parser.javaClass.getMethod("getDetailsMetadata", org.skepsun.kototoro.parsers.model.Content::class.java)
            .invoke(parser, parser.getDetails(content)) as String?
        assertTrue(missing == null || !org.json.JSONObject(missing).has("total_pages"))
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
