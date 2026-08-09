package org.joinmastodon.android.novel

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import org.joinmastodon.android.ui.utils.UiUtils

class NovelActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		val accountID = intent.getStringExtra(EXTRA_ACCOUNT_ID)
		val session = accountID?.let {
			runCatching { AccountSessionManager.getInstance().getAccount(it) }.getOrNull()
		}
		UiUtils.setUserPreferredTheme(this, session)
		super.onCreate(savedInstanceState)
		if (session == null) {
			finish()
			return
		}
		val libraryViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
			override fun <T : ViewModel> create(modelClass: Class<T>): T {
				@Suppress("UNCHECKED_CAST")
				return NovelLibraryViewModel(application, accountID) as T
			}
		})[NovelLibraryViewModel::class.java]

		val darkTheme = UiUtils.isDarkTheme()
		enableEdgeToEdge(
			statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
			navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
		)
		setContent {
			MiuixAppTheme {
				NovelHomeScreen(accountId = accountID, libraryViewModel = libraryViewModel, onBack = ::finish)
			}
		}
	}

	companion object {
		const val EXTRA_ACCOUNT_ID = "account"
	}
}
