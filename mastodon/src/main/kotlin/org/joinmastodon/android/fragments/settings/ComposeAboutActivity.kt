package org.joinmastodon.android.fragments.settings

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import org.joinmastodon.android.MainActivity
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.ui.compose.AboutPage
import org.joinmastodon.android.ui.compose.AppState
import org.joinmastodon.android.ui.compose.LocalAppState
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import org.joinmastodon.android.ui.utils.UiUtils

class ComposeAboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
		val accountID = intent.getStringExtra(EXTRA_ACCOUNT_ID)
		val session = accountID?.let {
			runCatching { AccountSessionManager.getInstance().getAccount(it) }.getOrNull()
		}
		UiUtils.setUserPreferredTheme(this, session)
        super.onCreate(savedInstanceState)
		setContent {
			val darkTheme = UiUtils.isDarkTheme()
			DisposableEffect(darkTheme) {
				enableEdgeToEdge(
					statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
					navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
				)
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					window.isNavigationBarContrastEnforced = false
				}
				onDispose {}
			}
			CompositionLocalProvider(LocalAppState provides AppState()) {
				MiuixAppTheme {
					AboutPage(onOpenSourceLicenses = ::openSourceLicenses)
				}
			}
		}
    }

	private fun openSourceLicenses() {
		val accountID = intent.getStringExtra(EXTRA_ACCOUNT_ID)
		startActivity(Intent(this, MainActivity::class.java).apply {
			addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
			putExtra(MainActivity.EXTRA_OPEN_SOURCE_LICENSES, true)
			accountID?.let { putExtra(EXTRA_ACCOUNT_ID, it) }
		})
		finish()
	}

	companion object {
		const val EXTRA_ACCOUNT_ID = "account"
	}
}
