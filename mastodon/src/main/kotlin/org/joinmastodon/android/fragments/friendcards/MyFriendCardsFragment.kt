package org.joinmastodon.android.fragments.friendcards

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.joinmastodon.android.api.requests.friendcards.FriendCardRequest
import org.joinmastodon.android.friendcards.FriendCardPages
import org.joinmastodon.android.model.FriendCard
import org.joinmastodon.android.ui.compose.friendcards.*
import top.yukonga.miuix.kmp.basic.Text

class MyFriendCardsFragment : FriendCardBaseFragment() {
    private var card: FriendCard? = null
    private var loaded = false
    private var loading = false
    private var busy = false
    private val history = FriendCardPages()
    override fun onCreate(state: Bundle?) { super.onCreate(state); setTitle("我的扩列卡") }
    override fun refresh() {
        if (busy) return
        cancelRequests()
        card = null; loaded = false; loading = true; globalError = null; history.reset(); changed()
        execute(FriendCardRequest.me(), { result ->
            loading = false
            if (result.card != null && !result.card.isSupported) { globalError = "扩列卡格式暂不受支持，请稍后重试" }
            else { card = result.card; loaded = true; loadHistory() }
            changed()
        }, { loading = false; globalError = message(it); changed() })
    }
    private fun loadHistory() {
        val ticket = history.begin() ?: return
        changed()
        execute(FriendCardRequest.history(ticket.cursor), { result ->
            if (result.cards == null) history.fail(ticket, "历史卡响应不完整，请重试") else history.accept(ticket, result.cards, result.nextCursor)
            changed()
        }, { history.fail(ticket, message(it)); changed() })
    }
    override fun onHidden() { busy = false; card = null; loaded = false; history.reset(); super.onHidden() }
    private fun changeState(value: FriendCard) {
        if (busy) return
        val next = if (value.state == "paused") "active" else "paused"
        confirm(if (next == "paused") "暂停展示" else "恢复展示", "只更新原卡与原发布帖，不会重复发帖。", "确认") {
            busy = true; changed()
            execute(FriendCardRequest.state(value.id, value.version, next), { result ->
                busy = false
                if (result.card?.isSupported == true && result.card.id == value.id && result.card.state == next) refresh() else { globalError = "服务端未确认状态变更，请刷新核实"; changed() }
            }, { busy = false; globalError = message(it); changed() })
        }
    }
    private fun delete(value: FriendCard) {
        confirm("删除扩列卡", "公开资料及关联扩列帖将撤回，历史链接会显示不可用。此操作不可撤销。", "删除") {
            if (busy) return@confirm
            busy = true; changed()
            execute(FriendCardRequest.delete(value.id, value.version), { result -> busy = false; if (result.deleted) refresh() else { globalError = "服务端未确认删除，请刷新核实"; changed() } }, { busy = false; globalError = message(it); changed() })
        }
    }
    private data class ScreenState(
        val card: FriendCard?, val loaded: Boolean, val loading: Boolean, val busy: Boolean, val error: String?,
        val history: List<FriendCard>, val historyLoaded: Boolean, val historyLoading: Boolean,
        val historyError: String?, val historyCursor: String?,
    )
    @Composable override fun Screen(revision: Int) {
        val state = remember(revision) {
            ScreenState(card, loaded, loading, busy, globalError, history.cards.toList(),
                history.loaded, history.loading, history.error, history.nextCursor)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { FriendCardActions("创建新卡" to { go(FriendCardCreateFragment::class.java) }, "浏览扩列" to { go(FriendCardFeedFragment::class.java) }, "刷新" to { refresh() }, enabled = !state.busy) }
            state.error?.let { text -> item { FriendCardNotice(text, "重试", { refresh() }) } }
            if (state.loading) item { FriendCardNotice("正在读取我的扩列卡…", null, null) }
            if (state.loaded && state.card == null) item { FriendCardPanel("还没有当前卡") { Text("可以先浏览。创建新卡需要当前有效宝宝认证，浏览与管理不设此门槛。") } }
            state.card?.let { value -> item {
                FriendCardPanel("当前卡 · ${stateName(value.state)}") {
                    FriendCardCover(value.coverUrl, value.title, value.coverFocusX, value.coverFocusY)
                    value.reason?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    FriendCardActions("详情" to { detail(value) }, "编辑" to { go(FriendCardCreateFragment::class.java, args(value.id)) }, (if (value.state == "paused") "恢复展示" else "暂停展示") to { changeState(value) }, "发布帖" to { post(value) }, "删除" to { delete(value) }, enabled = !state.busy)
                }
            } }
            if (state.loaded) item { Text("历史卡 · 仅自己可查看") }
            items(state.history, key = { it.id }) { value -> FriendCardPanel(value.title) { Text(stateName(value.state)); FriendCardActions("查看" to { detail(value) }, "编辑" to { go(FriendCardCreateFragment::class.java, args(value.id)) }, "发布帖" to { post(value) }, "删除" to { delete(value) }, enabled = !state.busy) } }
            if (state.historyLoaded && state.history.isEmpty()) item { FriendCardNotice("暂无历史卡", null, null) }
            state.historyError?.let { text -> item { FriendCardNotice(text, "重试加载历史", { loadHistory() }) } }
            if (state.historyLoading) item { FriendCardNotice("正在加载历史…", null, null) }
            if (state.historyLoaded && state.historyCursor != null && !state.historyLoading && state.historyError == null) item { FriendCardActions("加载更多历史" to { loadHistory() }) }
        }
    }
    private fun stateName(state: String?) = when (state) { "active" -> "展示中"; "paused" -> "已暂停"; "archived" -> "已归档"; else -> "${state ?: "状态未知"} · 以服务端审核状态为准" }
}
