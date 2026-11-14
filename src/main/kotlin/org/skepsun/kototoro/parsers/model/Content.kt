package org.skepsun.kototoro.parsers.model

import androidx.collection.ArrayMap
import org.skepsun.kototoro.parsers.util.findById
import org.skepsun.kototoro.parsers.util.nullIfEmpty


/** Optional website facts do not change Content's constructor or copy ABI. */
public object WebsiteWorkMetadata {
    private val records = HashMap<ContentReference, String>()
    private val queue = java.lang.ref.ReferenceQueue<Content>()

    private class ContentReference(content: Content, queue: java.lang.ref.ReferenceQueue<Content>? = null) :
        java.lang.ref.WeakReference<Content>(content, queue) {
        private val identityHash = System.identityHashCode(content)
        override fun hashCode(): Int = identityHash
        override fun equals(other: Any?): Boolean = this === other ||
            (other is ContentReference && get() != null && get() === other.get())
    }

    private fun prune() {
        while (true) records.remove(queue.poll() ?: return)
    }

    private fun store(content: Content, value: String) {
        prune()
        records[ContentReference(content, queue)] = value
    }

    public data class RelatedWork(public val source: String, public val url: String, public val title: String)

    public fun attach(content: Content, totalPages: Int? = null,
        parodies: Collection<String> = emptyList(), characters: Collection<String> = emptyList(),
    ): Content = attachFields(content, totalPages, parodies, characters)

    /** Omitted fields preserve facts; a numeric null or an empty collection explicitly clears a field. */
    public fun attachFields(
        content: Content,
        totalPages: Int? = Int.MIN_VALUE,
        parodies: Collection<String>? = null,
        characters: Collection<String>? = null,
        groups: Collection<String>? = null,
        producers: Collection<String>? = null,
        types: Collection<String>? = null,
        languages: Collection<String>? = null,
        subtitleLanguages: Collection<String>? = null,
        audioLanguages: Collection<String>? = null,
        durationSeconds: Long? = Long.MIN_VALUE,
        chapterCount: Int? = Int.MIN_VALUE,
        series: Collection<String>? = null,
        relatedWorks: Collection<RelatedWork>? = null,
    ): Content = synchronized(records) {
        fun names(values: Collection<String>) = org.json.JSONArray(
            values.map(String::trim).filter(String::isNotEmpty).distinct(),
        )
        val metadata = (get(content)?.let { org.json.JSONObject(it) } ?: org.json.JSONObject()).apply {
            if (totalPages != Int.MIN_VALUE) put("total_pages", totalPages?.takeIf { it > 0 } ?: org.json.JSONObject.NULL)
            parodies?.let { put("parodies", names(it)) }
            characters?.let { put("characters", names(it)) }
            groups?.let { put("groups", names(it)) }
            producers?.let { put("producers", names(it)) }
            types?.let { put("types", names(it)) }
            languages?.let { put("languages", names(it)) }
            subtitleLanguages?.let { put("subtitle_languages", names(it)) }
            audioLanguages?.let { put("audio_languages", names(it)) }
            if (durationSeconds != Long.MIN_VALUE) {
                put("duration_seconds", durationSeconds?.takeIf { it > 0 } ?: org.json.JSONObject.NULL)
            }
            if (chapterCount != Int.MIN_VALUE) {
                put("chapter_count", chapterCount?.takeIf { it > 0 } ?: org.json.JSONObject.NULL)
            }
            series?.let { put("series", names(it)) }
            relatedWorks?.let { works ->
                val values = works.map { work ->
                    work.copy(source = work.source.trim(), url = work.url.trim(), title = work.title.trim())
                }.filter { it.source.isNotEmpty() && it.url.isNotEmpty() && it.title.isNotEmpty() }
                    .distinctBy { it.source to it.url }.map { work ->
                        org.json.JSONObject().put("source", work.source).put("url", work.url).put("title", work.title)
                    }
                put("related_works", org.json.JSONArray(values))
            }
        }
        if (metadata.length() > 0) store(content, metadata.toString())
        content
    }

    /** Copy only absent fields so a fresh detail response wins over earlier list metadata. */
    public fun inherit(previous: Content, content: Content): Content = synchronized(records) {
        val before = get(previous)?.let { org.json.JSONObject(it) } ?: return@synchronized content
        val current = get(content)?.let { org.json.JSONObject(it) } ?: org.json.JSONObject()
        before.keys().forEach { key -> if (!current.has(key)) current.put(key, before.get(key)) }
        store(content, current.toString())
        content
    }

    public fun get(content: Content): String? = synchronized(records) {
        prune()
        records[ContentReference(content)]
    }
}

