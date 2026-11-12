package com.cobo.app.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cobo.app.App
import com.cobo.app.data.FollowedSuperTopic
import com.cobo.app.ui.NavState
import com.cobo.app.ui.Route
import com.cobo.app.ui.components.Avatar
import com.cobo.app.ui.components.EmptyState
import com.cobo.app.ui.components.ErrorState
import com.cobo.app.ui.components.FullPageRefreshHoldMillis
import com.cobo.app.ui.components.FullPageRefreshTexts
import com.cobo.app.ui.components.LoadingState
import com.cobo.app.ui.components.navBottomPadding
import com.cobo.app.ui.pageContainerColor
import io.github.suqi8.coui.kmp.basic.FullPagePullToRefresh
import io.github.suqi8.coui.kmp.basic.FullPagePullToRefreshState
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.fullPagePullToRefreshColors
import io.github.suqi8.coui.kmp.basic.rememberFullPagePullToRefreshState
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 关注超话列表的取数状态；首页的一个栏位复用它。 */
class SuperTopicsStore {
    val items = mutableStateListOf<FollowedSuperTopic>()
    var nextCursor by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var end by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var fetching = false
    private val lock = Mutex()

    suspend fun loadInitial() {
        if (loaded) return
        lock.withLock {
            if (loaded) return@withLock
            fetch()
        }
    }

    suspend fun refresh() {
        if (refreshing) return
        refreshing = true
        try {
            lock.withLock { fetch() }
        } finally {
            refreshing = false
        }
    }

    private suspend fun fetch() {
        fetching = true
        error = null
        try {
            val page = App.repo.followedSuperTopics()
            items.clear()
            items.addAll(page.items)
            nextCursor = page.nextCursor
            end = page.nextCursor == null
            loaded = true
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            error = t.message ?: "加载失败"
        } finally {
            fetching = false
        }
    }

    suspend fun loadMore() {
        if (loading || end || refreshing || fetching) return
        val cursor = nextCursor ?: return
        loading = true
        error = null
        try {
            lock.withLock {
                val page = App.repo.followedSuperTopics(cursor)
                val known = items.mapTo(HashSet()) { it.containerId }
                page.items.forEach { if (known.add(it.containerId)) items.add(it) }
                nextCursor = page.nextCursor
                end = page.nextCursor == null
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            error = t.message ?: "加载失败"
        } finally {
            loading = false
        }
    }
}

@Composable
fun SuperTopicsPage(
    nav: NavState,
    page: Int,
    topInset: Dp,
    listState: LazyListState,
    onPullState: (FullPagePullToRefreshState) -> Unit,
    onRefreshReady: (suspend () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val store = remember(page) { SuperTopicsStore() }
    val pullState = rememberFullPagePullToRefreshState()

    LaunchedEffect(pullState) { onPullState(pullState) }
    LaunchedEffect(store) {
        onRefreshReady { store.refresh() }
        store.loadInitial()
    }
    LaunchedEffect(listState, store) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (last >= store.items.size - 2 && store.items.isNotEmpty()) store.loadMore()
            }
    }

    FullPagePullToRefresh(
        isRefreshing = store.refreshing,
        onRefresh = { scope.launch { store.refresh() } },
        modifier = Modifier.fillMaxSize().testTag("supertopic_follow_page_$page"),
        state = pullState,
        contentPadding = PaddingValues(top = topInset),
        colors = fullPagePullToRefreshColors(backgroundColor = pageContainerColor()),
        refreshTexts = FullPageRefreshTexts,
        completeHoldDurationMillis = FullPageRefreshHoldMillis,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                top = topInset + 16.dp,
                end = 12.dp,
                bottom = 66.dp + navBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") {
                Column(Modifier.padding(8.dp)) {
                    Text("我关注的超话", fontSize = 20.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "点一个超话看最新评论或最新发帖",
                        fontSize = 12.sp,
                        color = COUITheme.colorScheme.onSurfaceSecondary,
                    )
                    if (store.error?.contains("登录") == true) {
                        TextButton(text = "前往登录", onClick = { nav.push(Route.Login()) })
                    }
                }
            }
            if (store.items.isEmpty()) {
                item(key = "state") {
                    when {
                        store.refreshing || (!store.loaded && store.error == null) -> LoadingState()
                        store.error != null -> ErrorState(store.error!!) {
                            scope.launch { store.refresh() }
                        }
                        else -> EmptyState("还没有关注的超话")
                    }
                }
            }
            items(store.items, key = { it.containerId }) { topic ->
                FollowedSuperTopicRow(topic) {
                    val fallback = topic.scheme.ifBlank {
                        "https://m.weibo.cn/p/index?containerid=${topic.containerId}"
                    }
                    nav.push(Route.SuperTopic(topic.containerId, fallback))
                }
            }
            if (store.items.isNotEmpty()) {
                item(key = "pagination") {
                    when {
                        store.loading || store.refreshing -> LoadingState()
                        store.error != null -> ErrorState(store.error!!) {
                            scope.launch { store.loadMore() }
                        }
                        store.end -> Text(
                            text = "没有更多了",
                            color = COUITheme.colorScheme.onSurfaceSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowedSuperTopicRow(topic: FollowedSuperTopic, onClick: () -> Unit) {
    val scheme = COUITheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(topic.avatar, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = topic.title,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val levelText = topic.desc1.trim()
            val hasLevel = isSuperTopicLevelText(levelText)
            val subtitle = if (hasLevel) {
                topic.desc2.trim()
            } else {
                listOf(levelText, topic.desc2.trim())
                    .filter { it.isNotBlank() }.joinToString(" · ")
            }
            if (hasLevel || subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (hasLevel) SuperTopicLevelBadge(levelText)
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            fontSize = 12.sp,
                            color = scheme.onSurfaceSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
        }
        if (topic.followed) {
            Spacer(Modifier.width(8.dp))
            Text("已关注", fontSize = 12.sp, color = scheme.onSurfaceSecondary)
        }
    }
}
