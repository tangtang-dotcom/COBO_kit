package com.cobo.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

enum class SuperTopicOrder(val key: String, val label: String, val suffix: String) {
    LatestComments("comments", "最新评论", "_-_feed"),
    LatestPosts("posts", "最新发帖", "_-_sort_time"),
}

data class FollowedSuperTopic(
    val containerId: String,
    val title: String,
    val avatar: String = "",
    val desc1: String = "",
    val desc2: String = "",
    val scheme: String = "",
    val followed: Boolean = false,
)

data class FollowedSuperTopicPage(
    val items: List<FollowedSuperTopic>,
    val nextCursor: String?,
)

data class SuperTopicInfo(
    val containerId: String,
    val title: String,
    val description: String,
    val avatar: String = "",
    val readCount: String = "",
    val postCount: String = "",
    val fanCount: String = "",
    /** 超话等级展示文本（如 "LV.7"），来自 pageInfo / 头部卡的 level 字段。 */
    val level: String = "",
    val levelIcon: String = "",
    val levelScheme: String = "",
    /** 超话头衔 / 排名小字（如 "手游超话No.3"），来自 portrait_sub_text / title_sub。 */
    val titleSub: String = "",
    /** 超话排名（urank）。 */
    val rank: String = "",
)

data class SuperTopicPage(
    val info: SuperTopicInfo,
    val items: List<Status>,
    val nextCursor: String?,
)

private val superTopicContainer = Regex("100808[0-9a-fA-F]{32}")
private val superTopicCount = Regex("(阅读|帖子|粉丝)([0-9]+(?:\\.[0-9]+)?[万亿千]?\\+?)(?=阅读|帖子|粉丝|$)")

private fun normalizedSuperTopicId(value: String): String? =
    value.removeSuffix(SuperTopicOrder.LatestComments.suffix)
        .removeSuffix(SuperTopicOrder.LatestPosts.suffix)
        .takeIf { superTopicContainer.matches(it) }?.lowercase()

