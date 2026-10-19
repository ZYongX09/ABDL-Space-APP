@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package org.joinmastodon.android.fragments.friendcards

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.R
import org.joinmastodon.android.api.requests.friendcards.FriendCardRequest
import org.joinmastodon.android.friendcards.FriendCardPages
import org.joinmastodon.android.model.FriendCard
import org.joinmastodon.android.ui.compose.friendcards.*
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

class FriendCardFeedFragment : FriendCardBaseFragment() {
    private val pages = FriendCardPages()
    private var position = 0
    private var visibleId: String? = null
    private var needsRevalidate = false
    private var pagerReset = 0
    override fun onCreate(state: Bundle?) { super.onCreate(state); setTitle("扩列"); position = state?.getInt("page") ?: 0; visibleId = state?.getString("visible_id") }
    override fun onSaveInstanceState(state: Bundle) { super.onSaveInstanceState(state); state.putInt("page", position); state.putString("visible_id", pages.cards.getOrNull(position)?.id ?: visibleId) }
    override fun refresh() {
        if (!pages.loaded) load()
        else if (needsRevalidate) {
            // Keep only the ID/position while hidden; never display stale private or blocked data.
            needsRevalidate = false
            pages.reset(); load()
        }
        changed()
    }
    private fun load() {
        val ticket = pages.begin() ?: return
        changed()
        execute(FriendCardRequest.feed(ticket.cursor, 20), { result ->
            val previousCount = pages.cards.size
            val accepted = if (result.cards == null) { pages.fail(ticket, "扩列推荐响应不完整，请重试"); false }
                else pages.accept(ticket, result.cards, result.nextCursor)
            if (!accepted) { changed(); return@execute }
            val restored = visibleId?.let { id -> pages.cards.indexOfFirst { it.id == id } } ?: -1
            if (restored >= 0) { position = restored; visibleId = null }
            if ((visibleId != null || pages.cards.size == previousCount) && pages.nextCursor != null) {
                // Empty/fully duplicate batches can still precede reachable cards. Advance without
                // relying on a pager change; FriendCardPages rejects repeated/cyclic cursors.
                load()
            } else { position = position.coerceIn(0, maxOf(0, pages.cards.lastIndex)); visibleId = null }
            changed()
        }, { pages.fail(ticket, message(it)); changed() })
    }
    override fun onHidden() {
        pages.cards.getOrNull(position)?.let { visibleId = it.id }
        needsRevalidate = true; pages.reset(); super.onHidden()
    }
    private data class ScreenState(
        val cards: List<FriendCard>, val loading: Boolean, val loaded: Boolean,
        val error: String?, val position: Int, val restoring: Boolean, val pagerReset: Int,
    )
    @Composable override fun Screen(revision: Int) {
        val state = remember(revision) {
            ScreenState(pages.cards.toList(), pages.loading, pages.loaded, pages.error, position, visibleId != null, pagerReset)
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FeedHeader(state.cards.size) { pagerReset++; position = 0; visibleId = null; pages.reset(); load() }
            if (state.cards.isEmpty() || state.restoring) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (state.error != null) FriendCardNotice(state.error, "重试", { load() })
                    else if (state.loading) FriendCardNotice("正在读取扩列推荐…", null, null)
                    else if (state.loaded) FriendCardNotice("暂时没有可推荐的卡，可以稍后刷新。", "刷新", { pages.reset(); load() })
                }
                return@Column
            }
            val pager = rememberPagerState(initialPage = state.position.coerceIn(0, state.cards.lastIndex), pageCount = { state.cards.size })
            LaunchedEffect(pager.currentPage, state.cards.size) {
                position = pager.currentPage
                if (position >= state.cards.size - 3 && state.error == null) load()
            }
            var displayedReset by remember { mutableIntStateOf(state.pagerReset) }
            LaunchedEffect(state.pagerReset) {
                if (displayedReset != state.pagerReset) { pager.scrollToPage(0); displayedReset = state.pagerReset }
            }
            VerticalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), pageSpacing = 12.dp, beyondViewportPageCount = 1, key = { state.cards[it].id }) { index ->
                val value = state.cards[index]
                val palette = friendCardPalette(value)
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(palette.background)) {
                    Column(Modifier.fillMaxSize()) {
                        // Only the portrait yields space on short screens; identity and Hi stay reachable.
                        FriendCardPortrait(value, compact = true, modifier = Modifier.weight(1f).fillMaxWidth()
                            .clickable(role = Role.Button, onClickLabel = "查看完整资料") { detail(value) })
                        Column(Modifier.fillMaxWidth().background(palette.background).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FriendCardIdentity(value, modifier = Modifier.weight(1f), onClick = { profile(value) })
                                FriendCardHello { chat(value) }
                            }
                            Text("${index + 1} / ${state.cards.size} · 上下滑动", color = palette.accent, fontSize = 12.sp)
                        }
                    }
                }
            }
            state.error?.let { FriendCardNotice(it, "重试下一页", { load() }) }
            if (state.loading) Text("正在加载更多…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
        }
    }

    @Composable private fun FeedHeader(count: Int, onRefresh: () -> Unit) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Move controls onto their own row rather than squeezing or shrinking accessibility text.
            val stacked = maxWidth.value / LocalDensity.current.fontScale < 340f
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FeedHeading(count)
                    FeedControls(onRefresh)
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FeedHeading(count, Modifier.weight(1f))
                    FeedControls(onRefresh)
                }
            }
        }
    }

    @Composable private fun FeedHeading(count: Int, modifier: Modifier = Modifier) {
        FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("扩列精选", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text("$count 张", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 6.dp))
        }
    }

    @Composable private fun FeedControls(onRefresh: () -> Unit) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(MiuixTheme.colorScheme.surfaceVariant)
                .clickable(role = Role.Button) { go(MyFriendCardsFragment::class.java) }.padding(horizontal = 14.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text("我的扩列卡", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
            }
            Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = onRefresh)
                .padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(painter = painterResource(R.drawable.ic_fluent_arrow_clockwise_24_regular), contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(20.dp))
                Text("刷新", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}
