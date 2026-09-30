/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/security/SecurityScreen.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.security

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.joinmastodon.android.R
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.domain.PinTimeout
import org.joinmastodon.android.security.domain.PinTrials
import org.joinmastodon.android.ui.compose.component.BackNavigationIcon
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SecurityScreen(
	viewModel: SecurityViewModel,
	biometricKeyProvider: org.joinmastodon.android.security.data.BiometricKeyProvider,
	onBack: () -> Unit,
	openSetupPin: () -> Unit,
	openDisablePin: () -> Unit,
	openChangePin: () -> Unit,
) {
	val uiState by viewModel.uiState.collectAsStateWithLifecycle()
	val context = LocalContext.current
	var showTrialsDialog by remember { mutableStateOf(false) }
	var showTimeoutDialog by remember { mutableStateOf(false) }
	var showBiometricDialog by remember { mutableStateOf(false) }
	var biometricRequestId by remember { mutableIntStateOf(0) }
	LaunchedEffect(viewModel) {
		viewModel.effects.collect { effect ->
			when (effect) {
				SecurityEffect.BiometricUnavailable -> Toast.makeText(
					context,
					R.string.security_biometric_unavailable,
					Toast.LENGTH_SHORT,
				).show()
				SecurityEffect.BiometricEnabled -> Unit
			}
		}
	}
	BackHandler(onBack = onBack)
	Scaffold(
		topBar = {
			SmallTopAppBar(
				title = stringResource(R.string.settings__security),
					navigationIcon = {
						BackNavigationIcon(
							onClick = onBack,
							contentDescription = stringResource(R.string.back),
						)
					},
			)
		},
	) { padding ->
		when {
			uiState.loading -> Box(
				modifier = Modifier.fillMaxSize().padding(padding),
				contentAlignment = Alignment.Center,
			) {
				CircularProgressIndicator()
			}
			else -> LazyColumn(
				modifier = Modifier.fillMaxSize().padding(padding),
				contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				if (!uiState.hasPin) {
					item {
						SecurityCard {
							SwitchPreference(
								checked = false,
								onCheckedChange = { if (it) openSetupPin() },
								title = stringResource(R.string.settings__pin_code),
								startAction = { SecurityIcon(R.drawable.ic_fluent_password_24_regular) },
							)
						}
					}
					item { SecurityHeader(stringResource(R.string.settings__biometrics)) }
					item {
						SecurityCard {
							SwitchPreference(
								checked = false,
								onCheckedChange = {},
								title = stringResource(R.string.settings__option_fingerprint),
								summary = stringResource(R.string.settings__option_fingerprint_description),
								startAction = { SecurityIcon(R.drawable.ic_fluent_fingerprint_24_regular) },
								enabled = false,
							)
						}
					}
				} else {
					item { SecurityHeader(stringResource(R.string.settings__settings)) }
					item {
						SecurityCard {
							SwitchPreference(
								checked = true,
								onCheckedChange = { if (!it) openDisablePin() },
								title = stringResource(R.string.settings__pin_code),
								startAction = { SecurityIcon(R.drawable.ic_fluent_password_24_regular) },
							)
							ArrowPreference(
								title = stringResource(R.string.security__change_pin),
								startAction = { SecurityIcon(R.drawable.ic_fluent_edit_lock_24_regular) },
								onClick = openChangePin,
							)
						}
					}
					item { SecurityHeader(stringResource(R.string.settings__app_blocking)) }
					item {
						SecurityCard {
							ArrowPreference(
								title = stringResource(R.string.settings__limit_of_trials),
								startAction = { SecurityIcon(R.drawable.ic_fluent_prohibited_24_regular) },
								endActions = { ValueText(uiState.pinTrials.displayLabel()) },
								onClick = { if (!uiState.policyUpdating) showTrialsDialog = true },
								enabled = !uiState.policyUpdating,
							)
							SecurityFooter(stringResource(R.string.settings__how_many_attempts_footer))
							ArrowPreference(
								title = stringResource(R.string.settings__block_for),
								startAction = { SecurityIcon(R.drawable.ic_fluent_clock_lock_24_regular) },
								endActions = { ValueText(stringResource(uiState.pinTimeout.labelRes())) },
								onClick = { if (!uiState.policyUpdating) showTimeoutDialog = true },
								enabled = uiState.pinTrials != PinTrials.NoLimit && !uiState.policyUpdating,
							)
							SecurityFooter(stringResource(R.string.settings__block_for_footer))
						}
					}
					item { SecurityHeader(stringResource(R.string.settings__biometrics)) }
					item {
						SecurityCard {
							SwitchPreference(
								checked = uiState.lockMethod == LockMethod.Biometrics,
								enabled = !uiState.policyUpdating && !showBiometricDialog,
								onCheckedChange = {
								if (it) {
									biometricRequestId++
									showBiometricDialog = true
									} else {
										viewModel.disableBiometric()
									}
								},
								title = stringResource(R.string.settings__option_fingerprint),
								startAction = { SecurityIcon(R.drawable.ic_fluent_fingerprint_24_regular) },
							)
						}
					}
				}
				uiState.errorMessageRes?.let { message ->
					item {
						Text(
							text = stringResource(message),
								modifier = Modifier
									.fillMaxWidth()
									.padding(horizontal = 16.dp)
									.semantics { liveRegion = LiveRegionMode.Assertive },
								color = MiuixTheme.colorScheme.error,
							fontSize = 14.sp,
						)
					}
				}
			}
		}
	}
	if (showTrialsDialog) {
		SelectionDialog(
			title = stringResource(R.string.settings__limit_of_trials),
			items = PinTrials.entries.map { it.displayLabel() },
			selected = PinTrials.entries.indexOf(uiState.pinTrials),
			onDismiss = { showTrialsDialog = false },
			onSelect = {
				viewModel.updatePinTrials(PinTrials.entries[it])
				showTrialsDialog = false
			},
		)
	}
	if (showTimeoutDialog) {
		SelectionDialog(
			title = stringResource(R.string.settings__block_for),
			items = PinTimeout.entries.map { stringResource(it.labelRes()) },
			selected = PinTimeout.entries.indexOf(uiState.pinTimeout),
			onDismiss = { showTimeoutDialog = false },
			onSelect = {
				viewModel.updatePinTimeout(PinTimeout.entries[it])
				showTimeoutDialog = false
			},
		)
	}
	if (showBiometricDialog) {
		org.joinmastodon.android.security.ui.biometric.BiometricDialog(
			title = stringResource(R.string.biometric_dialog_setup_title),
			subtitle = stringResource(R.string.biometric_dialog_auth_subtitle),
			negative = stringResource(R.string.biometric_dialog_setup_cancel),
			biometricKeyProvider = biometricKeyProvider,
			createKeyIfMissing = true,
			requestId = biometricRequestId,
			onSuccess = {
				showBiometricDialog = false
				viewModel.onBiometricEnabled()
			},
			onDismiss = { showBiometricDialog = false },
			onInvalidated = { showBiometricDialog = false },
		)
	}
}

