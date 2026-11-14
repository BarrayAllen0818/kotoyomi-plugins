package org.skepsun.kototoro.parsers.site.all

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.nodes.Entities
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
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.json.getStringOrNull
import org.skepsun.kototoro.parsers.util.json.mapJSON
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.parseJsonArray
import org.skepsun.kototoro.parsers.util.parseSafe
import java.math.BigInteger
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

// API fields and X-Version follow yt-dlp's iwara extractor and LoveIwara's public API client.
@ContentSourceParser("IWARA", "Iwara", type = ContentType.HENTAI_VIDEO)
internal class Iwara(context: ContentLoaderContext) : PagedContentParser(context, ContentParserSource.IWARA, 32) {
    override val configKeyDomain = ConfigKey.Domain("iwara.tv")
    private val preferredQuality = ConfigKey.Text("preferred_quality", "偏好画质高度（如 1080）", "1080")
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.RATING, SortOrder.RELEVANCE,
    )
    override val filterCapabilities = ContentListFilterCapabilities(isSearchSupported = true)

    init {
        setFirstPage(0)
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(preferredQuality)
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .set("Referer", "https://$domain/")
        .set("Origin", "https://$domain")
        .build()

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val url = apiUrl("tags").addQueryParameter("page", "0").addQueryParameter("limit", "100").build()
        return ContentListFilterOptions(
            availableTags = results(readJson(url), url.toString()).mapItems(url.toString()) { parseTag(it) }.toSet(),
            availableContentTypes = setOf(ContentType.HENTAI_VIDEO),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        require(page >= 0) { "Iwara: invalid page" }
        require(filter.tags.size <= 1) { "Iwara: select only one tag" }
        require(filter.tagsExclude.isEmpty()) { "Iwara: tag exclusion is not supported" }
        val query = filter.query.orEmpty().trim()
        require(query.isEmpty() || filter.tags.isEmpty()) { "Iwara: keyword search cannot be combined with a tag" }
        require(order in availableSortOrders) { "Iwara: unsupported sort order $order" }
        if (query.startsWith("https://") || query.startsWith("http://")) {
            val id = videoId(query) ?: throw IllegalArgumentException("Iwara: unsupported video URL")
            return if (page == 0) listOf(parseVideo(fetchVideo(id), details = true)) else emptyList()
        }
        val builder = apiUrl(if (query.isEmpty()) "videos" else "search")
            .addQueryParameter("page", page.toString()).addQueryParameter("limit", pageSize.toString())
        if (query.isNotEmpty()) builder.addQueryParameter("type", "videos").addQueryParameter("query", query)
        val sort = when (order) {
            SortOrder.UPDATED -> "date"
            SortOrder.POPULARITY -> "views"
            SortOrder.RATING -> "likes"
            else -> if (query.isEmpty()) "date" else null
        }
        if (sort != null) builder.addQueryParameter("sort", sort)
        filter.tags.singleOrNull()?.let { tag ->
            require(tag.source == source && tag.key.isNotBlank()) { "Iwara: invalid tag" }
            builder.addQueryParameter("tags", tag.key)
        }
        val url = builder.build()
        return results(readJson(url), url.toString())
            .mapItems(url.toString()) { parseVideo(it, details = false) }.distinctBy { it.id }
    }

    override suspend fun getDetails(manga: Content): Content {
        val id = videoId(manga.url) ?: throw ParseException("Iwara: invalid video URL", manga.url)
        val parsed = parseVideo(fetchVideo(id), details = true)
        return org.skepsun.kototoro.parsers.model.WebsiteWorkMetadata.inherit(
            parsed, parsed.copy(id = manga.id, url = manga.url, source = manga.source),
        )
    }

    override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Content? {
        val id = videoId(link.toString()) ?: return null
        return parseVideo(fetchVideo(id), details = true)
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val id = videoId(chapter.url) ?: throw ParseException("Iwara: invalid playback URL", chapter.url)
        // File and media URLs expire: keep only the stable video ID in chapters.
        val video = fetchVideo(id)
        val file = video.getStringOrNull("fileUrl")?.toHttpUrlOrNull()
        if (file == null) {
            val reason = when {
                !video.getStringOrNull("embedUrl").isNullOrEmpty() -> "此视频由外部网站提供，请在网站打开"
                video.optBoolean("private") -> "此视频需要账号权限，首版不支持登录"
                else -> "video fileUrl not found; video may be unavailable or still processing"
            }
            throw ParseException("Iwara: $reason", "https://$domain/video/$id")
        }
        val rootDomain = domain.removePrefix("www.")
        if (!isSafeHttps(file) || file.host !in setOf("api.$rootDomain", "files.$rootDomain", "filesq.$rootDomain") ||
            !file.encodedPath.startsWith("/file/")) {
            throw ParseException("Iwara: invalid file endpoint ${file.host}${file.encodedPath}",
                "https://$domain/video/$id")
        }
        val expires = file.queryParameter("expires")?.takeIf { it.toLongOrNull() != null }
            ?: throw ParseException("Iwara: file URL has no expires value", "https://$domain/video/$id")
        val filename = file.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() }
            ?: throw ParseException("Iwara: file ID not found", "https://$domain/video/$id")
        val signature = BigInteger(1, MessageDigest.getInstance("SHA-1").digest(
            "${filename}_${expires}_$X_VERSION_SALT".toByteArray(Charsets.UTF_8),
        )).toString(16).padStart(40, '0')
        val streams = try {
            webClient.httpGet(file, getRequestHeaders().newBuilder()
                .set("Accept", "application/json").set("X-Version", signature).build()).parseJsonArray()
        } catch (e: JSONException) {
            throw ParseException("Iwara: invalid video source response", "https://$domain/video/$id", e)
        }
        val stream = selectStream(streams, "https://$domain/video/$id")
            ?: throw ParseException("Iwara: no playable video source found", "https://$domain/video/$id")
        return listOf(ContentPage(
            id = generateUid("page:$id"), url = stream.toString(), preview = null,
            headers = getRequestHeaders().toMap(), source = source,
        ))
    }

    private fun selectStream(streams: JSONArray, publicUrl: String): HttpUrl? {
        val candidates = streams.mapItems(publicUrl) { item ->
            val name = item.getStringOrNull("name").orEmpty()
            val src = item.optJSONObject("src")
            val url = if (item.getStringOrNull("type")?.startsWith("video/") == true) {
                sequenceOf("view", "download").mapNotNull { key ->
                    src?.getStringOrNull(key)?.let { if (it.startsWith("//")) "https:$it" else it }
                        ?.toHttpUrlOrNull()?.takeIf(::isSafeHttps)
                }.firstOrNull()
            } else null
            Triple(name.toIntOrNull() ?: 0, name, url)
        }.filter { it.third != null }
        val preferred = config[preferredQuality].removeSuffix("p").toIntOrNull()?.takeIf { it > 0 } ?: 1080
        val numbered = candidates.filter { it.first > 0 }
        val choice = numbered.filter { it.first <= preferred }.maxByOrNull { it.first }
            ?: numbered.minByOrNull { it.first }
            ?: candidates.firstOrNull { it.second == "Source" }
        return choice?.third
    }

    private suspend fun fetchVideo(id: String): JSONObject {
        val url = apiUrl("video").addPathSegment(id).build()
        val json = readJson(url)
        if (json.getStringOrNull("id") != id) throw ParseException("Iwara: video ID mismatch", url.toString())
        return json
    }

    private suspend fun readJson(url: HttpUrl): JSONObject {
        val result = try {
            val headers = getRequestHeaders().newBuilder().set("Accept", "application/json").build()
            webClient.httpGet(url, headers).parseJson()
        } catch (e: JSONException) {
            throw ParseException("Iwara: expected JSON; the site may require browser verification", url.toString(), e)
        }
        result.getStringOrNull("message")?.let { message ->
            throw ParseException("Iwara: $message", url.toString())
        }
        return result
    }

    private fun results(json: JSONObject, url: String): JSONArray = json.optJSONArray("results")
        ?: throw ParseException("Iwara: results not found", url)

    private inline fun <T> JSONArray.mapItems(url: String, mapper: (JSONObject) -> T): List<T> = try {
        mapJSON(mapper)
    } catch (e: JSONException) {
        throw ParseException("Iwara: malformed response item", url, e)
    }

    private fun parseTag(json: JSONObject): ContentTag {
        val id = json.getStringOrNull("id")?.takeIf { it.isNotBlank() }
            ?: throw ParseException("Iwara: tag ID not found", "https://$domain/")
        return ContentTag(id, id, source)
    }

    private fun parseVideo(json: JSONObject, details: Boolean): Content {
        val id = json.getStringOrNull("id")?.takeIf { VIDEO_ID.matches(it) }
            ?: throw ParseException("Iwara: video ID not found", "https://$domain/")
        val path = "/video/$id"
        val title = json.getStringOrNull("title")?.takeIf { it.isNotBlank() }
            ?: throw ParseException("Iwara: video title not found", "https://$domain$path")
        val tags = json.optJSONArray("tags")?.mapItems("https://$domain$path") { parseTag(it) }?.toSet().orEmpty()
        val cover = thumbnail(json)
        val user = json.optJSONObject("user")
        val uploader = user?.getStringOrNull("name")?.trim()?.takeIf(String::isNotEmpty)
            ?: user?.getStringOrNull("username")?.trim()?.takeIf(String::isNotEmpty)
        val description = if (details) buildString {
            json.getStringOrNull("body")?.let {
                append("<p>").append(Entities.escape(it).replace("\n", "<br>")).append("</p>")
            }
        }.ifEmpty { null } else null
        val chapter = if (details) listOf(ContentChapter(
            id = generateUid("chapter:$id"), url = path, title = "播放", number = 1f, volume = 0,
            uploadDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parseSafe(json.getStringOrNull("createdAt")),
            branch = null, scanlator = null, source = source,
        )) else null
        return Content(
            id = generateUid(id), title = title, altTitles = emptySet(), url = path,
            publicUrl = "https://$domain$path", rating = RATING_UNKNOWN,
            contentRating = if (json.getStringOrNull("rating") == "general") {
                ContentRating.SAFE
            } else ContentRating.ADULT,
            coverUrl = cover, largeCoverUrl = cover, tags = tags, state = ContentState.FINISHED,
            authors = setOfNotNull(uploader), description = description, chapters = chapter, source = source,
        ).let { content ->
            org.skepsun.kototoro.parsers.model.WebsiteWorkMetadata.attachFields(
                content,
                durationSeconds = if (json.optJSONObject("file")?.has("duration") == true) {
                    (json.getJSONObject("file").opt("duration") as? Number)?.toLong()
                } else Long.MIN_VALUE,
                types = json.optJSONArray("tags")?.mapItems(content.publicUrl) { it }
                    ?.filter { it.getStringOrNull("type") == "category" }
                    ?.mapNotNull { it.getStringOrNull("id") },
            )
        }
    }

    private fun thumbnail(json: JSONObject): String? {
        val custom = json.optJSONObject("customThumbnail")
        val file = custom ?: json.optJSONObject("file") ?: return null
        val id = file.getStringOrNull("id") ?: return null
        val name = if (custom != null) custom.getStringOrNull("name") ?: return null else {
            "thumbnail-${json.optInt("thumbnail", 0).coerceAtLeast(0).toString().padStart(2, '0')}.jpg"
        }
        return "https://files.${domain.removePrefix("www.")}/image/thumbnail/".toHttpUrl().newBuilder()
            .addPathSegment(id).addPathSegment(name).build().toString()
    }

    private fun videoId(value: String): String? {
        val url = "https://$domain/".toHttpUrl().resolve(value) ?: return null
        val rootDomain = domain.removePrefix("www.")
        if (!isSafeHttps(url) || url.host !in setOf(rootDomain, "www.$rootDomain")) return null
        val path = url.pathSegments
        return path.getOrNull(1)?.takeIf { path.firstOrNull() in setOf("video", "videos") && VIDEO_ID.matches(it) }
    }

    private fun apiUrl(path: String): HttpUrl.Builder = "https://api.${domain.removePrefix("www.")}/".toHttpUrl()
        .newBuilder().addPathSegment(path)

    private fun isSafeHttps(url: HttpUrl): Boolean = url.isHttps && url.username.isEmpty() && url.password.isEmpty()

    private companion object {
        val VIDEO_ID = Regex("[A-Za-z0-9]+")
        const val X_VERSION_SALT = "mSvL05GfEmeEmsEYfGCnVpEjYgTJraJN"
    }
}
