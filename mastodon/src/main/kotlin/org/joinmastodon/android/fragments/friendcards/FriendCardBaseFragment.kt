package org.joinmastodon.android.fragments.friendcards

import android.app.Fragment
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.mutableStateOf
import org.joinmastodon.android.api.ServiceResourcePause
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
    private var serviceResourcePause by mutableStateOf<ServiceResourcePause?>(null)
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
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
                        if (!accountValid()) FriendCardNotice(getString(R.string.fc_account_unavailable_help), null, null)
                        else Column(Modifier.fillMaxSize()) {
                            serviceResourcePause?.let { pause ->
                                AndroidView(
                                    factory = { context -> android.widget.ScrollView(context).apply { addView(android.widget.TextView(context).apply { setTextSize(16f); setPadding(24, 16, 24, 16) }) } },
                                    update = { scroll -> pause.bind(scroll.getChildAt(0) as android.widget.TextView) },
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(horizontal = 8.dp),
                                )
                            }
                            Box(Modifier.weight(1f)) { Screen(redraw) }
                        }
                    }
                }
            }
        }
    }
    @Composable protected abstract fun Screen(revision: Int)
    override fun onShown() {
        super.onShown(); shown = true
        serviceResourcePause = null
        if (accountValid()) refresh() else changed()
    }
    protected abstract fun refresh()
    override fun onHidden() { super.onHidden(); shown = false; activeDialog?.dismiss(); activeDialog = null; cancelRequests() }
    override fun onDestroyView() { activeDialog?.dismiss(); activeDialog = null; cancelRequests(); contentView = null; super.onDestroyView() }
    protected fun cancelRequests() { generation++; requests.toList().forEach { it.cancel() }; requests.clear(); changed() }
    protected fun <T> execute(request: MastodonAPIRequest<T>, success: (T) -> Unit, failure: (ErrorResponse) -> Unit = { globalError = message(it); changed() }) {
        if (!accountValid()) { globalError = getString(R.string.fc_account_unavailable); changed(); return }
        val token = generation
        requests.add(request)
        request.setCallback(object : Callback<T> {
            override fun onSuccess(result: T) { requests.remove(request); if (token == generation && shown && accountValid()) success(result) }
            override fun onError(error: ErrorResponse) {
                requests.remove(request)
                if (token == generation && shown && accountValid()) {
                    ServiceResourcePause.fromError(error)?.let { serviceResourcePause = it }
                    failure(error)
                }
            }
        })
        request.exec(account)
    }
    protected fun message(error: ErrorResponse): String = when {
        // Full live pause text is bound above the screen; never freeze calendar words in draft/UI strings.
        ServiceResourcePause.fromError(error) != null -> getString(R.string.fc_request_failed)
        error is MastodonErrorResponse -> if (error.httpStatus == 404 || error.httpStatus == 501) getString(R.string.friend_cards_unavailable) else error.error?.takeIf { it.isNotBlank() } ?: getString(R.string.fc_request_failed)
        else -> getString(R.string.fc_request_failed)
    }
    protected fun definitive(error: ErrorResponse) = error is MastodonErrorResponse && error.httpStatus in 400..499 && error.httpStatus !in listOf(408, 425, 429)
    protected fun args(cardId: String? = null) = Bundle().apply { putString("account", account); cardId?.let { putString("card_id", it) } }
    protected fun go(type: Class<out Fragment>, bundle: Bundle = args()) { if (accountValid()) Nav.go(activity, type, bundle) }
    protected fun finish() { Nav.finish(this) }
    protected fun detail(card: FriendCard) { go(FriendCardDetailFragment::class.java, args(card.id)) }
    protected fun diaper(item: FriendCard.Diaper) {
        if (!item.available) { toast(getString(R.string.fc_product_not_available)); return }
        go(DiaperDetailFragment::class.java, args().apply { putInt("diaper_id", item.id) })
    }
    protected fun profile(card: FriendCard) { if (accountValid()) UiUtils.openProfileByID(activity, account, card.ownerId) }
    protected fun chat(card: FriendCard) {
        val peer = card.ownerId?.toLongOrNull()
        if (peer == null || peer <= 0) { toast(getString(R.string.fc_message_unavailable)); return }
        go(ChatFragment::class.java, args().apply { putLong("peer_id", peer); putString("peer_name", card.owner?.displayName?.takeIf { it.isNotBlank() } ?: card.owner?.username); putString("peer_avatar", card.owner?.avatar) })
    }
    protected fun post(card: FriendCard) {
        val id = card.postId?.takeIf { it.matches(Regex("[0-9]+")) } ?: run { toast(getString(R.string.fc_post_unavailable)); return }
        execute(GetStatusByID("p_$id"), { status -> go(ThreadFragment::class.java, args().apply { putParcelable("status", Parcels.wrap(status)) }) })
    }
    protected fun owner(card: FriendCard) = !card.ownerId.isNullOrBlank() && card.ownerId == AccountSessionManager.getInstance().tryGetAccount(account)?.self?.id
    protected fun confirm(title: String, body: String, action: String, run: () -> Unit) {
        if (!accountValid()) return
        activeDialog?.dismiss()
        activeDialog = M3AlertDialogBuilder(activity).setTitle(title).setMessage(body).setPositiveButton(action) { _, _ -> if (shown && accountValid()) run() }.setNegativeButton(getString(R.string.fc_cancel), null).show()
    }
    protected fun toast(text: String) { activity?.let { Toast.makeText(it, text, Toast.LENGTH_LONG).show() } }
    override fun wantsLightStatusBar() = !UiUtils.isDarkTheme()
}
