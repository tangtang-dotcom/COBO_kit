package com.cobo.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SuperTopicTest {
    private val id = "100808e1f868bf9980f09ab6908787d7eaf0f0"
    private val other = "100808fc439dedbb06ca5fd858848e521b8716"

    private fun response(): JSONObject = JSONObject(
        """{"ok":1,"data":{"pageInfo":{
            "containerid":"${id}_-_feed","page_title":"崩坏：星穹铁道",
            "desc":"超话简介","portrait":"https://example.com/avatar.jpg",
            "portrait_hd":"https://example.com/avatar-hd.jpg",
            "page_view":"super","total":4811642,"portrait_sub_text":"手游超话No.3",
            "desc_more":["阅读965.8亿　帖子481.2万　粉丝1276.2万"],
            "since_id":5184066029470123},"cards":[]}}"""
    )

    @Test
    fun parsesIdentityMetadataAndExactNumericCursor() {
        val result = parseSuperTopic(response(), id)
        assertEquals(id, result.info.containerId)
        assertEquals("崩坏：星穹铁道", result.info.title)
        assertEquals("超话简介", result.info.description)
        assertEquals("https://example.com/avatar-hd.jpg", result.info.avatar)
        assertEquals("965.8亿", result.info.readCount)
        assertEquals("481.2万", result.info.postCount)
        assertEquals("1276.2万", result.info.fanCount)
        assertEquals("5184066029470123", result.nextCursor)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun countsIgnorePageTypeAndRankAndUseOnlyNumericTotalFallback() {
        val root = response()
        val page = root.getJSONObject("data").getJSONObject("pageInfo")
        page.remove("desc_more")
        val info = parseSuperTopic(root, id).info
        assertEquals("", info.readCount)
        assertEquals("4811642", info.postCount)
        assertEquals("", info.fanCount)
        page.put("desc_more", JSONArray().put("未知格式").put("阅读未知　帖子未知　粉丝未知"))
        page.put("total", "未知")
        val unknown = parseSuperTopic(root, id).info
        assertEquals("", unknown.readCount)
        assertEquals("", unknown.postCount)
        assertEquals("", unknown.fanCount)
    }

    @Test
    fun labelledCountsHandleWhitespaceAndPreserveDisplayUnits() {
        val root = response()
        root.getJSONObject("data").getJSONObject("pageInfo")
            .put("desc_more", JSONArray().put(" 阅读 965.8亿 \t 帖子　481.2万　粉丝 1276.2万 "))
        val info = parseSuperTopic(root, id).info
        assertEquals("965.8亿", info.readCount)
        assertEquals("481.2万", info.postCount)
        assertEquals("1276.2万", info.fanCount)
    }

    @Test
    fun emptyFinalPagePreservesHeaderAndIgnoresGenericCursor() {
        val root = response()
        val data = root.getJSONObject("data")
        data.getJSONObject("pageInfo").remove("since_id")
        data.put("cardlistInfo", JSONObject().put("since_id", "999"))
        val result = parseSuperTopic(root, id)
        assertEquals(id, result.info.containerId)
        assertNull(result.nextCursor)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun zeroNullAndInvalidCursorsEndPagination() {
        for (cursor in listOf(0, "0", "000", JSONObject.NULL, "", "null", "1.5", "-1")) {
            val root = response()
            root.getJSONObject("data").getJSONObject("pageInfo").put("since_id", cursor)
            assertNull(parseSuperTopic(root, id).nextCursor)
        }
    }

    @Test
    fun recursivelyParsesStatusGroupsAndDeduplicates() {
        val root = response()
        fun statusCard(statusId: String) = JSONObject().put(
            "mblog", JSONObject().put("id", statusId).put("text", "正文")
                .put("user", JSONObject().put("id", "42").put("screen_name", "测试用户"))
        )
        val nested = JSONObject().put("card_group", JSONArray().put(statusCard("2")))
        val group = JSONObject().put("card_group", JSONArray().put(statusCard("1")).put(nested))
        root.getJSONObject("data").put("cards", JSONArray().put(group).put(statusCard("1")))
        assertEquals(listOf("1", "2"), parseSuperTopic(root, id).items.map { it.id })
    }

    @Test
    fun rejectsMissingWrongAndNonSupertopicIdentity() {
        val missing = response()
        missing.getJSONObject("data").remove("pageInfo")
        assertFailure(missing)
        for (identity in listOf(other, "100103type=1&q=星穹铁道", "", "${id}_-_other")) {
            val root = response()
            root.getJSONObject("data").getJSONObject("pageInfo").put("containerid", identity)
            assertFailure(root)
        }
    }

    @Test
    fun apiFailuresUseSanitizedMessagesAndLoginCode() {
        val login = failure(JSONObject().put("ok", -100).put("msg", "private server payload"))
        assertEquals("-100", login.errno)
        assertEquals("超话需要登录后查看", login.message)
        val error = failure(JSONObject().put("ok", 0).put("msg", "private server payload"))
        assertEquals("超话加载失败", error.message)
        assertFailure(JSONObject().put("ok", 1))
    }

    @Test
    fun recognizesAllowedRoutesAndDecodesQueryOnce() {
        val urls = listOf(
            id,
            "https://m.weibo.cn/p/index?containerid=$id",
            "http://m.weibo.cn/p/index?extparam=abc&containerid=$id",
            "https://m.weibo.cn/p/index?containerid=%31${id.drop(1)}",
            "https://m.weibo.cn/p/index?containerid=${id}_-_feed",
            "sinaweibo://pageinfo?containerid=$id&extparam=abc",
            "intent://pageinfo?containerid=$id#Intent;scheme=sinaweibo;package=com.sina.weibo;end",
            "https://weibo.com/page/$id",
        )
        for (url in urls) assertEquals(url, id, superTopicId(url))
    }

    @Test
    fun acceptsObservedDesktopRedirectAndMatchingIdentityOnly() {
        val observed = "https://weibo.com/page/$id?containerid=$id&launchid=10000360-page_H5" +
            "&luicode=10000011&lfid=${id}_-_feed&v_p=42"
        assertEquals(id, superTopicId(observed))
        assertEquals(id, superTopicId("https://weibo.com/page/$id?launchid=10000360-page_H5"))
        assertEquals(id, superTopicId("https://weibo.com/page/$id?containerid=${id}_-_feed"))
        assertNull(superTopicId("https://weibo.com/page/$id?containerid=$other"))
        assertNull(superTopicId("https://weibo.com/page/$id?containerid=$id&containerid=$id"))
        assertNull(superTopicId("https://weibo.com/page/$id?containerid=$id&containerid=$other"))
        assertNull(superTopicId("https://weibo.com/page/$id?containerid="))
        assertNull(superTopicId("https://weibo.com/page/$id?launchid=%"))
    }

    @Test
    fun rejectsUntrustedAmbiguousMalformedAndOrdinaryTopicLinks() {
        val urls = listOf(
            "prefix${id}suffix", "${id}_-_feed",
            "https://evil.example/p/index?containerid=$id",
            "https://m.weibo.cn.evil.example/p/index?containerid=$id",
            "https://m.weibo.cn@evil.example/p/index?containerid=$id",
            "https://evil.example@m.weibo.cn/p/index?containerid=$id",
            "https://m.weibo.cn:443/p/index?containerid=$id",
            "https://m.weibo.cn/p/index?containerid=$id&containerid=$other",
            "https://m.weibo.cn/p/index?containerid=$id%",
            "https://m.weibo.cn/p/index?containerid=%2531${id.drop(1)}",
            "https://m.weibo.cn/p/index?containerid=100103type%3D38%26q%3D原神",
            "https://m.weibo.cn/p/index?page_id=$id",
            "sinaweibo://browser?containerid=$id",
            "intent://pageinfo?containerid=$id#Intent;scheme=https;end",
            "javascript:pageinfo?containerid=$id",
            "https://weibo.com/page/$id/extra",
        )
        for (url in urls) assertNull(url, superTopicId(url))
    }

    @Test
    fun parsesLevelTitleSubAndRank() {
        val root = response()
        val page = root.getJSONObject("data").getJSONObject("pageInfo")
        page.put("level", "7")
        page.put("level_icon", "https://example.com/lv7.png")
        page.put("level_scheme", "sinaweibo://pageinfo?containerid=x")
        page.put("urank", "3")
        val info = parseSuperTopic(root, id).info
        assertEquals("LV.7", info.level)
        assertEquals("https://example.com/lv7.png", info.levelIcon)
        assertEquals("手游超话", info.titleSub)
        assertEquals("No.3", info.rank)
    }

    @Test
    fun parsesLevelFromDescTextAndExtractsRank() {
        val root = response()
        val page = root.getJSONObject("data").getJSONObject("pageInfo")
        page.put(
            "desc",
            "阅读220.5亿\u3000帖子140.6万\u3000粉丝166.3万 等级LV.9初露極锋\u3000连续签到0天 >>",
        )
        page.put(
            "desc_more",
            JSONArray()
                .put("阅读220.5亿\u3000帖子140.6万\u3000粉丝166.3万")
                .put("等级LV.9初露極锋\u3000连续签到0天 >>"),
        )
        page.put("portrait_sub_text", "电竞俱乐部超话No.7")
        val info = parseSuperTopic(root, id).info
        assertEquals("LV.9", info.level)
        assertEquals("初露極锋", info.titleSub)
        assertEquals("No.7", info.rank)
        assertEquals("220.5亿", info.readCount)
        assertEquals("140.6万", info.postCount)
        assertEquals("166.3万", info.fanCount)
    }

    @Test
    fun parsesLevelFromHeadCardAndKeepsDisplayText() {
        val root = response()
        val card = JSONObject().put("level", "LV.12").put("level_icon", "https://example.com/lv12.png")
        root.getJSONObject("data").put(
            "cards",
            JSONArray().put(JSONObject().put("card_type", "11").put("card_group", JSONArray().put(card))),
        )
        val info = parseSuperTopic(root, id).info
        assertEquals("LV.12", info.level)
        assertEquals("https://example.com/lv12.png", info.levelIcon)
    }

    private fun assertFailure(root: JSONObject) {
        failure(root)
    }

    private fun failure(root: JSONObject): ApiException {
        try {
            parseSuperTopic(root, id)
            fail("Expected ApiException")
        } catch (error: ApiException) {
            return error
        }
        throw AssertionError("Expected ApiException")
    }
}
