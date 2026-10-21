package org.joinmastodon.android.fragments.friendcards

import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.joinmastodon.android.api.requests.friendcards.FriendCardRequest
import org.joinmastodon.android.friendcards.FriendCardPages
import org.joinmastodon.android.model.FriendCard
import org.joinmastodon.android.ui.compose.friendcards.*

/** Both entry routes share this owner; the bottom destinations are never Fragment navigation. */
open class MyFriendCardsFragment : FriendCardBaseFragment() {
    protected open val initialTab = FriendCardHubTab.MyCards
    private var tab = FriendCardHubTab.MyCards
    private var card: FriendCard? = null
    private var loaded = false
    private var loading = false
    private var busy = false
    private val history = FriendCardPages()
    private val pages = FriendCardPages()
    private var query = ""
    private var filter = FriendCardHubFilter.Recommended
    private var myPosition = FriendCardHubPosition()
    private var feedPosition = FriendCardHubPosition()
    private var restoringMy = false
    private var restoringFeed = false
    private var refreshToken = 0
    private var historyReconciled = false
    private var mineInvalidated = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        tab = enumValues<FriendCardHubTab>().firstOrNull { it.name == state?.getString("hub_tab") }
            ?: enumValues<FriendCardHubTab>().firstOrNull { it.name == arguments?.getString(ARG_INITIAL_TAB) }
            ?: initialTab
        query = state?.getString("hub_query").orEmpty()
        filter = enumValues<FriendCardHubFilter>().firstOrNull { it.name == state?.getString("hub_filter") }
            ?: FriendCardHubFilter.Recommended
        fun position(prefix: String) = FriendCardHubPosition(
            state?.getInt("${prefix}_index") ?: 0, state?.getInt("${prefix}_offset") ?: 0,
            state?.getString("${prefix}_card"), state?.getInt("${prefix}_anchor_delta") ?: 0,
        )
        myPosition = position("hub_my")
        feedPosition = position("hub_feed")
        restoringMy = myPosition.index > 0
        restoringFeed = feedPosition.index > 0
        updateTitle()
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putString("hub_tab", tab.name)
        state.putString("hub_query", query)
        state.putString("hub_filter", filter.name)
        fun save(prefix: String, value: FriendCardHubPosition) {
            state.putInt("${prefix}_index", value.index)
            state.putInt("${prefix}_offset", value.offset)
            state.putString("${prefix}_card", value.cardId)
            state.putInt("${prefix}_anchor_delta", value.anchorDelta)
        }
        save("hub_my", myPosition)
        save("hub_feed", feedPosition)
    }

    // AppKit/Base continue owning native system/keyboard insets. Only the duplicate toolbar goes.
    override fun onViewCreated(view: View, state: Bundle?) { super.onViewCreated(view, state); toolbar?.visibility = View.GONE }
    override fun onUpdateToolbar() { super.onUpdateToolbar(); toolbar?.visibility = View.GONE }
    private fun updateTitle() { setTitle(if (tab == FriendCardHubTab.MyCards) "我的扩列卡" else "扩列广场") }

    override fun refresh() {
        if (busy || !shown || !accountValid()) return
        if (tab == FriendCardHubTab.MyCards || filter == FriendCardHubFilter.SameCity) {
            if (!loaded && !loading && !mineInvalidated) loadMine()
            else if (tab == FriendCardHubTab.MyCards && loaded && !history.loaded && !history.loading) loadHistory()
        }
        if (tab == FriendCardHubTab.Plaza && !pages.loaded && !pages.loading) load()
        changed()
    }

    private fun loadMine() {
        if (busy || loading || mineInvalidated || !shown || !accountValid()) return
        card = null; loaded = false; loading = true; globalError = null; changed()
        execute(FriendCardRequest.me(), { result ->
            loading = false
            if (result.card != null && !result.card.isSupported) {
                globalError = "扩列卡格式暂不受支持，请稍后重试"
                restoringMy = false
            } else {
                card = result.card; loaded = true
                if (myPosition.cardId == card?.id) restoringMy = false
                if (tab == FriendCardHubTab.MyCards) loadHistory()
            }
            changed()
        }, { loading = false; restoringMy = false; globalError = message(it); changed() })
    }

    private fun loadHistory() {
        if (busy || mineInvalidated || !shown || !accountValid()) return
        val ticket = history.begin() ?: return
        changed()
        execute(FriendCardRequest.history(ticket.cursor), { result ->
            val accepted = if (result.cards == null) {
                history.fail(ticket, "历史卡响应不完整，请重试"); false
            } else history.accept(ticket, result.cards, result.nextCursor)
            if (accepted && reconcileHistoryOverlap(result.cards.orEmpty())) return@execute
            if (accepted && restoringMy && myPosition.cardId != null && myPosition.cardId != card?.id &&
                history.cards.none { it.id == myPosition.cardId } && history.nextCursor != null) loadHistory()
            else restoringMy = false
            changed()
        }, { history.fail(ticket, message(it)); restoringMy = false; changed() })
    }

    /** A replacement may happen between /me and /history. Never keep a now-archived current card. */
    private fun reconcileHistoryOverlap(batch: List<FriendCard>): Boolean {
        val current = card ?: return false
        val conflicting = (history.cards + batch).any { it.id == current.id && it.version >= current.version &&
            (it.version > current.version || it.state != current.state) }
        if (!conflicting) return false
        card = null; loaded = false; loading = false; history.reset(); restoringMy = false
        refreshToken++
        if (!historyReconciled) {
            historyReconciled = true
            loadMine()
        } else {
            // Retry once per explicit refresh/lifecycle cycle, not an unbounded /me/history loop.
            mineInvalidated = true
            globalError = "当前卡片在其他位置发生变化，请刷新后再管理"
            changed()
        }
        return true
    }

    /** Append in server order. Empty/duplicate pages still advance; FriendCardPages guards cycles. */
    private fun load() {
        if (busy || !shown || !accountValid()) return
        val ticket = pages.begin() ?: return
        changed()
        execute(FriendCardRequest.feed(ticket.cursor, 20), { result ->
            val previousCount = pages.cards.size
            val accepted = if (result.cards == null) {
                pages.fail(ticket, "扩列推荐响应不完整，请重试"); false
            } else pages.accept(ticket, result.cards, result.nextCursor)
            if (!accepted) { restoringFeed = false; changed(); return@execute }
            val missingAnchor = restoringFeed && feedPosition.cardId != null && pages.cards.none { it.id == feedPosition.cardId }
            if ((pages.cards.size == previousCount || missingAnchor) && pages.nextCursor != null) load()
            else restoringFeed = false
            changed()
        }, { pages.fail(ticket, message(it)); restoringFeed = false; changed() })
    }

    private fun invalidateData() {
        busy = false; card = null; loaded = false; loading = false; globalError = null
        historyReconciled = false; mineInvalidated = false
        history.reset(); pages.reset()
        restoringMy = myPosition.index > 0
        restoringFeed = feedPosition.index > 0
        refreshToken++
    }
    override fun onHidden() { invalidateData(); super.onHidden() }
    override fun onDestroyView() { invalidateData(); super.onDestroyView() }
    private fun reload() {
        if (busy || !shown || !accountValid()) return
        cancelRequests(); invalidateData(); refresh()
    }
    private fun selectTab(value: FriendCardHubTab) {
        if (busy || !shown || !accountValid() || tab == value) return
        tab = value; updateTitle(); refresh()
    }
    private fun selectFilter(value: FriendCardHubFilter) {
        if (busy || !shown || !accountValid()) return
        filter = value; feedPosition = FriendCardHubPosition(); restoringFeed = false; refreshToken++
        if (value == FriendCardHubFilter.SameCity && !loaded && !loading && !mineInvalidated) loadMine()
        changed()
    }
    private fun search(value: String) {
        if (busy || !shown || !accountValid()) return
        query = value; feedPosition = FriendCardHubPosition(); restoringFeed = false; refreshToken++; changed()
    }

    private fun managed(value: FriendCard): FriendCard? = (listOfNotNull(card) + history.cards)
        .firstOrNull { it.isSupported && it.id == value.id && it.version == value.version }
    private fun current(value: FriendCard): FriendCard? = managed(value)
        ?: pages.cards.firstOrNull { it.isSupported && it.id == value.id && it.version == value.version }
    private fun withCurrent(value: FriendCard, action: (FriendCard) -> Unit) {
        if (!busy && shown && accountValid()) current(value)?.let(action)
    }
    private fun withManaged(value: FriendCard, action: (FriendCard) -> Unit) {
        if (!busy && shown && accountValid()) managed(value)?.let(action)
    }
    private fun changeState(value: FriendCard) {
        val fresh = managed(value) ?: return
        if (busy || !shown || !accountValid() || fresh.state !in listOf("active", "paused")) return
        val next = if (fresh.state == "paused") "active" else "paused"
        confirm(if (next == "paused") "暂停展示" else "恢复展示", "只更新原卡与原发布帖，不会重复发帖。", "确认") {
            val confirmed = managed(fresh) ?: return@confirm
            if (busy) return@confirm
            busy = true; changed()
            execute(FriendCardRequest.state(confirmed.id, confirmed.version, next), { result ->
                busy = false
                if (result.card?.isSupported == true && result.card.id == confirmed.id && result.card.state == next) reload()
                else { globalError = "服务端未确认状态变更，请刷新核实"; changed() }
            }, { busy = false; globalError = message(it); changed() })
        }
    }
    private fun delete(value: FriendCard) {
        if (busy || !shown || !accountValid() || managed(value) == null) return
        confirm("删除扩列卡", "公开资料及关联扩列帖将撤回，历史链接会显示不可用。此操作不可撤销。", "删除") {
            val confirmed = managed(value) ?: return@confirm
            if (busy) return@confirm
            busy = true; changed()
            execute(FriendCardRequest.delete(confirmed.id, confirmed.version), { result ->
                busy = false
                if (result.deleted) reload() else { globalError = "服务端未确认删除，请刷新核实"; changed() }
            }, { busy = false; globalError = message(it); changed() })
        }
    }

    @Composable override fun Screen(revision: Int) {
        // Native callbacks mutate plain fields; materialize fresh list snapshots on every revision.
        val state = remember(revision) {
            FriendCardHubState(tab, card, loaded, loading, busy, globalError,
                history.cards.filterNot { it.id == card?.id }.toList(), history.loaded, history.loading, history.error, history.nextCursor,
                pages.cards.toList(), pages.loaded, pages.loading, pages.error, pages.nextCursor,
                query, filter, myPosition, feedPosition, restoringMy, restoringFeed, refreshToken)
        }
        FriendCardHubScreen(state, FriendCardHubActions(
            back = { finish() }, tab = ::selectTab, refresh = ::reload, query = ::search, filter = ::selectFilter,
            create = { if (!busy && shown) go(FriendCardCreateFragment::class.java) },
            edit = { withManaged(it) { value -> go(FriendCardCreateFragment::class.java, args(value.id)) } },
            detail = { withCurrent(it) { value -> detail(value) } },
            profile = { withCurrent(it) { value -> profile(value) } },
            state = ::changeState, post = { withManaged(it) { value -> post(value) } }, delete = ::delete,
            history = ::loadHistory, next = ::load,
            myPosition = { if (shown && loaded && !restoringMy) myPosition = it },
            feedPosition = { if (shown && pages.loaded && !restoringFeed) feedPosition = it },
        ))
    }

    companion object { const val ARG_INITIAL_TAB = "friend_card_hub_tab" }
}
