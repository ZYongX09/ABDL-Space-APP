package org.joinmastodon.android.ui.compose

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.joinmastodon.android.R
import org.joinmastodon.android.api.session.NBWForumOptionsRepository
import org.joinmastodon.android.model.NBWForumPolicy
import org.joinmastodon.android.model.NBWSelection
import org.joinmastodon.android.ui.sheets.NBWFallbackConsentSheet
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.overlay.OverlayCascadingListPopup

/** A local overlay bridge, not a second editor or a classification/settings dialog. */
class NBWClassificationController @JvmOverloads constructor(
    private val context: Context,
    private val repository: NBWForumOptionsRepository,
    private val onChanged: Runnable,
    private val onMenuDismissed: Runnable = Runnable {},
) {
    private var expanded by mutableStateOf(false)
    private var anchorBounds by mutableStateOf(IntRect.Zero)
    private var composeView: ComposeView? = null
    private var viewOwner: MenuViewOwner? = null
    private var subscription: NBWForumOptionsRepository.Cancelable? = null
    private var dispatcher: NavigationEventDispatcher? = null
    private var navigationInput: DirectNavigationEventInput? = null
    private var menuGeneration = -1L
    private var fallbackGeneration = -1L
    private var fallbackSheet: NBWFallbackConsentSheet? = null
    private var menuAnchor: View? = null
    private var anchorLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { disposeMenu() }
    }

    fun updateSnapshot(value: NBWForumOptionsRepository.Snapshot) {
        if (composeView != null && (!value.canWrite() || value.generation != menuGeneration)) disposeMenu()
        val sheet = fallbackSheet ?: return
        if (!value.canWrite() || value.generation != fallbackGeneration) sheet.dismissWithoutAnimation()
        else sheet.updateSnapshot(value)
    }

    /** One native editor click immediately opens the same two-level miuix popup as Home. */
    fun showMenu(anchor: View) {
        if (composeView != null || !anchor.isAttachedToWindow || !anchor.isShown || anchor.width <= 0 || anchor.height <= 0) return
        val snapshot = repository.snapshot
        if (!snapshot.canWrite()) return
        val host = (anchor.rootView.findViewById<View>(android.R.id.content) as? ViewGroup)
            ?: (anchor.rootView as? ViewGroup) ?: return
        fallbackSheet?.dismissWithoutAnimation()
        menuGeneration = snapshot.generation
        // Capture actual rows, names, order and selection once, not just a mutable model reference.
        // Catalog refreshes must never move a submenu under the user's finger.
        val frozenEntries = menuEntries(snapshot, menuGeneration)
        val input = DirectNavigationEventInput()
        val navigation = NavigationEventDispatcher { requestMenuDismiss() }.also { it.addInput(input) }
        dispatcher = navigation
        navigationInput = input
        val navigationOwner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = navigation
        }
        val owner = MenuViewOwner()
        viewOwner = owner
        expanded = true
        val view = ComposeView(context).apply {
            val installedView = this
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            // Stay above native editor/toolbar elevation, without creating another window.
            elevation = 32 * resources.displayMetrics.density
            setContent {
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navigationOwner) {
                    MiuixAppTheme {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            containerColor = Color.Transparent,
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        ) {
                            val density = LocalDensity.current
                            val bounds = anchorBounds
                            Box(Modifier.fillMaxSize()) {
                                Box(
                                    Modifier.absoluteOffset { IntOffset(bounds.left, bounds.top) }
                                        .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() }),
                                ) {
                                    OverlayCascadingListPopup(
                                        show = expanded,
                                        entries = frozenEntries,
                                        alignment = PopupPositionProvider.Align.Start,
                                        onDismissRequest = { requestMenuDismiss() },
                                        onDismissFinished = { installedView.post { if (composeView === installedView) disposeMenu() } },
                                        collapseOnSelection = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        composeView = view
        menuAnchor = anchor
        fun updateBounds() {
            if (!anchor.isAttachedToWindow || !anchor.isShown) { disposeMenu(); return }
            val anchorPosition = IntArray(2).also(anchor::getLocationInWindow)
            val hostPosition = IntArray(2).also(view::getLocationInWindow)
            val left = anchorPosition[0] - hostPosition[0]
            val top = anchorPosition[1] - hostPosition[1]
            anchorBounds = IntRect(left, top, left + anchor.width, top + anchor.height)
        }
        anchorLayoutListener = ViewTreeObserver.OnGlobalLayoutListener { updateBounds() }.also {
            anchor.viewTreeObserver.addOnGlobalLayoutListener(it)
        }
        view.addOnAttachStateChangeListener(detachListener)
        host.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateBounds()
        owner.resume()
        observe()
    }

    /** AppKit's native fragment back callback feeds miuix's submenu-first back handler. */
    fun onBackPressed(): Boolean {
        if (composeView == null) return false
        navigationInput?.backCompleted()
        return true
    }

    /** Account-default consent is independent of classification and never opens a menu. */
    fun showFallbackSettings() {
        if (fallbackSheet != null) return
        val snapshot = repository.snapshot
        if (!snapshot.canWrite()) return
        disposeMenu()
        fallbackGeneration = snapshot.generation
        val sheet = NBWFallbackConsentSheet(context) { accepted -> consent(accepted) }
        fallbackSheet = sheet
        sheet.updateSnapshot(snapshot)
        sheet.setOnDismissListener {
            if (fallbackSheet === sheet) {
                fallbackSheet = null
                fallbackGeneration = -1L
                stopObservingIfIdle()
            }
        }
        sheet.show()
        observe()
    }

    private fun menuEntries(snapshot: NBWForumOptionsRepository.Snapshot, generation: Long): List<DropdownEntry> {
        val choice = snapshot.selection
        val items = mutableListOf(DropdownItem(
            text = context.getString(R.string.nbw_forum_ai),
            selected = !choice.isManual,
            onClick = { select(NBWSelection.ai(), generation) },
        ))
        NBWForumPolicy.FORUM_IDS.mapNotNull { snapshot.options?.findForum(it) }.forEach { forum ->
            val children = mutableListOf<DropdownItem>()
            if (forum.required == false) {
                val selection = NBWSelection.manual(forum.fid, 0)
                children += DropdownItem(
                    text = context.getString(R.string.nbw_v2_no_type),
                    selected = choice == selection,
                    onClick = { select(selection, generation) },
                )
            }
            forum.types.filter { it.typeid > 0 && it.moderatorOnly == false }.forEach { type ->
                // Copy IDs before constructing callbacks; even mutable metadata cannot rewrite intent.
                val selection = NBWSelection.manual(forum.fid, type.typeid)
                children += DropdownItem(
                    text = type.name,
                    selected = choice == selection,
                    onClick = { select(selection, generation) },
                )
            }
            // Required forums with no ordinary-user type have no legal action and remain hidden.
            if (children.isNotEmpty()) items += DropdownItem(text = forum.name, children = children.toList())
        }
        return listOf(DropdownEntry(items = items.toList()))
    }

    private fun select(value: NBWSelection, generation: Long) {
        val latest = repository.snapshot
        // Revalidate against current authenticated options, never the frozen display tree.
        if (latest.canWrite() && latest.generation == generation && NBWForumPolicy.isLegal(value, latest.options) && repository.select(value)) onChanged.run()
        else Toast.makeText(context, R.string.nbw_v2_selection_expired, Toast.LENGTH_LONG).show()
    }

    private fun consent(accepted: Boolean) {
        val sheet = fallbackSheet ?: return
        val generation = fallbackGeneration
        repository.setFallbackConsent(accepted) { ok ->
            // Repository owns cancellation/generation. A dismissed sheet must not receive a late message.
            if (fallbackSheet === sheet && repository.snapshot.generation == generation) {
                sheet.showResult(if (ok) R.string.nbw_v2_consent_saved else R.string.nbw_v2_consent_not_saved)
            }
        }
    }

    private fun observe() {
        if (subscription == null) subscription = repository.observe(::updateSnapshot)
    }

    private fun stopObservingIfIdle() {
        if (composeView == null && fallbackSheet == null) {
            subscription?.cancel()
            subscription = null
        }
    }

    private fun requestMenuDismiss() { expanded = false }

    fun dismiss() {
        disposeMenu()
        fallbackSheet?.dismissWithoutAnimation()
    }

    private fun disposeMenu() {
        val view = composeView ?: return
        composeView = null
        menuAnchor?.let { anchor ->
            anchorLayoutListener?.let { if (anchor.viewTreeObserver.isAlive) anchor.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        }
        menuAnchor = null
        anchorLayoutListener = null
        view.removeOnAttachStateChangeListener(detachListener)
        view.disposeComposition()
        (view.parent as? ViewGroup)?.removeView(view)
        viewOwner?.destroy()
        viewOwner = null
        dispatcher?.dispose()
        dispatcher = null
        navigationInput = null
        expanded = false
        anchorBounds = IntRect.Zero
        menuGeneration = -1L
        stopObservingIfIdle()
        onMenuDismissed.run()
    }

    fun dispose() { dismiss(); stopObservingIfIdle() }

    /** Scoped owners also support native AppKit activities, without altering their lifecycle. */
    private class MenuViewOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        init {
            savedState.performAttach()
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }
        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    companion object {
        @JvmStatic
        fun selectionSummary(context: Context, snapshot: NBWForumOptionsRepository.Snapshot): String {
            val selection = snapshot.selection
            if (!selection.isManual) return context.getString(R.string.nbw_forum_ai)
            return if (NBWForumPolicy.isLegal(selection, snapshot.options)) {
                val label = snapshot.options?.label(selection.fid, selection.typeid).orEmpty()
                if (selection.typeid == 0) "$label / ${context.getString(R.string.nbw_v2_no_type)}" else label
            } else context.getString(R.string.nbw_v2_selection_expired)
        }
    }
}
