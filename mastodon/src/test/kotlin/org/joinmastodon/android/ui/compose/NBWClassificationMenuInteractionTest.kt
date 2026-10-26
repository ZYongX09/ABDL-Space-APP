package org.joinmastodon.android.ui.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.GlobalUserPreferences
import org.joinmastodon.android.R
import org.joinmastodon.android.api.NBWV2RepositoryTest
import org.joinmastodon.android.api.requests.nbw.RecommendNBWForum
import org.joinmastodon.android.api.requests.statuses.CreateStatus
import org.joinmastodon.android.api.session.NBWForumOptionsRepository
import org.joinmastodon.android.fragments.ComposeFragment
import org.joinmastodon.android.fragments.ComposeReplyPublishingBehaviorTest
import org.joinmastodon.android.model.NBWForumOptions
import org.joinmastodon.android.model.NBWSelection
import org.joinmastodon.android.ui.sheets.NBWFallbackConsentSheet
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
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.time.Duration

/** Actual ComposeFragment btn_visibility -> production anchored miuix rows, no fake UI/model clicks.
 * Transport alone is intercepted. These are offline API28 Robolectric checks, not device evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28], application = CompatibilityTestApplication::class, qualifiers = "en-w393dp-h852dp-mdpi",
    shadows = [ComposeReplyPublishingBehaviorTest.LocalRequests::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class NBWClassificationMenuInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val editor = ComposeReplyPublishingBehaviorTest()
    private lateinit var fragment: ComposeFragment
    private lateinit var repository: NBWForumOptionsRepository
    private val activity get() = editor.classificationActivity()
    private val context get() = activity
    private val anchor get() = fragment.view!!.findViewById<View>(R.id.btn_visibility)

    @Before fun setUp() {
        editor.setUp()
        fragment = editor.launchClassificationEditor()
        repository = editor.classificationRepository()
        // A native window attached while vsync was paused may still hold its traversal barrier.
        ShadowChoreographer.setPaused(false)
        ShadowLooper.idleMainLooper(32, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    @After fun tearDown() {
        ShadowChoreographer.setPaused(true)
        editor.tearDown()
    }

    @Test fun realComposeEntryOpensMenuOnOneClickWithNoClassificationTitleOrIntermediateDialog() {
        ready()
        assertEquals(context.getString(R.string.nbw_forum_ai), summary())
        val previousDialog = ShadowDialog.getLatestDialog()
        openMenu()
        compose.onNodeWithText(context.getString(R.string.nbw_forum_ai)).assertIsDisplayed()
        compose.onNodeWithText("分享").assertIsDisplayed()
        compose.onNodeWithText("闲聊").assertIsDisplayed()
        assertNoSettingsInMenu()
        assertSame("Classification is an in-editor overlay, never a ComponentDialog", previousDialog, ShadowDialog.getLatestDialog())
        compose.runOnUiThread { assertNoPublishingOrRecommendation(); assertEquals(0, calls(CONSENT)) }
    }

    @Test fun actualTwoLevelMenuSelectsCompleteLegalPairAndAiIsAnOrdinaryLeaf() {
        ready()
        openMenu()
        compose.onNodeWithText(context.getString(R.string.nbw_forum_none)).assertDoesNotExist()
        compose.onNodeWithText("分享").performClick()
        settle()
        compose.onNodeWithText("管理").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.nbw_v2_no_type)).assertDoesNotExist()
        clickPlacedLeaf("经验分享")
        settle()
        compose.runOnUiThread {
            assertEquals(NBWSelection.manual(27, 41), repository.snapshot.selection)
            assertEquals("分享 / 经验分享", summary())
            assertTrue(repository.snapshot.selection.isLegal(repository.snapshot.options))
            assertEquals(0, calls(CONSENT))
        }
        openMenu()
        compose.onNodeWithText(context.getString(R.string.nbw_forum_ai)).performClick()
        settle()
        compose.runOnUiThread {
            assertEquals(NBWSelection.ai(), repository.snapshot.selection)
            assertEquals(context.getString(R.string.nbw_forum_ai), summary())
            assertNoPublishingOrRecommendation()
            assertEquals(0, calls(CONSENT))
        }
    }

    @Test fun optionalZeroHasExplicitNoSubtypeLeafWhileRequiredAndModeratorOnlyForumsNeverOfferIt() {
        ready(optionsWithOptionalAndModeratorOnlyForums())
        openMenu()
        compose.onNodeWithText("仅管理").assertDoesNotExist()
        compose.onNodeWithText("可选").performClick()
        settle()
        compose.onNodeWithText("管理").assertDoesNotExist()
        assertEquals("无子分类", context.getString(R.string.nbw_v2_no_type))
        compose.onNodeWithText("不指定分类").assertDoesNotExist()
        clickPlacedLeaf(context.getString(R.string.nbw_v2_no_type))
        settle()
        compose.runOnUiThread {
            assertEquals(NBWSelection.manual(26, 0), repository.snapshot.selection)
            assertEquals("可选 / 无子分类", summary())
            assertFalse(repository.select(NBWSelection.manual(27, 0)))
            assertEquals(NBWSelection.manual(26, 0), repository.snapshot.selection)
        }
        openMenu()
        compose.onNodeWithText("分享").performClick()
        settle()
        compose.onNodeWithText("无子分类").assertDoesNotExist()
        compose.onNodeWithText("管理").assertDoesNotExist()
        clickPlacedLeaf("经验分享")
        settle()
        assertEquals(NBWSelection.manual(27, 41), repository.snapshot.selection)
    }

    @Test fun forumWithoutTypesStillOpensItsExplicitZeroLeafRatherThanSelectingTheParent() {
        ready()
        openMenu()
        compose.onNodeWithText("闲聊").performClick()
        settle()
        assertEquals(NBWSelection.ai(), repository.snapshot.selection)
        clickPlacedLeaf("无子分类")
        settle()
        assertEquals(NBWSelection.manual(18, 0), repository.snapshot.selection)
        assertEquals("闲聊 / 无子分类", summary())
    }

    @Test fun nativeBackCollapsesNestedMenuBeforeDismissingRootAndNeverDiscardsTheEditor() {
        ready()
        openMenu()
        compose.onNodeWithText("分享").performClick()
        settle()
        placedLeaf("经验分享").assertIsDisplayed()
        compose.runOnUiThread { activity.onBackPressed() }
        settle()
        compose.onNodeWithText("经验分享").assertDoesNotExist()
        compose.onNodeWithText("分享").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.nbw_forum_ai)).assertIsDisplayed()
        compose.runOnUiThread { activity.onBackPressed() }
        settle()
        compose.onNodeWithText("分享").assertDoesNotExist()
        assertTrue(fragment.isAdded)
        assertTrue(anchor.isShown)
        assertEquals(0, calls(CONSENT))
    }

    @Test fun firstFallbackConsentIsASeparateCompactSheetAndPutRequiresAuthenticatedReadback() {
        ready()
        val sheet = openFallbackSheet()
        assertFalse(repository.snapshot.hasFallbackConsent())
        assertEquals(NBWSelection.ai(), repository.snapshot.selection)
        assertEquals(0, calls(CONSENT))
        compose.runOnUiThread {
            assertTrue(sheet.findViewById<Button>(R.id.nbw_fallback_accept).performClick())
            assertFalse(sheet.findViewById<Button>(R.id.nbw_fallback_accept).isEnabled)
            assertEquals(1, calls(CONSENT))
            val request = ComposeReplyPublishingBehaviorTest.lastAppRequest(CONSENT)
            assertEquals("PUT", request.method)
            val wire = ComposeReplyPublishingBehaviorTest.json(request)
            assertTrue(wire.get("accepted").asBoolean)
            assertEquals(1, wire.get("policy_version").asInt)
            assertEquals(2, wire.size())
            assertFalse(repository.snapshot.hasFallbackConsent())
            ComposeReplyPublishingBehaviorTest.callback(request).onSuccess(Any())
            assertEquals(2, calls(CAPS))
            assertFalse("PUT success cannot fabricate account consent", repository.snapshot.hasFallbackConsent())
        }
        deliver(CAPS, NBWV2RepositoryTest.capabilities("full").apply { fallback.consented = true })
        assertEquals(context.getString(R.string.nbw_v2_fallback_accepted), sheet.findViewById<TextView>(R.id.nbw_fallback_status).text.toString())
        assertFalse(sheet.findViewById<Button>(R.id.nbw_fallback_accept).isEnabled)
        assertEquals(NBWSelection.ai(), repository.snapshot.selection)
        assertEquals(1, calls(CONSENT))
        assertNoPublishingOrRecommendation()
    }

    @Test fun decliningFallbackIsAnActualFalseRequestAndNeverChangesTheManualPair() {
        ready()
        openMenu()
        compose.onNodeWithText("闲聊").performClick()
        settle()
        clickPlacedLeaf("无子分类")
        settle()
        val sheet = openFallbackSheet()
        compose.runOnUiThread {
            assertTrue(sheet.findViewById<Button>(R.id.nbw_fallback_decline).performClick())
            assertEquals(NBWSelection.manual(18, 0), repository.snapshot.selection)
            assertEquals(1, calls(CONSENT))
            val request = ComposeReplyPublishingBehaviorTest.lastAppRequest(CONSENT)
            assertFalse(ComposeReplyPublishingBehaviorTest.json(request).get("accepted").asBoolean)
            ComposeReplyPublishingBehaviorTest.callback(request).onSuccess(Any())
        }
        deliver(CAPS, NBWV2RepositoryTest.capabilities("full"))
        assertFalse(repository.snapshot.hasFallbackConsent())
        assertEquals(NBWSelection.manual(18, 0), repository.snapshot.selection)
        assertNoPublishingOrRecommendation()
    }

    @Test fun dismissingConsentWithoutChoosingNeverImplicitlyAcceptsOrChangesClassification() {
        ready()
        val sheet = openFallbackSheet()
        compose.runOnUiThread { sheet.findViewById<View>(R.id.nbw_fallback_done).performClick() }
        settle()
        assertFalse(sheet.isShowing)
        assertFalse(repository.snapshot.hasFallbackConsent())
        assertEquals(NBWSelection.ai(), repository.snapshot.selection)
        assertEquals(0, calls(CONSENT))
        openMenu()
        compose.onNodeWithText(context.getString(R.string.nbw_forum_ai)).assertIsDisplayed()
    }

    @Test fun consentReadbackFalseRemainsAuthoritativeEvenAfterSuccessfulAcceptPut() {
        ready()
        val sheet = openFallbackSheet()
        compose.runOnUiThread {
            sheet.findViewById<Button>(R.id.nbw_fallback_accept).performClick()
            ComposeReplyPublishingBehaviorTest.callback(ComposeReplyPublishingBehaviorTest.lastAppRequest(CONSENT)).onSuccess(Any())
        }
        deliver(CAPS, NBWV2RepositoryTest.capabilities("full"))
        assertFalse(repository.snapshot.hasFallbackConsent())
        assertEquals(context.getString(R.string.nbw_v2_fallback_not_accepted), sheet.findViewById<TextView>(R.id.nbw_fallback_status).text.toString())
        assertTrue(sheet.findViewById<Button>(R.id.nbw_fallback_accept).isEnabled)
        assertNoPublishingOrRecommendation()
    }

    @Test fun missingCatalogStillOffersOnlyAiAndNonWriteModesHaveNoEntryOrDialog() {
        compose.runOnUiThread { repository.activate() }
        deliver(CAPS, NBWV2RepositoryTest.capabilities("full"))
        openMenu()
        compose.onNodeWithText(context.getString(R.string.nbw_forum_ai)).assertIsDisplayed()
        compose.onNodeWithText("分享").assertDoesNotExist()
        compose.onNodeWithText("闲聊").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.nbw_forum_none)).assertDoesNotExist()
        compose.runOnUiThread { activity.onBackPressed() }
        settle()
        for (mode in listOf("read_only", "local_only")) {
            compose.runOnUiThread { repository.refreshNow() }
            deliver(CAPS, NBWV2RepositoryTest.capabilities(mode))
            assertEquals(View.GONE, anchor.visibility)
            // Dispatch the old native listener too: capability loss is enforced, not only hidden.
            compose.runOnUiThread { anchor.performClick() }
            settle()
            compose.onNodeWithText(context.getString(R.string.nbw_forum_ai)).assertDoesNotExist()
            assertNoSettingsInMenu()
        }
        assertNoPublishingOrRecommendation()
    }

    @Test fun frozenExpandedTreeRevalidatesTheStaleLeafAndNeverSubstitutesAnotherManualPair() {
        ready()
        openMenu()
        compose.onNodeWithText("分享").performClick()
        settle()
        clickPlacedLeaf("经验分享")
        settle()
        assertEquals(NBWSelection.manual(27, 41), repository.snapshot.selection)
        openMenu()
        compose.runOnUiThread {
            ShadowSystemClock.advanceBy(Duration.ofMillis(NBWForumOptionsRepository.TTL_MS))
            repository.refreshNow()
        }
        deliver(CAPS, NBWV2RepositoryTest.capabilities("full"))
        deliver(OPTIONS, NBWV2RepositoryTest.options().apply { configRevision = "tree-b"; forums = listOf(forums[0]) })
        // Frozen root and child are actual visible old rows; click them, never repository.select().
        compose.onNodeWithText("分享").performClick()
        settle()
        clickPlacedLeaf("经验分享")
        settle()
        assertEquals(context.getString(R.string.nbw_v2_selection_expired), ShadowToast.getTextOfLatestToast())
        assertEquals(NBWSelection.manual(27, 41), repository.snapshot.selection)
        assertEquals(context.getString(R.string.nbw_v2_selection_expired), summary())
        assertEquals(0, calls(CONSENT))
        openMenu()
        compose.onNodeWithText("分享").assertDoesNotExist()
        compose.onNodeWithText("闲聊").assertIsDisplayed()
        assertNoPublishingOrRecommendation()
    }

    @Test fun repositoryGenerationChangeClosesMenuAndCancelsInFlightConsentWithoutLateUiWrites() {
        ready()
        openMenu()
        compose.runOnUiThread { repository.refreshNow() }
        deliver(CAPS, NBWV2RepositoryTest.capabilities("read_only"))
        compose.onNodeWithText("分享").assertDoesNotExist()
        assertEquals(View.GONE, anchor.visibility)
        compose.runOnUiThread { repository.refreshNow() }
        deliver(CAPS, NBWV2RepositoryTest.capabilities("full"))
        deliver(OPTIONS, NBWV2RepositoryTest.options())
        val sheet = openFallbackSheet()
        compose.runOnUiThread {
            sheet.findViewById<Button>(R.id.nbw_fallback_accept).performClick()
            val oldRequest = ComposeReplyPublishingBehaviorTest.lastAppRequest(CONSENT)
            val generation = repository.snapshot.generation
            repository.deactivate()
            assertTrue(repository.snapshot.generation > generation)
            // Session repository observers are posted to the UI queue, even from this thread.
            ComposeReplyPublishingBehaviorTest.callback(oldRequest).onSuccess(Any())
            assertFalse(repository.snapshot.hasFallbackConsent())
        }
        settle()
        assertFalse(sheet.isShowing)
        assertNoPublishingOrRecommendation()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun actualEditorMenuAndSeparateConsentSheetRenderInBothAppThemes() {
        ready()
        for ((theme, name) in listOf(R.style.Theme_Mastodon_Light to "light", R.style.Theme_Mastodon_Dark to "dark")) {
            compose.runOnUiThread {
                ShadowChoreographer.setPaused(true)
                GlobalUserPreferences.theme = if (name == "dark") GlobalUserPreferences.ThemePreference.DARK else GlobalUserPreferences.ThemePreference.LIGHT
                RuntimeEnvironment.setQualifiers("en-w393dp-h852dp-${if (name == "dark") "night" else "notnight"}-mdpi")
                fragment = editor.launchClassificationEditor(theme)
                ShadowChoreographer.setPaused(false)
                ShadowLooper.idleMainLooper(32, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            openMenu()
            capture(activity.window.decorView, "menu-primary-$name")
            compose.onNodeWithText("分享").performClick()
            settle()
            placedLeaf("经验分享").assertIsDisplayed()
            capture(activity.window.decorView, "menu-nested-$name")
            compose.runOnUiThread { activity.onBackPressed() }
            settle()
            compose.runOnUiThread { activity.onBackPressed() }
            settle()
            val sheet = openFallbackSheet()
            capture(sheet.window!!.decorView, "fallback-sheet-$name")
            compose.runOnUiThread { sheet.dismissWithoutAnimation() }
            settle()
        }
    }

    private fun ready(options: NBWForumOptions = NBWV2RepositoryTest.options()) {
        compose.runOnUiThread { repository.activate() }
        deliver(CAPS, NBWV2RepositoryTest.capabilities("full"))
        deliver(OPTIONS, options)
        compose.runOnUiThread {
            assertEquals(NBWForumOptionsRepository.State.READY, repository.snapshot.state)
            assertFalse(repository.snapshot.capabilities.hasGrant()) // Missing grant is not a binding gate.
            assertEquals(0, calls(CONSENT))
            assertTrue(anchor.isShown)
        }
    }

    private fun openMenu() {
        compose.runOnUiThread {
            assertTrue("The actual Java ComposeFragment button must be attached", anchor.isAttachedToWindow)
            assertTrue("Dispatch the real btn_visibility listener once", anchor.performClick())
        }
        settle()
        compose.onNodeWithText(context.getString(R.string.nbw_forum_ai)).assertIsDisplayed()
        assertNoSettingsInMenu()
    }

    private fun assertNoSettingsInMenu() {
        compose.onNodeWithText(context.getString(R.string.nbw_v2_classification_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.nbw_v2_fallback_explanation)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.nbw_v2_fallback_accept)).assertDoesNotExist()
    }

    private fun openFallbackSheet(): NBWFallbackConsentSheet {
        compose.runOnUiThread {
            assertTrue(fragment.view!!.findViewById<View>(R.id.newbabyworld_card_action).performClick())
        }
        settle()
        val sheet = ShadowDialog.getLatestDialog()
        assertTrue("Account consent uses the existing AppKit BottomSheet, not ComponentDialog", sheet is NBWFallbackConsentSheet)
        sheet as NBWFallbackConsentSheet
        assertTrue(sheet.isShowing)
        val content = sheet.findViewById<View>(R.id.nbw_fallback_sheet)
        assertNotNull(content)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, content.layoutParams.height)
        assertTrue("Normal phone consent content is compact", content.height > 0 && content.height < activity.window.decorView.height * .85f)
        compose.onNodeWithText("分享").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.nbw_v2_classification_title)).assertDoesNotExist()
        return sheet
    }

    private fun deliver(path: String, value: Any) {
        compose.runOnUiThread {
            ComposeReplyPublishingBehaviorTest.callback(ComposeReplyPublishingBehaviorTest.lastAppRequest(path)).onSuccess(value)
        }
        settle()
    }

    private fun clickPlacedLeaf(text: String) { placedLeaf(text).assertIsDisplayed().assertIsEnabled().performClick() }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun placedLeaf(text: String) = compose.onNode(hasText(text) and SemanticsMatcher("placed cascading leaf, excluding secondary_probe") { node ->
        val root = semanticsRoot(node)
        node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0 && root.boundsInRoot.contains(node.boundsInRoot.center) &&
            generateSequence(node.layoutInfo) { it.parentInfo }.all { it.isAttached && it.isPlaced }
    })

    private fun semanticsRoot(node: SemanticsNode): SemanticsNode = generateSequence(node) { it.parent }.last()

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        ShadowLooper.idleMainLooper()
    }

    private fun capture(view: View, name: String) {
        lateinit var image: Bitmap
        compose.runOnUiThread {
            view.getViewTreeObserver().dispatchOnPreDraw()
            assertTrue(view.isAttachedToWindow && view.width > 0 && view.height > 0)
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
        }
        val directory = File("/tmp/abdl-nbw-v2-menu-renders").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }

    private fun optionsWithOptionalAndModeratorOnlyForums() = NBWV2RepositoryTest.options().apply {
        val optional = NBWForumOptions.Forum().apply {
            fid = 26; name = "可选"; required = false
            types = listOf(NBWForumOptions.Type().apply { typeid = 61; name = "日常"; moderatorOnly = false },
                NBWForumOptions.Type().apply { typeid = 62; name = "管理"; moderatorOnly = true })
        }
        val moderatorOnly = NBWForumOptions.Forum().apply {
            fid = 28; name = "仅管理"; required = true
            types = listOf(NBWForumOptions.Type().apply { typeid = 71; name = "管理"; this.moderatorOnly = true })
        }
        forums = forums + listOf(optional, moderatorOnly)
    }

    private fun summary() = fragment.view!!.findViewById<TextView>(R.id.visibility_text1).text.toString()
    private fun calls(path: String) = ComposeReplyPublishingBehaviorTest.appCount(path)
    private fun assertNoPublishingOrRecommendation() {
        assertEquals(0, ComposeReplyPublishingBehaviorTest.count(CreateStatus::class.java))
        assertEquals(0, ComposeReplyPublishingBehaviorTest.count(RecommendNBWForum::class.java))
        assertTrue(ComposeReplyPublishingBehaviorTest.LocalRequests.accounts.all { it == editor.classificationSession().getID() })
    }

    companion object {
        private const val CAPS = "/api/app/capabilities"
        private const val OPTIONS = "/api/app/nbw/options"
        private const val CONSENT = "/api/app/nbw/fallback-consent"
    }
}
