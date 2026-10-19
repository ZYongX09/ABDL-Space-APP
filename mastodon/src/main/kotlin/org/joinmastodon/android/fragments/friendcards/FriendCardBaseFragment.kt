package org.joinmastodon.android.fragments.friendcards

import android.app.Fragment
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import me.grishka.appkit.Nav
import me.grishka.appkit.api.Callback
import me.grishka.appkit.api.ErrorResponse
import me.grishka.appkit.fragments.ToolbarFragment
import org.joinmastodon.android.R
import org.joinmastodon.android.api.MastodonAPIRequest
import org.joinmastodon.android.api.MastodonErrorResponse
import org.joinmastodon.android.api.requests.statuses.GetStatusByID
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.chat.ui.ChatFragment
import org.joinmastodon.android.fragments.ThreadFragment
import org.joinmastodon.android.fragments.diapers.DiaperDetailFragment
import org.joinmastodon.android.model.FriendCard
import org.joinmastodon.android.ui.M3AlertDialogBuilder
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import org.joinmastodon.android.ui.compose.friendcards.FriendCardNotice
import org.joinmastodon.android.ui.utils.UiUtils
import org.parceler.Parcels
import top.yukonga.miuix.kmp.theme.MiuixTheme

abstract class FriendCardBaseFragment : ToolbarFragment() {
    protected lateinit var account: String
    protected var generation = 0
    private var revision by mutableIntStateOf(0)
    private val requests = mutableListOf<MastodonAPIRequest<*>>()
    private var contentView: ComposeView? = null
    protected var shown = false
    protected var globalError: String? = null
    protected var activeDialog: android.app.Dialog? = null
    private var boundSession: org.joinmastodon.android.api.session.AccountSession? = null
    protected fun changed() { revision++ }
    protected fun accountValid(): Boolean = ::account.isInitialized && boundSession != null && AccountSessionManager.getInstance().tryGetAccount(account) === boundSession && AccountSessionManager.getInstance().lastActiveAccountID == account
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        retainInstance = true
        account = arguments?.getString("account").orEmpty()
        boundSession = AccountSessionManager.getInstance().tryGetAccount(account)
    }
    override fun onCreateContentView(inflater: LayoutInflater, container: ViewGroup, state: Bundle?): View {
        return ComposeView(activity).also { view ->
            contentView = view
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            view.setContent {
                val redraw = revision
                MiuixAppTheme {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).imePadding()) {
                        if (!accountValid()) FriendCardNotice("当前账号不可用，请返回并切换到原账号", null, null)
                        else Screen(redraw)
                    }
                }
            }
        }
    }
    @Composable protected abstract fun Screen(revision: Int)
    override fun onShown() {
        super.onShown(); shown = true
        if (accountValid()) refresh() else changed()
    }
    protected abstract fun refresh()
    override fun onHidden() { super.onHidden(); shown = false; activeDialog?.dismiss(); activeDialog = null; cancelRequests() }
    override fun onDestroyView() { activeDialog?.dismiss(); activeDialog = null; cancelRequests(); contentView = null; super.onDestroyView() }
    protected fun cancelRequests() { generation++; requests.toList().forEach { it.cancel() }; requests.clear(); changed() }
    protected fun <T> execute(request: MastodonAPIRequest<T>, success: (T) -> Unit, failure: (ErrorResponse) -> Unit = { globalError = message(it); changed() }) {
        if (!accountValid()) { globalError = "当前账号不可用"; changed(); return }
        val token = generation
        requests.add(request)
        request.setCallback(object : Callback<T> {
            override fun onSuccess(result: T) { requests.remove(request); if (token == generation && shown && accountValid()) success(result) }
            override fun onError(error: ErrorResponse) { requests.remove(request); if (token == generation && shown && accountValid()) failure(error) }
        })
        request.exec(account)
    }
    protected fun message(error: ErrorResponse): String = when (error) {
        is MastodonErrorResponse -> if (error.httpStatus == 404 || error.httpStatus == 501) activity?.getString(R.string.friend_cards_unavailable) ?: "扩列服务或此卡暂不可用，请稍后重试" else error.error?.takeIf { it.isNotBlank() } ?: "网络请求失败，请重试"
        else -> "网络请求失败，请重试"
    }
    protected fun definitive(error: ErrorResponse) = error is MastodonErrorResponse && error.httpStatus in 400..499 && error.httpStatus !in listOf(408, 425, 429)
    protected fun args(cardId: String? = null) = Bundle().apply { putString("account", account); cardId?.let { putString("card_id", it) } }
    protected fun go(type: Class<out Fragment>, bundle: Bundle = args()) { if (accountValid()) Nav.go(activity, type, bundle) }
    protected fun finish() { Nav.finish(this) }
    protected fun detail(card: FriendCard) { go(FriendCardDetailFragment::class.java, args(card.id)) }
    protected fun diaper(item: FriendCard.Diaper) {
        if (!item.available) { toast("商品已下架或不可用"); return }
        go(DiaperDetailFragment::class.java, args().apply { putInt("diaper_id", item.id) })
    }
    protected fun profile(card: FriendCard) { if (accountValid()) UiUtils.openProfileByID(activity, account, card.ownerId) }
    protected fun chat(card: FriendCard) {
        val peer = card.ownerId?.toLongOrNull()
        if (peer == null || peer <= 0) { toast("无法打开此用户的私信"); return }
        go(ChatFragment::class.java, args().apply { putLong("peer_id", peer); putString("peer_name", card.owner?.displayName?.takeIf { it.isNotBlank() } ?: card.owner?.username); putString("peer_avatar", card.owner?.avatar) })
    }
    protected fun post(card: FriendCard) {
        val id = card.postId?.takeIf { it.matches(Regex("[0-9]+")) } ?: run { toast("发布帖暂不可用"); return }
        execute(GetStatusByID("p_$id"), { status -> go(ThreadFragment::class.java, args().apply { putParcelable("status", Parcels.wrap(status)) }) })
    }
    protected fun owner(card: FriendCard) = !card.ownerId.isNullOrBlank() && card.ownerId == AccountSessionManager.getInstance().tryGetAccount(account)?.self?.id
    protected fun confirm(title: String, body: String, action: String, run: () -> Unit) {
        if (!accountValid()) return
        activeDialog?.dismiss()
        activeDialog = M3AlertDialogBuilder(activity).setTitle(title).setMessage(body).setPositiveButton(action) { _, _ -> if (shown && accountValid()) run() }.setNegativeButton("取消", null).show()
    }
    protected fun toast(text: String) { activity?.let { Toast.makeText(it, text, Toast.LENGTH_LONG).show() } }
    override fun wantsLightStatusBar() = !UiUtils.isDarkTheme()
}
