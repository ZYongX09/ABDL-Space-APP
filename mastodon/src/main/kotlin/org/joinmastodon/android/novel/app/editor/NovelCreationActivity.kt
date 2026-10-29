package org.joinmastodon.android.novel.app.editor

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.ui.platform.AbstractComposeView
import com.squareup.otto.Subscribe
import kotlinx.coroutines.cancel
import org.joinmastodon.android.E
import org.joinmastodon.android.events.AccountLoggedOutEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.novel.app.NovelAppApi
import org.joinmastodon.android.novel.app.design.NovelTheme
import org.joinmastodon.android.ui.utils.UiUtils

/** Local-first creation and management. Merely opening this Activity never uploads a manuscript. */
class NovelCreationActivity : ComponentActivity(), NavigationEventDispatcherOwner {
    private var controller: NovelEditorController? = null
    private var accountId: String? = null
    private var eventsRegistered = false
    override val navigationEventDispatcher = NavigationEventDispatcher { if (sessionActive()) controller?.leaveEditor(::finish) ?: finish() else closeExpiredSession() }
    override fun onCreate(savedInstanceState: Bundle?) {
        val account = intent.getStringExtra("account")
        val session = account?.let { AccountSessionManager.getInstance().tryGetAccount(it) }
        UiUtils.setUserPreferredTheme(this, session)
        super.onCreate(savedInstanceState)
        if (session == null || account == null) { closeExpiredSession(); return }
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return NovelEditorController(application, NovelEditorRepository(application, account, NovelAppApi(session))) as T
            }
        })[NovelEditorController::class.java]
        controller = model
        accountId = account
        if (!sessionActive()) { closeExpiredSession(); return }
        E.register(this); eventsRegistered = true
        if (model.state.value.page == "loading") model.open(intent.getStringExtra("local_work_id"), intent.getStringExtra("work_id"), intent.getStringExtra("mode") ?: "create")
        enableEdgeToEdge(SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT), SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        setContent {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides this) {
                NovelTheme(account) {
                    val state by model.state.collectAsState()
                    BackHandler { model.leaveEditor(::finish) }
                    NovelCreationScreen(state, model, { model.leaveEditor(::finish) }, ::finish) { work, chapter ->
                        startActivity(NovelReaderActivity.localIntent(this, account, work, chapter))
                    }
                }
            }
        }
    }
    override fun onResume() { super.onResume(); if (!sessionActive()) closeExpiredSession() }
    override fun onPause() {
        if (sessionActive()) controller?.backgroundSave() else closeExpiredSession()
        super.onPause()
    }
    @Subscribe fun onAccountLoggedOut(event: AccountLoggedOutEvent) {
        if (event.id == accountId) runOnUiThread { closeExpiredSession() }
    }
    private fun sessionActive(): Boolean = controller?.repository?.let { it.accountId == accountId && (it.api as? NovelAppApi)?.isSessionActive() == true } == true
    private fun closeExpiredSession() {
        // Do NOT invoke leave/backgroundSave: a logged-out frame must never be saved into a new login.
        controller?.viewModelScope?.cancel()
        controller = null
        viewModelStore.clear()
        findViewById<ViewGroup>(android.R.id.content)?.let { content ->
            for (i in 0 until content.childCount) (content.getChildAt(i) as? AbstractComposeView)?.disposeComposition()
            content.removeAllViews()
        }
        finish()
    }
    override fun onDestroy() {
        if (eventsRegistered) { E.unregister(this); eventsRegistered = false }
        super.onDestroy()
    }
    companion object {
        @JvmStatic @JvmOverloads
        fun intent(context: Context, accountId: String, workId: String? = null, localWorkId: String? = null, mode: String = "create") =
            Intent(context, NovelCreationActivity::class.java).putExtra("account", accountId).putExtra("work_id", workId).putExtra("local_work_id", localWorkId).putExtra("mode", mode)
    }
}
