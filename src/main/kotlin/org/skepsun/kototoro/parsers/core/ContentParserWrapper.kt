package org.skepsun.kototoro.parsers.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import org.skepsun.kototoro.parsers.FavoritesProvider
import org.skepsun.kototoro.parsers.FavoritesSyncProvider
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.model.search.ContentSearchQuery
import org.skepsun.kototoro.parsers.util.mergeWith

internal class ContentParserWrapper(
	private val delegate: ContentParser,
) : ContentParser by delegate {

	internal val favoritesProvider: FavoritesProvider? = delegate as? FavoritesProvider
	internal val favoritesSyncProvider: FavoritesSyncProvider? = delegate as? FavoritesSyncProvider

	private val combinedSearch by lazy {
		if (delegate is org.skepsun.kototoro.parsers.site.zh.JmParser ||
			delegate is org.skepsun.kototoro.parsers.site.all.NhentaiParser) WebsiteCombinedSearch(delegate) else null
	}

	public fun getCombinedSearchCapabilitiesJson(): String? = combinedSearch?.capabilities()

	public fun getCombinedSearchProblemJson(keyword: String, expression: String): String? =
		combinedSearch?.problem(keyword, expression) ?: if (combinedSearch == null) "此来源尚未接入组合搜索" else null

	public suspend fun getCombinedSearchPageJson(offset: Int, order: String, keyword: String,
		expression: String, matches: (Content) -> Boolean,
	): List<Content> = checkNotNull(combinedSearch) { "此来源尚未接入组合搜索" }
		.read(offset, order, keyword, expression, matches)

	override val authorizationProvider: ContentParserAuthProvider?
		get() = delegate as? ContentParserAuthProvider

	@Deprecated("Too complex. Use getList with filter instead")
	override suspend fun getList(query: ContentSearchQuery): List<Content> = withContext(Dispatchers.Default) {
		if (!query.skipValidation) {
			searchQueryCapabilities.validate(query)
		}
		delegate.getList(query)
	}

	override suspend fun getList(
		offset: Int,
		order: SortOrder,
		filter: ContentListFilter,
	): List<Content> = withContext(Dispatchers.Default) {
		delegate.getList(offset, order, filter)
	}

	// The host receives this wrapper from the factory, so optional facts must be exposed here too.
	public fun getDetailsMetadata(content: Content): String? =
		org.skepsun.kototoro.parsers.model.WebsiteWorkMetadata.get(content)

    public suspend fun getImageCandidatesJson(url: String): String? =
        (delegate as? org.skepsun.kototoro.parsers.site.all.NhentaiParser)?.imageCandidates(url)

	override suspend fun getDetails(manga: Content): Content = withContext(Dispatchers.Default) {
		WebsiteWorkMetadata.inherit(manga, delegate.getDetails(manga))
	}

	override suspend fun getPages(chapter: ContentChapter): List<ContentPage> = withContext(Dispatchers.Default) {
		delegate.getPages(chapter)
	}

	override suspend fun getPageUrl(page: ContentPage): String = withContext(Dispatchers.Default) {
		delegate.getPageUrl(page)
	}

	override suspend fun getFilterOptions(): ContentListFilterOptions = withContext(Dispatchers.Default) {
		delegate.getFilterOptions()
	}

	override suspend fun getFavicons(): Favicons = withContext(Dispatchers.Default) {
		delegate.getFavicons()
	}

	override suspend fun getRelatedContent(seed: Content): List<Content> = withContext(Dispatchers.Default) {
		delegate.getRelatedContent(seed)
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val headers = request.headers.newBuilder()
			.mergeWith(delegate.getRequestHeaders(), replaceExisting = false)
			.build()
		val newRequest = request.newBuilder().headers(headers).build()
		return delegate.intercept(ProxyChain(chain, newRequest))
	}

	private class ProxyChain(
		private val delegate: Interceptor.Chain,
		private val request: Request,
	) : Interceptor.Chain by delegate {

		override fun request(): Request = request
	}
}
