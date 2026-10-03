/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.domain

data class SecurityState(
	val lockMethod: LockMethod,
	val pinOptions: PinOptions,
	val invalidPinStatus: InvalidPinStatus,
) {
	val isEnabled: Boolean
		get() = lockMethod != LockMethod.NoLock
}
