/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Semantics adapted from:
 * https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/data/session/src/main/java/com/twofasapp/data/session/SecurityRepositoryImpl.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.joinmastodon.android.security.domain.BootSessionMarkerProvider
import org.joinmastodon.android.security.domain.ElapsedRealtimeProvider
import org.joinmastodon.android.security.domain.InvalidPinStatus
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.domain.PinCipher
import org.joinmastodon.android.security.domain.PinCipherException
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.domain.PinOptions
import org.joinmastodon.android.security.domain.PinTimeout
import org.joinmastodon.android.security.domain.PinTrials
import org.joinmastodon.android.security.domain.PinVerificationResult
import org.joinmastodon.android.security.domain.SecurityState
import org.joinmastodon.android.security.domain.StoreErrorReason

class SecurityRepositoryImpl(
	private val store: SecurityStore,
	private val pinCipher: PinCipher,
	private val elapsedRealtimeProvider: ElapsedRealtimeProvider,
	private val bootSessionMarkerProvider: BootSessionMarkerProvider,
	private val biometricKeyCleanup: () -> Unit = {},
	private val operationMutex: Mutex = Mutex(),
) : SecurityRepository {
	private val pinVerifier = PinVerifier(pinCipher)

	override suspend fun getSecurityState(): SecurityResult<SecurityState> = operationMutex.withLock {
		when (val result = normalizeLockoutLocked()) {
			is NormalizedStore.Success -> SecurityResult.Success(result.data.toSecurityState(result.status))
			NormalizedStore.Corrupted -> SecurityResult.Corrupted
			NormalizedStore.Unavailable -> SecurityResult.StoreError
		}
	}

	override suspend fun verifyPin(pin: CharArray): PinVerificationResult {
		return try {
			if (!pin.isPotentialPinInput()) return PinVerificationResult.InvalidInput
			operationMutex.withLock { verifyPinLocked(pin) }
		} finally {
			pin.clearSensitive()
		}
	}

	override suspend fun setupPin(
		pin: CharArray,
		digits: PinDigits,
		lockMethod: LockMethod,
	): SecurityResult<SecurityState> {
		return try {
			if (lockMethod == LockMethod.NoLock || !pin.isValidPin(digits.value)) {
				return SecurityResult.InvalidInput
			}
			operationMutex.withLock { setupPinLocked(pin, digits, lockMethod) }
		} finally {
			pin.clearSensitive()
		}
	}

	override suspend fun changePin(
		currentPin: CharArray,
		newPin: CharArray,
		digits: PinDigits,
		lockMethod: LockMethod?,
	): PinProtectedMutationResult {
		return try {
			if (
				!currentPin.isPotentialPinInput() ||
				!newPin.isValidPin(digits.value) ||
				lockMethod == LockMethod.NoLock
			) {
				return PinProtectedMutationResult.InvalidInput
			}
			operationMutex.withLock {
				changePinLocked(currentPin, newPin, digits, lockMethod)
			}
		} finally {
			currentPin.clearSensitive()
			newPin.clearSensitive()
		}
	}

	override suspend fun disablePin(currentPin: CharArray): PinProtectedMutationResult {
		return try {
			if (!currentPin.isPotentialPinInput()) return PinProtectedMutationResult.InvalidInput
			operationMutex.withLock { disablePinLocked(currentPin) }
		} finally {
			currentPin.clearSensitive()
		}
	}

	override suspend fun enableBiometrics(): SecurityResult<SecurityState> = operationMutex.withLock {
		try {
			when (val updated = store.update { current ->
				val existing = current.requireLocked()
				if (existing.lockStatus != LockMethodEntity.PIN_SECURED &&
					existing.lockStatus != LockMethodEntity.FINGERPRINT_WITH_PIN_SECURED) {
					throw InvalidSetupStateException()
				}
				existing.copy(lockStatus = LockMethodEntity.FINGERPRINT_WITH_PIN_SECURED)
			}) {
				is SecurityStoreResult.Success -> SecurityResult.Success(
					updated.value.toSecurityState(InvalidPinStatus.Default),
				)
				SecurityStoreResult.Corrupted,
				SecurityStoreResult.Missing,
				-> SecurityResult.Corrupted
				SecurityStoreResult.Unavailable -> SecurityResult.StoreError
			}
		} catch (error: CancellationException) {
			throw error
		} catch (_: InvalidSetupStateException) {
			SecurityResult.InvalidState
		} catch (_: CorruptedSecurityStoreException) {
			SecurityResult.Corrupted
		} catch (_: UnavailableSecurityStoreException) {
			SecurityResult.StoreError
		} catch (_: Exception) {
			SecurityResult.StoreError
		}
	}

	override suspend fun demoteToPinAfterBiometricInvalidation(): SecurityResult<SecurityState> =
		operationMutex.withLock {
			try {
				when (val updated = store.update { current ->
					val existing = current.requireLocked()
					if (existing.lockStatus != LockMethodEntity.FINGERPRINT_WITH_PIN_SECURED) {
						throw InvalidSetupStateException()
					}
					existing.copy(
						lockStatus = LockMethodEntity.PIN_SECURED,
						invalidPinStatus = InvalidPinStatusEntity(),
					)
				}) {
					is SecurityStoreResult.Success -> SecurityResult.Success(
						updated.value.toSecurityState(InvalidPinStatus.Default),
					)
					SecurityStoreResult.Corrupted,
					SecurityStoreResult.Missing,
					-> SecurityResult.Corrupted
					SecurityStoreResult.Unavailable -> SecurityResult.StoreError
				}
			} catch (error: CancellationException) {
				throw error
			} catch (_: InvalidSetupStateException) {
				SecurityResult.InvalidState
			} catch (_: CorruptedSecurityStoreException) {
				SecurityResult.Corrupted
			} catch (_: UnavailableSecurityStoreException) {
				SecurityResult.StoreError
			} catch (_: Exception) {
				SecurityResult.StoreError
			}
		}

	override suspend fun editLockoutPolicy(
		trials: PinTrials,
		timeout: PinTimeout,
	): SecurityResult<SecurityState> = operationMutex.withLock {
		var status: InvalidPinStatus? = null
		try {
			when (val updated = store.update { current ->
				val existing = current.requireReadable()
				val changed = existing.copy(
					pinOptions = existing.pinOptions.copy(
						trials = trials.trials,
						timeout = timeout.timeoutMs,
					),
				)
				val evaluated = evaluateStatus(changed)
				status = evaluated.status
				changed.copy(invalidPinStatus = evaluated.entity)
			}) {
				is SecurityStoreResult.Success -> SecurityResult.Success(
					updated.value.toSecurityState(status ?: evaluateStatus(updated.value).status),
				)
				SecurityStoreResult.Corrupted,
				SecurityStoreResult.Missing,
				-> SecurityResult.Corrupted
				SecurityStoreResult.Unavailable -> SecurityResult.StoreError
			}
		} catch (error: CancellationException) {
			throw error
		} catch (_: CorruptedSecurityStoreException) {
			SecurityResult.Corrupted
		} catch (_: UnavailableSecurityStoreException) {
			SecurityResult.StoreError
		} catch (_: Exception) {
			SecurityResult.StoreError
		}
	}

	private suspend fun verifyPinLocked(pin: CharArray): PinVerificationResult {
		var outcome: VerificationOutcome? = null
		return try {
			when (store.update { current ->
				val data = current.requireLocked()
				if (!pin.isValidPin(data.pinOptions.digits)) throw InvalidPinInputException()
				val evaluated = evaluateStatus(data)
				when {
					evaluated.status.shouldBlock -> {
						outcome = VerificationOutcome.Blocked(evaluated.status)
						data.copy(invalidPinStatus = evaluated.entity)
					}
					else -> when (val check = pinVerifier.verify(pin, data.pinSecured)) {
						PinCheckResult.Match -> {
							outcome = VerificationOutcome.Success
							data.copy(invalidPinStatus = InvalidPinStatusEntity())
						}
						PinCheckResult.Mismatch -> failedAttempt(data, evaluated.entity).let { failed ->
							outcome = failed.asVerificationOutcome()
							data.copy(invalidPinStatus = failed.entity)
						}
						is PinCheckResult.StoreError -> throw PinVerificationStoreException(check.reason)
					}
				}
			}) {
				is SecurityStoreResult.Success -> when (val verified = outcome) {
					VerificationOutcome.Success -> PinVerificationResult.Success
					is VerificationOutcome.Wrong -> PinVerificationResult.Wrong(verified.status)
					is VerificationOutcome.Blocked -> PinVerificationResult.Blocked(verified.status)
					null -> PinVerificationResult.StoreError(StoreErrorReason.Unavailable)
				}
				SecurityStoreResult.Corrupted,
				SecurityStoreResult.Missing,
				-> PinVerificationResult.StoreError(StoreErrorReason.Corrupted)
				SecurityStoreResult.Unavailable -> PinVerificationResult.StoreError(StoreErrorReason.Unavailable)
			}
		} catch (error: CancellationException) {
			throw error
		} catch (_: InvalidPinInputException) {
			PinVerificationResult.InvalidInput
		} catch (error: PinVerificationStoreException) {
			PinVerificationResult.StoreError(error.reason)
		} catch (_: CorruptedSecurityStoreException) {
			PinVerificationResult.StoreError(StoreErrorReason.Corrupted)
		} catch (_: UnavailableSecurityStoreException) {
			PinVerificationResult.StoreError(StoreErrorReason.Unavailable)
		} catch (_: Exception) {
			PinVerificationResult.StoreError(StoreErrorReason.Unavailable)
		}
	}

	private suspend fun setupPinLocked(
		pin: CharArray,
		digits: PinDigits,
		lockMethod: LockMethod,
	): SecurityResult<SecurityState> {
		return try {
			when (val updated = store.update { current ->
				val existing = when (current) {
					SecurityStoreResult.Missing -> defaultStoredData()
					is SecurityStoreResult.Success -> current.value
						.takeIf(StoredSecurityData::isStructurallyValid)
						?: throw CorruptedSecurityStoreException()
					SecurityStoreResult.Corrupted -> throw CorruptedSecurityStoreException()
					SecurityStoreResult.Unavailable -> throw UnavailableSecurityStoreException()
				}
				if (existing.lockStatus != LockMethodEntity.NO_LOCK || existing.pinSecured.isNotEmpty()) {
					throw InvalidSetupStateException()
				}

				// Encrypt only after the latest state has proved that setup is still permitted.
				val securedPin = pinCipher.encrypt(pin)
				existing.copy(
					pinSecured = securedPin,
					lockStatus = lockMethod.asEntity(),
					pinOptions = existing.pinOptions.copy(digits = digits.value),
					invalidPinStatus = InvalidPinStatusEntity(),
				)
			}) {
				is SecurityStoreResult.Success -> SecurityResult.Success(
					updated.value.toSecurityState(InvalidPinStatus.Default),
				)
				SecurityStoreResult.Corrupted,
				SecurityStoreResult.Missing,
				-> SecurityResult.Corrupted
				SecurityStoreResult.Unavailable -> SecurityResult.StoreError
			}
		} catch (error: CancellationException) {
			throw error
		} catch (_: InvalidSetupStateException) {
			SecurityResult.InvalidState
		} catch (_: CorruptedSecurityStoreException) {
			SecurityResult.Corrupted
		} catch (_: UnavailableSecurityStoreException) {
			SecurityResult.StoreError
		} catch (_: PinCipherException.Corrupted) {
			SecurityResult.Corrupted
		} catch (_: PinCipherException.Unavailable) {
			SecurityResult.StoreError
		} catch (_: Exception) {
			SecurityResult.StoreError
		}
	}

	private suspend fun changePinLocked(
		currentPin: CharArray,
		newPin: CharArray,
		digits: PinDigits,
		lockMethod: LockMethod?,
	): PinProtectedMutationResult {
		var outcome: MutationOutcome? = null
		return try {
			when (val updated = store.update { current ->
				val existing = current.requireLocked()
				if (!currentPin.isValidPin(existing.pinOptions.digits)) throw InvalidPinInputException()
				val evaluated = evaluateStatus(existing)
				when {
					evaluated.status.shouldBlock -> {
						outcome = MutationOutcome.Blocked(evaluated.status)
						existing.copy(invalidPinStatus = evaluated.entity)
					}
					else -> when (val check = pinVerifier.verify(currentPin, existing.pinSecured)) {
						PinCheckResult.Match -> {
							// Never create or fetch an encryption key for the replacement until the
							// currently persisted ciphertext has authenticated successfully.
							val securedPin = pinCipher.encrypt(newPin)
							outcome = MutationOutcome.Success
							existing.copy(
								pinSecured = securedPin,
								lockStatus = (lockMethod ?: existing.lockStatus.asDomain()).asEntity(),
								pinOptions = existing.pinOptions.copy(digits = digits.value),
								invalidPinStatus = InvalidPinStatusEntity(),
							)
						}
						PinCheckResult.Mismatch -> failedAttempt(existing, evaluated.entity).let { failed ->
							outcome = failed.asMutationOutcome()
							existing.copy(invalidPinStatus = failed.entity)
						}
						is PinCheckResult.StoreError -> throw PinVerificationStoreException(check.reason)
					}
				}
			}) {
				is SecurityStoreResult.Success -> when (val mutation = outcome) {
					MutationOutcome.Success -> PinProtectedMutationResult.Success(
						updated.value.toSecurityState(InvalidPinStatus.Default),
					)
					is MutationOutcome.Wrong -> PinProtectedMutationResult.Wrong(mutation.status)
					is MutationOutcome.Blocked -> PinProtectedMutationResult.Blocked(mutation.status)
					null -> PinProtectedMutationResult.StoreError
				}
				SecurityStoreResult.Corrupted,
				SecurityStoreResult.Missing,
				-> PinProtectedMutationResult.Corrupted
				SecurityStoreResult.Unavailable -> PinProtectedMutationResult.StoreError
			}
		} catch (error: CancellationException) {
			throw error
		} catch (_: InvalidPinInputException) {
			PinProtectedMutationResult.InvalidInput
		} catch (error: PinVerificationStoreException) {
			error.reason.asMutationError()
		} catch (_: CorruptedSecurityStoreException) {
			PinProtectedMutationResult.Corrupted
		} catch (_: UnavailableSecurityStoreException) {
			PinProtectedMutationResult.StoreError
		} catch (_: PinCipherException.Corrupted) {
			PinProtectedMutationResult.Corrupted
		} catch (_: PinCipherException.Unavailable) {
			PinProtectedMutationResult.StoreError
		} catch (_: Exception) {
			PinProtectedMutationResult.StoreError
		}
	}

	private suspend fun disablePinLocked(currentPin: CharArray): PinProtectedMutationResult {
		var outcome: MutationOutcome? = null
		return try {
			when (val updated = store.update { current ->
				val existing = current.requireLocked()
				if (!currentPin.isValidPin(existing.pinOptions.digits)) throw InvalidPinInputException()
				val evaluated = evaluateStatus(existing)
				when {
					evaluated.status.shouldBlock -> {
						outcome = MutationOutcome.Blocked(evaluated.status)
						existing.copy(invalidPinStatus = evaluated.entity)
					}
					else -> when (val check = pinVerifier.verify(currentPin, existing.pinSecured)) {
						PinCheckResult.Match -> {
							outcome = MutationOutcome.Success
							existing.copy(
								pinSecured = "",
								lockStatus = LockMethodEntity.NO_LOCK,
								invalidPinStatus = InvalidPinStatusEntity(),
							)
						}
						PinCheckResult.Mismatch -> failedAttempt(existing, evaluated.entity).let { failed ->
							outcome = failed.asMutationOutcome()
							existing.copy(invalidPinStatus = failed.entity)
						}
						is PinCheckResult.StoreError -> throw PinVerificationStoreException(check.reason)
					}
				}
			}) {
				is SecurityStoreResult.Success -> when (val mutation = outcome) {
					MutationOutcome.Success -> {
						// The disable is committed; removing the optional biometric key afterwards
						// cannot fail the operation (deletion is idempotent).
						biometricKeyCleanup()
						PinProtectedMutationResult.Success(
							updated.value.toSecurityState(InvalidPinStatus.Default),
						)
					}
					is MutationOutcome.Wrong -> PinProtectedMutationResult.Wrong(mutation.status)
					is MutationOutcome.Blocked -> PinProtectedMutationResult.Blocked(mutation.status)
					null -> PinProtectedMutationResult.StoreError
				}
				SecurityStoreResult.Corrupted,
				SecurityStoreResult.Missing,
				-> PinProtectedMutationResult.Corrupted
				SecurityStoreResult.Unavailable -> PinProtectedMutationResult.StoreError
			}
		} catch (error: CancellationException) {
			throw error
		} catch (_: InvalidPinInputException) {
			PinProtectedMutationResult.InvalidInput
		} catch (error: PinVerificationStoreException) {
			error.reason.asMutationError()
		} catch (_: CorruptedSecurityStoreException) {
			PinProtectedMutationResult.Corrupted
		} catch (_: UnavailableSecurityStoreException) {
			PinProtectedMutationResult.StoreError
		} catch (_: Exception) {
			PinProtectedMutationResult.StoreError
		}
	}

	private suspend fun normalizeLockoutLocked(): NormalizedStore {
		val data = when (val read = store.read()) {
			is SecurityStoreResult.Success -> read.value
			SecurityStoreResult.Missing -> return NormalizedStore.Success(
				data = defaultStoredData(),
				status = InvalidPinStatus.Default,
			)
			SecurityStoreResult.Corrupted -> return NormalizedStore.Corrupted
			SecurityStoreResult.Unavailable -> return NormalizedStore.Unavailable
		}

		val evaluated = try {
			evaluateStatus(data)
		} catch (error: CancellationException) {
			throw error
		} catch (_: CorruptedSecurityStoreException) {
			return NormalizedStore.Corrupted
		} catch (_: Exception) {
			return NormalizedStore.Unavailable
		}
		if (evaluated.entity == data.invalidPinStatus) {
			return NormalizedStore.Success(data, evaluated.status)
		}

		var normalizedStatus: InvalidPinStatus? = null
		return try {
			when (val updated = store.update { current ->
				val latest = current.requireReadable()
				val latestEvaluation = evaluateStatus(latest)
				normalizedStatus = latestEvaluation.status
				latest.copy(invalidPinStatus = latestEvaluation.entity)
			}) {
				is SecurityStoreResult.Success -> NormalizedStore.Success(
					data = updated.value,
					status = normalizedStatus ?: evaluateStatus(updated.value).status,
				)
				SecurityStoreResult.Corrupted,
				SecurityStoreResult.Missing,
				-> NormalizedStore.Corrupted
				SecurityStoreResult.Unavailable -> NormalizedStore.Unavailable
			}
		} catch (error: CancellationException) {
			throw error
		} catch (_: CorruptedSecurityStoreException) {
			NormalizedStore.Corrupted
		} catch (_: Exception) {
			NormalizedStore.Unavailable
		}
	}

	private fun failedAttempt(
		data: StoredSecurityData,
		status: InvalidPinStatusEntity,
	): EvaluatedStatus {
		val failed = status.copy(
			attempts = incrementAttempts(status.attempts),
			lastAttemptSinceBootMs = safeNow(),
			lockBootMarker = safeBootMarker(),
		)
		return evaluateStatus(data.copy(invalidPinStatus = failed))
	}

	private fun evaluateStatus(data: StoredSecurityData): EvaluatedStatus {
		if (!data.isStructurallyValid()) throw CorruptedSecurityStoreException()
		val entity = data.invalidPinStatus
		val options = data.pinOptions.asDomain()
		if (entity.attempts == 0) return EvaluatedStatus(entity, InvalidPinStatus.Default)
		if (options.trials == PinTrials.NoLimit) {
			return EvaluatedStatus(entity, entity.asUnblockedStatus())
		}
		if (entity.attempts < options.trials.trials) {
			return EvaluatedStatus(entity, entity.asUnblockedStatus())
		}

		val now = safeNow()
		val currentBoot = safeBootMarker()
		if (entity.lockBootMarker != currentBoot) {
			val restarted = entity.copy(
				lastAttemptSinceBootMs = now,
				lockBootMarker = currentBoot,
			)
			return EvaluatedStatus(
				entity = restarted,
				status = InvalidPinStatus.blocked(
					attempts = restarted.attempts,
					lastAttemptSinceBootMs = restarted.lastAttemptSinceBootMs,
					lockBootMarker = currentBoot,
					timeLeftMs = options.timeout.timeoutMs,
				),
			)
		}

		if (now < entity.lastAttemptSinceBootMs) {
			val restarted = entity.copy(lastAttemptSinceBootMs = now)
			return EvaluatedStatus(
				entity = restarted,
				status = InvalidPinStatus.blocked(
					attempts = restarted.attempts,
					lastAttemptSinceBootMs = restarted.lastAttemptSinceBootMs,
					lockBootMarker = currentBoot,
					timeLeftMs = options.timeout.timeoutMs,
				),
			)
		}

		val elapsed = now - entity.lastAttemptSinceBootMs
		val timeLeft = options.timeout.timeoutMs - elapsed
		if (timeLeft <= 0L) {
			return EvaluatedStatus(InvalidPinStatusEntity(), InvalidPinStatus.Default)
		}
		return EvaluatedStatus(
			entity = entity,
			status = InvalidPinStatus.blocked(
				attempts = entity.attempts,
				lastAttemptSinceBootMs = entity.lastAttemptSinceBootMs,
				lockBootMarker = currentBoot,
				timeLeftMs = timeLeft,
			),
		)
	}

	private fun safeNow(): Long = elapsedRealtimeProvider.elapsedRealtimeMs().also {
		if (it < 0L) throw IllegalStateException("Monotonic clock unavailable")
	}

	private fun safeBootMarker(): String = bootSessionMarkerProvider.marker().also {
		if (it.isBlank() || it.length > MAX_BOOT_MARKER_LENGTH) {
			throw IllegalStateException("Boot marker unavailable")
		}
	}

	private fun incrementAttempts(attempts: Int): Int =
		if (attempts == Int.MAX_VALUE) Int.MAX_VALUE else attempts + 1

	private fun InvalidPinStatusEntity.asUnblockedStatus() = InvalidPinStatus(
		attempts = attempts,
		lastAttemptSinceBootMs = lastAttemptSinceBootMs,
		lockBootMarker = lockBootMarker,
	)

	private fun EvaluatedStatus.asVerificationOutcome(): VerificationOutcome =
		if (status.shouldBlock) VerificationOutcome.Blocked(status) else VerificationOutcome.Wrong(status)

	private fun EvaluatedStatus.asMutationOutcome(): MutationOutcome =
		if (status.shouldBlock) MutationOutcome.Blocked(status) else MutationOutcome.Wrong(status)

	private fun StoreErrorReason.asMutationError(): PinProtectedMutationResult = when (this) {
		StoreErrorReason.Corrupted -> PinProtectedMutationResult.Corrupted
		StoreErrorReason.Unavailable -> PinProtectedMutationResult.StoreError
	}

	private fun StoredSecurityData.toSecurityState(status: InvalidPinStatus) = SecurityState(
		lockMethod = lockStatus.asDomain(),
		pinOptions = pinOptions.asDomain(),
		invalidPinStatus = status,
	)

	private fun defaultStoredData() = StoredSecurityData(
		pinSecured = "",
		lockStatus = LockMethodEntity.NO_LOCK,
		pinOptions = PinOptions.Default.asEntity(),
		invalidPinStatus = InvalidPinStatusEntity(),
	)

	private fun SecurityStoreResult<StoredSecurityData>.requireReadable(): StoredSecurityData = when (this) {
		is SecurityStoreResult.Success -> value.takeIf(StoredSecurityData::isStructurallyValid)
			?: throw CorruptedSecurityStoreException()
		SecurityStoreResult.Missing,
		SecurityStoreResult.Corrupted,
		-> throw CorruptedSecurityStoreException()
		SecurityStoreResult.Unavailable -> throw UnavailableSecurityStoreException()
	}

	private fun SecurityStoreResult<StoredSecurityData>.requireLocked(): StoredSecurityData =
		requireReadable().also {
			if (it.lockStatus == LockMethodEntity.NO_LOCK || it.pinSecured.isBlank()) {
				throw CorruptedSecurityStoreException()
			}
		}

	private fun CharArray.isPotentialPinInput(): Boolean =
		(size == PinDigits.Code4.value || size == PinDigits.Code6.value) && all { it in '0'..'9' }

	private fun CharArray.isValidPin(expectedDigits: Int): Boolean =
		size == expectedDigits && all { it in '0'..'9' }

	private fun CharArray.clearSensitive() = fill('\u0000')

	private data class EvaluatedStatus(
		val entity: InvalidPinStatusEntity,
		val status: InvalidPinStatus,
	)

	private sealed interface VerificationOutcome {
		data object Success : VerificationOutcome
		data class Wrong(val status: InvalidPinStatus) : VerificationOutcome
		data class Blocked(val status: InvalidPinStatus) : VerificationOutcome
	}

	private sealed interface MutationOutcome {
		data object Success : MutationOutcome
		data class Wrong(val status: InvalidPinStatus) : MutationOutcome
		data class Blocked(val status: InvalidPinStatus) : MutationOutcome
	}

	private sealed interface NormalizedStore {
		data class Success(
			val data: StoredSecurityData,
			val status: InvalidPinStatus,
		) : NormalizedStore
		data object Corrupted : NormalizedStore
		data object Unavailable : NormalizedStore
	}

	private class PinVerificationStoreException(val reason: StoreErrorReason) : Exception()
	private class InvalidPinInputException : Exception()
	private class InvalidSetupStateException : Exception()
	private class CorruptedSecurityStoreException : Exception()
	private class UnavailableSecurityStoreException : Exception()

	private companion object {
		const val MAX_BOOT_MARKER_LENGTH = 128
	}
}
