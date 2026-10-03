/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/disablepin/DisablePinUiState.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.disablepin

import androidx.annotation.StringRes
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.ui.pin.PinScreenState

data class DisablePinUiState(
	val digits: PinDigits = PinDigits.Code4,
	val enteredCount: Int = 0,
	val loading: Boolean = true,
	val persisting: Boolean = false,
	val finished: Boolean = false,
	@StringRes val errorMessageRes: Int? = null,
	val blockedMinutes: Int = 0,
	val pinScreenState: PinScreenState = PinScreenState.Loading,
) {
	val inputEnabled: Boolean
		get() = !loading && !persisting && !finished
	val canCancel: Boolean
		get() = !persisting && !finished
}

sealed interface DisablePinEffect {
	data object InvalidPin : DisablePinEffect
	data object Finished : DisablePinEffect
}
