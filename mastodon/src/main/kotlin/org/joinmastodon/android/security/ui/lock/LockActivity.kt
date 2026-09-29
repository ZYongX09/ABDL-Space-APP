/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/lock/LockActivity.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.lock

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.joinmastodon.android.security.ui.SecurityGraphFactory
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import org.joinmastodon.android.ui.utils.UiUtils

/**
 * App lock screen. Back always exits the whole task when the lock is mandatory (canGoBack=false is
 * the only mode used by the gatekeeper), matching the upstream behavior.
 */
class LockActivity : androidx.fragment.app.FragmentActivity() {
	private lateinit var authTracker: org.joinmastodon.android.security.AuthTracker
	private var pendingNotificationAction: Intent? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		UiUtils.setUserPreferredTheme(this)
		overridePendingTransition(0, 0)
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			window.setHideOverlayWindows(true)
		}
		val graph = SecurityGraphFactory.create(this)
		authTracker = org.joinmastodon.android.security.AppSecurity.authTrackerOrNull() ?: graph.authTracker
		authTracker.onAuthenticateScreen()
		pendingNotificationAction = intent.getParcelableExtra("pending_notification_action")
		val viewModel = LockViewModel(graph.repository, authTracker)
		setContent {
			val darkTheme = UiUtils.isDarkTheme()
			androidx.compose.runtime.DisposableEffect(darkTheme) {
				enableEdgeToEdge(
					statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
					navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
				)
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					window.isNavigationBarContrastEnforced = false
				}
				onDispose {}
			}
			MiuixAppTheme {
				LockScreen(
					viewModel = viewModel,
					biometricKeyProvider = graph.biometricKeyProvider,
					onSuccess = ::finishWithSuccess,
				)
			}
		}
	}

	override fun onResume() {
		super.onResume()
		authTracker.onAuthenticateScreen()
	}

	private fun finishWithSuccess() {
		authTracker.onAuthenticated()
		pendingNotificationAction?.let {
			it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			startService(it)
			pendingNotificationAction = null
		}
		setResult(Activity.RESULT_OK)
		finish()
	}

	@Deprecated("Deprecated in Java")
	override fun onBackPressed() {
		// A mandatory lock cannot be dismissed with back; closing the task is the only way out.
		setResult(Activity.RESULT_CANCELED)
		finishAffinity()
	}
}