public data class Content(
	/**
	 * Unique identifier for manga
	 */
	@JvmField public val id: Long,
	/**
	 * Content title, human-readable
	 */
	@JvmField public val title: String,
	/**
	 * Alternative titles (for example on other language), may be empty
	 */
	@JvmField public val altTitles: Set<String>,
	/**
	 * Relative url to manga (**without** a domain) or any other uri.
	 * Used principally in parsers
	 */
	@JvmField public val url: String,
	/**
	 * Absolute url to manga, must be ready to open in browser
	 */
	@JvmField public val publicUrl: String,
	/**
	 * Normalized manga rating, must be in range of 0..1 or [RATING_UNKNOWN] if rating s unknown
	 * @see hasRating
	 */
	@JvmField public val rating: Float,
	/**
	 * Indicates that manga may contain sensitive information (18+, NSFW)
	 */
	@JvmField public val contentRating: ContentRating?,
	/**
	 * Absolute link to the cover
	 * @see largeCoverUrl
	 */
	@JvmField public val coverUrl: String?,
	/**
	 * Tags (genres) of the manga
	 */
	@JvmField public val tags: Set<ContentTag>,
	/**
	 * Content status (ongoing, finished) or null if unknown
	 */
	@JvmField public val state: ContentState?,
	/**
	 * Authors of the manga
	 */
	@JvmField public val authors: Set<String>,
	/**
	 * Large cover url (absolute), null if is no large cover
	 * @see coverUrl
	 */
	@JvmField public val largeCoverUrl: String? = null,
	/**
	 * Content description, may be html or null
	 */
	@JvmField public val description: String? = null,
	/**
	 * List of chapters
	 */
	@JvmField public val chapters: List<ContentChapter>? = null,
	/**
	 * Content source
	 */
	@JvmField public val source: ContentSource,
) {

	@Deprecated("Accepts rating as Int; use Float in range 0..1 instead")
	public constructor(
		id: Long,
		title: String,
		altTitles: Set<String>,
		url: String,
		publicUrl: String,
		rating: Int,
		contentRating: ContentRating?,
		coverUrl: String?,
		tags: Set<ContentTag>,
		state: ContentState?,
		authors: Set<String>,
		largeCoverUrl: String? = null,
		description: String? = null,
		chapters: List<ContentChapter>? = null,
		source: ContentSource,
	) : this(
		id = id,
		title = title,
		altTitles = altTitles,
		url = url,
		publicUrl = publicUrl,
		rating = rating.toFloat(),
		contentRating = contentRating,
		coverUrl = coverUrl?.nullIfEmpty(),
		tags = tags,
		state = state,
		authors = authors,
		largeCoverUrl = largeCoverUrl?.nullIfEmpty(),
		description = description?.nullIfEmpty(),
		chapters = chapters,
		source = source,
	)

	@Deprecated("Use other constructor")
	public constructor(
		/**
		 * Unique identifier for manga
		 */
		id: Long,
		/**
		 * Content title, human-readable
		 */
		title: String,
		/**
		 * Alternative title (for example on other language), may be null
		 */
		altTitle: String?,
		/**
		 * Relative url to manga (**without** a domain) or any other uri.
		 * Used principally in parsers
		 */
		url: String,
		/**
		 * Absolute url to manga, must be ready to open in browser
		 */
		publicUrl: String,
		/**
		 * Normalized manga rating, must be in range of 0..1 or [RATING_UNKNOWN] if rating s unknown
		 * @see hasRating
		 */
		rating: Float,
		/**
		 * Indicates that manga may contain sensitive information (18+, NSFW)
		 */
		isNsfw: Boolean,
		/**
		 * Absolute link to the cover
		 * @see largeCoverUrl
		 */
		coverUrl: String?,
		/**
		 * Tags (genres) of the manga
		 */
		tags: Set<ContentTag>,
		/**
		 * Content status (ongoing, finished) or null if unknown
		 */
		state: ContentState?,
		/**
		 * Authors of the manga
		 */
		author: String?,
		/**
		 * Large cover url (absolute), null if is no large cover
		 * @see coverUrl
		 */
		largeCoverUrl: String? = null,
		/**
		 * Content description, may be html or null
		 */
		description: String? = null,
		/**
		 * List of chapters
		 */
		chapters: List<ContentChapter>? = null,
		/**
		 * Content source
		 */
		source: ContentSource,
	) : this(
		id = id,
		title = title,
		altTitles = setOfNotNull(altTitle?.nullIfEmpty()),
		url = url,
		publicUrl = publicUrl,
		rating = rating,
		contentRating = if (isNsfw) ContentRating.ADULT else null,
		coverUrl = coverUrl?.nullIfEmpty(),
		tags = tags,
		state = state,
		authors = setOfNotNull(author),
		largeCoverUrl = largeCoverUrl?.nullIfEmpty(),
		description = description?.nullIfEmpty(),
		chapters = chapters,
		source = source,
	)

	/**
	 * Author of the manga, may be null
	 */
	@Deprecated("Please use authors")
	public val author: String?
		get() = authors.firstOrNull()

	/**
	 * Alternative title (for example on other language), may be null
	 */
	@Deprecated("Please use altTitles")
	public val altTitle: String?
		get() = altTitles.firstOrNull()

	/**
	 * Return if manga has a specified rating
	 * @see rating
	 */
	public val hasRating: Boolean
		get() = rating > 0f && rating <= 1f

	@Deprecated("Use contentRating instead", ReplaceWith("contentRating == ContentRating.ADULT"))
	public val isNsfw: Boolean
		get() = contentRating == ContentRating.ADULT

	public fun getChapters(branch: String?): List<ContentChapter> {
		return chapters?.filter { x -> x.branch == branch }.orEmpty()
	}

	public fun findChapterById(id: Long): ContentChapter? = chapters?.findById(id)

	public fun requireChapterById(id: Long): ContentChapter = findChapterById(id)
		?: throw NoSuchElementException("Chapter with id $id not found")

	public fun getBranches(): Map<String?, Int> {
		if (chapters.isNullOrEmpty()) {
			return emptyMap()
		}
		val result = ArrayMap<String?, Int>()
		chapters.forEach {
			val key = it.branch
			result[key] = result.getOrDefault(key, 0) + 1
		}
		return result
	}
}
