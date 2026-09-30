/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/pin/PinScreen.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.pin

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PinScreen(
	message: String,
	errorMessage: String,
	digits: Int,
	enteredCount: Int,
	state: PinScreenState,
	isEnabled: Boolean,
	showLogo: Boolean = false,
	showBiometrics: Boolean = false,
	onDigit: (Int) -> Unit,
	onBackspace: () -> Unit,
	onBiometrics: () -> Unit = {},
	modifier: Modifier = Modifier,
	footer: @Composable () -> Unit = {},
) {
	if (state == PinScreenState.Loading) {
		Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			CircularProgressIndicator(
				modifier = Modifier
					.size(32.dp)
					.semantics { contentDescription = message },
				color = MiuixTheme.colorScheme.primary,
			)
		}
		return
	}

	val configuration = LocalConfiguration.current
	val shortHeight = configuration.screenWidthDp >= 600 &&
		(configuration.screenHeightDp < 560 || configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
	val contentModifier = modifier
		.fillMaxSize()
		.verticalScroll(rememberScrollState())
		.padding(horizontal = 20.dp, vertical = 12.dp)

	if (shortHeight) {
		Row(
			modifier = contentModifier,
			horizontalArrangement = Arrangement.spacedBy(20.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			PinHeaderAndInput(
				message = message,
				errorMessage = errorMessage,
				digits = digits,
				enteredCount = enteredCount,
				state = state,
				isEnabled = isEnabled,
				showLogo = showLogo,
				onBackspace = onBackspace,
				modifier = Modifier.weight(1f),
			)
			Column(
				modifier = Modifier.weight(1f).widthIn(max = 420.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
				PinKeyboard(
					isEnabled = isEnabled && state == PinScreenState.Default,
					showBiometrics = showBiometrics,
					onKeyClick = onDigit,
					onBiometricsClick = onBiometrics,
				)
				footer()
			}
		}
	} else {
		Column(
			modifier = contentModifier,
			verticalArrangement = Arrangement.Center,
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Spacer(Modifier.heightIn(min = 8.dp))
			PinHeaderAndInput(
				message = message,
				errorMessage = errorMessage,
				digits = digits,
				enteredCount = enteredCount,
				state = state,
				isEnabled = isEnabled,
				showLogo = showLogo,
				onBackspace = onBackspace,
				modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
			)
			PinKeyboard(
				isEnabled = isEnabled && state == PinScreenState.Default,
				showBiometrics = showBiometrics,
				onKeyClick = onDigit,
				onBiometricsClick = onBiometrics,
				modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
			)
			Box(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
				footer()
			}
			Spacer(Modifier.heightIn(min = 8.dp))
		}
	}
}

@Composable
private fun PinHeaderAndInput(
	message: String,
	errorMessage: String,
	digits: Int,
	enteredCount: Int,
	state: PinScreenState,
	isEnabled: Boolean,
	showLogo: Boolean,
	onBackspace: () -> Unit,
	modifier: Modifier,
) {
	Column(
		modifier = modifier,
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center,
	) {
		if (showLogo) {
			Image(
				painter = painterResource(R.drawable.ic_ntf_logo),
				contentDescription = null,
				contentScale = ContentScale.Fit,
				modifier = Modifier.padding(bottom = 10.dp).size(48.dp),
			)
		}
		Text(
			text = errorMessage.ifBlank { message },
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 8.dp, vertical = 14.dp)
				.semantics {
					if (errorMessage.isNotBlank()) liveRegion = LiveRegionMode.Assertive
				},
			textAlign = TextAlign.Center,
			fontSize = 17.sp,
			color = if (errorMessage.isNotBlank()) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
		)
		PinInput(
			digits = digits,
			enteredDigits = enteredCount,
			isVerifying = state == PinScreenState.Verifying,
			enabled = isEnabled && state == PinScreenState.Default,
			onBackspace = onBackspace,
			modifier = Modifier.fillMaxWidth(),
		)
	}
}
