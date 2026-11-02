@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.joinmastodon.android.fragments.friendcards

import org.joinmastodon.android.R
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalView
import org.joinmastodon.android.GlobalUserPreferences
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
    private var safeTopPx by mutableIntStateOf(0)
    private lateinit var cardId: String
    override fun onCreate(state: Bundle?) { super.onCreate(state); setTitle(R.string.friend_cards_detail_title); cardId = arguments?.getString("card_id").orEmpty() }
    @Suppress("DEPRECATION")
    override fun onApplyWindowInsets(insets: WindowInsets) {
        // AppKit already lays out behind the bars. Move only its top reservation into the
        // overlay/hero content; the background must still start at the native root's y=0.
        safeTopPx = maxOf(insets.systemWindowInsetTop,
            if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0,
            if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
            } else 0)
        val contentInsets = if (Build.VERSION.SDK_INT >= 29) {
            // Unlike replaceSystemWindowInsets, inset also clears typed status/cutout and
            // stable tops on API 30+, without converting an IME bottom into navigation bars.
            insets.inset(0, safeTopPx, 0, 0)
        } else {
            insets.replaceSystemWindowInsets(insets.systemWindowInsetLeft, 0,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        // Sides, navigation/gesture bottom and keyboard resizing remain owned by AppKit.
        super.onApplyWindowInsets(contentInsets)
    }
    override fun onViewCreated(view: View, state: Bundle?) { super.onViewCreated(view, state); styleToolbar() }
    override fun onUpdateToolbar() { super.onUpdateToolbar(); styleToolbar() }
    private fun styleToolbar() {
        if (activity == null) return
        val bar = toolbar ?: return
        val value = card
        val background = value?.let { friendCardPalette(it).background.toArgb() } ?: 0xFF263F43.toInt()
        bar.visibility = View.GONE
        setStatusBarColor(android.graphics.Color.TRANSPARENT)
        setNavigationBarColor(background)
    }
    override fun wantsLightStatusBar() = false
    override fun refresh() {
        if (busy) return
        cancelRequests()
        card = null; loading = true; globalError = null; styleToolbar(); changed()
        if (cardId.isBlank()) { loading = false; globalError = getString(R.string.fc_card_unavailable); changed(); return }
        execute(FriendCardRequest.detail(cardId), { result ->
            loading = false
            if (result.card?.isSupported == true && result.card.id == cardId) card = result.card else globalError = getString(R.string.fc_card_unsupported)
            onUpdateToolbar()
            changed()
        }, { loading = false; globalError = message(it); changed() })
    }
    override fun onHidden() { busy = false; super.onHidden(); card = null; changed() }
    private fun report(value: FriendCard) {
        val input = EditText(activity).apply { hint = getString(R.string.fc_report_hint); minLines = 2; maxLines = 6 }
        activeDialog?.dismiss()
        activeDialog = M3AlertDialogBuilder(activity).setTitle(getString(R.string.fc_report_card)).setView(input).setPositiveButton(getString(R.string.fc_submit_report)) { _, _ ->
            if (!shown || !accountValid() || busy) return@setPositiveButton
            val reason = input.text.toString().trim()
            if (reason.isEmpty() || reason.codePointCount(0, reason.length) > 500) { toast(getString(R.string.fc_report_invalid)); return@setPositiveButton }
            busy = true; changed()
            execute(FriendCardRequest.report(value.id, reason, null), { result -> busy = false; if (result.reported) toast(getString(R.string.fc_report_sent)) else globalError = getString(R.string.fc_report_unconfirmed); changed() }, { busy = false; globalError = message(it); changed() })
        }.setNegativeButton(getString(R.string.fc_cancel), null).show()
    }
    private fun block(value: FriendCard) {
        confirm(getString(R.string.fc_block_user_title), getString(R.string.fc_block_user_help), getString(R.string.fc_block)) {
            busy = true; changed()
            execute(FriendCardRequest.block(value.ownerId, true), { result -> busy = false; if (result.blocked) { card = null; toast(getString(R.string.fc_blocked)); finish() } else globalError = getString(R.string.fc_block_unconfirmed); changed() }, { busy = false; globalError = message(it); changed() })
        }
    }
    private data class ScreenState(val card: FriendCard?, val loading: Boolean, val busy: Boolean, val error: String?)
    @Composable override fun Screen(revision: Int) {
        val state = remember(revision) { ScreenState(card, loading, busy, globalError) }
        val value = state.card
        val palette = value?.let { friendCardPalette(it) }
        val contrast = rememberFriendCardContrastSampler()
        val liquid = GlobalUserPreferences.isIosLiquidNavigationEnabled() && LocalView.current.isHardwareAccelerated && isRuntimeShaderSupported()
        val backdrop = if (liquid) contrast.backdrop else null
        val scroll = rememberLazyListState()
        LaunchedEffect(contrast, scroll) {
            snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }
                .collect { contrast.invalidateContent() }
        }
        val background = palette?.background ?: Color(0xFF263F43)
        val safeTop = with(LocalDensity.current) { safeTopPx.toDp() }
        val topClearance = safeTop + 64.dp
        Box(Modifier.fillMaxSize().background(background)) {
            Column(Modifier.fillMaxSize().friendCardContrastContent(contrast).background(background)) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.loading) item {
                    Box(Modifier.padding(top = topClearance, start = 12.dp, end = 12.dp).clip(RoundedCornerShape(18.dp)).background(MiuixTheme.colorScheme.surface)) {
                        FriendCardNotice(getString(R.string.fc_loading_card), null, null)
                    }
                }
                state.error?.let { text -> item {
                    Box(Modifier.padding(top = topClearance, start = 12.dp, end = 12.dp, bottom = 12.dp).clip(RoundedCornerShape(18.dp)).background(MiuixTheme.colorScheme.surface)) {
                        FriendCardNotice(text, getString(R.string.fc_reload), { refresh() })
                    }
                } }
                if (value != null) {
                    item {
                        androidx.compose.runtime.CompositionLocalProvider(LocalFriendCardContentInvalidated provides contrast::invalidateContent) {
                            FriendCardPortrait(value, topClearance = topClearance, onDiaper = { diaper(it) })
                        }
                    }
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                DetailLink(getString(R.string.fc_profile), !state.busy) { profile(value) }
                                DetailLink(getString(R.string.fc_post_interactions), !state.busy) { post(value) }
                                if (!owner(value)) {
                                    DetailLink(getString(R.string.fc_report), !state.busy) { report(value) }
                                    DetailLink(getString(R.string.fc_block_user), !state.busy) { block(value) }
                                }
                            }
                            if (owner(value)) Text(getString(R.string.fc_owner_preview_help),
                                color = Color.White.copy(alpha = .72f), fontSize = 12.sp)
                        }
                    }
                }
            }
            if (value != null) DetailFooter(value, state.busy)
            }
            FriendCardDetailControls(backdrop, !state.busy, { finish() }, { go(MyFriendCardsFragment::class.java) },
                modifier = Modifier.padding(top = safeTop), contrast = contrast)
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
                            FriendCardPrimaryButton(getString(R.string.fc_edit_card), { go(FriendCardCreateFragment::class.java, args(value.id)) }, enabled = !busy, outlined = true, light = true)
                            FriendCardPrimaryButton(getString(R.string.fc_manage_cards_short), { go(MyFriendCardsFragment::class.java) }, enabled = !busy, outlined = true, light = true)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FriendCardPrimaryButton(getString(R.string.fc_edit_card), { go(FriendCardCreateFragment::class.java, args(value.id)) }, modifier = Modifier.weight(1f), enabled = !busy, outlined = true, light = true)
                            FriendCardPrimaryButton(getString(R.string.fc_manage_cards_short), { go(MyFriendCardsFragment::class.java) }, modifier = Modifier.weight(1f), enabled = !busy, outlined = true, light = true)
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
