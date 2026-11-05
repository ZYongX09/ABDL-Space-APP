package org.joinmastodon.android.novel.app

import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType
import okio.Timeout
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.R
import org.joinmastodon.android.api.ServiceResourcePause
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/** Descriptor flow through novel transport/VM/formatter; synthetic responses, no loopback or live API. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class, qualifiers = "zh-rCN")
class NovelPauseAdapterTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun pause(): ServiceResourcePause {
        val response = Response.Builder().request(Request.Builder().url("https://api.abdl-space.top/api/v1/novels/app/works").build())
            .protocol(Protocol.HTTP_1_1).code(503).message("paused").build()
        return requireNotNull(ServiceResourcePause.fromResponse(response, """{"error":"paused","code":"D1_DAILY_QUOTA_EXHAUSTED","reset_at":"2026-11-05T00:00:00Z","server_time":"2026-11-04T15:59:59Z","reset_source":"estimated_utc_midnight"}"""))
    }
    @Test fun retainedHandleFormatsAtDisplayTimeAcrossChinaMidnightAndExpiresWithoutLeakingMachineKey() {
        val context = RuntimeEnvironment.getApplication()
        val key = NovelPauseMessages.retain(pause())
        assertTrue(key.startsWith("novel_pause:")); assertFalse(key.contains("明天")); assertFalse(key.contains("paused"))
        assertEquals(context.getString(R.string.service_resource_pause_message, context.getString(R.string.service_resource_pause_tomorrow)), novelMessage(context, key))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        assertEquals(context.getString(R.string.service_resource_pause_message, context.getString(R.string.service_resource_pause_today)), novelMessage(context, key))
        ShadowSystemClock.advanceBy(Duration.ofHours(49))
        assertEquals(context.getString(R.string.novel_en_request_incomplete), novelMessage(context, key))
    }
    @Test fun registryIsBoundedAndOldEvictedHandleHasSafeFallback() {
        val pause = pause(); val first = NovelPauseMessages.retain(pause)
        repeat(64) { NovelPauseMessages.retain(pause) }
        assertNull(NovelPauseMessages.find(first))
        assertFalse(novelMessage(RuntimeEnvironment.getApplication(), first).contains("novel_pause:"))
    }
    @Test fun viewModelRetainsPauseThroughLoadAndMutationWithoutChangingCommentOrAutomaticRetries() = runTest(dispatcher) {
        val pause = pause()
        val failure = NovelAppApiException(503, "D1_DAILY_QUOTA_EXHAUSTED", null, "raw must not display", pause)
        val transport = TestNovelTransport { _, _, _, _ -> throw failure }
        val vm = NovelAppViewModel({ NovelRepository(transport) }, MemoryNovelSearchHistory())
        try {
            vm.bindAccount("a"); runCurrent()
            val loadKey = requireNotNull(vm.state.value.works.error)
            assertSame(pause, NovelPauseMessages.find(loadKey))
            vm.setCommentDraft("local draft remains"); vm.postComment("w"); runCurrent()
            assertEquals("local draft remains", vm.state.value.commentDraft)
            assertSame(pause, NovelPauseMessages.find(requireNotNull(vm.state.value.actionError)))
            assertEquals(1, transport.requests.count { it.method == "POST" })
            assertTrue(vm.state.value.pending.isEmpty())
            vm.bindAccount("b"); runCurrent(); assertNull(vm.state.value.actionError)
        } finally { vm.unbindAccount() }
    }
    @Test fun editorExceptionFormatterCarriesStableHandleInsteadOfLocalizedDayOrRawBody() {
        val pause = pause()
        val failure = NovelAppApiException(503, "D1_DAILY_QUOTA_EXHAUSTED", null, "raw hidden", pause)
        val context = RuntimeEnvironment.getApplication()
        val key = failure.novelUserMessage(context, R.string.novel_en_request_incomplete)
        assertSame(pause, NovelPauseMessages.find(key)); assertEquals(pause.message(context), novelMessage(context, key))
    }
    @Test fun novelTransportRejectsUntrustedPauseButPreservesHttpTypeAndNonPauseConflictPayload() {
        var payload = """{"error":"version changed","code":"release_changed","release_id":"current-release"}"""
        var status = 409
        val factory = Call.Factory { request -> ImmediateCall(request) {
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body(ResponseBody.create(MediaType.parse("application/json"), payload)).build()
        } }
        val api = NovelAppApi("http://localhost/api/v1/novels/app", "synthetic-test", factory, true, NovelSessionRequests.capture("pause-adapter") { true })
        val conflict = assertThrows(NovelAppApiException::class.java) { api.request("GET", "/works/w") }
        assertEquals(409, conflict.status); assertEquals("current-release", conflict.payload?.getString("release_id")); assertNull(conflict.serviceResourcePause)
        payload = """{"error":"paused","code":"D1_DAILY_QUOTA_EXHAUSTED"}"""; status = 503
        val untrusted = assertThrows(NovelAppApiException::class.java) { api.request("GET", "/works/w") }
        assertEquals(503, untrusted.status); assertNull(untrusted.serviceResourcePause)
    }

    private class ImmediateCall(private val request: Request, private val result: () -> Response) : Call {
        private var executed = false
        private var cancelled = false
        override fun request() = request
        override fun execute(): Response { executed = true; return result() }
        override fun enqueue(callback: Callback) { executed = true; callback.onResponse(this, result()) }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = ImmediateCall(request, result)
    }
}
