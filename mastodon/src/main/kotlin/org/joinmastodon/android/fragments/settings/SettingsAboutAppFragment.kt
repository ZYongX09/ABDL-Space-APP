package org.joinmastodon.android.fragments.settings

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import org.joinmastodon.android.ui.compose.AboutPage
import org.joinmastodon.android.ui.compose.AppState
import org.joinmastodon.android.ui.compose.LocalAppState
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import org.joinmastodon.android.ui.utils.UiUtils
import me.grishka.appkit.fragments.ToolbarFragment

class SettingsAboutAppFragment : ToolbarFragment() {
	private var composeView: ComposeView? = null

    override fun onCreateContentView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle?): View {
        val composeView = ComposeView(getActivity())
		this.composeView = composeView
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            CompositionLocalProvider(LocalAppState provides AppState()) {
                MiuixAppTheme {
                    AboutPage()
                }
            }
        }
        return composeView
    }

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		view.setOnApplyWindowInsetsListener { root, insets ->
			root.setPadding(
				insets.systemWindowInsetLeft,
				0,
				insets.systemWindowInsetRight,
				insets.systemWindowInsetBottom,
			)
			composeView?.dispatchApplyWindowInsets(
				insets.replaceSystemWindowInsets(0, insets.systemWindowInsetTop, 0, 0),
			)
			insets.consumeSystemWindowInsets()
		}
		view.requestApplyInsets()
	}

    override fun onStart() {
        super.onStart()
        hideAppKitToolbar()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideAppKitToolbar()
    }

    private fun hideAppKitToolbar() {
        getToolbar()?.visibility = View.GONE
    }

	override fun wantsLightStatusBar(): Boolean = !UiUtils.isDarkTheme()

	override fun onDestroyView() {
		composeView = null
		super.onDestroyView()
	}
}
