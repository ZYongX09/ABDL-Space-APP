@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.joinmastodon.android.fragments.friendcards

import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.joinmastodon.android.R
import org.joinmastodon.android.ui.utils.UiUtils
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.api.requests.friendcards.FriendCardRequest
import org.joinmastodon.android.model.FriendCard
import org.joinmastodon.android.ui.M3AlertDialogBuilder
import org.joinmastodon.android.ui.compose.friendcards.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

class FriendCardDetailFragment : FriendCardBaseFragment() {
    private var card: FriendCard? = null
    private var loading = false
    private var busy = false
    private lateinit var cardId: String
    override fun onCreate(state: Bundle?) { super.onCreate(state); setTitle("扩列卡"); cardId = arguments?.getString("card_id").orEmpty() }
    override fun onViewCreated(view: View, state: Bundle?) { super.onViewCreated(view, state); styleToolbar() }
    override fun onUpdateToolbar() { super.onUpdateToolbar(); styleToolbar() }
    private fun styleToolbar() {
        val host = activity ?: return
        val bar = toolbar ?: return
        val value = card
        val background = value?.let { friendCardPalette(it).background.toArgb() } ?: UiUtils.getThemeColor(host, R.attr.colorM3Background)
        val foreground = if (value != null) android.graphics.Color.WHITE else UiUtils.getThemeColor(host, R.attr.colorM3OnSurface)
        bar.setBackgroundColor(background)
        bar.setTitleTextColor(foreground)
        bar.navigationIcon?.mutate()?.let { it.setTint(foreground); bar.navigationIcon = it }
        setStatusBarColor(background)
        setNavigationBarColor(background)
    }
    override fun wantsLightStatusBar() = card == null && !UiUtils.isDarkTheme()
    override fun refresh() {
        if (busy) return
        cancelRequests()
        card = null; loading = true; globalError = null; styleToolbar(); changed()
        if (cardId.isBlank()) { loading = false; globalError = "卡片暂不可用"; changed(); return }
        execute(FriendCardRequest.detail(cardId), { result ->
            loading = false
            if (result.card?.isSupported == true && result.card.id == cardId) card = result.card else globalError = "此卡暂不可用或格式不受支持"
            onUpdateToolbar()
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
    private data class ScreenState(val card: FriendCard?, val loading: Boolean, val busy: Boolean, val error: String?)
    @Composable override fun Screen(revision: Int) {
        val state = remember(revision) { ScreenState(card, loading, busy, globalError) }
        val value = state.card
        val palette = value?.let { friendCardPalette(it) }
        Column(Modifier.fillMaxSize().background(palette?.background ?: MiuixTheme.colorScheme.background)) {
            // The native toolbar is supplied by Base. Only the public portrait scrolls;
            // the footer takes its own measured space instead of covering the last section.
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.loading) item { FriendCardNotice("正在读取卡片…", null, null) }
                state.error?.let { text -> item {
                    Box(Modifier.padding(12.dp).clip(RoundedCornerShape(18.dp)).background(MiuixTheme.colorScheme.surface)) {
                        FriendCardNotice(text, "重新读取", { refresh() })
                    }
                } }
                if (value != null) {
                    item { FriendCardPortrait(value, onDiaper = { diaper(it) }) }
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                DetailLink("用户主页", !state.busy) { profile(value) }
                                DetailLink("查看发布帖与互动", !state.busy) { post(value) }
                                if (!owner(value)) {
                                    DetailLink("举报", !state.busy) { report(value) }
                                    DetailLink("屏蔽用户", !state.busy) { block(value) }
                                }
                            }
                            if (owner(value)) Text("此处预览公开资料；编辑页可查看和修改仅自己可见字段。",
                                color = Color.White.copy(alpha = .72f), fontSize = 12.sp)
                        }
                    }
                }
            }
            if (value != null) DetailFooter(value, state.busy)
        }
    }

    @Composable private fun DetailFooter(value: FriendCard, busy: Boolean) {
        val palette = friendCardPalette(value)
        Column(Modifier.fillMaxWidth().background(palette.background).padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (owner(value)) {
                FriendCardIdentity(value, onClick = if (busy) null else ({ profile(value) }))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stacked = maxWidth.value / LocalDensity.current.fontScale < 320f
                    if (stacked) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            FriendCardPrimaryButton("编辑卡片", { go(FriendCardCreateFragment::class.java, args(value.id)) }, enabled = !busy, outlined = true, light = true)
                            FriendCardPrimaryButton("管理我的卡", { go(MyFriendCardsFragment::class.java) }, enabled = !busy, outlined = true, light = true)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FriendCardPrimaryButton("编辑卡片", { go(FriendCardCreateFragment::class.java, args(value.id)) }, modifier = Modifier.weight(1f), enabled = !busy, outlined = true, light = true)
                            FriendCardPrimaryButton("管理我的卡", { go(MyFriendCardsFragment::class.java) }, modifier = Modifier.weight(1f), enabled = !busy, outlined = true, light = true)
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FriendCardIdentity(value, modifier = Modifier.weight(1f), onClick = if (busy) null else ({ profile(value) }))
                    FriendCardHello(enabled = !busy) { chat(value) }
                }
            }
        }
    }

    @Composable private fun DetailLink(text: String, enabled: Boolean, onClick: () -> Unit) {
        Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(18.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color.White.copy(alpha = if (enabled) .72f else .36f), fontSize = 13.sp)
        }
    }
}
