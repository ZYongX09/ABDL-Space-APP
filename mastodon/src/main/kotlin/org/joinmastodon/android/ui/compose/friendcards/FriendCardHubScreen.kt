@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.joinmastodon.android.ui.compose.friendcards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.joinmastodon.android.R
import org.joinmastodon.android.friendcards.FriendCardGames
import org.joinmastodon.android.model.FriendCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun FriendCardHubScreen(state: FriendCardHubState, actions: FriendCardHubActions) {
    // Both states outlive tab content, so switching tabs doesn't reset either list's position.
    val myScroll = remember { LazyListState(state.myPosition.index, state.myPosition.offset) }
    val feedScroll = remember { LazyListState(state.feedPosition.index, state.feedPosition.offset) }
    val ownRegion = FriendCardHubFilters.publicRegion(state.card)
    val visible = remember(state.cards, state.query, state.filter, ownRegion) {
        state.cards.filter { FriendCardHubFilters.matches(it, state.query, state.filter, ownRegion) }
    }
    val myKeys = myItemKeys(state)
    val feedKeys = feedItemKeys(state, visible)
    RestoreAndTrack(state.tab == FriendCardHubTab.MyCards, myScroll, myKeys, state.myPosition,
        state.loaded && !state.restoringMy, state.refreshToken, actions.myPosition)
    RestoreAndTrack(state.tab == FriendCardHubTab.Plaza, feedScroll, feedKeys, state.feedPosition,
        state.feedLoaded && !state.restoringFeed, state.refreshToken, actions.feedPosition)

    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.tab == FriendCardHubTab.MyCards) MyCards(state, actions, myScroll)
            else Plaza(state, actions, visible, ownRegion, feedScroll)
        }
        // This is a measured sibling, not an overlay or a guessed bottom content inset.
        // AppKit already consumes native system/IME insets; do not add another reservation.
        HubTabs(state.tab, !state.busy, actions.tab)
    }
}

private fun myItemKeys(state: FriendCardHubState): List<String> = buildList {
    add("heading")
    if (state.error != null) add("error")
    if (state.loading) add("loading")
    if (state.loaded && state.card == null) add("empty")
    state.card?.let { add("current"); add("card:${it.id}"); add("primary"); add("secondary") }
    add("create")
    if (state.loaded) add("history")
    state.historyCards.forEach { add("card:${it.id}") }
    if (state.historyLoaded && state.historyCards.isEmpty()) add("history-empty")
    if (state.historyError != null) add("history-error")
    if (state.historyLoading) add("history-loading")
    if (state.historyLoaded && state.historyCursor != null && !state.historyLoading && state.historyError == null) add("history-next:${state.historyCards.size}")
}

private fun feedItemKeys(state: FriendCardHubState, visible: List<FriendCard>): List<String> = buildList {
    add("heading"); add("search"); add("filters"); add("scope")
    if (state.filter == FriendCardHubFilter.SameCity) add("city")
    if (state.feedLoading && state.cards.isEmpty()) add("loading")
    if (state.feedLoaded && visible.isEmpty() && !state.feedLoading) add("empty")
    if (!state.restoringFeed) visible.forEach { add("card:${it.id}") }
    if (state.feedError != null) add("error")
    if (state.feedLoading && state.cards.isNotEmpty()) add("more-loading")
    if (state.feedLoaded && state.feedCursor != null && state.feedError == null) add("next:${state.cards.size}")
    if (state.feedLoaded && state.feedCursor == null && !state.feedLoading && state.feedError == null && visible.isNotEmpty()) add("end")
}

