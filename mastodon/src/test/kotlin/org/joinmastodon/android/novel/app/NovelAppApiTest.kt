package org.joinmastodon.android.novel.app

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.novel.app.models.NovelJson
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real loopback HTTP; never reaches the production API, AI or draft-upload services. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class)
class NovelAppApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: NovelAppApi
    @Before fun setup() { server = MockWebServer(); server.start(); api = NovelAppApi(server.url("/api/v1/novels/app").toString(), "test-token-not-a-secret", OkHttpClient(), true, NovelSessionRequests.capture("novel-http-test") { true }) }
    @After fun cleanup() { server.shutdown() }

    @Test fun actualGetScopesBearerToAppPathEncodesOpaqueIdsAndDisablesCaching() {
        server.enqueue(MockResponse().setBody(NovelAppTestFixtures.workJson().toString()))
        val work = api.getWork("opaque/work +?id")
        assertEquals("柔软日常", work.title)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/novels/app/works/opaque%2Fwork%20%2B%3Fid", request.path)
        assertEquals("Bearer test-token-not-a-secret", request.getHeader("Authorization"))
        assertEquals("no-store", request.getHeader("Cache-Control")); assertNull(request.getHeader("Idempotency-Key"))
        assertEquals("", request.body.readUtf8())
    }
    @Test fun explicitApplyAndMutationUseJsonAndCallerStableUuid() = runBlocking {
        val key = UUID.randomUUID().toString()
        repeat(2) { server.enqueue(MockResponse().setBody(NovelAppTestFixtures.creatorJson(active = true).toString())); assertTrue(NovelJson.creator(api.raw("POST", "/creator/apply", JSONObject(), key)).active) }
        repeat(2) {
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method); assertEquals("/api/v1/novels/app/creator/apply", request.path)
            assertEquals(key, request.getHeader("Idempotency-Key")); assertEquals("{}", request.body.readUtf8())
            assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        }
    }
    @Test fun rawHttpErrorPreservesStatusCodeAndCurrentReleasePayload() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"版本已改变","code":"release_changed","release_id":"current-release"}"""))
        try { api.getChapter("w", "c", "old-release"); fail("409 must not be decoded as empty content") }
        catch (error: NovelAppApiException) { assertEquals(409, error.status); assertEquals("release_changed", error.code); assertEquals("current-release", error.payload?.getString("release_id")) }
    }
    @Test fun redirectsMalformedJsonOversizeAndPathTraversalNeverBecomeSuccess() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/leak")))
        assertThrows(java.io.IOException::class.java) { api.request("GET", "/works") }
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setBody("not-json")); assertThrows(java.io.IOException::class.java) { api.request("GET", "/works") }
        assertThrows(IllegalArgumentException::class.java) { api.request("GET", "/%2e%2e/token") }
        assertThrows(IllegalArgumentException::class.java) { api.request("GET", "//example.test/") }
        assertThrows(IllegalArgumentException::class.java) { api.request("POST", "/submissions", JSONObject().put("body", "文".repeat(NovelAppApi.MAX_REQUEST_BYTES / 3 + 1))) }
        assertThrows(IllegalArgumentException::class.java) { api.request("POST", "/creator/apply", JSONObject(), "not-a-uuid") }
        assertEquals(2, server.requestCount)
    }
    @Test fun suspendedCancellationCancelsItsNetworkCallAndBlockingTokenCannotBeReused() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async { api.raw("GET", "/works") }
        kotlinx.coroutines.yield()
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        pending.cancelAndJoin(); assertTrue(pending.isCancelled)
        val token = NovelRequestCancellation(); token.cancel()
        assertThrows(java.io.InterruptedIOException::class.java) { api.request("GET", "/works", cancellation = token) }
        assertEquals(1, server.requestCount)
    }
    @Test fun repositoryUsesEveryRealMutationEndpointAndNeverLegacyDraftSync() = runBlocking {
        val transport = TestNovelTransport { _, path, _, _ -> when {
            path.startsWith("/shelf/") -> JSONObject().put("collected", true)
            path.contains("/follow") -> JSONObject().put("following", true)
            path.endsWith("/comments") && !path.contains('?') -> JSONObject().put("id", "c").put("body", "测试评论").put("author", NovelAppTestFixtures.authorJson()).put("created_at", NovelAppTestFixtures.time)
            else -> JSONObject().put("success", true)
        } }
        val repository = NovelRepository(transport)
        repository.collect("w", true); repository.follow(42, true); repository.comment("w", "测试评论", UUID.randomUUID().toString())
        repository.deleteComment("c"); repository.reportWork("w", "具体原因", "stable-c"); repository.reportComment("c", "具体原因")
        repository.markRead(); repository.markRead(listOf("m")); repository.ownerAction("w", "withdraw"); repository.searchClick("w")
        assertEquals(listOf("/shelf/w", "/authors/42/follow", "/works/w/comments", "/comments/c", "/works/w/report", "/comments/c/report", "/messages/read", "/messages/read", "/owner/works/w/action", "/works/w/search-click"), transport.requests.map { it.path })
        transport.requests.forEach { assertNotNull(UUID.fromString(it.key)) }
        assertTrue(transport.requests[6].body!!.getBoolean("all")); assertEquals("m", transport.requests[7].body!!.getJSONArray("ids").getString(0))
        assertEquals("stable-c", transport.requests[4].body!!.getString("chapter_id"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.ownerAction("w", "publish") } }
        Unit
    }
}
