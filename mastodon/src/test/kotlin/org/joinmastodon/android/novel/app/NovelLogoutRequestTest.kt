@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.joinmastodon.android.novel.app

import android.app.Application
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.lang.reflect.Field
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Okio
import okio.Timeout
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.E
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.api.novels.PrivateBookUpload
import org.joinmastodon.android.api.session.AccountSession
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.events.AccountLoggedOutEvent
import org.joinmastodon.android.model.Account
import org.joinmastodon.android.model.Instance
import org.joinmastodon.android.model.InstanceV2
import org.joinmastodon.android.model.Token
import org.joinmastodon.android.novel.NovelAccountDataCleaner
import org.joinmastodon.android.novel.app.editor.NovelCreationActivity
import org.joinmastodon.android.novel.app.editor.NovelEditorController
import org.joinmastodon.android.novel.app.editor.NovelEditorRepository
import org.joinmastodon.android.novel.app.editor.NovelMetadata
import org.joinmastodon.android.novel.app.editor.NovelReaderActivity
import org.joinmastodon.android.novel.app.editor.NovelReaderController
import org.joinmastodon.android.novel.app.editor.NovelSubmissionSender
import org.joinmastodon.noveleditor.EditorSession
import org.joinmastodon.noveleditor.NovelEditorStorage
import org.joinmastodon.reader.data.NovelBookEntity
import org.joinmastodon.reader.data.NovelChapterEntity
import org.joinmastodon.reader.data.NovelDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Only loopback HTTP and controlled held calls; production session validation is tested separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelLogoutRequestTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val main = StandardTestDispatcher()
    private val account = "novel-logout-${UUID.randomUUID()}"
    private val current = AtomicReference<Any?>(Any())
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    private val networkCalls = CopyOnWriteArrayList<Call>()
    private val transport = Call.Factory { request -> client.newCall(request).also(networkCalls::add) }
    private lateinit var server: MockWebServer
    private val sessionsToRemove = mutableSetOf<String>()
    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<out ComponentActivity>>()
    private var oldContext: android.content.Context? = null

    @Before fun setup() {
        oldContext = MastodonApp.context
        MastodonApp.context = app
        me.grishka.appkit.utils.V.setApplicationContext(app)
        Dispatchers.setMain(main)
        server = MockWebServer().apply { start() }
    }

    @After fun cleanup() {
        activities.asReversed().forEach { it.pause().stop().destroy() }
        main.scheduler.runCurrent()
        sessionsToRemove.forEach { id ->
            val session = sessions()[id]
            NovelAccountDataCleaner.revoke(id) { sessions().remove(id) }
            session?.let { instances().remove(it.domain) }
            deleteLocalData(id)
        }
        NovelAccountDataCleaner.invalidate(account)
        deleteLocalData(account)
        networkCalls.forEach(Call::cancel)
        server.shutdown()
        client.connectionPool().evictAll()
        client.dispatcher().executorService().shutdownNow()
        Dispatchers.resetMain()
        MastodonApp.context = oldContext
    }

    private fun api(factory: Call.Factory = transport, token: String = "old-test-token"): NovelAppApi {
        val captured = current.get()
        return NovelAppApi(server.url("/api/v1/novels/app").toString(), token, factory, true,
            NovelSessionRequests.capture(account) { captured != null && current.get() === captured })
    }
    private fun logout() = NovelAccountDataCleaner.revoke(account) { current.set(null) }
    private fun requireExpired(result: Result<*>) { assertTrue(result.exceptionOrNull().toString(), result.exceptionOrNull() is NovelSessionExpiredException) }

    private suspend fun submission(api: NovelAppApi): Pair<NovelEditorRepository, String> {
        val repository = NovelEditorRepository(app, account, api)
        val (work, selected) = NovelEditorStorage(app, account).use { storage ->
            val work = storage.createWork("网络注销测试", "只提交显式冻结章节", "fiction")
            storage.putAppMetadata(work.workId(), NovelMetadata("网络注销测试", "只提交显式冻结章节", accepted = true).json().toString(), null, null)
            val selected = (0 until 42).map { index ->
                val id = if (index == 0) work.chapterId() else storage.addChapter(work.workId(), work.volumeId(), "章节$index").id()
                storage.checkpoint(id, "冻结正文$index", 0, 0, 0, true)
                id
            }
            work.workId() to selected
        }
        return repository to repository.prepare(work, selected)
    }

    @Test fun pendingStartIsCancelledByLogoutAndSenderCannotDispatchPartsOrFinalize() = runBlocking {
        val api = api()
        val (repository, id) = submission(api)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async(Dispatchers.IO) { runCatching { NovelSubmissionSender(repository).send(id) } }
        assertEquals("/api/v1/novels/app/submissions/start", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        logout()
        requireExpired(withTimeout(5000) { pending.await() })
        assertTrue(networkCalls.single().isCanceled())
        requireExpired(runCatching { api.raw("POST", "/submissions/remote/finalize", JSONObject(), UUID.randomUUID().toString()) })
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        assertEquals(1, server.requestCount)
    }

    @Test fun pendingPartIsCancelledAcrossApisBeforeSessionRemovalAndNoLaterPartOrFinalizeDispatches() = runBlocking {
        val api = api()
        val secondApi = api()
        val (repository, id) = submission(api)
        server.enqueue(MockResponse().setBody("""{"id":"remote","work_id":"remote-work","status":"uploading"}"""))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async(Dispatchers.IO) { runCatching { NovelSubmissionSender(repository).send(id) } }
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS)!!.path!!.endsWith("/submissions/start"))
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS)!!.path!!.endsWith("/submissions/remote/parts/0"))
        val read = async(Dispatchers.IO) { runCatching { secondApi.request("GET", "/works/private") } }
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS)!!.path!!.endsWith("/works/private"))
        val part = networkCalls[1]
        val chapter = networkCalls[2]
        var privateUploadCancelled = false
        val upload = object : PrivateBookUpload(null, {}) { override fun cancel() { privateUploadCancelled = true } }
        val epoch = NovelAccountDataCleaner.captureGeneration(account)
        assertTrue(NovelAccountDataCleaner.registerUpload(account, epoch, upload) { current.get() != null })
        NovelAccountDataCleaner.revoke(account) {
            assertFalse(NovelAccountDataCleaner.isGenerationValid(account, epoch))
            assertTrue(part.isCanceled()); assertTrue(chapter.isCanceled()); assertTrue(privateUploadCancelled)
            requireExpired(runCatching { api.request("POST", "/submissions/remote/finalize") })
            current.set(null)
        }
        requireExpired(withTimeout(5000) { pending.await() })
        requireExpired(withTimeout(5000) { read.await() })
        assertFalse(NovelAccountDataCleaner.hasActiveUpload(account))
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        assertEquals(3, server.requestCount)
    }

    @Test fun coroutineCancellationStillCancelsARegisteredRealCallWithoutLoggingOut() = runBlocking {
        val api = api()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async(Dispatchers.IO) { api.raw("GET", "/works") }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        pending.cancelAndJoin()
        assertTrue(networkCalls.single().isCanceled())
        assertTrue(api.isSessionActive())
        val cancellation = NovelRequestCancellation().apply { cancel() }
        assertThrows(java.io.InterruptedIOException::class.java) { api.request("GET", "/works", cancellation = cancellation) }
        assertEquals(1, server.requestCount)
    }

    @Test fun revokeBetweenCallCreationAndRegistrationCancelsWithoutEnqueueOrExecute() = runBlocking {
        lateinit var held: HeldCall
        val api = api(Call.Factory { request -> HeldCall(request).also { held = it; logout() } })
        requireExpired(runCatching { api.raw("POST", "/submissions/start", JSONObject(), UUID.randomUUID().toString()) })
        assertTrue(held.isCanceled()); assertFalse(held.isExecuted())
        assertEquals(0, server.requestCount)
    }

    @Test fun lateReadSuccessAfterLogoutCannotReviveOldApiOnSameIdReloginButNewApiWorks() = runTest(main) {
        val factory = HeldFactory()
        val old = api(factory)
        val pending = async { runCatching { old.raw("GET", "/works/w/chapters/c") } }
        runCurrent()
        val held = factory.take()
        logout()
        current.set(Any()) // Same account id, entirely new login authority.
        assertTrue(held.isCanceled())
        val body = held.complete("""{"body":"must never surface old chapter text"}""")
        runCurrent()
        requireExpired(pending.await())
        assertTrue(body.closed)
        requireExpired(runCatching { old.raw("PUT", "/submissions/remote/parts/1", JSONObject(), UUID.randomUUID().toString()) })
        requireExpired(runCatching { old.request("POST", "/submissions/remote/finalize") })
        assertEquals(1, factory.created.size)
        val fresh = api(token = "new-test-token")
        server.enqueue(MockResponse().setBody("""{"fresh":true}"""))
        assertTrue(runBlocking(Dispatchers.IO) { fresh.request("GET", "/works") }.getBoolean("fresh"))
        assertEquals("Bearer new-test-token", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test fun alreadyDecodedResponseQueuedForResumeIsStillRejectedAfterLogout() = runTest(main) {
        val factory = HeldFactory()
        val api = api(factory)
        val pending = async { runCatching { api.raw("GET", "/works/w") } }
        runCurrent()
        val held = factory.take()
        val body = held.complete("""{"title":"queued old account data"}""")
        assertTrue(body.closed)
        assertFalse(pending.isCompleted)
        logout()
        runCurrent()
        requireExpired(pending.await())
        assertTrue(held.isCanceled())
    }

    @Test fun blockingReadRejectsAnIgnoringCancelTransportResponseAndClosesBody() {
        val factory = HeldFactory()
        val api = api(factory)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit(Callable { runCatching { api.request("GET", "/owner/works/w/chapters/c") } })
            val held = factory.take()
            assertTrue(held.dispatched.await(5, TimeUnit.SECONDS))
            logout()
            current.set(Any())
            val body = held.complete("""{"body":"old synchronous response"}""")
            requireExpired(pending.get(5, TimeUnit.SECONDS))
            assertTrue(held.isCanceled()); assertTrue(body.closed)
        } finally { executor.shutdownNow() }
    }

    @Test fun logoutDuringBodyReadIsCheckedAfterDecodeForSuspendAndBlockingRequests() = runBlocking {
        repeat(2) { blocking ->
            current.set(Any())
            val factory = HeldFactory(onBodyRead = ::logout)
            val api = api(factory)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val pending = if (blocking == 1) executor.submit(Callable { runCatching { api.request("GET", "/works/w") } }) else null
                val suspended = if (blocking == 0) async(Dispatchers.IO) { runCatching { api.raw("GET", "/works/w") } } else null
                val held = factory.take()
                assertTrue(held.dispatched.await(5, TimeUnit.SECONDS))
                val body = held.complete("""{"body":"decoded while epoch was revoked"}""")
                requireExpired(pending?.get(5, TimeUnit.SECONDS) ?: withTimeout(5000) { suspended!!.await() })
                assertTrue(body.closed); assertTrue(held.isCanceled())
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun productionAuthorityPinsActualSessionIdentityAndCapturedToken() {
        val session = realSession("production-authority")
        val api = NovelAppApi(session)
        assertTrue(api.isSessionActive())
        session.token.accessToken = "rotated-test-token"
        assertFalse(api.isSessionActive())
        session.token.accessToken = "old-test-token"
        assertFalse(api.isSessionActive()) // Even restoring the same object/token cannot undo observed expiry.
        val currentApi = NovelAppApi(session)
        val replacement = newSession(session.domain, "old-test-token")
        sessions()[session.getID()] = replacement
        assertFalse(currentApi.isSessionActive())
        val beforeLogout = NovelAppApi(replacement)
        assertTrue(beforeLogout.isSessionActive())
        NovelAccountDataCleaner.revoke(session.getID()) { sessions().remove(session.getID()) }
        sessions()[session.getID()] = replacement // Even the identical object+token cannot ABA the epoch.
        assertFalse(beforeLogout.isSessionActive())
        assertTrue(NovelAppApi(replacement).isSessionActive())
    }

    @Test fun editorLogoutEventDropsMemoryFrameAndDoesNotSaveOrRecreateDeletedAccountData() {
        val session = realSession("editor-event")
        val work = NovelEditorStorage(app, session.getID()).use { storage ->
            storage.createWork("旧账号编辑器", "本机文稿", "fiction").also { storage.checkpoint(it.chapterId(), "原始已保存正文", 0, 0, 0, true) }
        }
        val host = Robolectric.buildActivity(NovelCreationActivity::class.java, NovelCreationActivity.intent(app, session.getID(), localWorkId = work.workId())).also(activities::add)
        host.create().start().resume()
        val activity = host.get()
        val model = controller(activity) as NovelEditorController
        // open() publishes the directory before its owner-refresh IO finishes; busy still gates openChapter.
        awaitModel { model.state.value.directory != null && !model.state.value.busy }
        assertNull("Local-only editor fixture failed to open", model.state.value.error)
        model.openChapter(work.chapterId())
        awaitModel { !model.state.value.busy }
        assertNull("Local-only chapter fixture failed to open", model.state.value.error)
        assertNotNull("Chapter fixture must load a frame before logout", model.state.value.frame)
        model.frame(EditorSession.Frame("秘密标题", "仅在旧账号内存的未保存正文", 1, 3, 7))
        assertEquals("仅在旧账号内存的未保存正文", model.state.value.frame!!.body())
        NovelAccountDataCleaner.revoke(session.getID()) { sessions().remove(session.getID()) }
        deleteLocalData(session.getID())
        E.post(AccountLoggedOutEvent(session.getID()))
        shadowOf(Looper.getMainLooper()).idle()
        assertClosed(activity)
        assertFalse(model.viewModelScope.isActive)
        main.scheduler.runCurrent()
        assertFalse(app.getDatabasePath(NovelEditorStorage.databaseName(session.getID())).exists())
        assertEquals(0, server.requestCount)
    }

    @Test fun returningEditorAndPrivateReaderCannotDisplayOldMemoryAfterSameIdRelogin() {
        val session = realSession("returning-hosts")
        val work = NovelEditorStorage(app, session.getID()).use { storage -> storage.createWork("旧账号本机作品", "简介", "fiction").also { storage.checkpoint(it.chapterId(), "旧编辑器正文", 0, 0, 0, true) } }
        runBlocking(Dispatchers.IO) {
            val db = NovelDatabase.open(app, session.getID())
            try {
                db.novelBookDao().upsert(NovelBookEntity("private-book", session.getID(), "旧私人书库"))
                db.novelChapterDao().upsert(listOf(NovelChapterEntity("private-chapter", "private-book", "私人章节", "旧私人读者正文", 0)))
            } finally { db.close() }
        }
        val editorHost = Robolectric.buildActivity(NovelCreationActivity::class.java, NovelCreationActivity.intent(app, session.getID(), localWorkId = work.workId())).also(activities::add)
        val readerHost = Robolectric.buildActivity(NovelReaderActivity::class.java, NovelReaderActivity.privateIntent(app, session.getID(), "private-book")).also(activities::add)
        editorHost.create().start().resume()
        readerHost.create().start().resume()
        val editor = controller(editorHost.get()) as NovelEditorController
        val reader = controller(readerHost.get()) as NovelReaderController
        awaitModel { editor.state.value.directory != null && reader.state.value.chapter != null }
        editor.openChapter(work.chapterId())
        awaitModel { editor.state.value.frame != null }
        assertEquals("旧编辑器正文", editor.state.value.frame!!.body())
        assertEquals("旧私人读者正文", reader.state.value.chapter!!.content)
        editorHost.pause().stop(); readerHost.pause().stop()
        awaitModel { !editor.state.value.saving && editor.viewModelScope.coroutineContext[Job]!!.children.count { it.isActive } == 1 && reader.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive } }
        NovelAccountDataCleaner.revoke(session.getID()) { sessions().remove(session.getID()) }
        deleteLocalData(session.getID())
        sessions()[session.getID()] = newSession(session.domain, "new-test-token")
        // Intentionally omit event delivery: onResume must reject the retained original authority itself.
        editorHost.restart().start().resume(); readerHost.restart().start().resume()
        assertClosed(editorHost.get()); assertClosed(readerHost.get())
        assertFalse(editor.viewModelScope.isActive); assertFalse(reader.viewModelScope.isActive)
        main.scheduler.runCurrent()
        assertFalse(app.getDatabasePath(NovelEditorStorage.databaseName(session.getID())).exists())
        assertFalse(app.getDatabasePath(NovelDatabase.databaseName(session.getID())).exists())
        assertEquals(0, server.requestCount)
    }

    private fun realSession(name: String): AccountSession = newSession("$name-${UUID.randomUUID()}.example.test", "old-test-token").also {
        sessions()[it.getID()] = it
        instances()[it.domain] = InstanceV2().apply { domain = it.domain; version = "4.3.0" }
        sessionsToRemove += it.getID()
    }
    private fun newSession(domain: String, accessToken: String): AccountSession = AccountSession::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance().apply {
        this.domain = domain
        self = Account().apply { id = "42"; username = "logout-test" }
        token = Token().apply { this.accessToken = accessToken }
    }
    @Suppress("UNCHECKED_CAST")
    private fun sessions(): MutableMap<String, AccountSession> = field(AccountSessionManager::class.java, "sessions").get(AccountSessionManager.getInstance()) as MutableMap<String, AccountSession>
    @Suppress("UNCHECKED_CAST")
    private fun instances(): MutableMap<String, Instance> = field(AccountSessionManager::class.java, "instances").get(AccountSessionManager.getInstance()) as MutableMap<String, Instance>
    private fun field(type: Class<*>, name: String): Field = type.getDeclaredField(name).apply { isAccessible = true }
    private fun controller(activity: ComponentActivity): Any? = field(activity.javaClass, "controller").get(activity)
    private fun assertClosed(activity: ComponentActivity) {
        assertTrue(activity.isFinishing)
        assertNull(controller(activity))
        assertEquals(0, activity.findViewById<ViewGroup>(android.R.id.content).childCount)
    }
    private fun awaitModel(ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            main.scheduler.runCurrent()
            if (ready()) return
            Thread.sleep(5) // Only wait for real SQLite IO, never for a fake elapsed logout delay.
        }
        fail("Timed out waiting for actual local controller IO")
    }
    private fun deleteLocalData(id: String) {
        NovelDatabase.closeAccount(id)
        assertTrue(NovelAccountDataCleaner.deleteAccountData(app.filesDir, app.cacheDir, requireNotNull(app.getDatabasePath("placeholder").parentFile), id))
    }

    private class HeldFactory(private val onBodyRead: () -> Unit = {}) : Call.Factory {
        val created = CopyOnWriteArrayList<HeldCall>()
        private val pending = LinkedBlockingQueue<HeldCall>()
        override fun newCall(request: Request): Call = HeldCall(request, onBodyRead).also { created += it; pending.add(it) }
        fun take(): HeldCall = requireNotNull(pending.poll(5, TimeUnit.SECONDS)) { "No call was created" }
    }
    /** Deliberately ignores cancel delivery, to prove the authority fence rejects even a late success. */
    private class HeldCall(private val request: Request, private val onBodyRead: () -> Unit = {}) : Call {
        @Volatile private var cancelled = false
        @Volatile private var executed = false
        @Volatile private var callback: Callback? = null
        @Volatile private var response: Response? = null
        val dispatched = CountDownLatch(1)
        private val completed = CountDownLatch(1)
        override fun request() = request
        override fun enqueue(responseCallback: Callback) { executed = true; callback = responseCallback; dispatched.countDown() }
        override fun execute(): Response {
            executed = true; dispatched.countDown()
            if (!completed.await(5, TimeUnit.SECONDS)) throw IOException("Held response timed out")
            return requireNotNull(response)
        }
        fun complete(json: String): ObservedBody {
            val body = ObservedBody(json, onBodyRead)
            val reply = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
            response = reply
            completed.countDown()
            callback?.onResponse(this, reply)
            return body
        }
        override fun cancel() { cancelled = true }
        override fun isCanceled() = cancelled
        override fun isExecuted() = executed
        override fun timeout() = Timeout()
        override fun clone(): Call = HeldCall(request, onBodyRead)
    }
    private class ObservedBody(json: String, onRead: () -> Unit) : ResponseBody() {
        @Volatile var closed = false
        private val bytes = json.toByteArray(Charsets.UTF_8)
        private var first = true
        private val source = Okio.buffer(object : ForwardingSource(Buffer().write(bytes)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (first) { first = false; onRead() }
                return super.read(sink, byteCount)
            }
            override fun close() { closed = true; super.close() }
        })
        override fun contentType(): MediaType? = MediaType.parse("application/json")
        override fun contentLength(): Long = bytes.size.toLong()
        override fun source(): BufferedSource = source
    }
}
