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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.joinmastodon.android.R
import org.joinmastodon.android.security.data.BiometricKeyProvider
import org.joinmastodon.android.security.data.BiometricKeyInvalidatedException
import org.joinmastodon.android.security.data.BiometricKeyStoreCorruptedException
import org.joinmastodon.android.security.data.BiometricKeyStoreUnavailableException
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher

/**
 * Crypto-object biometric prompt. A single session may complete at most once: stale callbacks from
 * a disposed prompt cannot resolve a newer [BiometricRequest]. `onAuthenticationFailed` is a
 * non-terminal event — the system prompt stays up for another attempt.
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
) {
	val context = LocalContext.current
	val activity = context.findFragmentActivity()
	val lifecycleOwner = LocalLifecycleOwner.current
	val sessionCounter = remember { AtomicInteger(0) }

	if (activity == null) {
		LaunchedEffect(Unit) {
			toast(context, context.getString(R.string.security_error_no_fragment_activity))
			onDismiss()
		}
		return
	}

	LaunchedEffect(lifecycleOwner.lifecycle.currentState) {
		val lifecycleState = lifecycleOwner.lifecycle.currentState
		if (lifecycleState == Lifecycle.State.DESTROYED) return@LaunchedEffect
		if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
			val session = sessionCounter.incrementAndGet()
			val isCurrentSession = { sessionCounter.get() == session }
			runPrompt(
				activity = activity,
				keyProvider = biometricKeyProvider,
				title = title,
				subtitle = subtitle,
				negative = negative,
				isCurrentSession = isCurrentSession,
				onSuccess = { if (isCurrentSession()) onSuccess() },
				onDismiss = { if (isCurrentSession()) onDismiss() },
				onInvalidated = { if (isCurrentSession()) onInvalidated() },
			)
		}
	}

	androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
				sessionCounter.incrementAndGet()
			}
		}
		lifecycleOwner.lifecycle.addObserver(observer)
		onDispose {
			sessionCounter.incrementAndGet()
			lifecycleOwner.lifecycle.removeObserver(observer)
		}
	}
}

private suspend fun runPrompt(
	activity: FragmentActivity,
	keyProvider: BiometricKeyProvider,
	title: String,
	subtitle: String,
	negative: String,
	isCurrentSession: () -> Boolean,
	onSuccess: () -> Unit,
	onDismiss: () -> Unit,
	onInvalidated: () -> Unit,
) {
	val promptInfo = BiometricPrompt.PromptInfo.Builder()
		.setTitle(title)
		.setSubtitle(subtitle)
		.setNegativeButtonText(negative)
		.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
		.build()
	val callback = object : BiometricPrompt.AuthenticationCallback() {
		override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
			if (isCurrentSession()) onSuccess()
		}

		override fun onAuthenticationFailed() {
			// Non-terminal: the system prompt remains visible for the next attempt.
		}

		override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
			if (!isCurrentSession()) return
			when (errorCode) {
				BiometricPrompt.ERROR_USER_CANCELED,
				BiometricPrompt.ERROR_NEGATIVE_BUTTON,
				BiometricPrompt.ERROR_CANCELED,
				-> onDismiss()

				else -> {
					toast(activity, activity.getString(R.string.security_biometric_error, errString))
					onDismiss()
				}
			}
		}
	}
	val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
	val cipher = try {
		val secretKey = keyProvider.loadSecretKey()
			?: throw BiometricKeyStoreCorruptedException()
		Cipher.getInstance(BiometricKeyProvider.TRANSFORMATION).apply {
			init(Cipher.ENCRYPT_MODE, secretKey)
		}
	} catch (error: BiometricKeyInvalidatedException) {
		if (isCurrentSession()) onInvalidated()
		return
	} catch (error: BiometricKeyStoreCorruptedException) {
		if (isCurrentSession()) onInvalidated()
		return
	} catch (error: BiometricKeyStoreUnavailableException) {
		toast(activity, activity.getString(R.string.security_biometric_unavailable))
		if (isCurrentSession()) onDismiss()
		return
	} catch (error: Exception) {
		toast(activity, activity.getString(R.string.security_biometric_error, error.message ?: ""))
		if (isCurrentSession()) onDismiss()
		return
	}
	prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
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
