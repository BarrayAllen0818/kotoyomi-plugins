package org.skepsun.kototoro.parsers.site.en

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.LinkResolver
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import java.util.EnumSet

// Site routes and player fields follow Aniyomi's Rule34Video source (yuzono/anime-extensions).
@ContentSourceParser("RULE34VIDEO", "Rule34Video", "en", type = ContentType.HENTAI_VIDEO)
internal class Rule34Video(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.RULE34VIDEO, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("rule34video.com")
    private val preferredQuality = ConfigKey.Text("preferred_quality", "偏好画质高度（如 1080）", "1080")
    private val redirectClient by lazy {
        context.httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.RATING, SortOrder.RELEVANCE,
    )
    override val filterCapabilities = ContentListFilterCapabilities(isSearchSupported = true)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(preferredQuality)
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .set("Referer", "https://$domain/")
        .build()

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val doc = webClient.httpGet("https://$domain/categories/", getRequestHeaders()).parseHtml()
        val tags = doc.select("a[href*=/categories/]").mapNotNull { a ->
            val path = sitePath(a.attr("href")) ?: return@mapNotNull null
            val slug = browsePath.matchEntire(path)?.takeIf { it.groupValues[1] == "categories" }
                ?.groupValues?.get(2) ?: return@mapNotNull null
            val title = a.selectFirst(".title, .thumb_title, .name")?.text()
                ?: a.attr("title").ifBlank { a.text() }
            title.trim().takeIf { it.isNotEmpty() }?.let { ContentTag(it, "categories:$slug", source) }
        }.distinctBy { it.key }.toSet()
        if (tags.isEmpty()) throw ParseException("Rule34Video: categories not found", doc.location())
        return ContentListFilterOptions(availableTags = tags, availableContentTypes = setOf(ContentType.HENTAI_VIDEO))
    }

    internal fun buildListUrl(page: Int, order: SortOrder, filter: ContentListFilter): String {
        require(page > 0)
        require(filter.tags.size <= 1) { "Rule34Video: select only one category or tag" }
        require(filter.tagsExclude.isEmpty()) { "Rule34Video: tag exclusion is not supported" }
        val query = filter.query.orEmpty().trim()
        val tag = filter.tags.singleOrNull()
        require(query.isEmpty() || tag == null) { "Rule34Video: keyword search cannot be combined with a tag" }
        if (query.startsWith("http://") || query.startsWith("https://") || query.startsWith("slug:")) {
            val path = if (query.startsWith("slug:")) {
                val id = query.removePrefix("slug:").trim('/').takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
                    ?: throw IllegalArgumentException("Rule34Video: invalid video ID")
                "/video/$id/"
            } else {
                sitePath(query)?.takeIf { videoPath.matches(it) }
                    ?: throw IllegalArgumentException("Rule34Video: unsupported video URL")
            }
            return "https://$domain$path"
        }
        if (query.isEmpty() && tag == null && order == SortOrder.UPDATED) {
            return "https://$domain/latest-updates/$page/"
        }
        val builder = "https://$domain/".toHttpUrl().newBuilder()
        when {
            tag != null -> {
                val parts = tag.key.split(':', limit = 2)
                require(parts.size == 2 && parts[0] in setOf("tags", "models", "categories")) {
                    "Rule34Video: unsupported tag ${tag.key}"
                }
                require(slugPattern.matches(parts[1])) { "Rule34Video: invalid tag path" }
                builder.addPathSegment(parts[0]).addPathSegment(parts[1])
                if (page > 1) builder.addPathSegment(page.toString())
                builder.addPathSegment("")
            }
            else -> {
                builder.addPathSegment("search")
                if (query.isNotEmpty()) {
                    val slug = query.lowercase().replace(slugSeparators, "-").trim('-')
                    require(slug.isNotEmpty()) { "Rule34Video: enter a search term" }
                    builder.addPathSegment(slug)
                }
                builder.addPathSegment("")
            }
        }
        val sort = when (order) {
            SortOrder.UPDATED -> "post_date"
            SortOrder.POPULARITY -> "video_viewed"
            SortOrder.RATING -> "rating"
            SortOrder.RELEVANCE -> ""
            else -> throw IllegalArgumentException("Rule34Video: unsupported sort order $order")
        }
        if (sort.isNotEmpty()) builder.addQueryParameter("sort_by", sort)
        if (page > 1 && tag == null) builder.addQueryParameter("from_videos", page.toString())
        return builder.build().toString()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val url = buildListUrl(page, order, filter)
        if (videoPath.matches(url.toHttpUrl().encodedPath) && page > 1) return emptyList()
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        return if (videoPath.matches(url.toHttpUrl().encodedPath)) {
            listOf(parseDetails(doc, newContent(url.toHttpUrl().encodedPath, "Rule34Video", null)))
        } else parseList(doc)
    }

    internal fun parseList(doc: Document): List<Content> = doc.select("div.item.thumb:has(a.th)").mapNotNull { card ->
        val a = card.selectFirst("a.th") ?: return@mapNotNull null
        val path = sitePath(a.attr("href"))?.takeIf(videoPath::matches) ?: return@mapNotNull null
        val title = a.attr("title").ifBlank { a.selectFirst(".thumb_title")?.text().orEmpty() }.trim()
        if (title.isEmpty()) return@mapNotNull null
        val img = a.selectFirst(".img img, img")
        val cover = listOf("data-rd-jpg", "data-rd-src", "data-original", "src").firstNotNullOfOrNull { attr ->
            img?.attr(attr)?.takeIf {
                it.isNotBlank() && !it.contains("placeholder", true) && !it.contains("blank", true)
            }?.let { absoluteUrl(it, doc) }
        }
        newContent(path, title, cover)
    }.distinctBy { it.id }

    private fun newContent(path: String, title: String, cover: String?) = Content(
        id = generateUid(videoPath.matchEntire(path)?.groupValues?.get(1) ?: path),
        title = title,
        altTitles = emptySet(),
        url = path,
        publicUrl = "https://$domain$path",
        rating = RATING_UNKNOWN,
        contentRating = ContentRating.ADULT,
        coverUrl = cover,
        tags = emptySet(),
        state = ContentState.FINISHED,
        authors = emptySet(),
        source = source,
    )

    override suspend fun getDetails(manga: Content): Content {
        val path = sitePath(manga.url)?.takeIf(videoPath::matches)
            ?: throw ParseException("Rule34Video: invalid detail path", manga.url)
        return parseDetails(webClient.httpGet("https://$domain$path", getRequestHeaders()).parseHtml(), manga)
    }

    internal fun parseDetails(doc: Document, content: Content): Content {
        val title = doc.selectFirst("h1.title_video, h1.vp-title")?.text()?.takeIf { it.isNotBlank() }
            ?: throw ParseException("Rule34Video: video title not found", doc.location())
        val cover = doc.selectFirst("video[poster]")?.attr("poster")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val authors = doc.select(".col a[href*=/models/], .vp-group a[href*=/models/]")
            .map { it.selectFirst(".name")?.text() ?: it.text() }.filter { it.isNotBlank() }.toSet()
        val tags = doc.select(".col a[href], .vp-group a[href], .vp-tags a[href], .row_spacer a.tag_item[href]")
            .mapNotNull { a ->
                val match = sitePath(a.attr("href"))?.let(browsePath::matchEntire) ?: return@mapNotNull null
                if (match.groupValues[1] == "models") return@mapNotNull null
                val text = a.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ContentTag(text, "${match.groupValues[1]}:${match.groupValues[2]}", source)
            }.toSet()
        val path = sitePath(content.url) ?: throw ParseException("Rule34Video: invalid chapter path", content.url)
        return content.copy(
            title = title,
            coverUrl = cover?.let { absoluteUrl(it, doc) } ?: content.coverUrl,
            largeCoverUrl = cover?.let { absoluteUrl(it, doc) } ?: content.largeCoverUrl,
            authors = authors,
            tags = tags,
            description = doc.selectFirst(".vp-desc, .row:has(.label > em) > .label > em")?.text(),
            chapters = listOf(ContentChapter(
                id = generateUid("chapter:${videoPath.matchEntire(path)?.groupValues?.get(1)}"),
                url = path,
                title = "Video",
                number = 1f,
                volume = 0,
                uploadDate = 0L,
                branch = null,
                scanlator = null,
                source = source,
            )),
        )
    }

    override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Content? {
        val path = sitePath(link.toString())?.takeIf(videoPath::matches) ?: return null
        return getDetails(newContent(path, "Rule34Video", null))
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val path = sitePath(chapter.url)?.takeIf(videoPath::matches)
            ?: throw ParseException("Rule34Video: invalid playback page", chapter.url)
        val response = webClient.httpGet("https://$domain$path", getRequestHeaders())
        val pageUrl = response.request.url
        val doc = response.parseHtml()
        for (candidate in streamCandidates(doc)) {
            val resolved = resolveStream(candidate, pageUrl) ?: continue
            return listOf(ContentPage(
                id = generateUid("page:${chapter.id}"),
                url = resolved.toString(),
                preview = null,
                headers = playbackHeaders(resolved, pageUrl).toMap(),
                source = source,
            ))
        }
        throw ParseException("Rule34Video: no accessible video stream found", pageUrl.toString())
    }

    internal fun streamCandidates(doc: Document): List<HttpUrl> {
        val scripts = doc.select("script").map { it.data() }.filter { "flashvars" in it && "video_url" in it }
        val player = scripts.flatMap { script ->
            val labels = playerText.findAll(script).associate { it.groupValues[1] to it.groupValues[2] }
            val license = licensePattern.find(script)?.groupValues?.get(1)
            playerUrl.findAll(script).mapNotNull { match ->
                val key = match.groupValues[1]
                if (Regex("""['"]?${Regex.escape(key)}_redirect['"]?\s*:\s*['"]?1""").containsMatchIn(script)) {
                    return@mapNotNull null
                }
                var raw = unescape(match.groupValues[2])
                if (raw.startsWith("function/0/")) {
                    raw = kvsDecodeUrl(raw, license ?: return@mapNotNull null)
                }
                absoluteUrl(raw, doc)?.toHttpUrlOrNull()?.takeIf(::isMediaUrl)?.let {
                    quality(labels[key].orEmpty()).takeIf { n -> n > 0 }?.let { n -> n to it }
                        ?: (quality(it.encodedPath) to it)
                }
            }.toList()
        }
        val downloads = doc.select(
            ".vp-sheet[data-sheet=download] a.tag_item_download, .row:has(.label:contains(Download)) a.tag_item",
        )
            .mapNotNull { a ->
                val url = absoluteUrl(a.attr("href"), doc)?.toHttpUrlOrNull()?.takeIf(::isMediaUrl)
                    ?: return@mapNotNull null
                quality(a.text()) to url.newBuilder().removeAllQueryParameters("download")
                    .removeAllQueryParameters("download_filename").build()
            }
        val html = doc.select("video source[src], video[src]").mapNotNull { a ->
            val url = absoluteUrl(a.attr("src"), doc)?.toHttpUrlOrNull()?.takeIf(::isMediaUrl)
                ?: return@mapNotNull null
            quality(a.attr("title").ifBlank { a.attr("label") }).takeIf { it > 0 }
                .let { (it ?: quality(url.encodedPath)) to url }
        }
        val preferred = config[preferredQuality].removeSuffix("p").toIntOrNull()?.takeIf { it > 0 } ?: 1080
        // Keep fallback families available when a player's token or quality is no longer accessible.
        return listOf(player, downloads, html).flatMap { candidates ->
            candidates.sortedWith(compareBy<Pair<Int, HttpUrl>> {
                if (it.first <= preferred) 0 else 1
            }.thenBy { if (it.first <= preferred) -it.first else it.first }).map { it.second }
        }.distinct()
    }

    private suspend fun resolveStream(initial: HttpUrl, pageUrl: HttpUrl): HttpUrl? {
        var current = initial
        repeat(6) {
            val request = Request.Builder().url(current).headers(playbackHeaders(current, pageUrl))
                .tag(org.skepsun.kototoro.parsers.model.ContentSource::class.java, source).build()
            redirectClient.newCall(request).await().use { response ->
                when {
                    response.isRedirect -> {
                        val next = response.header("Location")?.let(current::resolve) ?: return null
                        if (current.scheme == "https" && next.scheme != "https") return null
                        current = next
                    }
                    response.isSuccessful -> {
                        val type = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
                        return current.takeIf {
                            type.startsWith("video/") || type.contains("mpegurl") ||
                                type == "application/octet-stream" || (type.isEmpty() && isMediaUrl(current))
                        }
                    }
                    else -> return null
                }
            }
        }
        return null
    }

    internal fun playbackHeaders(target: HttpUrl, pageUrl: HttpUrl): Headers = getRequestHeaders().newBuilder().apply {
        set("Referer", pageUrl.toString())
        set("Accept", "video/*,application/vnd.apple.mpegurl,application/octet-stream;q=0.9,*/*;q=0.5")
        if (target.host == pageUrl.host || target.host.endsWith(".${pageUrl.host}")) {
            val cookies = context.cookieJar.loadForRequest(target)
                .filterNot { it.name in setOf("__ddgid_", "__ddgmark_") }
            if (cookies.isNotEmpty()) set("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
            cookies.firstOrNull { it.name == "XSRF-TOKEN" }?.value?.let { set("X-XSRF-TOKEN", it.replace("%3D", "=")) }
        }
    }.build()

    private fun sitePath(value: String): String? {
        val base = "https://$domain/".toHttpUrl()
        val url = base.resolve(value) ?: return null
        if (url.host != base.host || url.port != base.port || url.scheme != base.scheme) return null
        return url.encodedPath
    }

    private fun absoluteUrl(value: String, doc: Document): String? = value.takeIf { it.isNotBlank() }?.let {
        (doc.location().toHttpUrlOrNull() ?: "https://$domain/".toHttpUrl()).resolve(unescape(it))?.toString()
    }

    private fun unescape(value: String): String = Parser.unescapeEntities(value.replace("\\/", "/"), false)
    private fun quality(value: String): Int = qualityPattern.find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    private fun isMediaUrl(url: HttpUrl): Boolean = url.encodedPath.lowercase().trimEnd('/').let {
        it.contains("/get_file/") || it.endsWith(".mp4") || it.endsWith(".webm") || it.endsWith(".m3u8")
    }

    companion object {
        private val videoPath = Regex("""/videos?/(\d+)(?:/[^/]*)?/?""")
        private val browsePath = Regex("""/(categories|tags|models)/([^/]+)/?""")
        private val slugPattern = Regex("""[\p{L}\p{N}_-]+""")
        private val slugSeparators = Regex("""[^\p{L}\p{N}]+""")
        private val qualityPattern = Regex("""(\d{3,4})p\b""")
        private val playerUrl = Regex("""['"]?(video(?:_alt)?_url\d*)['"]?\s*:\s*['"]([^'"]+)['"]""")
        private val playerText = Regex("""['"]?(video(?:_alt)?_url\d*)_text['"]?\s*:\s*['"]([^'"]+)['"]""")
        private val licensePattern = Regex("""['"]?license_code['"]?\s*:\s*['"]([^'"]+)['"]""")
    }
}
