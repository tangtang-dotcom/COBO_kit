package com.cobo.app.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cobo.app.App
import com.cobo.app.data.ApiException
import com.cobo.app.data.FeedPage
import com.cobo.app.data.SuperTopicInfo
import com.cobo.app.data.SuperTopicOrder
import com.cobo.app.data.SuperTopicPage
import com.cobo.app.ui.CoboIcons
import com.cobo.app.ui.NavState
import com.cobo.app.ui.PinnedBarDensity
import com.cobo.app.ui.Route
import com.cobo.app.ui.blurEffectsEnabled
import com.cobo.app.ui.expandOrigin
import com.cobo.app.ui.glassBarContentTop
import com.cobo.app.ui.layerBackdropIf
import com.cobo.app.ui.pageContainerColor
import com.cobo.app.ui.pinnedHeight
import com.cobo.app.ui.rememberExpandOrigin
import com.cobo.app.ui.components.Avatar
import com.cobo.app.ui.components.BackIconButton
import com.cobo.app.ui.components.EmptyState
import com.cobo.app.ui.components.ErrorState
import com.cobo.app.ui.components.FullPageRefreshHoldMillis
import com.cobo.app.ui.components.FullPageRefreshTexts
import com.cobo.app.ui.components.LoadingState
import com.cobo.app.ui.components.StatusCard
import com.cobo.app.ui.components.navBottomPadding
import io.github.suqi8.coui.kmp.basic.FullPagePullToRefresh
import io.github.suqi8.coui.kmp.basic.HighlightedIconButton
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.LocalEdgeToEdge
import io.github.suqi8.coui.kmp.basic.SmallTopAppBar
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TopAppBarDefaults
import io.github.suqi8.coui.kmp.basic.couiTopAppBarActionItem
import io.github.suqi8.coui.kmp.basic.fullPagePullToRefreshColors
import io.github.suqi8.coui.kmp.basic.rememberFullPagePullToRefreshState
import io.github.suqi8.coui.kmp.blur.LocalBackdrop
import io.github.suqi8.coui.kmp.blur.rememberLayerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

/** Keeps the existing feed's request serialization, refresh and duplicate filtering. */
class SuperTopicStore(
    containerId: String,
    order: SuperTopicOrder = SuperTopicOrder.LatestComments,
    loader: suspend (String?) -> SuperTopicPage,
) {
    var info by mutableStateOf<SuperTopicInfo?>(null)
        private set
    var lastRequestWasRefresh = true
        private set
    private val cursors = HashSet<String>()
    val feed = FeedStore("$containerId:${order.key}", readOnly = true) { cursor, _ ->
        lastRequestWasRefresh = cursor == null
        val page = loader(cursor)
        if (cursor == null) cursors.clear() else cursors.add(cursor)
        if (page.nextCursor != null && page.nextCursor in cursors) {
            throw ApiException("超话分页游标重复，请刷新或网页查看")
        }
        info = page.info
        FeedPage(page.items, page.nextCursor)
    }
}

