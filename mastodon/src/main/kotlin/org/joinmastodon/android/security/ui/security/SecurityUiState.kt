/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/security/SecurityUiState.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.security

import androidx.annotation.StringRes
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.domain.PinTimeout
import org.joinmastodon.android.security.domain.PinTrials

data class SecurityUiState(
	val loading: Boolean = true,
	val lockMethod: LockMethod = LockMethod.NoLock,
	val pinTrials: PinTrials = PinTrials.Trials3,
	val pinTimeout: PinTimeout = PinTimeout.Timeout5,
	val pinDigits: PinDigits = PinDigits.Code4,
	val policyUpdating: Boolean = false,
	@StringRes val errorMessageRes: Int? = null,
) {
	val hasPin: Boolean
		get() = lockMethod != LockMethod.NoLock
}

sealed interface SecurityEffect {
	data object BiometricUnavailable : SecurityEffect
}
