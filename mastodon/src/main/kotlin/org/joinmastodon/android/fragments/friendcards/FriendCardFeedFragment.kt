@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package org.joinmastodon.android.fragments.friendcards

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.joinmastodon.android.api.requests.friendcards.FriendCardRequest
import org.joinmastodon.android.friendcards.FriendCardPages
import org.joinmastodon.android.ui.compose.friendcards.*
import top.yukonga.miuix.kmp.basic.Text

class FriendCardFeedFragment : FriendCardBaseFragment() {
    private val pages = FriendCardPages()
    private var position = 0
    private var visibleId: String? = null
    private var needsRevalidate = false
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
    @Composable override fun Screen() {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FriendCardActions("我的扩列卡" to { go(MyFriendCardsFragment::class.java) }, "刷新" to { position = 0; visibleId = null; pages.reset(); load() })
            if (pages.cards.isEmpty() || visibleId != null) {
                if (pages.error != null) FriendCardNotice(pages.error!!, "重试", { load() })
                else if (pages.loading) FriendCardNotice("正在读取扩列推荐…", null, null)
                else if (pages.loaded) FriendCardNotice("暂时没有可推荐的卡，可以稍后刷新。", "刷新", { pages.reset(); load() })
                return@Column
            }
            val pager = rememberPagerState(initialPage = position.coerceIn(0, pages.cards.lastIndex), pageCount = { pages.cards.size })
            LaunchedEffect(pager.currentPage) {
                position = pager.currentPage
                if (position >= pages.cards.size - 3 && pages.error == null) load()
            }
            LaunchedEffect(pages.generation, position) { if (position in pages.cards.indices && pager.currentPage != position) pager.scrollToPage(position) }
            VerticalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 1, key = { pages.cards[it].id }) { index ->
                val value = pages.cards[index]
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val room = maxHeight.value / LocalDensity.current.fontScale
                    FriendCardPanel(modifier = Modifier.fillMaxSize().clickable { detail(value) }) {
                        FriendCardCover(if (room >= 420) value.coverUrl else null, value.title, value.coverFocusX, value.coverFocusY, ratio = if (room >= 620) 2.2f else 3.2f)
                        if (room >= 620) FriendCardContents(value, compact = true)
                        else if (value.isPublic("looking_for") && !value.lookingFor.isNullOrBlank()) Text("我想找 · ${value.lookingFor}", maxLines = if (room >= 400) 2 else 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.weight(1f))
                        if (room >= 400) Text(value.owner?.displayName?.takeIf { it.isNotBlank() } ?: value.owner?.username ?: "扩列用户", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        FriendCardActions("完整资料" to { detail(value) }, "私信" to { chat(value) })
                        if (room >= 360) Text("${index + 1} / ${pages.cards.size} · 上下滑动")
                    }
                }
            }
            pages.error?.let { FriendCardNotice(it, "重试下一页", { load() }) }
            if (pages.loading) Text("正在加载更多…")
        }
    }
}