@Composable
private fun RestoreAndTrack(
    active: Boolean, scroll: LazyListState, keys: List<String>, position: FriendCardHubPosition,
    ready: Boolean, refreshToken: Int, onPosition: (FriendCardHubPosition) -> Unit,
) {
    var restoredToken by remember { mutableIntStateOf(-1) }
    LaunchedEffect(active, ready, refreshToken) {
        if (!active || !ready) return@LaunchedEffect
        if (restoredToken != refreshToken) {
            val anchor = position.cardId?.let { keys.indexOf("card:$it") } ?: -1
            val target = if (anchor >= 0) anchor + position.anchorDelta else position.index
            scroll.scrollToItem(target.coerceIn(0, maxOf(0, keys.lastIndex)), position.offset)
            restoredToken = refreshToken
        }
    }
    val latestKeys by rememberUpdatedState(keys)
    val latestPosition by rememberUpdatedState(onPosition)
    LaunchedEffect(active, ready, restoredToken, refreshToken) {
        if (!active || !ready || restoredToken != refreshToken) return@LaunchedEffect
        snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }
            .distinctUntilChanged().collect { (index, offset) ->
                val anchor = (index.coerceAtMost(latestKeys.lastIndex) downTo 0).firstOrNull {
                    latestKeys[it].startsWith("card:")
                }
                latestPosition(FriendCardHubPosition(index, offset,
                    anchor?.let { latestKeys[it].removePrefix("card:") }, anchor?.let { index - it } ?: 0))
            }
    }
}

