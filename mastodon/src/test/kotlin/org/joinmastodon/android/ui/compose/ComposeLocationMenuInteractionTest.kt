package org.joinmastodon.android.ui.compose

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.TextView
import android.widget.Toolbar
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.GlobalUserPreferences
import org.joinmastodon.android.R
import org.joinmastodon.android.api.requests.statuses.CreateStatus
import org.joinmastodon.android.fragments.ComposeFragment
import org.joinmastodon.android.fragments.ComposeReplyPublishingBehaviorTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/** Real native btn_location -> production flat MIUIX overlay, entirely offline with no device access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class, qualifiers = "en-w393dp-h852dp-mdpi",
    shadows = [ComposeReplyPublishingBehaviorTest.LocalRequests::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ComposeLocationMenuInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val editor = ComposeReplyPublishingBehaviorTest()
    private lateinit var fragment: ComposeFragment
    private val activity get() = editor.classificationActivity()
    private val anchor get() = fragment.view!!.findViewById<View>(R.id.btn_location)

    @Before fun setUp() {
        // Cold Robolectric JVMs compose the first popup slowly; the 60s device-tuned master
        // policy fires before the frame work finishes. A bounded larger budget changes when the
        // sync gives up, never what the tests assert.
        androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
        androidx.test.espresso.IdlingPolicies.setIdlingResourceTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
        editor.setUp()
        fragment = editor.launchClassificationEditor()
        resumeFrames()
    }
    @After fun tearDown() {
        ShadowChoreographer.setPaused(true)
        editor.tearDown()
    }

    @Test fun nativeEntryImmediatelyShowsAllFourChoicesAndOnlyProvinceAndNoneAreEnabledWithoutDataOrPermission() {
        val previousDialog = ShadowDialog.getLatestDialog()
        val previousCalls = ComposeReplyPublishingBehaviorTest.LocalRequests.sent.size
        openMenu()
        compose.onNodeWithText(province()).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(city()).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(district()).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(none()).assertIsDisplayed().assertIsEnabled()
        assertSame("Opening geo must not request permission or show a settings dialog", previousDialog, ShadowDialog.getLatestDialog())
        assertEquals(previousCalls, ComposeReplyPublishingBehaviorTest.LocalRequests.sent.size)
        compose.runOnUiThread { activity.onBackPressed() }
        settle()
        compose.onNodeWithText(city()).assertDoesNotExist()
        assertTrue(fragment.isAdded)
        assertEquals(province(), currentLabel())
    }

    @Test fun chosenNoneIsSelectedOnReopenAndSerializesNoGeoFields() {
        openMenu()
        compose.onNodeWithText(none()).performClick()
        settle()
        assertEquals(none(), currentLabel())
        openMenu()
        compose.onNodeWithText(none()).assertIsDisplayed()
        assertEquals(none(), currentLabel())
        compose.runOnUiThread { activity.onBackPressed() }
        settle()
        val wire = publish()
        for (key in listOf("geo_province", "geo_city", "geo_district")) assertFalse(key, wire.has(key))
    }

    @Test fun selectingCityWithPermissionAndExistingDataSendsOnlyProvinceAndCity() {
        reopenWithCachedLocation()
        compose.runOnUiThread {
            Shadow.extract<ShadowApplication>(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        openMenu()
        compose.onNodeWithText(city("Offline city")).assertIsEnabled().performClick()
        settle()
        assertEquals(city("Offline city"), currentLabel())
        val wire = publish()
        assertEquals("Offline province", wire.get("geo_province").asString)
        assertEquals("Offline city", wire.get("geo_city").asString)
        assertFalse(wire.has("geo_district"))
        assertFalse(wire.has("latitude")); assertFalse(wire.has("longitude"))
    }

    @Test fun cachedFineChoiceRemainsVisibleButSelectingWithoutPermissionReturnsToProvinceAndShowsPermissionGuide() {
        reopenWithCachedLocation()
        openMenu()
        compose.onNodeWithText(district("Offline district")).assertIsEnabled().performClick()
        settle()
        assertEquals(province("Offline province"), currentLabel())
        val guide = ShadowDialog.getLatestDialog()
        assertTrue(guide is org.joinmastodon.android.ui.sheets.LocationPermissionSheet)
        assertTrue(guide.isShowing)
        compose.runOnUiThread { guide.dismiss() }
        settle()
        val wire = publish()
        assertEquals("Offline province", wire.get("geo_province").asString)
        assertFalse(wire.has("geo_city")); assertFalse(wire.has("geo_district"))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun actualAnchoredPopupRendersInLightAndDarkThemes() {
        for ((theme, name) in listOf(R.style.Theme_Mastodon_Light to "light", R.style.Theme_Mastodon_Dark to "dark")) {
            compose.runOnUiThread {
                ShadowChoreographer.setPaused(true)
                GlobalUserPreferences.theme = if (name == "dark") GlobalUserPreferences.ThemePreference.DARK else GlobalUserPreferences.ThemePreference.LIGHT
                RuntimeEnvironment.setQualifiers("en-w393dp-h852dp-${if (name == "dark") "night" else "notnight"}-mdpi")
                fragment = editor.launchClassificationEditor(theme)
                resumeFrames()
            }
            openMenu()
            compose.onNodeWithText(none()).assertIsDisplayed()
            lateinit var image: Bitmap
            compose.runOnUiThread {
                val view = activity.window.decorView
                view.viewTreeObserver.dispatchOnPreDraw()
                image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(image))
            }
            val output = File("/tmp/abdl-nbw-v2-menu-renders").apply { mkdirs() }
            File(output, "geo-menu-$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            image.recycle()
            compose.runOnUiThread { activity.onBackPressed() }
            settle()
        }
    }

    private fun reopenWithCachedLocation() {
        compose.runOnUiThread {
            RuntimeEnvironment.getApplication().getSharedPreferences("location_cache", Context.MODE_PRIVATE).edit()
                .putString("province", "Offline province").putString("city", "Offline city")
                .putString("district", "Offline district").putLong("last_update", System.currentTimeMillis()).commit()
            ShadowChoreographer.setPaused(true)
            fragment = editor.launchClassificationEditor()
            resumeFrames()
        }
    }
    private fun openMenu() {
        compose.runOnUiThread { assertTrue(anchor.isAttachedToWindow); assertTrue(anchor.performClick()) }
        settle()
    }
    private fun publish(): com.google.gson.JsonObject {
        compose.runOnUiThread {
            fragment.view!!.findViewById<org.joinmastodon.android.ui.views.ComposeEditText>(R.id.toot_text).setText("Offline location post")
            val menu = fragment.view!!.findViewById<Toolbar>(R.id.toolbar).menu
            assertTrue(menu.findItem(R.id.publish).isEnabled)
            assertTrue(menu.performIdentifierAction(R.id.publish, 0))
        }
        settle()
        val request = ComposeReplyPublishingBehaviorTest.LocalRequests.sent.last { it is CreateStatus }
        return ComposeReplyPublishingBehaviorTest.json(request)
    }
    private fun currentLabel() = fragment.view!!.findViewById<TextView>(R.id.location_text).text.toString()
    private fun province(value: String = activity.getString(R.string.location_unknown)) = activity.getString(R.string.location_province, value)
    private fun city(value: String = activity.getString(R.string.location_unknown)) = activity.getString(R.string.location_city, value)
    private fun district(value: String = activity.getString(R.string.location_unknown)) = activity.getString(R.string.location_district, value)
    private fun none() = activity.getString(R.string.location_none)
    private fun resumeFrames() { ShadowChoreographer.setPaused(false); ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS) }
    private fun settle() {
        // Deliver real choreographer frames first so popup enter animations finish without
        // leaning on Espresso's idling loop, then let the rule confirm quiescence.
        repeat(30) { ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        ShadowLooper.idleMainLooper()
    }
}
