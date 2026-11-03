package org.joinmastodon.android.novel.app

import android.os.Bundle
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import org.joinmastodon.android.api.MastodonAPIController
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.api.HomeTimelineSnapshotCacheTest
import org.joinmastodon.android.api.session.AccountSession
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.security.AppGatekeeper
import org.joinmastodon.android.security.AppSecurity
import org.joinmastodon.android.security.AuthTracker
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.ui.FakeSecurityRepository
import org.joinmastodon.android.security.ui.securityState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real Activity.onCreate/onSaveInstanceState while the real gate remains locked; no onCreate
 * bypass, premature bind, server request or security gate shadow. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelCreatorPendingRestoreTest {
    private val children = mutableListOf<ActivityController<NovelAppActivity>>()
    private lateinit var session: AccountSession
    private lateinit var accounts: MutableMap<String, AccountSession>
    private var previousSession: AccountSession? = null
    private var previousActive: String? = null
    private var oldTracker: Any? = null
    private var oldGate: Any? = null
    private var oldHttp: OkHttpClient? = null
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var gate: AppGatekeeper
    private val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
    private fun field(type: Class<*>, name: String) = HomeTimelineSnapshotCacheTest.field(type, name)

    @Before @Suppress("UNCHECKED_CAST") fun setUp() {
        Dispatchers.setMain(dispatcher)
        MastodonApp.context = RuntimeEnvironment.getApplication()
        oldHttp = MastodonAPIController.getHttpClient()
        val offline = requireNotNull(oldHttp).newBuilder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.method() + " " + request.url().encodedPath()
            val json = if (request.url().encodedPath().endsWith("/creator")) NovelAppTestFixtures.creatorJson() else NovelAppTestFixtures.page()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Offline fixture")
                .body(ResponseBody.create(MediaType.parse("application/json"), json.toString())).build()
        }.build()
        field(MastodonAPIController::class.java, "httpClient").set(null, offline)
        val manager = AccountSessionManager.getInstance()
        accounts = field(AccountSessionManager::class.java, "sessions").get(manager) as MutableMap<String, AccountSession>
        session = HomeTimelineSnapshotCacheTest.session("creator-restore.offline.example.test", "creator-restore-42")
        previousSession = accounts.put(session.id, session)
        previousActive = manager.lastActiveAccountID
        field(AccountSessionManager::class.java, "lastActiveAccountID").set(manager, session.id)
        oldTracker = field(AppSecurity::class.java, "trackerInstance").get(null)
        oldGate = field(AppSecurity::class.java, "gatekeeper").get(null)
        val tracker = AuthTracker(FakeSecurityRepository(SecurityResult.Success(securityState(LockMethod.Pin))))
        tracker.onAppCreate()
        field(AppSecurity::class.java, "trackerInstance").set(null, tracker)
        gate = AppGatekeeper(RuntimeEnvironment.getApplication(), tracker, dispatcher, dispatcher, {})
        field(AppSecurity::class.java, "gatekeeper").set(null, gate)
        assertTrue(AppSecurity.isLocked())
    }

    @After fun tearDown() {
        children.asReversed().forEach { controller ->
            model(controller)?.unbindAccount()
            gate.onActivityDestroyed(controller.get())
            if (!controller.get().isDestroyed) controller.close()
        }
        field(MastodonAPIController::class.java, "httpClient").set(null, oldHttp)
        accounts.remove(session.id)
        previousSession?.let { accounts[session.id] = it }
        field(AccountSessionManager::class.java, "lastActiveAccountID").set(AccountSessionManager.getInstance(), previousActive)
        field(AppSecurity::class.java, "trackerInstance").set(null, oldTracker)
        field(AppSecurity::class.java, "gatekeeper").set(null, oldGate)
        Dispatchers.resetMain()
    }

    private fun open(saved: Bundle?): ActivityController<NovelAppActivity> {
        val entry = NovelAppActivity.creatorIntent(RuntimeEnvironment.getApplication(), session.id)
        return Robolectric.buildActivity(NovelAppActivity::class.java, entry).also { controller ->
            children += controller
            controller.create(saved)
            assertFalse(controller.get().isFinishing)
            assertNull("No model/page is bound until the original unlock gate delivers", field(NovelAppActivity::class.java, "model").get(controller.get()))
            assertNotNull(controller.get().window.decorView.findViewWithTag<View>("abdl_security_shield"))
        }
    }

    private fun model(controller: ActivityController<NovelAppActivity>) =
        field(NovelAppActivity::class.java, "model").get(controller.get()) as NovelAppViewModel?

    @Test fun actualActivitySaveDestroyFreshVmRestoreWaitsForUnlockAndKeepsShelf() {
        val original = open(null)
        original.start().resume()
        gate.onActivityResumed(original.get()); gate.onUnlocked()
        val first = requireNotNull(model(original))
        assertEquals(NovelPage.Creator, first.state.value.page)
        first.navigate(NovelPage.Shelf)
        original.pause(); gate.onActivityPaused(original.get())
        val saved = Bundle(); original.saveInstanceState(saved)
        gate.onActivityDestroyed(original.get()); original.stop().destroy()
        val tracker = AuthTracker(FakeSecurityRepository(SecurityResult.Success(securityState(LockMethod.Pin))))
        tracker.onAppCreate()
        gate = AppGatekeeper(RuntimeEnvironment.getApplication(), tracker, dispatcher, dispatcher, {})
        field(AppSecurity::class.java, "trackerInstance").set(null, tracker)
        field(AppSecurity::class.java, "gatekeeper").set(null, gate)
        val restored = open(saved)
        restored.start().resume(); gate.onActivityResumed(restored.get())
        assertNull("Process-restored descriptor must not bind private pages while locked", model(restored))
        val savedAgain = Bundle(); restored.saveInstanceState(savedAgain)
        assertEquals(listOf(NovelPage.Shelf), restoreNovelAppNavigation(savedAgain.getBundle(NOVEL_NAVIGATION_STATE), session.id, restored.get().intent, false))
        gate.onUnlocked()
        val fresh = requireNotNull(model(restored))
        assertNotSame("Destroy then rebuild must not reuse the retained ViewModel", first, fresh)
        assertEquals(listOf(NovelPage.Shelf), fresh.state.value.pages)
        assertFalse(AppSecurity.isLocked())
        assertNull(restored.get().window.decorView.findViewWithTag<View>("abdl_security_shield"))
        assertTrue("Neither restoration nor unlock auto-applies creator", requests.none { it.endsWith("/creator/apply") })
    }

    @Test fun processRestoredShelfAndStatisticsDescriptorsSurviveAnotherLockedActivitySave() {
        val entry = NovelAppActivity.creatorIntent(RuntimeEnvironment.getApplication(), session.id)
        for (pages in listOf(listOf(NovelPage.Shelf), listOf(NovelPage.Creator, NovelPage.Statistics), listOf(NovelPage.Creator, NovelPage.OwnerWorks))) {
            val navigation = requireNotNull(saveNovelAppNavigation(NovelAppState(accountId = session.id, pages = pages), entry, false))
            val saved = Bundle().apply { putBundle(NOVEL_NAVIGATION_STATE, navigation) }
            val waiting = open(saved)
            val savedAgain = Bundle(); waiting.saveInstanceState(savedAgain)
            assertEquals(pages, restoreNovelAppNavigation(savedAgain.getBundle(NOVEL_NAVIGATION_STATE), session.id, entry, false))
            assertTrue(AppSecurity.isLocked())
        }
    }

    @Test fun freshCreatorWaitingForUnlockAlsoPersistsItsPendingEntryWithoutExecutingIt() {
        val waiting = open(null)
        val saved = Bundle(); waiting.saveInstanceState(saved)
        val entry = waiting.get().intent
        assertEquals(listOf(NovelPage.Creator), restoreNovelAppNavigation(saved.getBundle(NOVEL_NAVIGATION_STATE), session.id, entry, false))
        val restored = open(saved)
        val repeated = Bundle(); restored.saveInstanceState(repeated)
        assertEquals(listOf(NovelPage.Creator), restoreNovelAppNavigation(repeated.getBundle(NOVEL_NAVIGATION_STATE), session.id, entry, false))
    }
}
