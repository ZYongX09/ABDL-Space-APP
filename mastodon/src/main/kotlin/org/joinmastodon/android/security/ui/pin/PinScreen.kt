/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/pin/PinScreen.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.pin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Scaffold

/** A PIN route paints beneath the bars; only its content consumes the safe drawing/IME insets. */
@Composable
internal fun PinScaffold(
	topBar: @Composable () -> Unit = {},
	content: @Composable () -> Unit,
) {
	val palette = pinPalette()
	PinWindowAppearance(palette.background)
	Scaffold(
		containerColor = palette.background,
		topBar = topBar,
		contentWindowInsets = WindowInsets(0, 0, 0, 0),
	) { padding ->
		Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
	}
}

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
	contentInsets: WindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime),
) {
	val palette = pinPalette()
	val configuration = LocalConfiguration.current
	BoxWithConstraints(modifier.fillMaxSize().background(palette.background).windowInsetsPadding(contentInsets)) {
		val wide = maxWidth >= 600.dp && maxHeight < 560.dp
		val compact = maxHeight < 650.dp || configuration.fontScale > 1.3f
		val header: @Composable () -> Unit = {
			Column(horizontalAlignment = Alignment.CenterHorizontally,
				modifier = Modifier.fillMaxWidth().padding(top = if (compact) 12.dp else 48.dp)) {
				Box(Modifier.size(56.dp).background(palette.tile, RoundedCornerShape(18.dp)),
					contentAlignment = Alignment.Center) {
					Icon(painterResource(R.drawable.ic_security_lock_shield),
						contentDescription = null, tint = palette.accent, modifier = Modifier.size(28.dp))
				}
				Spacer(Modifier.height(if (compact) 16.dp else 26.dp))
				if (showLogo) {
					Text(stringResource(R.string.security_pin_welcome), fontSize = 30.sp,
						fontWeight = FontWeight.Medium, color = palette.foreground, textAlign = TextAlign.Center)
					Spacer(Modifier.height(10.dp))
				}
				Text(message, fontSize = if (showLogo) 14.sp else 22.sp,
					fontWeight = if (showLogo) FontWeight.Normal else FontWeight.Medium,
					color = if (showLogo) palette.secondary else palette.foreground,
					textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
				Spacer(Modifier.height(if (compact) 24.dp else 40.dp))
				PinInput(digits, enteredCount)
				Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 12.dp),
					contentAlignment = Alignment.Center) {
					if (state == PinScreenState.Loading || state == PinScreenState.Verifying) {
						CircularProgressIndicator(color = palette.accent, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
					} else if (errorMessage.isNotBlank()) {
						Text(errorMessage, color = palette.error, fontSize = 14.sp,
							textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
					}
				}
			}
		}
		val keyboard: @Composable () -> Unit = {
			Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
				PinKeyboard(isEnabled = isEnabled && state == PinScreenState.Default,
					showBiometrics = showBiometrics, onKeyClick = onDigit,
					onBiometricsClick = onBiometrics, onBackspace = onBackspace, canBackspace = enteredCount > 0)
				Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(top = 12.dp),
					contentAlignment = Alignment.Center) { footer() }
			}
		}
		if (wide) {
			Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
				verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
				Box(Modifier.weight(1f)) { header() }
				Box(Modifier.weight(1f)) { keyboard() }
			}
		} else {
			Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
				.heightIn(min = maxHeight).padding(horizontal = 32.dp, vertical = 12.dp),
				verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
				header()
				Spacer(Modifier.height(if (compact) 12.dp else 32.dp))
				keyboard()
			}
		}
	}
}
