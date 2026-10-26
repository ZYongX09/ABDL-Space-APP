package org.joinmastodon.android.ui.compose

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
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
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.overlay.OverlayCascadingListPopup
import java.util.function.Consumer

/** Flat, anchored single-choice popup for the native editor. It never reads location or requests permission. */
class ComposeLocationController(
    private val context: Context,
    private val onSelected: Consumer<Int>,
    private val onDismissed: Runnable,
) {
    data class Choice(val level: Int, val title: String, val summary: String, val enabled: Boolean, val selected: Boolean, val icon: Int)

    private var expanded by mutableStateOf(false)
    private var anchorBounds by mutableStateOf(IntRect.Zero)
    private var composeView: ComposeView? = null
    private var viewOwner: MenuViewOwner? = null
    private var dispatcher: NavigationEventDispatcher? = null
    private var navigationInput: DirectNavigationEventInput? = null
    private var menuAnchor: View? = null
    private var anchorLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { dispose() }
    }

    fun showMenu(anchor: View, choices: List<Choice>): Boolean {
        if (composeView != null || !anchor.isAttachedToWindow || !anchor.isShown || anchor.width <= 0 || anchor.height <= 0) return false
        val host = (anchor.rootView.findViewById<View>(android.R.id.content) as? ViewGroup)
            ?: (anchor.rootView as? ViewGroup) ?: return false
        val frozenChoices = choices.toList()
        val input = DirectNavigationEventInput()
        val navigation = NavigationEventDispatcher { expanded = false }.also { it.addInput(input) }
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
            elevation = 32 * resources.displayMetrics.density
            setContent {
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navigationOwner) {
                    MiuixAppTheme {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            containerColor = Color.Transparent,
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        ) {
                            val density = androidx.compose.ui.platform.LocalDensity.current
                            val bounds = anchorBounds
                            Box(Modifier.fillMaxSize()) {
                                Box(
                                    Modifier.absoluteOffset { IntOffset(bounds.left, bounds.top) }
                                        .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() }),
                                ) {
                                    OverlayCascadingListPopup(
                                        show = expanded,
                                        entries = listOf(DropdownEntry(items = frozenChoices.map { choice ->
                                            DropdownItem(
                                                text = choice.title,
                                                summary = choice.summary.ifBlank { null },
                                                selected = choice.selected,
                                                enabled = choice.enabled,
                                                onClick = { if (choice.enabled) {
                                                    dispose()
                                                    onSelected.accept(choice.level)
                                                } },
                                            )
                                        })),
                                        alignment = PopupPositionProvider.Align.Start,
                                        onDismissRequest = { expanded = false },
                                        onDismissFinished = { installedView.post { if (composeView === installedView) dispose() } },
                                        collapseOnSelection = false,
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
            if (!anchor.isAttachedToWindow || !anchor.isShown) { dispose(); return }
            val position = IntArray(2).also(anchor::getLocationInWindow)
            val origin = IntArray(2).also(view::getLocationInWindow)
            val left = position[0] - origin[0]
            val top = position[1] - origin[1]
            anchorBounds = IntRect(left, top, left + anchor.width, top + anchor.height)
        }
        anchorLayoutListener = ViewTreeObserver.OnGlobalLayoutListener { updateBounds() }.also {
            anchor.viewTreeObserver.addOnGlobalLayoutListener(it)
        }
        view.addOnAttachStateChangeListener(detachListener)
        host.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateBounds()
        owner.resume()
        return true
    }

    fun onBackPressed(): Boolean {
        if (composeView == null) return false
        navigationInput?.backCompleted()
        return true
    }

    fun dispose() {
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
        onDismissed.run()
    }

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
}
