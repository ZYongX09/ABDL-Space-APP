/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/navigation/SecurityNavigation.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.joinmastodon.android.security.ui.changepin.ChangePinScreen
import org.joinmastodon.android.security.ui.changepin.ChangePinViewModel
import org.joinmastodon.android.security.ui.disablepin.DisablePinScreen
import org.joinmastodon.android.security.ui.disablepin.DisablePinViewModel
import org.joinmastodon.android.security.ui.security.SecurityScreen
import org.joinmastodon.android.security.ui.security.SecurityViewModel
import org.joinmastodon.android.security.ui.setuppin.SetupPinScreen
import org.joinmastodon.android.security.ui.setuppin.SetupPinViewModel
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import org.joinmastodon.android.ui.utils.UiUtils

/** Compiles as the future host but is intentionally not registered in AndroidManifest this round. */
class SecurityActivity : FragmentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		UiUtils.setUserPreferredTheme(this)
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			window.setHideOverlayWindows(true)
		}
		val graph = SecurityGraph.create(this)
		val factory = SecurityViewModelFactory(graph)
		val securityViewModel = ViewModelProvider(this, factory)[SecurityViewModel::class.java]
		val setupPinViewModel = ViewModelProvider(this, factory)[SetupPinViewModel::class.java]
		val changePinViewModel = ViewModelProvider(this, factory)[ChangePinViewModel::class.java]
		val disablePinViewModel = ViewModelProvider(this, factory)[DisablePinViewModel::class.java]
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
			MiuixAppTheme {
				SecurityHost(
					securityViewModel = securityViewModel,
					setupPinViewModel = setupPinViewModel,
					changePinViewModel = changePinViewModel,
					disablePinViewModel = disablePinViewModel,
					biometricKeyProvider = graph.biometricKeyProvider,
					onFinish = ::finish,
				)
			}
		}
	}
}

private enum class SecurityRoute {
	Security,
	SetupPin,
	ChangePin,
	DisablePin,
}

@Composable
private fun SecurityHost(
	securityViewModel: SecurityViewModel,
	setupPinViewModel: SetupPinViewModel,
	changePinViewModel: ChangePinViewModel,
	disablePinViewModel: DisablePinViewModel,
	biometricKeyProvider: org.joinmastodon.android.security.data.BiometricKeyProvider,
	onFinish: () -> Unit,
) {
	var route by rememberSaveable { mutableStateOf(SecurityRoute.Security.name) }
	val currentRoute = SecurityRoute.valueOf(route)
	val backToSecurity = {
		route = SecurityRoute.Security.name
		securityViewModel.refresh()
	}
	val openRoute = { next: SecurityRoute ->
		route = next.name
		when (next) {
			SecurityRoute.SetupPin -> setupPinViewModel.beginFlow()
			SecurityRoute.ChangePin -> changePinViewModel.beginFlow()
			SecurityRoute.DisablePin -> disablePinViewModel.beginFlow()
			SecurityRoute.Security -> Unit
		}
	}
	androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
			when (currentRoute) {
			SecurityRoute.Security -> {
				LaunchedEffect(securityViewModel) { securityViewModel.refresh() }
				SecurityScreen(
					viewModel = securityViewModel,
					biometricKeyProvider = biometricKeyProvider,
					onBack = onFinish,
					openSetupPin = { openRoute(SecurityRoute.SetupPin) },
					openChangePin = { openRoute(SecurityRoute.ChangePin) },
					openDisablePin = { openRoute(SecurityRoute.DisablePin) },
				)
			}
			SecurityRoute.SetupPin -> {
				LaunchedEffect(setupPinViewModel) { setupPinViewModel.refresh() }
				SetupPinScreen(
					viewModel = setupPinViewModel,
					onBack = backToSecurity,
					onFinished = backToSecurity,
				)
			}
			SecurityRoute.ChangePin -> {
				LaunchedEffect(changePinViewModel) { changePinViewModel.refresh() }
				ChangePinScreen(
					viewModel = changePinViewModel,
					onBack = backToSecurity,
					onFinished = backToSecurity,
				)
			}
			SecurityRoute.DisablePin -> {
				LaunchedEffect(disablePinViewModel) { disablePinViewModel.refresh() }
				DisablePinScreen(
					viewModel = disablePinViewModel,
					onBack = backToSecurity,
					onFinished = backToSecurity,
				)
			}
		}
	}
}

private class SecurityViewModelFactory(
	private val graph: SecurityGraph,
) : ViewModelProvider.Factory {
	override fun <T : ViewModel> create(modelClass: Class<T>): T {
		val viewModel: ViewModel = when (modelClass) {
			SecurityViewModel::class.java -> SecurityViewModel(graph.repository)
			SetupPinViewModel::class.java -> SetupPinViewModel(graph.repository)
			ChangePinViewModel::class.java -> ChangePinViewModel(graph.repository)
			DisablePinViewModel::class.java -> DisablePinViewModel(graph.repository)
			else -> throw IllegalArgumentException("Unknown security ViewModel: ${modelClass.name}")
		}
		@Suppress("UNCHECKED_CAST")
		return viewModel as T
	}
}
