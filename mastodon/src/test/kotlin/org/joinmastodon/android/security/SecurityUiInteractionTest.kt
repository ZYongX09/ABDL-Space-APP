package org.joinmastodon.android.security

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.joinmastodon.android.R
import org.joinmastodon.android.security.ui.SecuritySelectionDialog
import org.joinmastodon.android.security.ui.pin.PinKeyboard
import org.joinmastodon.android.security.ui.security.securitySwitchColors
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.joinmastodon.android.MastodonApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.preference.SwitchPreference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class SecurityUiInteractionTest {
	@get:Rule val compose = createComposeRule()

	@Before fun initializeApplicationContext() { MastodonApp.context = RuntimeEnvironment.getApplication() }

	@Test fun switchReflectsCommittedStateRatherThanRequestedState() {
		val checked = mutableStateOf(false)
		var requested = false
		compose.setContent {
			MiuixAppTheme {
				SwitchPreference(title = "PIN", checked = checked.value,
					modifier = Modifier.semantics { toggleableState = ToggleableState(checked.value) },
					onCheckedChange = { requested = it }, switchColors = securitySwitchColors())
			}
		}
		compose.onNodeWithText("PIN").performClick()
		compose.runOnIdle { assertEquals(true, requested) }
		compose.onNodeWithText("PIN").assertIsOff()
		compose.runOnIdle { checked.value = true }
		compose.onNodeWithText("PIN").assertIsOn()
	}

	@Test fun eachSelectionDialogAppearsAndDeliversChosenValue() {
		val cases = listOf(listOf("3", "5", "10", "Unlimited"), listOf("3 minutes", "5 minutes", "10 minutes"), listOf("4 digits", "6 digits"))
		val current = mutableStateOf(0)
		var selected = -1
		compose.setContent {
			MiuixAppTheme {
				SecuritySelectionDialog("Options", cases[current.value], 0, {}, { selected = it })
			}
		}
		cases.forEachIndexed { index, values ->
			compose.runOnIdle { current.value = index }
			compose.onNodeWithText(values[0]).assertIsSelected()
			compose.onNodeWithText(values.last()).performClick()
			compose.runOnIdle { assertEquals(values.lastIndex, selected) }
		}
	}

	@Test fun keypadDeliversDigitsBackspaceAndBiometricRequests() {
		var digit = -1
		var backspaces = 0
		var biometrics = 0
		compose.setContent {
			MiuixAppTheme {
				PinKeyboard(true, true, { digit = it }, { biometrics++ }, { backspaces++ }, true)
			}
		}
		val context = RuntimeEnvironment.getApplication()
		compose.onNodeWithText("0").performClick()
		compose.onNodeWithContentDescription(context.getString(R.string.security_a11y_backspace)).performClick()
		compose.onNodeWithContentDescription(context.getString(R.string.security_a11y_biometric)).performClick()
		compose.runOnIdle {
			assertEquals(0, digit)
			assertEquals(1, backspaces)
			assertEquals(1, biometrics)
		}
	}
}
