package org.joinmastodon.android.fragments.friendcards

import android.os.Bundle
import android.widget.EditText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.joinmastodon.android.api.requests.friendcards.FriendCardRequest
import org.joinmastodon.android.model.FriendCard
import org.joinmastodon.android.ui.M3AlertDialogBuilder
import org.joinmastodon.android.ui.compose.friendcards.*
import top.yukonga.miuix.kmp.basic.Text

class FriendCardDetailFragment : FriendCardBaseFragment() {
    private var card: FriendCard? = null
    private var loading = false
    private var busy = false
    private lateinit var cardId: String
    override fun onCreate(state: Bundle?) { super.onCreate(state); setTitle("扩列卡"); cardId = arguments?.getString("card_id").orEmpty() }
    override fun refresh() {
        if (busy) return
        cancelRequests()
        card = null; loading = true; globalError = null; changed()
        if (cardId.isBlank()) { loading = false; globalError = "卡片暂不可用"; changed(); return }
        execute(FriendCardRequest.detail(cardId), { result ->
            loading = false
            if (result.card?.isSupported == true && result.card.id == cardId) card = result.card else globalError = "此卡暂不可用或格式不受支持"
            changed()
        }, { loading = false; globalError = message(it); changed() })
    }
    override fun onHidden() { busy = false; super.onHidden(); card = null; changed() }
    private fun report(value: FriendCard) {
        val input = EditText(activity).apply { hint = "请填写举报原因（1–500字）"; minLines = 2; maxLines = 6 }
        activeDialog?.dismiss()
        activeDialog = M3AlertDialogBuilder(activity).setTitle("举报扩列卡").setView(input).setPositiveButton("提交举报") { _, _ ->
            if (!shown || !accountValid() || busy) return@setPositiveButton
            val reason = input.text.toString().trim()
            if (reason.isEmpty() || reason.codePointCount(0, reason.length) > 500) { toast("请填写1–500字的举报原因"); return@setPositiveButton }
            busy = true; changed()
            execute(FriendCardRequest.report(value.id, reason, null), { result -> busy = false; if (result.reported) toast("举报已提交") else globalError = "服务端未确认举报，请重试"; changed() }, { busy = false; globalError = message(it); changed() })
        }.setNegativeButton("取消", null).show()
    }
    private fun block(value: FriendCard) {
        confirm("屏蔽此用户", "将停止互相推荐与读取新卡，并拒绝私信。只对这位用户生效。", "屏蔽") {
            busy = true; changed()
            execute(FriendCardRequest.block(value.ownerId, true), { result -> busy = false; if (result.blocked) { card = null; toast("已屏蔽此用户"); finish() } else globalError = "服务端未确认屏蔽，请重试"; changed() }, { busy = false; globalError = message(it); changed() })
        }
    }
    @Composable override fun Screen() {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (loading) item { FriendCardNotice("正在读取卡片…", null, null) }
            globalError?.let { text -> item { FriendCardNotice(text, "重新读取", { refresh() }) } }
            card?.let { value ->
                item { FriendCardCover(value.coverUrl, value.title, value.coverFocusX, value.coverFocusY, 1.5f) }
                item { FriendCardPanel {
                    Text(value.owner?.displayName?.takeIf { it.isNotBlank() } ?: value.owner?.username ?: "扩列用户")
                    FriendCardActions("用户主页" to { profile(value) }, "查看发布帖与互动" to { post(value) }, enabled = !busy)
                    FriendCardContents(value, onDiaper = { diaper(it) })
                } }
                item { FriendCardPanel {
                    if (owner(value)) {
                        Text("此处预览公开资料；编辑页可查看和修改仅自己可见字段。")
                        FriendCardActions("编辑卡片" to { go(FriendCardCreateFragment::class.java, args(value.id)) }, "管理我的卡" to { go(MyFriendCardsFragment::class.java) }, enabled = !busy)
                    } else FriendCardActions("发私信" to { chat(value) }, "举报" to { report(value) }, "屏蔽用户" to { block(value) }, enabled = !busy)
                } }
            }
        }
    }
}
