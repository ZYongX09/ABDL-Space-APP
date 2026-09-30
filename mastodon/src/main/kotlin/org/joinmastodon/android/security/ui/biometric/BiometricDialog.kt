/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/biometric/BiometricDialog.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.biometric

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.joinmastodon.android.R
import org.joinmastodon.android.security.data.BiometricKeyInvalidatedException
import org.joinmastodon.android.security.data.BiometricKeyProvider
import org.joinmastodon.android.security.data.BiometricKeyStoreCorruptedException
import org.joinmastodon.android.security.data.BiometricKeyStoreUnavailableException
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher

/**
 * Crypto-object biometric prompt. A request id starts one prompt session; disposing the composable
 * cancels the platform prompt and invalidates all late callbacks. When [createKeyIfMissing] is true
 * (the setup flow), a newly-created key is deleted if the user cancels or the prompt fails.
 */
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
	val activity = context.findFragmentActivity()
	val lifecycleOwner = LocalLifecycleOwner.current
	val sessionCounter = remember { AtomicInteger(0) }
	var prompt by remember { mutableStateOf<BiometricPrompt?>(null) }

	if (activity == null) {
		LaunchedEffect(Unit) {
			toast(context, context.getString(R.string.security_error_no_fragment_activity))
			onDismiss()
		}
		return
	}

	LaunchedEffect(requestId, lifecycleOwner.lifecycle.currentState) {
		val lifecycleState = lifecycleOwner.lifecycle.currentState
		if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
		val session = sessionCounter.incrementAndGet()
		val isCurrentSession = { sessionCounter.get() == session }
		runPrompt(
			activity = activity,
			keyProvider = biometricKeyProvider,
			title = title,
			subtitle = subtitle,
			negative = negative,
			createKeyIfMissing = createKeyIfMissing,
			isCurrentSession = isCurrentSession,
			setPrompt = { prompt = it },
			onSuccess = { if (isCurrentSession()) onSuccess() },
			onDismiss = { if (isCurrentSession()) onDismiss() },
			onInvalidated = { if (isCurrentSession()) onInvalidated() },
		)
	}

	DisposableEffect(lifecycleOwner, requestId) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_DESTROY) {
				sessionCounter.incrementAndGet()
				prompt?.cancelAuthentication()
			}
		}
		lifecycleOwner.lifecycle.addObserver(observer)
		onDispose {
			sessionCounter.incrementAndGet()
			prompt?.cancelAuthentication()
			prompt = null
			lifecycleOwner.lifecycle.removeObserver(observer)
		}
	}
}

private fun runPrompt(
	activity: FragmentActivity,
	keyProvider: BiometricKeyProvider,
	title: String,
	subtitle: String,
	negative: String,
	createKeyIfMissing: Boolean,
	isCurrentSession: () -> Boolean,
	setPrompt: (BiometricPrompt) -> Unit,
	onSuccess: () -> Unit,
	onDismiss: () -> Unit,
	onInvalidated: () -> Unit,
) {
	val availability = BiometricManager.from(activity).canAuthenticate(
		BiometricManager.Authenticators.BIOMETRIC_STRONG,
	)
	if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
		if (isCurrentSession()) {
			toast(activity, activity.getString(R.string.security_biometric_unavailable))
			onDismiss()
		}
		return
	}

	var createdForSession = false
	val secretKey = try {
		try {
			keyProvider.loadSecretKey()
		} catch (_: BiometricKeyInvalidatedException) {
			if (!createKeyIfMissing) throw BiometricKeyInvalidatedException()
			keyProvider.deleteSecretKey()
			null
		} ?: run {
			if (!createKeyIfMissing) throw BiometricKeyStoreCorruptedException()
			createdForSession = true
			keyProvider.createSecretKey()
		}
	} catch (_: BiometricKeyInvalidatedException) {
		if (isCurrentSession()) onInvalidated()
		return
	} catch (_: BiometricKeyStoreCorruptedException) {
		if (isCurrentSession()) onInvalidated()
		return
	} catch (_: BiometricKeyStoreUnavailableException) {
		toast(activity, activity.getString(R.string.security_biometric_unavailable))
		if (isCurrentSession()) onDismiss()
		return
	} catch (error: Exception) {
		toast(activity, activity.getString(R.string.security_biometric_error, error.message ?: ""))
		if (isCurrentSession()) onDismiss()
		return
	}

	val promptInfo = BiometricPrompt.PromptInfo.Builder()
		.setTitle(title)
		.setSubtitle(subtitle)
		.setNegativeButtonText(negative)
		.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
		.build()
	val callback = object : BiometricPrompt.AuthenticationCallback() {
		override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
			if (isCurrentSession()) {
				createdForSession = false
				onSuccess()
			}
		}

		override fun onAuthenticationFailed() {
			// Non-terminal: the system prompt remains visible for the next attempt.
		}

		override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
			if (!isCurrentSession()) return
			if (createdForSession) keyProvider.deleteSecretKey()
			when (errorCode) {
				BiometricPrompt.ERROR_USER_CANCELED,
				BiometricPrompt.ERROR_NEGATIVE_BUTTON,
				BiometricPrompt.ERROR_CANCELED,
				BiometricPrompt.ERROR_LOCKOUT,
				BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
				-> onDismiss()

				else -> {
					toast(activity, activity.getString(R.string.security_biometric_error, errString))
					onDismiss()
				}
			}
		}
	}
	val biometricPrompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
	setPrompt(biometricPrompt)
	val cipher = try {
		Cipher.getInstance(BiometricKeyProvider.TRANSFORMATION).apply {
			init(Cipher.ENCRYPT_MODE, secretKey)
		}
	} catch (error: BiometricKeyInvalidatedException) {
		if (createdForSession) keyProvider.deleteSecretKey()
		if (isCurrentSession()) onInvalidated()
		return
	} catch (error: Exception) {
		if (createdForSession) keyProvider.deleteSecretKey()
		toast(activity, activity.getString(R.string.security_biometric_error, error.message ?: ""))
		if (isCurrentSession()) onDismiss()
		return
	}
	biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
}

private fun Context.findFragmentActivity(): FragmentActivity? {
	var current = this
	while (current is android.content.ContextWrapper) {
		if (current is FragmentActivity) return current
		current = current.baseContext
	}
	return null
}

private fun toast(context: Context, message: String) {
	android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
}
