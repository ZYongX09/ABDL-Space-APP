package org.joinmastodon.android.ui.compose

import android.app.Dialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import org.joinmastodon.android.R
import org.joinmastodon.android.api.session.NBWForumOptionsRepository
import org.joinmastodon.android.model.NBWForumOptions
import org.joinmastodon.android.model.NBWSelection
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.menu.OverlayIconCascadingDropdownMenu

/** Local ComposeView bridge; Java editor/body/local validation stay untouched. */
class NBWClassificationController(
    private val context: Context,
    private val repository: NBWForumOptionsRepository,
    private val onChanged: Runnable,
) {
    private var snapshot by mutableStateOf(repository.snapshot)
    private var frozenOptions by mutableStateOf<NBWForumOptions?>(null)
    private var expanded by mutableStateOf(false)
    private var dialog: Dialog? = null
    private var composeView: ComposeView? = null
    private var subscription: NBWForumOptionsRepository.Cancelable? = null
    private var dispatcher: NavigationEventDispatcher? = null
    private var message by mutableStateOf("")

    fun updateSnapshot(value: NBWForumOptionsRepository.Snapshot) {
        snapshot = value
        if (!value.canWrite()) dismiss()
    }

    fun show() {
        if (dialog != null || !repository.snapshot.canWrite()) return
        val input = DirectNavigationEventInput()
        val navigation = NavigationEventDispatcher { dismiss() }.also { it.addInput(input) }
        dispatcher = navigation
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = navigation
        }
        // ComponentDialog installs Lifecycle/SavedState owners for this local ComposeView.
        val windowDialog = ComponentDialog(context)
        windowDialog.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { input.backCompleted() }
        })
        windowDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CompositionLocalProvider(
                    LocalAppState provides AppState(),
                    LocalNavigationEventDispatcherOwner provides owner,
                ) {
                    MiuixAppTheme {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        ) { padding ->
                            val options = if (expanded) frozenOptions else snapshot.options
                            val choice = snapshot.selection
                            val manualItems = options?.forums.orEmpty().mapNotNull { forum ->
                                val children = forum.types.filter { it.moderatorOnly == false }.map { type ->
                                    DropdownItem(
                                        text = type.name,
                                        selected = choice.isManual && choice.fid == forum.fid && choice.typeid == type.typeid,
                                        onClick = { select(NBWSelection.manual(forum.fid, type.typeid)) },
                                    )
                                }.toMutableList()
                                if (forum.required == false && children.isNotEmpty()) {
                                    children.add(0, DropdownItem(
                                        text = context.getString(R.string.nbw_v2_no_type),
                                        selected = choice.isManual && choice.fid == forum.fid && choice.typeid == 0,
                                        onClick = { select(NBWSelection.manual(forum.fid, 0)) },
                                    ))
                                }
                                when {
                                    children.isNotEmpty() -> DropdownItem(text = forum.name, children = children)
                                    forum.required == false -> DropdownItem(
                                        text = forum.name,
                                        selected = choice.isManual && choice.fid == forum.fid && choice.typeid == 0,
                                        onClick = { select(NBWSelection.manual(forum.fid, 0)) },
                                    )
                                    else -> null
                                }
                            }
                            val entries = listOf(DropdownEntry(items = listOf(
                                DropdownItem(
                                    text = context.getString(R.string.nbw_forum_ai),
                                    selected = !choice.isManual,
                                    onClick = { select(NBWSelection.ai()) },
                                ),
                            ) + manualItems))
                            Column(
                                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Text(context.getString(R.string.nbw_v2_classification_title))
                                Text(context.getString(R.string.nbw_v2_publish_explanation))
                                OverlayIconCascadingDropdownMenu(
                                    entries = entries,
                                    collapseOnSelection = true,
                                    onExpandedChange = { open ->
                                        if (open) frozenOptions = snapshot.options
                                        expanded = open
                                        if (!open) frozenOptions = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(selectionSummary(context, snapshot))
                                }
                                if (snapshot.options == null) Text(context.getString(R.string.nbw_v2_options_unavailable))
                                Text(context.getString(R.string.nbw_v2_fallback_explanation))
                                Text(context.getString(if (snapshot.hasFallbackConsent()) R.string.nbw_v2_fallback_accepted else R.string.nbw_v2_fallback_not_accepted))
                                Button(
                                    onClick = { consent(true) },
                                    enabled = !snapshot.consentSaving && !snapshot.hasFallbackConsent(),
                                ) { Text(context.getString(R.string.nbw_v2_fallback_accept)) }
                                Button(onClick = { consent(false) }, enabled = !snapshot.consentSaving) {
                                    Text(context.getString(R.string.nbw_v2_fallback_decline))
                                }
                                if (message.isNotEmpty()) Text(message)
                                Button(onClick = { dismiss() }) { Text(context.getString(R.string.done)) }
                            }
                        }
                    }
                }
            }
        }
        composeView = view
        windowDialog.setContentView(view)
        windowDialog.setOnDismissListener { disposeWindow() }
        dialog = windowDialog
        subscription = repository.observe { value -> updateSnapshot(value) }
        windowDialog.show()
        windowDialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun select(value: NBWSelection) {
        // Validate against latest snapshot, even when the expanded menu is displaying an old tree.
        if (repository.select(value)) {
            message = ""
            onChanged.run()
        } else message = context.getString(R.string.nbw_v2_selection_expired)
    }

    private fun consent(accepted: Boolean) {
        repository.setFallbackConsent(accepted) { ok ->
            viewPost {
                message = context.getString(if (ok) R.string.nbw_v2_consent_saved else R.string.nbw_v2_consent_not_saved)
            }
        }
    }

    private fun viewPost(action: () -> Unit) { composeView?.post { action() } }
    fun dismiss() { dialog?.dismiss() }
    private fun disposeWindow() {
        subscription?.cancel(); subscription = null
        composeView?.disposeComposition(); composeView = null
        dispatcher?.dispose(); dispatcher = null
        dialog = null; expanded = false; frozenOptions = null
    }
    fun dispose() { dismiss(); disposeWindow() }

    companion object {
        @JvmStatic
        fun selectionSummary(context: Context, snapshot: NBWForumOptionsRepository.Snapshot): String {
            val selection = snapshot.selection
            if (!selection.isManual) return context.getString(R.string.nbw_forum_ai)
            return if (selection.isLegal(snapshot.options)) snapshot.options?.label(selection.fid, selection.typeid).orEmpty()
            else context.getString(R.string.nbw_v2_selection_expired)
        }
    }
}
