package org.joinmastodon.android.novel.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.novel.app.models.Author
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual host entry selection + VM/repository eligibility; no grant/apply/editor shortcut. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class)
class NovelCreatorEntryTest {
    private val dispatcher = StandardTestDispatcher()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val account = "fictional-creator-account"
    private val self: Author = NovelAppTestFixtures.author
    private lateinit var transport: TestNovelTransport
    private lateinit var vm: NovelAppViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        transport = TestNovelTransport()
        vm = NovelAppViewModel({ NovelRepository(transport) }, MemoryNovelSearchHistory())
    }
    @After fun tearDown() { vm.unbindAccount(); Dispatchers.resetMain() }

    @Test fun publishShortcutIsTheCreatorHostWithoutWorkReaderOrLegacyFlags() = runTest(dispatcher) {
        val entry = NovelAppActivity.creatorIntent(context, account)
        assertEquals(NovelAppActivity::class.java.name, entry.component!!.className)
        assertEquals(account, entry.getStringExtra(NovelAppActivity.EXTRA_ACCOUNT))
        assertTrue(entry.getBooleanExtra(NovelAppActivity.EXTRA_CREATOR, false))
        assertNull(entry.getStringExtra(NovelAppActivity.EXTRA_WORK_ID))
        assertNull(entry.getStringExtra(NovelAppActivity.EXTRA_CHAPTER_ID))
        bindNovelAppEntry(vm, account, self, entry, false)
        runCurrent()
        assertEquals(listOf(NovelPage.Creator), vm.state.value.pages)
        assertNotNull(vm.state.value.creator.value)
        assertTrue(transport.requests.any { it.path == "/creator" })
        assertTrue(transport.requests.none { it.method != "GET" || it.path == "/owner/works" })
    }

    @Test fun nonCreatorStaysInOriginalEligibilityStateAndDoesNotAutoApply() = runTest(dispatcher) {
        transport.response = { _, path, _, _ ->
            if (path == "/creator") NovelAppTestFixtures.creatorJson(active = false, eligible = false)
            else NovelAppTestFixtures.page()
        }
        bindNovelAppEntry(vm, account, self, NovelAppActivity.creatorIntent(context, account), false)
        runCurrent()
        assertEquals(NovelPage.Creator, vm.state.value.page)
        assertFalse(vm.state.value.creator.value!!.active)
        assertFalse(vm.state.value.creator.value!!.eligible)
        vm.applyCreator(); runCurrent()
        assertTrue("The existing qualification gate still rejects explicit apply", transport.requests.none { it.path == "/creator/apply" })
    }

    @Test fun retainedNavigationOnRotationIsNotReplacedAndFreshProcessCanRestoreCreatorShortcut() = runTest(dispatcher) {
        val entry = NovelAppActivity.creatorIntent(context, account)
        bindNovelAppEntry(vm, account, self, entry, false); runCurrent()
        vm.navigate(NovelPage.Statistics); runCurrent()
        val before = vm.state.value.pages
        bindNovelAppEntry(vm, account, self, entry, false); runCurrent()
        assertEquals("Retained VM owns navigation after configuration recreation", before, vm.state.value.pages)
        assertEquals(1, vm.state.value.pages.count { it == NovelPage.Creator })
        vm.unbindAccount()
        bindNovelAppEntry(vm, account, self, entry, false); runCurrent()
        assertEquals(listOf(NovelPage.Creator), vm.state.value.pages)
    }

    @Test fun ordinaryStoreAndExternalDetailWorkAndReaderIntentsRetainTheirOriginalPriority() = runTest(dispatcher) {
        bindNovelAppEntry(vm, account, self, NovelAppActivity.intent(context, account), false)
        assertEquals(NovelPage.Store, vm.state.value.page)
        vm.unbindAccount()
        val work = NovelAppActivity.intent(context, account, "fixture-work-0", "chapter-1")
            .putExtra(NovelAppActivity.EXTRA_CREATOR, true)
        bindNovelAppEntry(vm, account, self, work, true)
        assertEquals(listOf(NovelPage.Store, NovelPage.Detail("fixture-work-0")), vm.state.value.pages)
        assertEquals("chapter-1", work.getStringExtra(NovelAppActivity.EXTRA_CHAPTER_ID))
        vm.unbindAccount()
        bindNovelAppEntry(vm, account, self, NovelAppActivity.creatorIntent(context, account), true)
        assertEquals("An external-detail root must not be commandeered by a creator flag", NovelPage.Store, vm.state.value.page)
    }

    private fun processSnapshot(bundle: android.os.Bundle): android.os.Bundle {
        val parcel = android.os.Parcel.obtain()
        return try {
            parcel.writeBundle(bundle); parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(javaClass.classLoader))
        } finally { parcel.recycle() }
    }

    @Test fun processDeathRestoresShelfStatisticsAndOwnerStackInsteadOfReplayingCreatorShortcut() = runTest(dispatcher) {
        val entry = NovelAppActivity.creatorIntent(context, account)
        for (destination in listOf(NovelPage.Shelf, NovelPage.Statistics, NovelPage.OwnerWorks)) {
            vm.unbindAccount()
            bindNovelAppEntry(vm, account, self, entry, false); vm.navigate(destination); runCurrent()
            val expected = vm.state.value.pages
            val saved = processSnapshot(requireNotNull(saveNovelAppNavigation(vm.state.value, entry, false)))
            vm.unbindAccount() // a new process has no retained VM, not just a recreated Activity
            bindNovelAppEntry(vm, account, self, entry, false, saved, restoring = true); runCurrent()
            assertEquals("Restore $destination, not the original Creator intent", expected, vm.state.value.pages)
            assertEquals(destination, vm.state.value.page)
            assertTrue(transport.requests.none { it.path == "/creator/apply" })
        }
    }

    @Test fun processRestoreIsAccountEntryScopedAndUnknownOrOversizedRoutesCannotReplayShortcut() = runTest(dispatcher) {
        val entry = NovelAppActivity.creatorIntent(context, account)
        bindNovelAppEntry(vm, account, self, entry, false); vm.navigate(NovelPage.Shelf)
        val saved = requireNotNull(saveNovelAppNavigation(vm.state.value, entry, false))
        assertNull(restoreNovelAppNavigation(saved, "different-account", entry, false))
        assertNull(restoreNovelAppNavigation(saved, account, NovelAppActivity.intent(context, account, "different-work"), false))
        assertNull(restoreNovelAppNavigation(saved, account, entry, true))
        val unknown = android.os.Bundle(saved).apply { putStringArrayList("pages", arrayListOf("creator", "untrusted-route")) }
        val invalidId = android.os.Bundle(saved).apply { putStringArrayList("pages", arrayListOf("creator", "detail:" + "x".repeat(257))) }
        val tooMany = android.os.Bundle(saved).apply { putStringArrayList("pages", ArrayList(List(33) { "shelf" })) }
        for (invalid in listOf(unknown, invalidId, tooMany)) {
            assertNull(restoreNovelAppNavigation(invalid, account, entry, false))
            vm.unbindAccount()
            bindNovelAppEntry(vm, account, self, entry, false, invalid, restoring = true)
            assertEquals("A rejected restore descriptor must not invoke the stale shortcut", NovelPage.Store, vm.state.value.page)
        }
    }

    @Test fun restoredWorkChapterAndExternalDetailKeepTheirEntryIdentityAndBackStack() = runTest(dispatcher) {
        val entry = NovelAppActivity.intent(context, account, "fixture-work-0", "chapter-1")
            .putExtra(NovelAppActivity.EXTRA_CREATOR, true)
        bindNovelAppEntry(vm, account, self, entry, true)
        val expected = vm.state.value.pages
        val saved = processSnapshot(requireNotNull(saveNovelAppNavigation(vm.state.value, entry, true)))
        vm.unbindAccount()
        bindNovelAppEntry(vm, account, self, entry, true, saved, restoring = true)
        assertEquals(expected, vm.state.value.pages)
        assertEquals(NovelPage.Detail("fixture-work-0"), vm.state.value.page)
        assertEquals("chapter-1", entry.getStringExtra(NovelAppActivity.EXTRA_CHAPTER_ID))
        val replaced = NovelAppActivity.intent(context, account, "new-work", "new-chapter").putExtra(NovelAppActivity.EXTRA_CREATOR, true)
        vm.unbindAccount()
        bindNovelAppEntry(vm, account, self, replaced, true, saved, restoring = true)
        assertEquals("Foreign saved navigation cannot override a new work entry", NovelPage.Detail("new-work"), vm.state.value.page)
    }

    @Test fun unlockPendingDescriptorSurvivesRepeatedSaveWithoutLoadingOrGrantingCreator() = runTest(dispatcher) {
        val entry = NovelAppActivity.creatorIntent(context, account)
        val pending = processSnapshot(requireNotNull(pendingNovelAppNavigation(account, entry, false)))
        assertTrue("Saving navigation while locked performs no load", transport.requests.isEmpty())
        val repeated = processSnapshot(android.os.Bundle(pending))
        bindNovelAppEntry(vm, account, self, entry, false, repeated, restoring = true); runCurrent()
        assertEquals(listOf(NovelPage.Creator), vm.state.value.pages)
        assertTrue(transport.requests.none { it.path == "/creator/apply" })
        vm.navigate(NovelPage.Shelf)
        val shelf = processSnapshot(requireNotNull(saveNovelAppNavigation(vm.state.value, entry, false)))
        vm.unbindAccount()
        val savedWhileWaitingAgain = processSnapshot(android.os.Bundle(shelf))
        bindNovelAppEntry(vm, account, self, entry, false, savedWhileWaitingAgain, restoring = true)
        assertEquals(listOf(NovelPage.Shelf), vm.state.value.pages)
    }

    @Test fun blankAccountStillFailsTheOriginalIntentContract() {
        assertThrows(IllegalArgumentException::class.java) { NovelAppActivity.creatorIntent(context, " ") }
    }
}