@Composable
private fun MyCards(state: FriendCardHubState, actions: FriendCardHubActions, scroll: LazyListState) {
    LazyColumn(Modifier.fillMaxSize().testTag("friend-card-hub-my-list"), state = scroll,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item("heading") { HubHeading("我的扩列卡", "让同频的人，先认识你。", !state.busy && !state.loading, actions.back, actions.refresh) }
        state.error?.let { text -> item("error") { FriendCardNotice(text, "重试", actions.refresh) } }
        if (state.loading) item("loading") { FriendCardNotice("正在读取我的扩列卡…", null, null) }
        if (state.loaded && state.card == null) item("empty") {
            HubPanel {
                Text("还没有当前卡", fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface)
                Text("可以先浏览。创建新卡需要当前有效宝宝认证，浏览与管理不设此门槛。", fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        state.card?.let { value ->
            item("current") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp).semantics(mergeDescendants = true) {
                        contentDescription = "当前卡 · ${friendCardHubStateName(value.state)}"
                    }) {
                    Text("当前卡片", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 4.dp))
                    HubStateBadge(value.state)
                }
            }
            item("card:${value.id}") { CurrentCard(value, !state.busy, { actions.profile(value) }) }
            item("primary") {
                HubPrimaryActions(
                    { HubButton("编辑卡片", R.drawable.ic_fluent_edit_24_regular, !state.busy, Modifier.fillMaxWidth(), primary = true) { actions.edit(value) } },
                    { HubButton("查看详情", R.drawable.ic_fluent_arrow_up_right_24_regular, !state.busy, Modifier.fillMaxWidth()) { actions.detail(value) } },
                )
            }
            item("secondary") {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (value.state == "active" || value.state == "paused") HubTextAction(
                        if (value.state == "paused") "恢复展示" else "暂停展示",
                        if (value.state == "paused") R.drawable.ic_fluent_play_24_regular else R.drawable.ic_fluent_pause_24_regular,
                        !state.busy,
                    ) { actions.state(value) }
                    HubTextAction("发布帖", R.drawable.ic_fluent_send_24_regular, !state.busy) { actions.post(value) }
                    HubTextAction("删除", R.drawable.ic_fluent_delete_24_regular, !state.busy, destructive = true) { actions.delete(value) }
                }
            }
        }
        item("create") {
            HubButton("创建新卡", R.drawable.ic_fluent_add_24_regular, !state.busy, Modifier.fillMaxWidth().padding(top = 8.dp), primary = true, onClick = actions.create)
        }
        if (state.loaded) item("history") {
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("历史卡", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    HubIcon(R.drawable.ic_fluent_lock_closed_24_regular, null, Modifier.size(14.dp), MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text("仅自己可见", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        items(state.historyCards, key = { "card:${it.id}" }) { value ->
            HubPanel {
                Text(value.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(friendCardHubStateName(value.state), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HubTextAction("查看", R.drawable.ic_fluent_arrow_up_right_24_regular, !state.busy) { actions.detail(value) }
                    HubTextAction("编辑", R.drawable.ic_fluent_edit_24_regular, !state.busy) { actions.edit(value) }
                    HubTextAction("发布帖", R.drawable.ic_fluent_send_24_regular, !state.busy) { actions.post(value) }
                    HubTextAction("删除", R.drawable.ic_fluent_delete_24_regular, !state.busy, destructive = true) { actions.delete(value) }
                }
            }
        }
        if (state.historyLoaded && state.historyCards.isEmpty()) item("history-empty") {
            HubPanel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    HubIcon(R.drawable.ic_fluent_archive_24_regular, null, Modifier.size(28.dp), MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("还没有历史卡片", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                        Text("替换后的卡片会收纳在这里", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
        state.historyError?.let { text -> item("history-error") { FriendCardNotice(text, "重试加载历史", actions.history) } }
        if (state.historyLoading) item("history-loading") { FriendCardNotice("正在加载历史…", null, null) }
        if (state.historyLoaded && state.historyCursor != null && !state.historyLoading && state.historyError == null) item("history-next:${state.historyCards.size}") {
            HubButton("加载更多历史", R.drawable.ic_fluent_archive_24_regular, !state.busy, Modifier.fillMaxWidth(), onClick = actions.history)
        }
    }
}

@Composable
private fun Plaza(state: FriendCardHubState, actions: FriendCardHubActions, visible: List<FriendCard>, ownRegion: String?, scroll: LazyListState) {
    LazyColumn(Modifier.fillMaxSize().testTag("friend-card-hub-plaza-list"), state = scroll,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item("heading") { HubHeading("扩列广场", "发现今天也想认识新朋友的人", !state.busy && !state.feedLoading, actions.back, actions.refresh) }
        item("search") { HubSearch(state.query, !state.busy, actions.query) }
        item("filters") {
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FriendCardHubFilter.entries.forEach { filter ->
                    val selected = state.filter == filter
                    Text(filter.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.clip(CircleShape).background(if (selected) MiuixTheme.colorScheme.primary else hubSurface())
                            .selectable(selected, enabled = !state.busy, role = Role.RadioButton) { actions.filter(filter) }
                            .heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
        }
        item("scope") {
            Text("已加载卡片筛选 · ${state.cards.size} 张已加载", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (state.filter == FriendCardHubFilter.SameCity) item("city") {
            val helper = when {
                state.loading -> "正在读取当前卡片的公开地区…"
                !state.loaded && state.error != null -> state.error
                ownRegion == null -> "同城仅匹配你当前卡片的公开地区。请先在卡片中填写并公开地区；不会使用定位。"
                else -> "同城按公开地区「$ownRegion」精确匹配，不使用定位。"
            }
            FriendCardNotice(helper, if (!state.loaded && state.error != null) "重新读取地区" else null,
                if (!state.loaded && state.error != null) actions.refresh else null)
        }
        if (state.feedLoading && state.cards.isEmpty()) item("loading") { FriendCardNotice("正在读取扩列推荐…", null, null) }
        if (state.feedLoaded && visible.isEmpty() && !state.feedLoading) item("empty") {
            HubPanel {
                Text(if (state.cards.isEmpty()) "暂时没有可推荐的卡" else "已加载的卡片中没有匹配项", color = MiuixTheme.colorScheme.onSurface)
                Text(if (state.feedCursor != null) "可以调整筛选，或继续加载下一批。" else "可以调整筛选，或稍后刷新再来看看。",
                    fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (!state.restoringFeed) items(visible, key = { "card:${it.id}" }) { value ->
            PlazaCard(value, !state.busy, { actions.detail(value) }, { actions.profile(value) })
        }
        state.feedError?.let { text -> item("error") { FriendCardNotice(text, "重试下一页", actions.next) } }
        if (state.feedLoading && state.cards.isNotEmpty()) item("more-loading") { FriendCardNotice("正在加载更多…", null, null) }
        if (state.feedLoaded && state.feedCursor != null && state.feedError == null) item("next:${state.cards.size}") {
            HubButton("换一批，遇见新朋友", R.drawable.ic_fluent_arrow_shuffle_24_regular, !state.busy && !state.feedLoading,
                Modifier.fillMaxWidth(), plain = true, onClick = actions.next)
        }
        if (state.feedLoaded && state.feedCursor == null && !state.feedLoading && state.feedError == null && visible.isNotEmpty()) item("end") {
            Text("已看完本次推荐，稍后再来看看", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun HubHeading(title: String, subtitle: String, enabled: Boolean, back: () -> Unit, refresh: () -> Unit) {
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            HubIconButton(R.drawable.ic_arrow_back_24, "返回", true, back)
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() })
            HubIconButton(R.drawable.ic_fluent_arrow_clockwise_24_regular, "刷新", enabled, refresh)
        }
        Text(subtitle, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun HubSearch(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(hubSurface())
        .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        HubIcon(R.drawable.ic_fluent_search_24_regular, null, Modifier.size(20.dp), MiuixTheme.colorScheme.onSurfaceVariantSummary)
        BasicTextField(value, onChange, enabled = enabled, singleLine = true,
            textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 14.sp),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.weight(1f).testTag("friend-card-hub-search").semantics { contentDescription = "筛选已加载的公开卡片" },
            decorationBox = { inner -> Box {
                if (value.isEmpty()) Text("搜索兴趣、城市或关键词", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                inner()
            } })
    }
}

@Composable
private fun HubTabs(tab: FriendCardHubTab, enabled: Boolean, onSelect: (FriendCardHubTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(IntrinsicSize.Min)
        .clip(RoundedCornerShape(36.dp)).border(1.dp, MiuixTheme.colorScheme.dividerLine, RoundedCornerShape(36.dp))
        .background(hubSurface()).padding(5.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(FriendCardHubTab.Plaza, FriendCardHubTab.MyCards).forEach { destination ->
            val selected = tab == destination
            val color = if (selected) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary
            Column(Modifier.weight(1f).fillMaxHeight().heightIn(min = 52.dp).clip(RoundedCornerShape(30.dp))
                .background(if (selected) lerp(hubSurface(), MiuixTheme.colorScheme.primary, .16f) else Color.Transparent)
                .testTag(if (destination == FriendCardHubTab.MyCards) "friend-card-hub-tab-my" else "friend-card-hub-tab-plaza")
                .selectable(selected, enabled = enabled, role = Role.Tab) { onSelect(destination) }
                .padding(horizontal = 6.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                HubIcon(if (destination == FriendCardHubTab.MyCards) R.drawable.ic_fluent_contact_card_24_regular else R.drawable.ic_fluent_compass_northwest_24_regular,
                    null, Modifier.size(20.dp), color)
                Text(if (destination == FriendCardHubTab.MyCards) "我的卡片" else "扩列广场", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
            }
        }
    }
}

@Composable
private fun hubSurface(): Color = lerp(MiuixTheme.colorScheme.background, MiuixTheme.colorScheme.onSurface, .055f)

@Composable
private fun HubPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(hubSurface()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
private fun HubStateBadge(state: String?) {
    val active = state == "active"
    Text(friendCardHubStateName(state), fontSize = 12.sp, fontWeight = FontWeight.Medium,
        color = if (active) MiuixTheme.colorScheme.onTertiaryContainer else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.clip(CircleShape).background(if (active) MiuixTheme.colorScheme.tertiaryContainer else MiuixTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
private fun CurrentCard(value: FriendCard, enabled: Boolean, profile: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(hubSurface())) {
        FriendCardHubCover(value, ratio = 1.86f)
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(value.title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
            HubTags(value)
            val about = value.interactionHabits?.takeIf { it.isNotBlank() } ?: value.lookingFor?.takeIf { it.isNotBlank() }
            about?.let { Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            value.reason?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            value.owner?.let { owner ->
                val name = owner.displayName?.takeIf { it.isNotBlank() } ?: owner.username
                name?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled, role = Role.Button, onClickLabel = "用户主页", onClick = profile)
                            .padding(vertical = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun PlazaCard(value: FriendCard, enabled: Boolean, detail: () -> Unit, profile: () -> Unit) {
    HubPanel {
        FriendCardHubCover(value, ratio = 2f, modifier = Modifier.clip(RoundedCornerShape(12.dp)))
        Text(value.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clickable(enabled, role = Role.Button, onClickLabel = "查看详情", onClick = detail))
        if (value.isPublic("looking_for")) value.lookingFor?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        HubTags(value)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HubButton("认识一下", R.drawable.ic_fluent_arrow_up_right_24_regular, enabled, onClick = detail)
            if (!value.ownerId.isNullOrBlank()) HubTextAction("用户主页", R.drawable.ic_fluent_person_24_regular, enabled, onClick = profile)
        }
    }
}

@Composable
private fun HubTags(value: FriendCard) {
    val tags = buildList {
        if (value.isPublic("interests")) addAll(value.interests.orEmpty())
        if (value.isPublic("games")) addAll(value.games.orEmpty().map(FriendCardGames::gameName))
        FriendCardHubFilters.publicRegion(value)?.let { add(it) }
    }.filter(String::isNotBlank).distinct().take(5)
    if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { tag ->
            Text(tag, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MiuixTheme.colorScheme.background).padding(horizontal = 8.dp, vertical = 5.dp))
        }
    }
}

@Composable
private fun HubPrimaryActions(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        if (maxWidth.value / fontScale < 300f) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { first(); second() }
        else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { first() }; Box(Modifier.weight(1f)) { second() }
        }
    }
}

@Composable
private fun HubButton(text: String, icon: Int, enabled: Boolean, modifier: Modifier = Modifier, primary: Boolean = false, plain: Boolean = false, onClick: () -> Unit) {
    val foreground = when { primary -> MiuixTheme.colorScheme.onPrimary; plain -> MiuixTheme.colorScheme.onSurfaceVariantSummary; else -> MiuixTheme.colorScheme.onSurface }
    val background = when { primary -> MiuixTheme.colorScheme.primary; plain -> Color.Transparent; else -> lerp(hubSurface(), MiuixTheme.colorScheme.primary, .12f) }
    Row(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(background)
        .clickable(enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        HubIcon(icon, null, Modifier.size(18.dp), foreground.copy(alpha = if (enabled) 1f else .5f))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = foreground.copy(alpha = if (enabled) 1f else .5f))
    }
}

@Composable
private fun HubTextAction(text: String, icon: Int, enabled: Boolean, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HubIcon(icon, null, Modifier.size(16.dp), color.copy(alpha = if (enabled) 1f else .5f))
        Text(text, fontSize = 12.sp, color = color.copy(alpha = if (enabled) 1f else .5f))
    }
}

@Composable
private fun HubIconButton(icon: Int, label: String, enabled: Boolean, action: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(hubSurface())
        .clickable(enabled, role = Role.Button, onClick = action), contentAlignment = Alignment.Center) {
        HubIcon(icon, label, Modifier.size(20.dp), MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = if (enabled) 1f else .5f))
    }
}

@Composable
private fun HubIcon(resource: Int, description: String?, modifier: Modifier, tint: Color) {
    FriendCardResourceIcon(resource, tint, modifier.then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier))
}
