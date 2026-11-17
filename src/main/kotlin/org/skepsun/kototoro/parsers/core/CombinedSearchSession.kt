package org.skepsun.kototoro.parsers.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.site.all.NhentaiParser
import org.skepsun.kototoro.parsers.site.zh.JmParser

/** Independent website cursors are unrelated to the number of filtered results already shown. */
internal class CombinedSearchSession<T>(
    private val loaders: List<suspend (Int) -> CombinedSearchPage<T>>,
    private val key: (T) -> String,
    private val rank: (T) -> Long,
    private val accepts: suspend (T) -> Boolean,
) {
    private val mutex = Mutex()
    private val cursors = loaders.map { Cursor(it) }
    private val results = ArrayList<T>()
    private val seen = HashSet<String>()

    private class Cursor<T>(val load: suspend (Int) -> CombinedSearchPage<T>) {
        var page = 0
        var items = emptyList<T>()
        var index = 0
        var more = true
        suspend fun head(): T? {
            while (index == items.size && more) {
                currentCoroutineContext().ensureActive()
                val next = load(page) // Commit the cursor only after a successful response.
                check(page == 0 || next.items.isEmpty() || next.items != items) { "网站重复返回同一页，请刷新后重试" }
                items = next.items
                index = 0
                more = next.hasMore
                page++
            }
            return items.getOrNull(index)
        }
    }

    suspend fun read(offset: Int, limit: Int): List<T> = mutex.withLock {
        require(offset >= 0 && limit in 1..100)
        while (results.size.toLong() < offset.toLong() + limit) {
            currentCoroutineContext().ensureActive()
            var selected: Cursor<T>? = null
            var candidate: T? = null
            for (cursor in cursors) {
                val head = cursor.head() ?: continue
                if (selected == null || rank(head) > rank(checkNotNull(candidate))) {
                    selected = cursor
                    candidate = head
                }
            }
            val cursor = selected ?: break
            val item = checkNotNull(candidate)
            val identity = key(item)
            if (identity !in seen) {
                val accepted = accepts(item) // Failures/cancellation leave this candidate pending.
                currentCoroutineContext().ensureActive()
                seen.add(identity)
                if (accepted) results.add(item)
            }
            cursor.index++
        }
        if (offset >= results.size) emptyList()
        else results.subList(offset, minOf(results.size.toLong(), offset.toLong() + limit).toInt()).toList()
    }
}

internal data class CombinedSearchPage<T>(val items: List<T>, val hasMore: Boolean,
    val orderKeys: Map<String, Long> = emptyMap(),
)

/** Primitive JSON boundary avoids adding host-only interface classes to a plugin for older apps. */
internal class WebsiteCombinedSearch(private val parser: ContentParser) {
    private val isNh = parser is NhentaiParser
    private val fields = if (isNh) setOf("title", "author", "tag", "parody", "character", "group", "type",
        "language", "pages", "translated", "full_color", "uncensored")
        else setOf("title", "author", "tag", "number", "parody", "character", "type", "pages",
            "translated", "full_color", "uncensored")
    private val sessions = LinkedHashMap<String, Search>()
    private val sessionMutex = Mutex()
    private class Search(val session: CombinedSearchSession<Content>, val resolved: MutableMap<Long, Content>)

    fun capabilities(): String = JSONObject().put("fields", org.json.JSONArray(fields.toList()))
        .put("supportsAll", true).put("supportsAny", true).put("supportsNot", true)
        .put("supportsNesting", true).put("supportsKeyword", true).toString()

    fun problem(keyword: String, encoded: String): String? = try {
        require(keyword.length <= 500) { "搜索词过长" }
        val expression = JSONObject(encoded)
        var count = 0
        fun validate(node: JSONObject, depth: Int, negated: Boolean) {
            require(++count <= 128 && depth <= 12) { "搜索条件过多或分组过深" }
            when (node.getString("type")) {
                "term" -> {
                    val field = node.getString("field")
                    require(field in fields) { "此来源不支持「$field」条件" }
                    require(!negated || field == "tag") { "仅标签支持排除" }
                    require(node.getString("value").isNotBlank()) { "搜索条件不能为空" }
                }
                "not" -> validate(node.getJSONObject("child"), depth + 1, true)
                "all", "any" -> {
                    val children = node.getJSONArray("children")
                    require(children.length() > 0) { "条件分组不能为空" }
                    for (i in 0 until children.length()) validate(children.getJSONObject(i), depth + 1, negated)
                }
                else -> error("无法读取搜索条件")
            }
        }
        validate(expression, 0, false)
        candidateBranches(expression)
        null
    } catch (e: Exception) { e.message ?: "无法读取搜索条件" }

