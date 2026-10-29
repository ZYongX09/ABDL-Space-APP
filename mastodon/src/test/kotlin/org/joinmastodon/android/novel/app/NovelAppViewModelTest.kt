package org.joinmastodon.android.novel.app

import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.novel.app.models.NovelJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class)
class NovelAppViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var transport: TestNovelTransport
    private lateinit var vm: NovelAppViewModel
    private lateinit var history: MemoryNovelSearchHistory
    private var valid = true
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        history = MemoryNovelSearchHistory(); transport = TestNovelTransport()
        vm = NovelAppViewModel({ NovelRepository(transport) }, history, { valid }, { listOf(NovelAppTestFixtures.local) })
    }
    @After fun cleanup() { vm.unbindAccount(); Dispatchers.resetMain() }

    @Test fun eligibleDoesNotAutomaticallyApplyAndOnlyExplicitApplyCanActivate() = runTest(dispatcher) {
        vm.bindAccount("account-a"); vm.navigate(NovelPage.Creator); runCurrent()
        assertEquals(3, vm.state.value.creator.value?.completedConditions)
        assertEquals(false, vm.state.value.creator.value?.active)
        assertTrue(transport.requests.none { it.method == "POST" })
        assertEquals(listOf(NovelAppTestFixtures.local), vm.state.value.localWorks.value)
        transport.response = { method, path, _, key ->
            assertEquals("POST", method); assertEquals("/creator/apply", path); UUID.fromString(key)
            NovelAppTestFixtures.creatorJson(active = true)
        }
        vm.applyCreator(); vm.applyCreator(); runCurrent()
        assertTrue(vm.state.value.creator.value!!.active)
        assertEquals(1, transport.requests.count { it.path == "/creator/apply" })
        vm.applyCreator(); runCurrent()
        assertEquals(1, transport.requests.count { it.path == "/creator/apply" })
    }
    @Test fun missingConditionOrEligibilityFailureCannotGrantRoleOrInventZeroDrafts() = runTest(dispatcher) {
        transport.response = { _, path, _, _ -> if (path == "/creator") NovelAppTestFixtures.creatorJson(eligible = false) else NovelAppTestFixtures.page() }
        vm.bindAccount("account-a"); vm.navigate(NovelPage.Creator); runCurrent(); vm.applyCreator(); runCurrent()
        assertEquals(1, vm.state.value.creator.value?.completedConditions)
        assertFalse(vm.state.value.creator.value!!.active)
        assertTrue(transport.requests.none { it.path == "/creator/apply" })
        transport.response = { _, _, _, _ -> throw NovelAppApiException(503, "unavailable", null, "服务暂不可用") }
        vm.refresh(); runCurrent()
        assertNotNull(vm.state.value.creator.error)
        assertEquals(false, vm.state.value.creator.value?.active)
        assertEquals(1, vm.state.value.localWorks.value?.size)
    }
    @Test fun rapidQueryChangesAndOutOfOrderResponsesCannotResurrectOldResults() = runTest(dispatcher) {
        val completions = mutableMapOf<String, kotlin.coroutines.Continuation<JSONObject>>()
        transport.response = { _, path, _, _ -> if (path.startsWith("/works?")) suspendCoroutine { completions[path] = it } else NovelAppTestFixtures.page() }
        vm.bindAccount("a"); runCurrent()
        vm.navigate(NovelPage.Search); vm.setQuery("旧查询"); advanceTimeBy(350); runCurrent()
        val old = completions.keys.last()
        vm.setQuery("新查询"); advanceTimeBy(350); runCurrent()
        val current = completions.keys.last()
        completions[current]!!.resume(NovelAppTestFixtures.page(NovelAppTestFixtures.work().copy(title = "新回包"))); runCurrent()
        completions[old]!!.resume(NovelAppTestFixtures.page(NovelAppTestFixtures.work().copy(title = "不该出现"))); runCurrent()
        assertEquals("新回包", vm.state.value.search.value?.items?.single()?.title)
        assertEquals("新查询", history.read("a").first())
        assertTrue(history.read("b").isEmpty())
        vm.clearHistory(); assertTrue(history.read("a").isEmpty())
    }
    @Test fun accountEpochFencesLateLoadsMutationsAndLocalManuscripts() = runTest(dispatcher) {
        var late: kotlin.coroutines.Continuation<JSONObject>? = null
        transport.response = { _, path, _, _ -> if (path == "/creator") suspendCoroutine { late = it } else NovelAppTestFixtures.page() }
        vm.bindAccount("old"); vm.navigate(NovelPage.Creator); runCurrent()
        assertNotNull(late)
        vm.bindAccount("new"); runCurrent()
        late!!.resume(NovelAppTestFixtures.creatorJson(active = true)); runCurrent()
        assertEquals("new", vm.state.value.accountId); assertNull(vm.state.value.creator.value); assertNull(vm.state.value.localWorks.value)
        transport.response = { _, _, _, _ -> NovelAppTestFixtures.creatorJson() }
        vm.navigate(NovelPage.Creator); runCurrent()
        var apply: kotlin.coroutines.Continuation<JSONObject>? = null
        transport.response = { _, _, _, _ -> suspendCoroutine { apply = it } }
        vm.applyCreator(); runCurrent(); vm.unbindAccount()
        apply!!.resume(NovelAppTestFixtures.creatorJson(active = true)); runCurrent()
        assertEquals("", vm.state.value.accountId); assertNull(vm.state.value.creator.value)
    }
    @Test fun repeatedPaginationMergesStableIdsAndPropagatesSelectedFilters() = runTest(dispatcher) {
        transport.response = { _, path, _, _ ->
            if (path.contains("cursor=")) NovelAppTestFixtures.page(NovelAppTestFixtures.work(), NovelAppTestFixtures.work(1))
            else NovelAppTestFixtures.page(NovelAppTestFixtures.work(), cursor = "cursor+two")
        }
        vm.bindAccount("a"); runCurrent(); vm.loadMoreWorks(); vm.loadMoreWorks(); runCurrent()
        assertEquals(listOf("fixture-work-0", "fixture-work-1"), vm.state.value.works.value?.items?.map { it.id })
        assertEquals(1, transport.requests.count { it.path.contains("cursor=") })
        assertTrue(transport.requests.last().path.contains("cursor=cursor%2Btwo"))
        vm.setStoreType("ai"); runCurrent(); assertTrue(transport.requests.last().path.contains("type=ai"))
        vm.navigate(NovelPage.Search); vm.setQuery("日常"); vm.setSearchType("repost"); vm.setSearchSort("collections"); runCurrent()
        assertTrue(transport.requests.last().path.contains("type=repost")); assertTrue(transport.requests.last().path.contains("sort=collections"))
    }
    @Test fun commentTimeoutRetriesSameOperationThenClearsInputOnlyOnSuccess() = runTest(dispatcher) {
        vm.bindAccount("a"); runCurrent()
        transport.response = { _, path, _, _ -> when {
            path == "/works/fixture-work-0" -> NovelAppTestFixtures.workJson()
            path.startsWith("/authors/") -> JSONObject().put("author", NovelAppTestFixtures.authorJson()).put("stats", JSONObject()).put("items", JSONArray())
            else -> NovelAppTestFixtures.page()
        } }
        vm.navigate(NovelPage.Comments("fixture-work-0")); runCurrent()
        transport.response = { _, _, _, _ -> throw java.io.IOException("test timeout") }
        vm.setCommentDraft("实际待提交评论"); vm.postComment("fixture-work-0"); vm.postComment("fixture-work-0"); runCurrent()
        assertEquals("实际待提交评论", vm.state.value.commentDraft); assertNotNull(vm.state.value.actionError)
        val failed = transport.requests.last()
        transport.response = { _, _, body, _ -> JSONObject().put("id", "comment-created").put("body", body!!.getString("body")).put("author", NovelAppTestFixtures.authorJson()).put("created_at", NovelAppTestFixtures.time).put("can_delete", true) }
        vm.postComment("fixture-work-0"); runCurrent()
        assertEquals(failed.key, transport.requests.last().key); assertEquals("", vm.state.value.commentDraft)
        assertEquals("实际待提交评论", vm.state.value.comments.value?.items?.first()?.body)
        assertEquals(2, transport.requests.count { it.method == "POST" && it.path.endsWith("/comments") })
    }
    @Test fun removedSessionPreventsLateSuccessAndReaderFiltersUseActualProgress() = runTest(dispatcher) {
        var late: kotlin.coroutines.Continuation<JSONObject>? = null
        transport.response = { _, _, _, _ -> suspendCoroutine { late = it } }
        vm.bindAccount("a"); runCurrent(); valid = false
        late!!.resume(NovelAppTestFixtures.page(NovelAppTestFixtures.work())); runCurrent()
        assertNull(vm.state.value.works.value)
        val state = NovelAppTestFixtures.state(NovelPage.Shelf)
        assertEquals(2, state.copy(shelfFilter = "updates").shelfItems.size)
        assertEquals(1, state.copy(shelfFilter = "completed").shelfItems.size)
    }
    @Test fun unavailableWorkRefreshDropsOldMetadataWhileTransientFailureRetainsIt() = runTest(dispatcher) {
        var failure: Exception? = null
        transport.response = { _, path, _, _ -> when {
            path == "/works/fixture-work-0" -> failure?.let { throw it } ?: NovelAppTestFixtures.workJson()
            path.startsWith("/authors/") -> JSONObject().put("author", NovelAppTestFixtures.authorJson()).put("stats", JSONObject()).put("items", JSONArray())
            else -> NovelAppTestFixtures.page()
        } }
        vm.bindAccount("a"); vm.openWork("fixture-work-0"); runCurrent()
        assertNotNull(vm.state.value.work.value)
        failure = java.io.IOException("temporary offline")
        vm.refresh(); runCurrent()
        assertNotNull(vm.state.value.work.value)
        for (status in listOf(401, 403, 404)) {
            failure = null; vm.refresh(); runCurrent(); assertNotNull(vm.state.value.work.value)
            failure = NovelAppApiException(status, "unavailable", null, "作品暂不可用")
            vm.refresh(); runCurrent()
            assertNull(vm.state.value.work.value)
            assertNull(vm.state.value.author.value)
            assertNull(vm.state.value.comments.value)
            assertNotNull(vm.state.value.work.error)
        }
    }

    @Test fun creatorInventoryUsesOwnerStatusesAndDoesNotCallServerDraftsLocal() {
        val local = NovelAppTestFixtures.local
        val inventory = NovelCreatorInventory(listOf(NovelAppTestFixtures.work(), NovelAppTestFixtures.work(1, status = "pending"), NovelAppTestFixtures.work(2, status = "rejected"), NovelAppTestFixtures.work(3, status = "draft")), listOf(local))
        assertEquals(listOf("fixture-work-0"), inventory.published.map { it.id })
        assertEquals(listOf("fixture-work-1"), inventory.reviewing.map { it.id })
        assertEquals(listOf(local), inventory.local)
        assertTrue(NovelJson.creator(NovelAppTestFixtures.creatorJson(active = true, eligible = false)).active)
    }
}
