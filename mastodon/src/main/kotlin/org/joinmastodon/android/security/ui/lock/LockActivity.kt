/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/lock/LockActivity.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.lock

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.joinmastodon.android.NotificationActionHandlerService
import org.joinmastodon.android.security.AppSecurity
import org.joinmastodon.android.security.ui.SecurityGraph
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import org.joinmastodon.android.ui.utils.UiUtils

class LockActivity : androidx.fragment.app.FragmentActivity() {
	private var finishedUnlock = false
	private var pendingNotificationAction: Intent? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		UiUtils.setUserPreferredTheme(this)
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
		val darkTheme = UiUtils.isDarkTheme()
		enableEdgeToEdge(
			statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
			navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
		)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
		val graph = SecurityGraph.create(this)
		val tracker = AppSecurity.authTrackerOrNull() ?: graph.authTracker
		tracker.onAuthenticateScreen()
		pendingNotificationAction = readNotificationAction(intent)
		val factory = object : ViewModelProvider.Factory {
			override fun <T : ViewModel> create(modelClass: Class<T>): T {
				@Suppress("UNCHECKED_CAST")
				return LockViewModel(graph.repository, tracker) as T
			}
		}
		val viewModel = ViewModelProvider(this, factory)[LockViewModel::class.java]
		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				AppSecurity.onLockFinished(false)
				setResult(Activity.RESULT_CANCELED)
				finishAffinity()
			}
		})
		setContent {
			MiuixAppTheme {
				LockScreen(viewModel, graph.biometricKeyProvider, ::finishWithSuccess)
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		pendingNotificationAction = readNotificationAction(intent)
	}

	private fun readNotificationAction(source: Intent): Intent? = try {
		IntentCompat.getParcelableExtra(source, "pending_notification_action", Intent::class.java)?.also { action ->
			if (action.component?.className != NotificationActionHandlerService::class.java.name) return null
			val input = RemoteInput.getResultsFromIntent(source)
			if (input != null) {
				RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder("replyText").build()), action, input)
			}
		}
	} catch (_: RuntimeException) {
		null
	}

	private fun finishWithSuccess() {
		if (finishedUnlock) return
		finishedUnlock = true
		AppSecurity.onLockFinished(true)
		pendingNotificationAction?.let { action ->
			pendingNotificationAction = null
			startService(action)
		}
		setResult(Activity.RESULT_OK)
		finish()
	}
}
