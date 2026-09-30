/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/biometric/BiometricDialog.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.biometric

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.joinmastodon.android.R
import org.joinmastodon.android.security.data.BiometricKeyInvalidatedException
import org.joinmastodon.android.security.data.BiometricKeyProvider
import org.joinmastodon.android.security.data.BiometricKeyStoreCorruptedException
import org.joinmastodon.android.security.ui.BiometricSession
import javax.crypto.Cipher

private class PromptRequest {
	val session = BiometricSession()
	var prompt: BiometricPrompt? = null
	@Volatile var disposed = false
	@Volatile var setupKeyCreated = false
	@Volatile var verified = false
}

@Composable
internal fun BiometricDialog(
	title: String,
	subtitle: String,
	negative: String,
	biometricKeyProvider: BiometricKeyProvider,
	onSuccess: () -> Unit,
	onDismiss: () -> Unit,
	onInvalidated: () -> Unit,
	createKeyIfMissing: Boolean = false,
	requestId: Int = 0,
) {
	val context = LocalContext.current
	val activity = remember(context) { context.findFragmentActivity() }
	val owner = LocalLifecycleOwner.current
	val request = remember(activity, requestId) { PromptRequest() }
	val success = rememberUpdatedState(onSuccess)
	val dismiss = rememberUpdatedState(onDismiss)
	val invalidated = rememberUpdatedState(onInvalidated)
	DisposableEffect(request, owner) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_STOP) {
				request.session.cancel()
				request.prompt?.cancelAuthentication()
				dismiss.value()
			}
		}
		owner.lifecycle.addObserver(observer)
		onDispose {
			owner.lifecycle.removeObserver(observer)
			request.disposed = true
			request.session.cancel()
			request.prompt?.cancelAuthentication()
			if (createKeyIfMissing && request.setupKeyCreated && !request.verified) {
				ContextCompat.getMainExecutor(context).execute {
					runCatching { biometricKeyProvider.deleteSecretKey() }
				}
			}
		}
	}
	LaunchedEffect(request) {
		if (activity == null) {
			toast(context, context.getString(R.string.security_error_no_fragment_activity))
			dismiss.value()
			return@LaunchedEffect
		}
		owner.lifecycle.withResumed { }
		val session = request.session.begin()
		val availability = BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
		if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
			if (request.session.complete(session)) {
				toast(context, context.getString(R.string.security_biometric_unavailable))
				dismiss.value()
			}
			return@LaunchedEffect
		}
		val cipher = try {
			withContext(Dispatchers.IO) {
				val key = if (createKeyIfMissing) {
					// A disabled biometric method owns no active key. Replace any orphan left by
					// an interrupted setup; the new key still requires a fresh crypto prompt.
					biometricKeyProvider.deleteSecretKey()
					biometricKeyProvider.createSecretKey().also {
						request.setupKeyCreated = true
						if (request.disposed) biometricKeyProvider.deleteSecretKey()
					}
				} else biometricKeyProvider.loadSecretKey() ?: throw BiometricKeyStoreCorruptedException()
				Cipher.getInstance(BiometricKeyProvider.TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
			}
		} catch (error: CancellationException) {
			throw error
		} catch (_: KeyPermanentlyInvalidatedException) {
			if (request.session.complete(session)) invalidated.value()
			return@LaunchedEffect
		} catch (_: BiometricKeyInvalidatedException) {
			if (request.session.complete(session)) invalidated.value()
			return@LaunchedEffect
		} catch (_: BiometricKeyStoreCorruptedException) {
			if (request.session.complete(session)) invalidated.value()
			return@LaunchedEffect
		} catch (_: Exception) {
			if (request.session.complete(session)) {
				toast(context, context.getString(R.string.security_biometric_unavailable))
				dismiss.value()
			}
			return@LaunchedEffect
		}
		if (request.disposed || !request.session.isActive(session)) return@LaunchedEffect
		val callback = object : BiometricPrompt.AuthenticationCallback() {
			override fun onAuthenticationFailed() = Unit
			override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
				if (!request.session.isActive(session) || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
				try {
					val authenticatedCipher = result.cryptoObject?.cipher ?: throw IllegalStateException()
					authenticatedCipher.doFinal(byteArrayOf(0))
					if (request.session.complete(session)) {
						request.verified = true
						success.value()
					}
				} catch (_: Exception) {
					if (request.session.complete(session)) dismiss.value()
				}
			}
			override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
				if (!request.session.complete(session)) return
				if (biometricErrorNeedsMessage(errorCode)) {
					toast(context, context.getString(R.string.security_biometric_unavailable))
				}
				dismiss.value()
			}
		}
		request.prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
		val info = BiometricPrompt.PromptInfo.Builder()
			.setTitle(title).setSubtitle(subtitle).setNegativeButtonText(negative)
			.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).build()
		try {
			request.prompt?.authenticate(info, BiometricPrompt.CryptoObject(cipher))
		} catch (_: RuntimeException) {
			if (request.session.complete(session)) dismiss.value()
		}
	}
}

internal fun biometricErrorNeedsMessage(code: Int): Boolean = code !in setOf(
	BiometricPrompt.ERROR_USER_CANCELED,
	BiometricPrompt.ERROR_NEGATIVE_BUTTON,
	BiometricPrompt.ERROR_CANCELED,
)

private fun Context.findFragmentActivity(): FragmentActivity? {
	var current = this
	while (current is android.content.ContextWrapper) {
		if (current is FragmentActivity) return current
		val next = current.baseContext
		if (next === current) return null
		current = next
	}
	return current as? FragmentActivity
}

private fun toast(context: Context, message: String) {
	android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
}
