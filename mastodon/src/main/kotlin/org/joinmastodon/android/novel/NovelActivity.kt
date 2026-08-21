package org.joinmastodon.android.novel

import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.novel.author.AuthoringViewModel
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import org.joinmastodon.android.ui.utils.UiUtils

class NovelActivity : ComponentActivity(), NavigationEventDispatcherOwner {
	override val navigationEventDispatcher = NavigationEventDispatcher { finish() }

	override fun onCreate(savedInstanceState: Bundle?) {
		val externalDocument = if (savedInstanceState == null) externalDocument(intent) else null
		val accountID = if (externalDocument != null) AccountSessionManager.getInstance().lastActiveAccountID else intent.getStringExtra(EXTRA_ACCOUNT_ID)
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
		val authoringViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
			override fun <T : ViewModel> create(modelClass: Class<T>): T {
				@Suppress("UNCHECKED_CAST")
				return AuthoringViewModel(application, accountID) as T
			}
		})[AuthoringViewModel::class.java]
		val storeViewModel = ViewModelProvider(this)[NovelStoreViewModel::class.java]

		val darkTheme = UiUtils.isDarkTheme()
		enableEdgeToEdge(
			statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
			navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
		)
		setContent {
			CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides this) {
				MiuixAppTheme {
					NovelHomeScreen(accountId = accountID, libraryViewModel = libraryViewModel, authoringViewModel = authoringViewModel, storeViewModel = storeViewModel, externalDocument = externalDocument, onBack = ::finish)
				}
			}
		}
	}

	private fun externalDocument(intent: Intent): Uri? = when (intent.action) {
		Intent.ACTION_VIEW -> intent.data
		Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
		else -> null
	}

	companion object {
		const val EXTRA_ACCOUNT_ID = "account"
	}
}
