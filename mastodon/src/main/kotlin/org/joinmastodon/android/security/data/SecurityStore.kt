/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.data

sealed interface SecurityStoreResult<out T> {
	data class Success<T>(val value: T) : SecurityStoreResult<T>
	data object Missing : SecurityStoreResult<Nothing>
	data object Corrupted : SecurityStoreResult<Nothing>
	data object Unavailable : SecurityStoreResult<Nothing>
}

interface SecurityStore {
	suspend fun read(): SecurityStoreResult<StoredSecurityData>

	/** The transform and its resulting write are serialized as one store transaction. */
	suspend fun update(
		transform: (SecurityStoreResult<StoredSecurityData>) -> StoredSecurityData,
	): SecurityStoreResult<StoredSecurityData>
}
