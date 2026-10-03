/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/setuppin/SetupPinUiState.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.setuppin

import androidx.annotation.StringRes
import org.joinmastodon.android.R
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.ui.pin.PinScreenState

enum class SetupPinStage {
	EnterFirst,
	TransitionToConfirm,
	EnterConfirm,
	Persisting,
	Finished,
}

data class SetupPinUiState(
	val digits: PinDigits = PinDigits.Code4,
	val enteredCount: Int = 0,
	val stage: SetupPinStage = SetupPinStage.EnterFirst,
	val showPinOptions: Boolean = true,
	@StringRes val messageRes: Int = R.string.security__enter_your_new_pin,
	@StringRes val errorMessageRes: Int? = null,
	val pinScreenState: PinScreenState = PinScreenState.Default,
) {
	val inputEnabled: Boolean
		get() = stage == SetupPinStage.EnterFirst || stage == SetupPinStage.EnterConfirm

	val canCancel: Boolean
		get() = stage != SetupPinStage.Persisting && stage != SetupPinStage.Finished
}

sealed interface SetupPinEffect {
	data object InvalidPin : SetupPinEffect
	data object Finished : SetupPinEffect
}