@Composable
private fun SecurityCard(content: @Composable () -> Unit) {
	Card(modifier = Modifier.fillMaxWidth(), content = { content() })
}

@Composable
private fun SecurityIcon(drawable: Int) {
	Icon(
		painter = painterResource(drawable),
		contentDescription = null,
		tint = MiuixTheme.colorScheme.primary,
		modifier = Modifier.size(24.dp),
	)
}

@Composable
private fun SecurityHeader(title: String) {
	Text(
		text = title,
		modifier = Modifier.padding(start = 12.dp, top = 4.dp),
		fontSize = 14.sp,
		fontWeight = FontWeight.Medium,
		color = MiuixTheme.colorScheme.primary,
	)
}

@Composable
private fun SecurityFooter(text: String) {
	Text(
		text = text.trimEnd(),
		modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 10.dp),
		fontSize = 13.sp,
		color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
	)
}

@Composable
private fun ValueText(value: String) {
	Text(text = value, color = MiuixTheme.colorScheme.onSurfaceVariantActions, fontSize = 14.sp)
}

@Composable
private fun PinTrials.displayLabel(): String = if (this == PinTrials.NoLimit) {
	stringResource(R.string.settings__no_limit)
} else {
	label
}

private fun PinTimeout.labelRes(): Int = when (this) {
	PinTimeout.Timeout3 -> R.string.settings__3_minutes
	PinTimeout.Timeout5 -> R.string.settings__5_minutes
	PinTimeout.Timeout10 -> R.string.settings__10_minutes
}

@Composable
private fun SelectionDialog(
	title: String,
	items: List<String>,
	selected: Int,
	onDismiss: () -> Unit,
	onSelect: (Int) -> Unit,
) {
	OverlayDialog(show = true, title = title, onDismissRequest = onDismiss) {
		Column {
			items.forEachIndexed { index, item ->
				TextButton(
					text = if (index == selected) "✓ $item" else item,
					onClick = { onSelect(index) },
					modifier = Modifier.fillMaxWidth(),
				)
			}
		}
	}
}
