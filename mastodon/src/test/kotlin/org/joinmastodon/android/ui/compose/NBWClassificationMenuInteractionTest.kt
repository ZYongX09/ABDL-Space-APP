package org.joinmastodon.android.ui.compose

import android.content.Context
import android.view.View
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.viewinterop.AndroidView
import me.grishka.appkit.utils.V
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.R
import org.joinmastodon.android.api.CacheController
import org.joinmastodon.android.api.NBWV2RepositoryTest
import org.joinmastodon.android.api.requests.nbw.NBWAppRequest
import org.joinmastodon.android.api.requests.nbw.RecommendNBWForum
import org.joinmastodon.android.api.requests.statuses.CreateStatus
import org.joinmastodon.android.api.session.AccountSession
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.api.session.NBWForumOptionsRepository
import org.joinmastodon.android.fragments.ComposeReplyPublishingBehaviorTest
import org.joinmastodon.android.model.Account
import org.joinmastodon.android.model.NBWSelection
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual production ComponentDialog, cascading menu and consent button clicks.
 * Only request transport is intercepted. No private controller methods/fields or fake UI.
 * These are Robolectric Compose interactions, not emulator/device acceptance evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28], application = CompatibilityTestApplication::class, qualifiers = "en",
    shadows = [ComposeReplyPublishingBehaviorTest.LocalRequests::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class NBWClassificationMenuInteractionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context
    private lateinit var session: AccountSession
    private lateinit var sessions: MutableMap<String, AccountSession>
    private var previousSession: AccountSession? = null
    private lateinit var repository: NBWForumOptionsRepository
    private lateinit var menu: NBWClassificationController
    private var changes = 0

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        MastodonApp.context = context
        V.setApplicationContext(context)
        val constructor = AccountSession::class.java.getDeclaredConstructor().apply { isAccessible = true }
        session = constructor.newInstance().apply {
            domain = "nbw-menu.offline.example.test"
            self = Account().apply { id = "42"; username = "offline"; acct = "offline" }
        }
        session.getRawLocalPreferences().edit().clear().commit()
        // Register a real offline session, not a binding/capability field substitute.
        val sessionField = AccountSessionManager::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val sessionMap = sessionField.get(AccountSessionManager.getInstance()) as MutableMap<String, AccountSession>
        sessions = sessionMap
        previousSession = sessions.put(session.getID(), session)
        repository = session.getNBWForumOptionsRepository()
        ComposeReplyPublishingBehaviorTest.LocalRequests.sent.clear()
        ComposeReplyPublishingBehaviorTest.LocalRequests.accounts.clear()
        compose.setContent {
            AndroidView(factory = { host ->
                menu = NBWClassificationController(host, repository, Runnable { changes++ })
                View(host)
            })
        }
        compose.waitForIdle()
    }

    @After fun tearDown() {
        compose.runOnIdle {
            if (::menu.isInitialized) menu.dispose()
            if (::repository.isInitialized) repository.deactivate()
        }
        if (::session.isInitialized) {
            // deactivate() enqueues a real cache invalidation. Keep the session/context alive
            // until a FIFO database-thread barrier has drained it and closed this fixture DB.
            val drained = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            CacheController.databaseThread.postRunnable({
                try {
                    session.getCacheController().closeDatabase()
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    drained.countDown()
                }
            }, 0)
            assertTrue("Fixture database invalidations must finish before context teardown", drained.await(10, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Fixture database close failed", it) }
            session.getRawLocalPreferences().edit().clear().commit()
            if (previousSession == null) sessions.remove(session.getID())
            else sessions[session.getID()] = previousSession!!
        }
        ComposeReplyPublishingBehaviorTest.LocalRequests.sent.clear()
        ComposeReplyPublishingBehaviorTest.LocalRequests.accounts.clear()
        MastodonApp.context = null
    }

    @Test fun actualTwoLevelMenuSelectsTheCompleteLegalPairAndHasNoNoSyncOrModeratorOption() {
        ready()
        show()
        expand(context.getString(R.string.nbw_forum_ai))
        compose.onNodeWithText(context.getString(R.string.nbw_forum_none)).assertDoesNotExist()
        compose.onNodeWithText("分享").performClick()
        settle()
        compose.onNodeWithText("管理").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.nbw_v2_no_type)).assertDoesNotExist()
        clickPlacedDialogLeaf("经验分享")
        settle()
        compose.runOnIdle {
            assertEquals(NBWSelection.manual(27, 41), repository.snapshot.selection)
            assertEquals(1, changes)
            assertEquals(0, calls(CONSENT))
            assertTrue(repository.snapshot.selection.isLegal(repository.snapshot.options))
        }
        compose.onNodeWithText("分享 / 经验分享").assertExists()
        // AI is an ordinary leaf, not a recommendation request or implicit fallback acceptance.
        expand("分享 / 经验分享")
        compose.onNodeWithText(context.getString(R.string.nbw_forum_ai)).performClick()
        settle()
        compose.runOnIdle {
            assertEquals(NBWSelection.ai(), repository.snapshot.selection)
            assertEquals(2, changes)
            assertNoPublishingOrRecommendation()
            assertEquals(0, calls(CONSENT))
        }
    }

    @Test fun firstFallbackConsentIsAnIndependentExplicitClickAndPutMustBeConfirmedByCapabilities() {
        ready()
        show()
        compose.runOnIdle {
            assertFalse(repository.snapshot.hasFallbackConsent())
            assertEquals(NBWSelection.ai(), repository.snapshot.selection)
            assertEquals(0, calls(CONSENT))
        }
        compose.onNodeWithText(context.getString(R.string.nbw_v2_fallback_accept))
            .performScrollTo().assertIsEnabled().performClick()
        settle()
        compose.runOnIdle {
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
            assertFalse("A successful PUT is not locally fabricated authenticated consent", repository.snapshot.hasFallbackConsent())
        }
        deliver(CAPS, NBWV2RepositoryTest.capabilities("full").apply { fallback.consented = true })
        compose.onNodeWithText(context.getString(R.string.nbw_v2_fallback_accepted)).assertExists()
        compose.onNodeWithText(context.getString(R.string.nbw_v2_fallback_accept)).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(NBWSelection.ai(), repository.snapshot.selection)
            assertEquals(0, changes)
            assertEquals(1, calls(CONSENT))
            assertNoPublishingOrRecommendation() // Consent never replays a draft or old operation.
        }
    }

    @Test fun decliningFallbackNeverMeansNoSyncAndDoesNotChangeManualClassification() {
        ready()
        show()
        expand(context.getString(R.string.nbw_forum_ai))
        compose.onNodeWithText("闲聊").performClick()
        settle()
        compose.onNodeWithText(context.getString(R.string.nbw_v2_fallback_decline))
            .performScrollTo().performClick()
        settle()
        compose.runOnIdle {
            assertEquals(NBWSelection.manual(18, 0), repository.snapshot.selection)
            assertEquals(1, changes)
            assertEquals(1, calls(CONSENT))
            val request = ComposeReplyPublishingBehaviorTest.lastAppRequest(CONSENT)
            assertFalse(ComposeReplyPublishingBehaviorTest.json(request).get("accepted").asBoolean)
            ComposeReplyPublishingBehaviorTest.callback(request).onSuccess(Any())
        }
        deliver(CAPS, NBWV2RepositoryTest.capabilities("full"))
        compose.runOnIdle {
            assertFalse(repository.snapshot.hasFallbackConsent())
            assertEquals(NBWSelection.manual(18, 0), repository.snapshot.selection)
            assertNoPublishingOrRecommendation()
        }
    }

    @Test fun missingOptionsHasNoInventedManualRowsAndNonWriteModesHaveNoClassificationDialog() {
        compose.runOnIdle { repository.activate() }
        deliver(CAPS, NBWV2RepositoryTest.capabilities("full"))
        show()
        compose.onNodeWithText(context.getString(R.string.nbw_v2_options_unavailable)).assertExists()
        expand(context.getString(R.string.nbw_forum_ai))
        compose.onNodeWithText("分享").assertDoesNotExist()
        compose.onNodeWithText("闲聊").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.nbw_forum_none)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(NBWSelection.ai(), repository.snapshot.selection)
            assertEquals(0, changes)
            menu.dismiss()
        }
        for (mode in listOf("read_only", "local_only")) {
            compose.runOnIdle { repository.refreshNow() }
            deliver(CAPS, NBWV2RepositoryTest.capabilities(mode))
            compose.runOnIdle { menu.show() }
            settle()
            compose.onNodeWithText(context.getString(R.string.nbw_v2_classification_title)).assertDoesNotExist()
        }
        compose.runOnIdle { assertNoPublishingOrRecommendation() }
    }

    @Test fun staleExpandedLeafIsRevalidatedAgainstTheNewAuthenticatedOptions() {
        ready()
        show()
        expand(context.getString(R.string.nbw_forum_ai))
        compose.runOnIdle {
            ShadowSystemClock.advanceBy(Duration.ofMillis(NBWForumOptionsRepository.TTL_MS))
            repository.refreshNow()
        }
        deliver(CAPS, NBWV2RepositoryTest.capabilities("full"))
        val replacement = NBWV2RepositoryTest.options().apply {
            configRevision = "tree-b"
            forums = listOf(forums[0])
        }
        deliver(OPTIONS, replacement)
        // The open menu is deliberately displaying its frozen old tree. Click it, do not invoke select().
        compose.onNodeWithText("分享").performClick()
        settle()
        clickPlacedDialogLeaf("经验分享")
        settle()
        compose.onNodeWithText(context.getString(R.string.nbw_v2_selection_expired)).assertExists()
        compose.runOnIdle {
            assertEquals(NBWSelection.ai(), repository.snapshot.selection)
            assertEquals(0, changes)
            assertEquals(0, calls(CONSENT))
            assertNoPublishingOrRecommendation()
        }
    }

    @Test fun losingWriteCapabilityDismissesAnAlreadyOpenProductionDialog() {
        ready()
        show()
        compose.runOnIdle { repository.refreshNow() }
        deliver(CAPS, NBWV2RepositoryTest.capabilities("read_only"))
        compose.onNodeWithText(context.getString(R.string.nbw_v2_classification_title)).assertDoesNotExist()
        compose.runOnIdle { assertNoPublishingOrRecommendation() }
    }

    private fun ready() {
        compose.runOnIdle { repository.activate() }
        deliver(CAPS, NBWV2RepositoryTest.capabilities("full"))
        deliver(OPTIONS, NBWV2RepositoryTest.options())
        compose.runOnIdle {
            assertEquals(NBWForumOptionsRepository.State.READY, repository.snapshot.state)
            assertFalse(repository.snapshot.capabilities.hasGrant()) // Unbound/missing is real metadata, not a fake binding gate.
            assertEquals(0, calls(CONSENT))
        }
    }

    private fun show() {
        compose.runOnIdle { menu.show() }
        settle()
        compose.onNodeWithText(context.getString(R.string.nbw_v2_classification_title)).assertExists()
    }

    private fun expand(summary: String) {
        compose.onNodeWithText(summary).performClick()
        settle()
    }

    private fun deliver(path: String, value: Any) {
        compose.runOnIdle {
            val request = ComposeReplyPublishingBehaviorTest.lastAppRequest(path)
            assertTrue(request is NBWAppRequest<*>)
            ComposeReplyPublishingBehaviorTest.callback(request).onSuccess(value)
        }
        settle()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun clickPlacedDialogLeaf(text: String) {
        val title = compose.onNodeWithText(context.getString(R.string.nbw_v2_classification_title))
            .fetchSemanticsNode()
        val dialogRoot = semanticsRoot(title)
        // miuix CascadingListPopupLayout measures secondary_probe with the same row content
        // as secondary, but only places secondary. Checking the entire LayoutInfo ancestry
        // excludes that permanently unplaced measurement copy, not an arbitrary first/last
        // match. The overlay is inside this ComponentDialog's Scaffold, not a native Popup.
        val placedInDialog = SemanticsMatcher("placed leaf in the classification dialog root") { node ->
            semanticsRoot(node).id == dialogRoot.id &&
                node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0 &&
                dialogRoot.boundsInRoot.contains(node.boundsInRoot.center) &&
                generateSequence(node.layoutInfo) { it.parentInfo }.all { it.isAttached && it.isPlaced }
        }
        val leaf = hasText(text) and placedInDialog
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(leaf).fetchSemanticsNodes().size == 1
        }
        // onNode still enforces uniqueness; performClick injects a real touch, never a
        // private selection callback or a click on the probe's no-op measurement handler.
        compose.onNode(leaf).assertIsDisplayed().assertIsEnabled().performClick()
    }

    private fun semanticsRoot(node: SemanticsNode): SemanticsNode =
        generateSequence(node) { it.parent }.last()

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun calls(path: String) = ComposeReplyPublishingBehaviorTest.appCount(path)
    private fun assertNoPublishingOrRecommendation() {
        assertEquals(0, ComposeReplyPublishingBehaviorTest.count(CreateStatus::class.java))
        assertEquals(0, ComposeReplyPublishingBehaviorTest.count(RecommendNBWForum::class.java))
        assertTrue(ComposeReplyPublishingBehaviorTest.LocalRequests.accounts.all { it == session.getID() })
    }

    companion object {
        private const val CAPS = "/api/app/capabilities"
        private const val OPTIONS = "/api/app/nbw/options"
        private const val CONSENT = "/api/app/nbw/fallback-consent"
    }
}
