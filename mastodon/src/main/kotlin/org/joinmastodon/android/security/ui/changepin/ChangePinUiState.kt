/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/changepin/ChangePinUiState.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.changepin

import androidx.annotation.StringRes
import org.joinmastodon.android.R
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.ui.pin.PinScreenState

enum class ChangePinStage {
	Loading,
	EnterCurrent,
	TransitionToNew,
	EnterNew,
	TransitionToConfirm,
	EnterConfirm,
	Persisting,
	Finished,
}

data class ChangePinUiState(
	val digits: PinDigits = PinDigits.Code4,
	val newDigits: PinDigits = PinDigits.Code4,
	val enteredCount: Int = 0,
	val stage: ChangePinStage = ChangePinStage.Loading,
	val showPinOptions: Boolean = false,
	val blockedMinutes: Int = 0,
	@StringRes val messageRes: Int = R.string.security__enter_current_pin,
	@StringRes val errorMessageRes: Int? = null,
	val messageIncludesDigits: Boolean = false,
	val pinScreenState: PinScreenState = PinScreenState.Loading,
) {
	val inputEnabled: Boolean
		get() = blockedMinutes == 0 && (
			stage == ChangePinStage.EnterCurrent ||
			stage == ChangePinStage.EnterNew ||
			stage == ChangePinStage.EnterConfirm
		)

	val canCancel: Boolean
		get() = stage != ChangePinStage.Persisting && stage != ChangePinStage.Finished
}

sealed interface ChangePinEffect {
	data object InvalidPin : ChangePinEffect
	data object Finished : ChangePinEffect
}
