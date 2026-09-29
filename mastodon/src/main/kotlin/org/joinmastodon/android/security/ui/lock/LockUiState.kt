/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/lock/LockUiState.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.lock

import androidx.annotation.StringRes
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.domain.InvalidPinStatus
import org.joinmastodon.android.security.ui.pin.PinScreenState

data class LockUiState(
	val lockMethod: LockMethod = LockMethod.Pin,
	val digits: PinDigits = PinDigits.Code4,
	val invalidPinStatus: InvalidPinStatus = InvalidPinStatus.Default,
	@StringRes val errorMessageRes: Int? = null,
	val pinScreenState: PinScreenState = PinScreenState.Loading,
)
