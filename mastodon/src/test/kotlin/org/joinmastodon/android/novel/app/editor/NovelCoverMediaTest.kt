@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.joinmastodon.android.novel.app.editor

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.grishka.appkit.utils.V
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.api.CosMediaUpload
import org.joinmastodon.android.api.session.AccountSession
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.model.Account
import org.joinmastodon.android.model.Token
import org.joinmastodon.android.novel.NovelAccountDataCleaner
import org.joinmastodon.android.novel.app.NovelAppApi
import org.joinmastodon.android.novel.app.NovelSessionExpiredException
import org.joinmastodon.android.novel.app.NovelSessionRequests
import org.joinmastodon.android.novel.importer.NovelImportCoordinator
import org.joinmastodon.noveleditor.NovelEditorStorage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver

/** Real pixels, private files, SQLite and loopback HTTP. HTTPS production requests are routed only
 * to MockWebServer by an injected test interceptor; no production endpoint is ever contacted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelCoverMediaTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val account = "cover-media-${UUID.randomUUID()}"
    private val main = TestCoroutineScheduler()
    private val io = TestCoroutineScheduler()
    private val models = ViewModelStore()
    private val files = mutableListOf<File>()
    private val realAccounts = mutableSetOf<String>()
    private val originals = CopyOnWriteArrayList<Request>()
    private val calls = CopyOnWriteArrayList<Call>()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private var oldContext: android.content.Context? = null

    @Before fun setup() {
        oldContext = MastodonApp.context; MastodonApp.context = app; V.setApplicationContext(app)
        Dispatchers.setMain(StandardTestDispatcher(main))
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS)
            .eventListenerFactory { call -> calls += call; EventListener.NONE }
            .addInterceptor { chain ->
                val original = chain.request(); originals += original
                check(original.url().host() in setOf("api.example.test", "cos.example.test")) { "Unexpected external test URL" }
                // Only the network destination changes. The upload code still builds its real HTTPS contract.
                chain.proceed(original.newBuilder().url(server.url(original.url().encodedPath())).build())
            }.build()
    }
    @After fun cleanup() {
        models.clear(); drain(); Dispatchers.resetMain()
        calls.forEach(Call::cancel)
        server.shutdown(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow()
        (realAccounts + account).forEach { id ->
            NovelAccountDataCleaner.revoke(id) { sessions().remove(id) }
            app.deleteDatabase(NovelEditorStorage.databaseName(id))
            File(app.filesDir, "novel-app/${NovelImportCoordinator.accountHash(id)}").deleteRecursively()
        }
        files.forEach { it.deleteRecursively() }
        MastodonApp.context = oldContext
    }
    private fun drain() { repeat(30) { main.runCurrent(); io.runCurrent() } }
    private fun folder() = File(app.cacheDir, "cover-test-${UUID.randomUUID()}").apply { mkdirs(); files += this }
    private fun image(png: Boolean = true): File {
        val output = File(folder(), if (png) "source.png" else "source.jpg")
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
        for (y in 0 until 60) for (x in 0 until 40) bitmap.setPixel(x, y,
            when { x < 20 && y < 30 -> Color.RED; x >= 20 && y < 30 -> Color.GREEN; x < 20 -> Color.BLUE; else -> Color.YELLOW })
        try { output.outputStream().use { assertTrue(bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 100, it)) } }
        finally { bitmap.recycle() }
        return output
    }
    private fun provider(file: File, mime: String? = null): Pair<TestCoverProvider, Uri> {
        val authority = "cover-${UUID.randomUUID()}.example.test"
        val provider = TestCoverProvider(file, mime)
        provider.attachInfo(app, ProviderInfo().apply { this.authority = authority; exported = false })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        return provider to Uri.parse("content://$authority/selected")
    }
    private fun authority() = NovelSessionRequests.capture(account) { true }
    private fun uploader(authority: NovelSessionRequests.Authority = authority()) = NovelCoverUpload.forTests("api.example.test", "test-account-token", authority, client)
    private fun authorization() = JSONObject().put("upload_id", "test-upload")
        .put("upload_url", "https://cos.example.test/covers/object.png")
        .put("public_url", "https://media.example.test/covers/object.png")
        .put("required_headers", JSONObject().put("Authorization", "test-cos-signature").put("Content-Type", "image/png").put("x-cos-forbid-overwrite", "true"))
    private fun success() {
        server.enqueue(MockResponse().setBody(authorization().toString()))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setBody("""{"id":"attachment","type":"image","url":"https://media.example.test/final.png"}"""))
    }

    @Test fun providerWithNullMimeIsSniffedCopiedAndStillDecodableAfterGrantAndSourceDisappear() {
        for (png in listOf(true, false)) {
            val source = image(png); val (provider, uri) = provider(source)
            val owned = NovelCoverImages.select(app, uri, folder())
            assertNotEquals(source.absolutePath, owned.absolutePath)
            provider.denied = true; assertTrue(source.delete())
            val bitmap = NovelCoverImages.decode(owned)
            try { assertEquals(40, bitmap.width); assertEquals(60, bitmap.height); assertFalse(bitmap.isRecycled) }
            finally { bitmap.recycle() }
            assertTrue(owned.length() in 1..NovelCoverImages.MAX_BYTES)
            assertTrue(owned.parentFile!!.listFiles()!!.none { it.extension == "source" })
        }
    }

    @Test fun exifRotationAndMirrorAreBakedIntoPixelsAndLocationMetadataIsNotRetained() {
        for ((orientation, expectedColor) in listOf(ExifInterface.ORIENTATION_ROTATE_90 to Color.BLUE, ExifInterface.ORIENTATION_FLIP_HORIZONTAL to Color.GREEN)) {
            val source = image(false)
            ExifInterface(source.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "30/1,0/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N"); saveAttributes()
            }
            val (_, uri) = provider(source, "image/jpeg")
            val owned = NovelCoverImages.select(app, uri, folder())
            val bitmap = NovelCoverImages.decode(owned)
            try {
                assertEquals(if (orientation == ExifInterface.ORIENTATION_ROTATE_90) 60 else 40, bitmap.width)
                assertEquals(if (orientation == ExifInterface.ORIENTATION_ROTATE_90) 40 else 60, bitmap.height)
                val color = bitmap.getPixel(bitmap.width / 4, bitmap.height / 4)
                assertTrue(kotlin.math.abs(Color.red(color) - Color.red(expectedColor)) < 30)
                assertTrue(kotlin.math.abs(Color.green(color) - Color.green(expectedColor)) < 30)
                assertTrue(kotlin.math.abs(Color.blue(color) - Color.blue(expectedColor)) < 30)
            } finally { bitmap.recycle() }
            val exif = ExifInterface(owned.path)
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            // Bitmap.compress strips the original EXIF. Encoders may omit orientation or emit
            // UNDEFINED (0); only NORMAL/UNDEFINED are safe after the pixel transform above.
            val outputOrientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            assertTrue("Re-encoded pixels must never retain an EXIF rotate/mirror instruction", outputOrientation in setOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL))
        }
    }

    @Test fun invalidMimePixelsDeniedAccessAndOverFiveMiBLeaveNoPartialPrivateImage() {
        val source = image()
        val cases = listOf(provider(source, "image/gif"), provider(File(folder(), "bad.png").apply { writeText("not image data") }, "image/png"),
            provider(File(folder(), "large.png").apply { java.io.RandomAccessFile(this, "rw").use { it.setLength(NovelCoverImages.MAX_BYTES + 1) } }),
            provider(source).also { it.first.denied = true })
        for ((_, uri) in cases) {
            val destination = folder()
            assertTrue(runCatching { NovelCoverImages.select(app, uri, destination) }.isFailure)
            assertTrue(destination.listFiles()!!.isEmpty())
        }
        assertTrue(originals.isEmpty())
    }

    @Test fun realAuthorizePutCompleteUsesGenericMimeSignedHeadersAndOnlyCompleteUrl() = runBlocking {
        val file = image(); success()
        assertEquals("https://media.example.test/final.png", uploader().upload(file))
        val authorize = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", authorize.method); assertEquals("/api/v1/uploads/authorize", authorize.path)
        assertEquals("Bearer test-account-token", authorize.getHeader("Authorization"))
        val body = JSONObject(authorize.body.readUtf8())
        assertEquals("generic", body.getString("purpose")); assertEquals("image/png", body.getString("mimeType"))
        assertEquals(file.length(), body.getLong("declaredSize")); assertEquals(40, body.getInt("width")); assertEquals(60, body.getInt("height"))
        val put = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PUT", put.method); assertEquals("/covers/object.png", put.path)
        assertEquals("test-cos-signature", put.getHeader("Authorization")); assertEquals("image/png", put.getHeader("Content-Type"))
        assertEquals("true", put.getHeader("x-cos-forbid-overwrite")); assertArrayEquals(file.readBytes(), put.body.readByteArray())
        val complete = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", complete.method); assertEquals("/api/v1/uploads/test-upload/complete", complete.path)
        assertEquals("Bearer test-account-token", complete.getHeader("Authorization"))
        assertEquals(0, JSONObject(complete.body.readUtf8()).length())
        assertEquals(3, originals.size); assertTrue(originals.all { it.url().isHttps })
        assertTrue(file.isFile)
    }

    @Test fun authorizeAndPut401403ShowOnlySafeStatusRetainFileAndNeverComplete() = runBlocking {
        val file = image()
        for (status in listOf(401, 403)) for (putStage in listOf(false, true)) {
            val before = server.requestCount
            if (putStage) server.enqueue(MockResponse().setBody(authorization().toString()))
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"private-token private-signature private-response"}"""))
            val error = runCatching { uploader().upload(file) }.exceptionOrNull()
            assertTrue(error is NovelCoverUploadException); assertTrue(error!!.message!!.contains(status.toString()))
            val structured = (error as NovelCoverUploadException).failure
            assertEquals(if (putStage) CosMediaUpload.Stage.PUT else CosMediaUpload.Stage.AUTHORIZE, structured.stage)
            assertEquals(status, structured.status); assertSame(structured, error.cause)
            assertEquals(if (status == 401) CosMediaUpload.Category.AUTHENTICATION else CosMediaUpload.Category.PERMISSION, structured.category)
            assertFalse(error.message!!.contains("private-")); assertFalse(error.message!!.contains("test-account-token"))
            assertEquals(before + if (putStage) 2 else 1, server.requestCount)
            assertTrue(file.isFile)
        }
        assertTrue(originals.none { it.url().encodedPath().endsWith("/complete") })
    }

    @Test fun xml403Html502MalformedAndEmptyBodyKeepExactStageStatusAndSafeUiMessage() = runBlocking {
        val file = image()
        for (stage in CosMediaUpload.Stage.values()) for ((status, payload) in listOf(
            403 to "<Error><Code>SignatureDoesNotMatch</Code><Message>private-token https://secret/?signature</Message><RequestId>xml-request_42</RequestId></Error>",
            502 to "<html>private-token signed-url gateway failure</html>",
            502 to "{malformed private-token", 502 to "",
        )) {
            val before = server.requestCount
            if (stage != CosMediaUpload.Stage.AUTHORIZE) server.enqueue(MockResponse().setBody(authorization().toString()))
            if (stage == CosMediaUpload.Stage.COMPLETE) server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(status).setBody(payload))
            val error = runCatching { uploader().upload(file) }.exceptionOrNull()
            assertTrue(error is NovelCoverUploadException)
            val failure = (error as NovelCoverUploadException).failure
            assertEquals(stage, failure.stage); assertEquals(status, failure.status); assertSame(failure, error.cause)
            val label = when (stage) { CosMediaUpload.Stage.AUTHORIZE -> "授权阶段"; CosMediaUpload.Stage.PUT -> "传输阶段"; CosMediaUpload.Stage.COMPLETE -> "完成校验阶段" }
            assertTrue(error.message!!.contains(label)); assertTrue(error.message!!.contains("HTTP $status"))
            assertFalse(error.toString().contains("private-token")); assertFalse(error.message!!.contains("https://"))
            if (status == 403) { assertEquals("SignatureDoesNotMatch", failure.cosCode); assertEquals("xml-request_42", failure.requestId) }
            assertTrue(file.isFile); assertNotNull(NovelCoverImages.decode(file).also { it.recycle() })
            assertEquals(before + stage.ordinal + 1, server.requestCount)
        }
    }

    @Test fun complete409422502ExposeSafeCategoryAndCodeWithoutLosingCauseOrLocalPixels() = runBlocking {
        val file = image()
        for ((status, code, category) in listOf(
            Triple(409, "upload_expired", CosMediaUpload.Category.EXPIRED),
            Triple(409, "upload_conflict", CosMediaUpload.Category.CONFLICT),
            Triple(422, "verification_header_missing", CosMediaUpload.Category.VERIFICATION),
            Triple(422, "size_mismatch", CosMediaUpload.Category.VERIFICATION),
            Triple(422, "type_mismatch", CosMediaUpload.Category.VERIFICATION),
            Triple(502, "cos_head_failed", CosMediaUpload.Category.VERIFICATION),
        )) {
            server.enqueue(MockResponse().setBody(authorization().toString())); server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"code":"$code","error":{"token":"private-token"},"request_id":"https://unsafe/signature"}"""))
            val error = runCatching { uploader().upload(file) }.exceptionOrNull() as NovelCoverUploadException
            assertEquals(CosMediaUpload.Stage.COMPLETE, error.failure.stage); assertEquals(status, error.failure.status)
            assertEquals(code, error.failure.code); assertEquals(category, error.failure.category); assertNull(error.failure.requestId)
            assertTrue(error.message!!.contains("完成校验阶段")); assertTrue(error.message!!.contains("本地图片已保留"))
            assertFalse(error.toString().contains("private-token")); assertFalse(error.message!!.contains("https://")); assertTrue(file.isFile)
        }
    }

    @Test fun malformedSuccessfulAuthorizeAndInvalidCompleteUrlUseStructuredStageNotRawParserMessage() = runBlocking {
        val file = image()
        for (payload in listOf("{private-token malformed", "[]", "", "null")) {
            server.enqueue(MockResponse().setBody(payload))
            val error = runCatching { uploader().upload(file) }.exceptionOrNull() as NovelCoverUploadException
            assertEquals(CosMediaUpload.Stage.AUTHORIZE, error.failure.stage); assertEquals(200, error.failure.status)
            assertEquals(CosMediaUpload.Category.INVALID_RESPONSE, error.failure.category); assertNotNull(error.failure.cause)
            assertFalse(error.toString().contains("private-token")); assertTrue(file.isFile)
        }
        server.enqueue(MockResponse().setBody(authorization().toString())); server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setBody("""{"id":"attachment","type":"image","url":"http://unsafe.example.test/private-token"}"""))
        val error = runCatching { uploader().upload(file) }.exceptionOrNull() as NovelCoverUploadException
        assertEquals(CosMediaUpload.Stage.COMPLETE, error.failure.stage); assertEquals(CosMediaUpload.Category.INVALID_RESPONSE, error.failure.category)
        assertFalse(error.toString().contains("private-token")); assertTrue(file.isFile)
    }

    @Test fun unsafeTransportCauseRemainsInternalWithoutMessageOrStageRegexParsing() = runBlocking {
        val file = image(); val underlying = IOException("private-token https://signed-url/?credential HTTP 403")
        var requests = 0
        val local = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url().encodedPath().endsWith("/authorize")) {
                requests++
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body(okhttp3.ResponseBody.create(okhttp3.MediaType.get("application/json"), authorization().toString())).build()
            } else { requests++; throw underlying }
        }.build()
        try {
            val error = runCatching { NovelCoverUpload.forTests("api.example.test", "fixture-token", authority(), local).upload(file) }.exceptionOrNull() as NovelCoverUploadException
            assertEquals(2, requests); assertEquals(CosMediaUpload.Stage.PUT, error.failure.stage); assertEquals(0, error.failure.status)
            assertEquals(CosMediaUpload.Category.NETWORK, error.failure.category); assertSame(underlying, error.failure.cause)
            assertFalse(error.toString().contains("private-token")); assertFalse(error.message!!.contains("403")); assertTrue(file.isFile)
        } finally { local.connectionPool().evictAll(); local.dispatcher().executorService().shutdownNow() }
    }

    @Test fun redirectsAndMissingSignedHeaderContractStopBeforePutOrCompletion() = runBlocking {
        val file = image()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://trap.example.test/stolen").setBody("{}"))
        assertTrue(runCatching { uploader().upload(file) }.isFailure)
        assertEquals(1, server.requestCount); assertEquals(1, originals.size)
        val malformed = authorization().apply { getJSONObject("required_headers").remove("x-cos-forbid-overwrite") }
        server.enqueue(MockResponse().setBody(malformed.toString()))
        assertTrue(runCatching { uploader().upload(file) }.isFailure)
        assertEquals(2, server.requestCount)
        server.enqueue(MockResponse().setBody(authorization().toString()))
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "https://trap.example.test/stolen").setBody("{}"))
        assertTrue(runCatching { uploader().upload(file) }.isFailure)
        assertEquals(4, server.requestCount); assertTrue(originals.none { it.url().host() == "trap.example.test" || it.url().encodedPath().endsWith("/complete") })
    }

    @Test fun coroutineCancellationCancelsRealPutAndLogoutRevokesLaterCompleteAndSameIdReuse() = runBlocking {
        val file = image()
        repeat(2) { logout ->
            val captured = authority()
            server.enqueue(MockResponse().setBody(authorization().toString()))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = async(Dispatchers.IO) { runCatching { uploader(captured).upload(file) } }
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS)!!.path!!.endsWith("/authorize"))
            assertEquals("PUT", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
            val put = calls.last()
            if (logout == 0) { pending.cancelAndJoin(); assertTrue(pending.isCancelled); assertTrue(runCatching { pending.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException) }
            else {
                NovelAccountDataCleaner.invalidate(account)
                val error = withTimeout(5000) { pending.await() }.exceptionOrNull()
                assertTrue(error.toString(), error is NovelSessionExpiredException)
                assertFalse(captured.isActive())
                assertTrue(runCatching { uploader(captured).upload(file) }.exceptionOrNull() is NovelSessionExpiredException)
            }
            assertTrue(put.isCanceled()); assertTrue(file.isFile)
        }
        assertEquals(4, server.requestCount)
        assertTrue(originals.none { it.url().encodedPath().endsWith("/complete") })
    }

    @Test fun repositoryPinsAuthorityAtConstructionAndCannotBorrowSameIdReloginCredentials() = runBlocking {
        val session = newSession("pinned-${UUID.randomUUID()}.example.test", "old-token")
        sessions()[session.getID()] = session; realAccounts += session.getID()
        val oldApi = NovelAppApi(session)
        val oldRepository = NovelEditorRepository(app, session.getID(), oldApi)
        val pinned = requireNotNull(oldRepository.coverSession)
        assertTrue(pinned.authority.isActive())
        val replacement = newSession(session.domain, "new-token")
        NovelAccountDataCleaner.revoke(session.getID()) { sessions()[session.getID()] = replacement }
        val staleUploaderConstructedAfterRelogin = NovelCoverUpload.forRepository(oldRepository)
        val file = image()
        assertTrue(runCatching { staleUploaderConstructedAfterRelogin.upload(file) }.isFailure)
        assertFalse(pinned.authority.isActive())
        assertNull(NovelEditorRepository(app, session.getID(), oldApi).coverSession)
        val freshRepository = NovelEditorRepository(app, replacement.getID(), NovelAppApi(replacement))
        assertEquals("new-token", requireNotNull(freshRepository.coverSession).token)
        assertTrue(requireNotNull(freshRepository.coverSession).authority.isActive())
        assertEquals(0, server.requestCount); assertTrue(originals.isEmpty())
    }

    @Test fun selectedReplacementPreviewsImmediatelyPersistsPendingAndFailedSelectionOrUploadKeepsOldCoverAndBody() {
        val transport = EditorTestTransport()
        val repository = NovelEditorRepository(app, account, transport, StandardTestDispatcher(io))
        val metadata = NovelMetadata("本机作品", "简介", accepted = true, coverUrl = "https://media.example.test/old.png")
        val work = NovelEditorStorage(app, account).use { s -> s.createWork(metadata.title, metadata.description, "fiction").also {
            s.putAppMetadata(it.workId(), metadata.json().toString(), null, null); s.checkpoint(it.chapterId(), "私密正文", 1, 2, 40, true)
        } }
        var uploads = 0
        val controller = NovelEditorController(app, repository, NovelCoverUploader { uploads++; throw IOException("offline") }) { main.currentTime }
        models.put("cover-controller", controller)
        controller.open(work.workId(), null, "edit"); drain(); controller.showMetadata(); drain()
        val (_, uri) = provider(image())
        controller.chooseCover(uri); drain()
        val selected = controller.state.value.metadata
        assertNotNull(selected.localCover); assertTrue(selected.coverPending); assertEquals(metadata.coverUrl, selected.coverUrl)
        assertEquals(0, uploads); assertTrue(transport.requests.isEmpty())
        NovelEditorStorage(app, account).use { s -> assertEquals(selected, NovelMetadata.from(JSONObject(s.appMetadata(work.workId())!!.payload()))); assertEquals("私密正文", s.loadDraft(work.chapterId()).content()) }
        controller.uploadCover(); drain()
        assertEquals(1, uploads); assertNotNull(controller.state.value.error); assertEquals(selected, controller.state.value.metadata)
        val denied = provider(image()).also { it.first.denied = true }
        controller.chooseCover(denied.second); drain()
        assertEquals(selected, controller.state.value.metadata)
        NovelEditorStorage(app, account).use { s -> assertEquals("私密正文", s.loadDraft(work.chapterId()).content()); assertEquals(40, s.loadDraft(work.chapterId()).scrollY()); assertNull(s.pendingAppSubmission(work.workId())) }
        controller.removeCover(); drain()
        assertFalse(controller.state.value.metadata.coverPending); assertNull(controller.state.value.metadata.localCover); assertNull(controller.state.value.metadata.coverUrl)
        assertTrue(File(selected.localCover!!).isFile) // A failed upload never removes the owned source.
    }

    @Test fun actualSubmissionHttpBodyKeepsRepostAuthorButNeverLocalCoverFieldsAndOldBackendAcceptsEmptyOriginalAi() = runBlocking {
        val wire = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        try {
            val api = NovelAppApi(server.url("/api/v1/novels/app").toString(), "test-wire-token", wire, true, authority())
            val repository = NovelEditorRepository(app, account, api)
            for (type in listOf("original", "ai", "repost")) {
                val metadata = NovelMetadata("作品$type", "简介", type = type, source = "来源", authorization = "授权", accepted = true,
                    originalAuthor = if (type == "repost") "真正作者" else "", localCover = "/private/cover.jpg", coverPending = false, coverUrl = "https://media.example.test/cover.jpg")
                val id = repository.create(metadata); val chapter = repository.directory(id).structure.chapters().single().id()
                repository.local { it.checkpoint(chapter, "实际HTTP投稿正文$type", 1, 4, 29, true) }
                val frozen = repository.prepare(id, listOf(chapter))
                server.enqueue(MockResponse().setBody("""{"id":"submission-$type","work_id":"work-$type"}"""))
                server.enqueue(MockResponse().setBody("{}"))
                server.enqueue(MockResponse().setBody("""{"id":"submission-$type","work_id":"work-$type","status":"pending"}"""))
                NovelSubmissionSender(repository).send(frozen)
                val start = server.takeRequest(5, TimeUnit.SECONDS)!!
                val payload = JSONObject(start.body.readUtf8())
                assertEquals(type == "repost", payload.has("original_author"))
                if (type == "repost") assertEquals("真正作者", payload.getString("original_author"))
                assertFalse(payload.has("local_cover")); assertFalse(payload.has("local_cover_pending")); assertFalse(payload.has("accepted"))
                assertTrue(payload.keys().asSequence().none { it.startsWith("local_") })
                assertTrue(server.takeRequest(5, TimeUnit.SECONDS)!!.path!!.contains("/parts/0"))
                assertTrue(server.takeRequest(5, TimeUnit.SECONDS)!!.path!!.endsWith("/finalize"))
            }
        } finally { wire.connectionPool().evictAll(); wire.dispatcher().executorService().shutdownNow() }
    }

    private fun newSession(domain: String, accessToken: String): AccountSession = AccountSession::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance().apply {
        this.domain = domain; self = Account().apply { id = "42"; username = "cover-test" }; token = Token().apply { this.accessToken = accessToken }
    }
    @Suppress("UNCHECKED_CAST")
    private fun sessions(): MutableMap<String, AccountSession> = AccountSessionManager::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        .get(AccountSessionManager.getInstance()) as MutableMap<String, AccountSession>

    private class TestCoverProvider(private val file: File, private val mime: String?) : ContentProvider() {
        var denied = false
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = mime
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (denied) throw SecurityException("Picker grant denied")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