@Composable
fun SuperTopicScreen(nav: NavState, containerId: String, fallbackUrl: String) {
    val scope = rememberCoroutineScope()
    var order by remember(containerId) { mutableStateOf(SuperTopicOrder.LatestComments) }
    val store = remember(containerId, order) {
        SuperTopicStore(containerId, order) { cursor -> App.repo.superTopicPreferClient(containerId, cursor, order) }
    }
    val feed = store.feed
    val listState = rememberLazyListState()
    val pullState = rememberFullPagePullToRefreshState()
    val backdrop = rememberLayerBackdrop()
    val topBarHeight = pinnedHeight(TopAppBarDefaults.SmallTopAppBarCenterHeight)
    val title = store.info?.title ?: "超话"

    LaunchedEffect(store) { feed.loadInitial() }
    LaunchedEffect(listState, feed, feed.items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (feed.items.isNotEmpty() && last >= feed.items.size - 1 && feed.error == null) {
                    feed.loadMore()
                }
            }
    }
    LaunchedEffect(feed.generation) {
        if (feed.loaded) listState.scrollToItem(0)
    }

    Box(Modifier.fillMaxSize().background(pageContainerColor())) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdropIf(blurEffectsEnabled(), backdrop)
                .background(pageContainerColor())
        ) {
            FullPagePullToRefresh(
                isRefreshing = feed.refreshing,
                onRefresh = { scope.launch { feed.refresh() } },
                modifier = Modifier.fillMaxSize().testTag("supertopic_page"),
                state = pullState,
                contentPadding = PaddingValues(top = topBarHeight),
                colors = fullPagePullToRefreshColors(backgroundColor = pageContainerColor()),
                refreshTexts = FullPageRefreshTexts,
                completeHoldDurationMillis = FullPageRefreshHoldMillis,
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = glassBarContentTop(topBarHeight, 8.dp),
                        end = 12.dp,
                        bottom = 8.dp + navBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "header") {
                        Column(Modifier.padding(8.dp)) {
                            store.info?.let { info ->
                                Row {
                                    Avatar(info.avatar, 56.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(info.title, fontSize = 20.sp)
                                        if (info.level.isNotBlank() || info.titleSub.isNotBlank() || info.rank.isNotBlank()) {
                                            Spacer(Modifier.height(5.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                if (info.level.isNotBlank()) SuperTopicLevelBadge(info.level)
                                                val subline = listOf(
                                                    info.titleSub,
                                                    info.rank.takeIf { it.isNotBlank() }?.let { "排名 $it" }.orEmpty(),
                                                ).filter { it.isNotBlank() }.joinToString(" · ")
                                                if (subline.isNotBlank()) {
                                                    Text(
                                                        text = subline,
                                                        fontSize = 11.sp,
                                                        color = COUITheme.colorScheme.onSurfaceSecondary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        val counts = listOf(
                                            info.readCount.takeIf { it.isNotBlank() }?.let { "阅读 $it" },
                                            info.postCount.takeIf { it.isNotBlank() }?.let { "帖子 $it" },
                                            info.fanCount.takeIf { it.isNotBlank() }?.let { "粉丝 $it" },
                                        ).filterNotNull().joinToString(" · ")
                                        if (counts.isNotBlank()) Text(counts, fontSize = 12.sp)
                                    }
                                }
                                if (info.description.isNotBlank()) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(info.description, fontSize = 14.sp)
                                }
                                if (info.debug.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "DBG " + info.debug,
                                        fontSize = 9.sp,
                                        color = Color(0xFFB00020),
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                            Text(
                                "赞、评、转暂未验证，当前仅浏览",
                                fontSize = 12.sp,
                                color = COUITheme.colorScheme.onSurfaceSecondary,
                            )
                            if (feed.error?.contains("登录") == true) {
                                TextButton(text = "前往登录", onClick = { nav.push(Route.Login()) })
                            }
                        }
                    }
                    item(key = "sort") {
                        SuperTopicSortHeader(
                            selected = order,
                            onSelect = { next -> if (next != order) order = next },
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
                        )
                    }
                    if (feed.items.isEmpty()) {
                        item(key = "state") {
                            when {
                                feed.refreshing || (!feed.loaded && feed.error == null) -> LoadingState()
                                feed.error != null -> ErrorState(feed.error!!) {
                                    scope.launch { feed.refresh() }
                                }
                                else -> EmptyState("这个超话暂时没有帖子")
                            }
                        }
                    }
                    items(feed.items, key = { "status-${it.id}" }) { status ->
                        val origin = rememberExpandOrigin()
                        StatusCard(
                            status = status,
                            modifier = Modifier.expandOrigin(origin),
                            actionsEnabled = false,
                            onOpen = { nav.push(Route.Detail(status.id, status.mid, readOnly = true), origin) },
                            onOpenQuoted = { quoted, quotedOrigin ->
                                nav.push(Route.Detail(quoted.id, quoted.mid, readOnly = true), quotedOrigin)
                            },
                            onUser = { nav.push(Route.Profile(it.id)) },
                            onImage = { pics, index -> nav.push(Route.Images(pics, index)) },
                            onVideo = { nav.push(Route.Video(it.url, it.title)) },
                            onLike = {},
                        )
                    }
                    if (feed.items.isNotEmpty()) {
                        item(key = "pagination") {
                            when {
                                feed.loading || feed.refreshing -> LoadingState()
                                feed.error != null -> ErrorState(feed.error!!) {
                                    scope.launch {
                                        if (store.lastRequestWasRefresh) feed.refresh() else feed.loadMore()
                                    }
                                }
                                feed.end || feed.sinceId == null -> EmptyState("已到最后一页")
                                else -> TextButton(
                                    text = "加载更多",
                                    onClick = { scope.launch { feed.loadMore() } },
                                )
                            }
                        }
                    }
                }
            }
        }

        CompositionLocalProvider(
            LocalBackdrop provides if (blurEffectsEnabled()) backdrop else null,
            LocalEdgeToEdge provides true,
        ) {
            PinnedBarDensity {
                SmallTopAppBar(
                    title = title,
                    modifier = Modifier.align(Alignment.TopCenter),
                    color = pageContainerColor(),
                    showDivider = false,
                    gradientGlass = blurEffectsEnabled(),
                    navigationIcon = { BackIconButton { nav.pop() } },
                    actions = {
                        HighlightedIconButton(
                            onClick = { nav.push(Route.Web(fallbackUrl, title)) },
                            modifier = Modifier.couiTopAppBarActionItem().testTag("supertopic_web"),
                        ) {
                            Icon(CoboIcons.Globe, "网页查看", Modifier.size(24.dp))
                        }
                    },
                )
            }
        }
    }
}

private val SuperTopicSortEntries = listOf(
    "最新评论" to SuperTopicOrder.LatestComments,
    "最新发帖" to SuperTopicOrder.LatestPosts,
)

@Composable
private fun SuperTopicSortHeader(
    selected: SuperTopicOrder,
    onSelect: (SuperTopicOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = COUITheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "超话帖子",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        SortSwitch(
            entries = SuperTopicSortEntries,
            selected = selected,
            onSelect = onSelect,
            tagFor = { order -> "supertopic_order_${order.key}" },
        )
    }
}

private val superTopicLevelPattern = Regex("(?i)^(等级\\s*)?(lv\\.?\\s*\\d+|\\d+\\s*级)$")

/** 列表卡片里的 desc1 是否是可作徽章的超话等级文本。 */
internal fun isSuperTopicLevelText(text: String): Boolean =
    superTopicLevelPattern.matches(text.trim())

/** 超话等级小徽章，详情页头部与关注列表卡片共用。 */
@Composable
internal fun SuperTopicLevelBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.trim().replace(Regex("(?i)^等级\\s*"), ""),
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = Color(0xFFB47A00),
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFFFF1CC))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