    suspend fun read(offset: Int, orderName: String, keyword: String, encoded: String,
        matches: (Content) -> Boolean,
    ): List<Content> {
        problem(keyword, encoded)?.let { throw UnsupportedOperationException(it) }
        val order = SortOrder.valueOf(orderName)
        require(order in parser.availableSortOrders) { "此来源不支持所选排序" }
        val identity = listOf(parser.domain, parser.config.toString(), orderName, keyword, encoded).joinToString("\u0000")
        val search = sessionMutex.withLock {
            if (offset == 0) sessions.remove(identity)
            sessions[identity] ?: run {
                val resolved = HashMap<Long, Content>()
                val expression = JSONObject(encoded)
                // A necessary positive term gives a complete candidate stream. Alternatives without
                // a shared positive term use the site's full listing, evaluated lazily page by page.
                val branches = if (keyword.isBlank()) candidateBranches(expression) else listOf(expression)
                require(branches.size == 1 || order in setOf(SortOrder.NEWEST, SortOrder.UPDATED)) {
                    "多次查询合并目前仅支持最新排序，请切换排序后重试"
                }
                val ranks = HashMap<String, Long>()
                val loaders = branches.map { branch ->
                    val seed = necessaryTerms(branch).firstOrNull { it.first in seedFields && safe(it.second) }
                    var previousRank = Long.MAX_VALUE
                    val loader: suspend (Int) -> CombinedSearchPage<Content> = { page ->
                        val response = when (parser) {
                            is NhentaiParser -> parser.getCombinedCandidates(page + 1, order, keyword, branch)
                            is JmParser -> parser.getCombinedCandidates(page + 1, order, keyword, seed?.first, seed?.second, branch)
                            else -> error("此来源尚未接入组合搜索")
                        }
                        if (branches.size > 1) {
                            var nextRank = previousRank
                            val nextRanks = HashMap<String, Long>()
                            for (item in response.items) {
                                val sortKey = response.orderKeys[item.url]
                                    ?: error("网站未返回合并所需的排序资料")
                                check(sortKey <= nextRank) { "网站结果顺序发生变化，请刷新后重试" }
                                nextRank = sortKey
                                nextRanks[item.url] = sortKey
                            }
                            previousRank = nextRank
                            ranks.putAll(nextRanks)
                        }
                        response
                    }
                    loader
                }
                val session = CombinedSearchSession(loaders, { it.id.toString() }, { ranks[it.url] ?: 0L }) { item ->
                    val content = resolved[item.id] ?: when (parser) {
                        is JmParser -> parser.getDetails(item)
                        else -> item
                    }.also { resolved[item.id] = it }
                    matches(content).also { if (!it) resolved.remove(item.id) }
                }
                Search(session, resolved).also {
                    if (sessions.size >= 8) sessions.remove(sessions.keys.first())
                    sessions[identity] = it
                }
            }
        }
        return search.session.read(offset, if (isNh) 25 else 20).map { search.resolved[it.id] ?: it }
    }

    companion object {
        private val seedFields = setOf("tag", "author", "parody", "character")
        internal fun safe(value: String): Boolean = value.isNotBlank() && value.none {
            it in "+-~*%\"\\()[]{}:|" || it.isISOControl()
        }
        internal fun necessaryTerms(node: JSONObject): List<Pair<String, String>> = when (node.getString("type")) {
            "term" -> listOf(node.getString("field") to node.getString("value"))
            "all" -> node.getJSONArray("children").let { children ->
                (0 until children.length()).flatMap { necessaryTerms(children.getJSONObject(it)) }
            }
            else -> emptyList()
        }

        private fun candidateBranches(node: JSONObject): List<JSONObject> {
            if (necessaryTerms(node).any { it.first in seedFields && safe(it.second) }) return listOf(node)
            val branches = if (node.optString("type") == "any") {
                node.getJSONArray("children").let { children ->
                    (0 until children.length()).map { children.getJSONObject(it) }
                }
            } else if (node.optString("type") == "all") {
                val children = node.getJSONArray("children")
                val alternatives = (0 until children.length()).firstOrNull {
                    children.getJSONObject(it).optString("type") == "any"
                } ?: return listOf(node)
                val options = children.getJSONObject(alternatives).getJSONArray("children")
                (0 until options.length()).map { option ->
                    JSONObject().put("type", "all").put("children", org.json.JSONArray(
                        (0 until children.length()).map { if (it == alternatives) options.getJSONObject(option) else children.getJSONObject(it) },
                    ))
                }
            } else return listOf(node)
            require(branches.size <= 16) { "搜索分支过多，请精简条件" }
            return branches
        }
    }
}
