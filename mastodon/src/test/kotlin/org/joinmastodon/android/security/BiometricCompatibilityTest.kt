package org.joinmastodon.android.security

import android.app.Application
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import org.joinmastodon.android.security.ui.BiometricSession
import org.joinmastodon.android.security.ui.biometric.biometricErrorNeedsMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 29, 30, 31, 33, 35], application = Application::class)
class BiometricCompatibilityTest {
	@Test fun strongOnlyPromptIsLegalAcrossSupportedAndroidVersions() {
		val info = BiometricPrompt.PromptInfo.Builder().setTitle("Authenticate").setNegativeButtonText("Use PIN")
			.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).build()
		assertEquals(BiometricManager.Authenticators.BIOMETRIC_STRONG, info.allowedAuthenticators)
	}

	@Test fun keyPolicyUsesPerOperationAuthenticationAndEnrollmentInvalidation() {
		val builder = KeyGenParameterSpec.Builder("compat-test", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
			.setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
			.setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
		if (Build.VERSION.SDK_INT >= 30) builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
		else {
			@Suppress("DEPRECATION")
			builder.setUserAuthenticationValidityDurationSeconds(-1)
		}
		val spec = builder.build()
		assertTrue(spec.isUserAuthenticationRequired)
		assertTrue(spec.isInvalidatedByBiometricEnrollment)
		if (Build.VERSION.SDK_INT >= 30) assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, spec.userAuthenticationType)
		else assertEquals(-1, spec.userAuthenticationValidityDurationSeconds)
	}

	@Test fun duplicateAndOldSessionCallbacksCannotCompleteTwice() {
		val session = BiometricSession()
		val first = session.begin()
		val second = session.begin()
		assertFalse(session.complete(first))
		assertTrue(session.complete(second))
		assertFalse(session.complete(second))
		val third = session.begin()
		session.cancel()
		assertFalse(session.complete(third))
	}

	@Test fun cancellationIsSilentButHardwareAndLockoutHaveActionableFallback() {
		for (code in listOf(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON)) {
			assertFalse(biometricErrorNeedsMessage(code))
		}
		for (code in listOf(BiometricPrompt.ERROR_HW_UNAVAILABLE, BiometricPrompt.ERROR_NO_BIOMETRICS,
			BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
			BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED)) {
			assertTrue(biometricErrorNeedsMessage(code))
		}
	}
}