/** Resolve only known supertopic links. Ordinary topic/search links keep their existing route. */
fun superTopicId(raw: String): String? {
    val value = raw.trim()
    if (superTopicContainer.matches(value)) return value.lowercase()
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (uri.rawUserInfo != null || uri.port != -1) return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val path = uri.rawPath.orEmpty()
    val desktopPage = (scheme == "https" || scheme == "http") &&
        (host == "weibo.com" || host == "www.weibo.com")
    val desktopId = if (desktopPage) {
        if (!path.startsWith("/page/")) return null
        normalizedSuperTopicId(path.removePrefix("/page/")) ?: return null
    } else null
    when (scheme) {
        "https", "http" -> {
            if (uri.rawFragment != null) return null
            if (!desktopPage && (host != "m.weibo.cn" || path != "/p/index")) return null
        }
        "sinaweibo" -> {
            if (host != "pageinfo" || path.isNotEmpty() || uri.rawFragment != null) return null
        }
        "intent" -> {
            if (host != "pageinfo" || path.isNotEmpty()) return null
            val parts = uri.rawFragment?.split(';') ?: return null
            if (parts.firstOrNull() != "Intent" || parts.lastOrNull() != "end" ||
                parts.count { it == "scheme=sinaweibo" } != 1 ||
                parts.any { it.startsWith("scheme=") && it != "scheme=sinaweibo" }
            ) return null
        }
        else -> return null
    }
    val params = runCatching {
        uri.rawQuery.orEmpty().split('&').map { part ->
            val pair = part.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to
                URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
    }.getOrNull() ?: return null
    val ids = params.filter { it.first == "containerid" }
    if (desktopId != null) {
        if (ids.size > 1) return null
        if (ids.isNotEmpty() && normalizedSuperTopicId(ids.single().second) != desktopId) return null
        return desktopId
    }
    if (ids.size != 1) return null
    return normalizedSuperTopicId(ids.single().second)
}

/** pageInfo identity is mandatory, including on an empty or final page. */
fun parseSuperTopic(root: JSONObject, expectedContainerId: String): SuperTopicPage {
    val expected = normalizedSuperTopicId(expectedContainerId)
        ?: throw ApiException("无效的超话地址")
    if (root.optInt("ok", 0) == -100) throw ApiException("超话需要登录后查看", "-100")
    if (root.optInt("ok", 0) != 1) throw ApiException("超话加载失败")
    val data = root.optJSONObject("data") ?: throw ApiException("超话数据缺失")
    val page = data.optJSONObject("pageInfo") ?: throw ApiException("超话信息缺失")
    if (normalizedSuperTopicId(page.str("containerid")) != expected) {
        throw ApiException("超话身份校验失败")
    }
    val title = page.str("page_title").trim()
        .ifBlank { page.str("nick").trim() }
        .ifBlank { page.str("title_top").trim() }
    if (title.isBlank()) throw ApiException("超话名称缺失")
    val items = LinkedHashMap<String, Status>()
    collectSuperTopicCards(data.optJSONArray("cards"), items)
    // Read as a string directly. These numeric cursors exceed Int and must never pass through Double.
    val cursor = page.str("since_id").takeIf { it.matches(Regex("[0-9]+")) && it.any { digit -> digit != '0' } }
    val counts = LinkedHashMap<String, String>()
    val descriptions = page.optJSONArray("desc_more")
    if (descriptions != null) {
        for (i in 0 until descriptions.length()) {
            val text = (descriptions.opt(i) as? String)?.replace(Regex("[\\s\u3000]+"), "") ?: continue
            for (match in superTopicCount.findAll(text)) {
                counts.putIfAbsent(match.groupValues[1], match.groupValues[2])
            }
        }
    }
    val levelNode = superTopicLevelNode(page, data)
    val levelRaw = levelNode.str("level")
        .ifBlank { levelNode.str("level_desc") }
        .ifBlank { levelNode.str("grade") }
    val titleSub = page.str("portrait_sub_text")
        .ifBlank { page.str("title_sub") }
        .ifBlank { levelNode.str("title_sub") }
    val rank = page.str("urank").ifBlank { levelNode.str("urank") }
    return SuperTopicPage(
        info = SuperTopicInfo(
            containerId = expected,
            title = title,
            description = page.str("desc").ifBlank { page.str("detail_desc") },
            avatar = page.str("portrait_hd").ifBlank { page.str("portrait") },
            readCount = counts["阅读"].orEmpty(),
            postCount = counts["帖子"] ?: page.str("total").takeIf { it.matches(Regex("[0-9]+")) }.orEmpty(),
            fanCount = counts["粉丝"].orEmpty(),
            level = normalizeSuperTopicLevel(levelRaw),
            levelIcon = levelNode.str("level_icon"),
            levelScheme = levelNode.str("level_scheme"),
            titleSub = stripHtml(titleSub).trim(),
            rank = rank.trim(),
        ),
        items = items.values.toList(),
        nextCursor = cursor,
    )
}

/** 等级可能挂在 pageInfo 顶层，也可能在头部卡片（head_cards / cardlist_head_cards）里，按优先级取第一个有值的节点。 */
private fun superTopicLevelNode(page: JSONObject, data: JSONObject): JSONObject {
    if (page.has("level") || page.has("level_icon") || page.has("level_desc")) return page
    findSuperTopicLevelCard(data.optJSONArray("cards"))?.let { return it }
    return page
}

private fun findSuperTopicLevelCard(cards: JSONArray?): JSONObject? {
    if (cards == null) return null
    for (i in 0 until cards.length()) {
        val card = cards.optJSONObject(i) ?: continue
        if (card.has("level_icon") || card.has("level_scheme") ||
            (card.has("level") && (card.has("urank") || card.has("level_desc")))
        ) return card
        findSuperTopicLevelCard(card.optJSONArray("card_group"))?.let { return it }
        findSuperTopicLevelCard(card.optJSONArray("group"))?.let { return it }
    }
    return null
}

/** 纯数字补成 "LV.n"；已经是 "LV.7" / "等级7" 等展示文本则原样保留。 */
private fun normalizeSuperTopicLevel(raw: String): String {
    val text = stripHtml(raw).trim()
    if (text.isEmpty()) return ""
    if (text.matches(Regex("[0-9]+"))) return "LV.$text"
    return text
}

private fun collectSuperTopicCards(cards: JSONArray?, items: MutableMap<String, Status>) {
    if (cards == null) return
    for (i in 0 until cards.length()) {
        val card = cards.optJSONObject(i) ?: continue
        Status.from(card.optJSONObject("mblog"))?.let { items.putIfAbsent(it.id, it) }
        collectSuperTopicCards(card.optJSONArray("card_group"), items)
    }
}

/** 「我关注的超话」列表。接口返回的卡片层级不固定，递归收集 card_type == 8。 */
fun parseFollowedSuperTopics(root: JSONObject?): FollowedSuperTopicPage {
    if (root == null) throw ApiException("超话列表加载失败")
    if (root.optInt("ok", 0) == -100) throw ApiException("超话需要登录后查看", "-100")
    if (root.optInt("ok", 0) != 1) throw ApiException("超话列表加载失败")
    val data = root.optJSONObject("data") ?: throw ApiException("超话列表数据缺失")
    val topics = LinkedHashMap<String, FollowedSuperTopic>()
    collectFollowedSuperTopics(data.optJSONArray("cards"), topics)
    val cursor = data.optJSONObject("cardlistInfo")?.str("since_id").orEmpty()
        .ifBlank { data.optJSONObject("pageInfo")?.str("since_id").orEmpty() }
        .takeIf { it.matches(Regex("[0-9]+")) && it.any { digit -> digit != '0' } }
    return FollowedSuperTopicPage(topics.values.toList(), cursor)
}

private fun collectFollowedSuperTopics(cards: JSONArray?, topics: MutableMap<String, FollowedSuperTopic>) {
    if (cards == null) return
    for (i in 0 until cards.length()) {
        val card = cards.optJSONObject(i) ?: continue
        val type = card.optString("card_type")
        val scheme = card.opt("scheme") as? String ?: ""
        val containerId = superTopicId(card.opt("containerid") as? String ?: "") ?: superTopicId(scheme)
        if ((type == "8" || type.isBlank()) && containerId != null) {
            val title = stripHtml(card.opt("title_sub") as? String).trim('#', ' ', '\u00a0')
                .ifBlank { stripHtml(card.opt("title") as? String).trim('#', ' ', '\u00a0') }
            if (title.isNotBlank()) {
                val buttonName = card.optJSONArray("buttons")?.optJSONObject(0)?.str("name").orEmpty()
                topics.putIfAbsent(
                    containerId,
                    FollowedSuperTopic(
                        containerId = containerId,
                        title = title,
                        avatar = card.opt("pic") as? String ?: "",
                        desc1 = card.opt("desc1") as? String ?: "",
                        desc2 = card.opt("desc2") as? String ?: "",
                        scheme = scheme,
                        followed = "已关注" in buttonName || "关注" in buttonName,
                    ),
                )
            }
        }
        collectFollowedSuperTopics(card.optJSONArray("card_group"), topics)
        collectFollowedSuperTopics(card.optJSONArray("group"), topics)
    }
}

/** Comprehensive search may contain native supertopic cards alongside ordinary search results. */
fun parseSuperTopicSearch(root: JSONObject?): List<TopicItem> {
    if (root?.optInt("ok", 0) == -100) throw ApiException("超话需要登录后查看", "-100")
    if (root?.optInt("ok", 0) != 1) return emptyList()
    val topics = LinkedHashMap<String, TopicItem>()
    collectSuperTopicSearchCards(root.optJSONObject("data")?.optJSONArray("cards"), topics)
    return topics.values.toList()
}

private fun collectSuperTopicSearchCards(cards: JSONArray?, topics: MutableMap<String, TopicItem>) {
    if (cards == null) return
    for (i in 0 until cards.length()) {
        val card = cards.optJSONObject(i) ?: continue
        if (card.has("mblog")) continue
        val scheme = card.opt("scheme") as? String ?: ""
        val containerId = superTopicId(card.opt("containerid") as? String ?: "") ?: superTopicId(scheme)
        val title = stripHtml(card.opt("title_sub") as? String).trim('#', ' ', '\u00a0')
            .ifBlank { stripHtml(card.opt("title") as? String).trim('#', ' ', '\u00a0') }
            .ifBlank { stripHtml(card.opt("item_title") as? String).trim('#', ' ', '\u00a0') }
        if (containerId != null && title.isNotBlank()) {
            topics.putIfAbsent(
                containerId,
                TopicItem(
                    title = title,
                    desc1 = (card.opt("desc1") as? String).orEmpty()
                        .ifBlank { card.opt("item_desc") as? String ?: "" },
                    desc2 = card.opt("desc2") as? String ?: "",
                    scheme = scheme,
                    containerId = containerId,
                ),
            )
        }
        collectSuperTopicSearchCards(card.optJSONArray("card_group"), topics)
        collectSuperTopicSearchCards(card.optJSONArray("group"), topics)
    }
}
