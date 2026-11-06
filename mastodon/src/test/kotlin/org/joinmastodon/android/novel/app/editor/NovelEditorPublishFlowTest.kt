@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.joinmastodon.android.novel.app.editor

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.joinmastodon.android.R
import org.joinmastodon.android.api.CosMediaUpload
import org.joinmastodon.android.novel.app.NovelAppApiException
import org.joinmastodon.android.novel.app.novelUserMessage
import org.joinmastodon.noveleditor.EditorModels
import org.joinmastodon.noveleditor.NovelEditorStorage
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Local-first publish flow: a pending cover is uploaded exactly once at an explicit submit,
 * metadata drafts are kept locally, and only published chapters are preselected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "zh-rCN")
class NovelEditorPublishFlowTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val account = "editor-publish-${UUID.randomUUID()}"
    private val main = TestCoroutineScheduler()
    private val io = TestCoroutineScheduler()
    private val models = ViewModelStore()
    private val api = EditorTestTransport()
    private val uploads = mutableListOf<File>()
    private var coverResult: (File) -> String = { "https://media.example.test/cover-new.png" }
    private lateinit var repository: NovelEditorRepository

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(main))
        repository = NovelEditorRepository(app, account, api, StandardTestDispatcher(io))
    }
    @After fun cleanup() {
        models.clear(); drain(); Dispatchers.resetMain()
        app.deleteDatabase(NovelEditorStorage.databaseName(account))
        File(app.filesDir, "novel-app/${org.joinmastodon.android.novel.importer.NovelImportCoordinator.accountHash(account)}").deleteRecursively()
    }
    private fun drain() { repeat(40) { main.runCurrent(); io.runCurrent() } }
    private fun model(): NovelEditorController =
        NovelEditorController(app, repository, { file -> uploads += file; coverResult(file) }) { main.currentTime }
            .also { models.put(UUID.randomUUID().toString(), it) }

    private fun create(metadata: NovelMetadata): EditorModels.CreatedWork = NovelEditorStorage(app, account).use { storage ->
        storage.createWork(metadata.title, metadata.description, "fiction").also { work ->
            storage.putAppMetadata(work.workId(), metadata.json().toString(), null, null)
            storage.checkpoint(work.chapterId(), "首发正文", 0, 0, 0, true)
        }
    }
    private fun addDraft(work: EditorModels.CreatedWork, title: String, body: String): String = NovelEditorStorage(app, account).use { storage ->
        storage.addChapter(work.workId(), work.volumeId(), title).id().also { storage.checkpoint(it, body, 0, 0, 0, true) }
    }
    private fun open(controller: NovelEditorController, work: EditorModels.CreatedWork) {
        controller.open(work.workId(), null, "edit"); drain(); controller.showMetadata(); drain()
        assertEquals("metadata", controller.state.value.page)
    }
    private fun stored(): NovelMetadata = NovelEditorStorage(app, account).use { storage ->
        val work = storage.listWorks(false).first()
        NovelMetadata.from(JSONObject(requireNotNull(storage.appMetadata(work.id())).payload()))
    }
    private fun coverFile(): File = File(app.cacheDir, "cover-${UUID.randomUUID()}.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test fun newWorkButtonOnlyCreatesALocalWorkAndNeverSubmits() {
        val controller = model()
        controller.open(null, null, "create"); drain()
        controller.metadata(NovelMetadata("新作品", "本地草稿简介", accepted = true))
        controller.storeMetadata(); drain()
        assertNotNull(controller.state.value.workId); assertEquals("editor", controller.state.value.page)
        assertNull(controller.state.value.error)
        assertTrue("Creating a work must not upload", api.requests.isEmpty())
    }

    @Test fun updateAndPublishPreselectsOnlyPublishedChaptersAndUploadsAPendingCoverExactlyOnce() {
        val local = coverFile()
        val metadata = NovelMetadata("更新作品", "更新简介", accepted = true, localCover = local.absolutePath,
            coverUrl = "https://media.example.test/old.png", coverPending = true)
        val work = create(metadata)
        val draft = addDraft(work, "私密草稿", "绝不能默认上传")
        NovelEditorStorage(app, account).use { it.putRemoteChapter(work.chapterId(), "published", null, null, 1) }
        val controller = model(); open(controller, work)
        var selected: List<String>? = null
        controller.updateAndPublish { selected = it }; drain()
        assertNull(controller.state.value.error)
        assertEquals(listOf(work.chapterId()), selected)
        assertFalse("Private drafts must not be preselected", draft in selected.orEmpty())
        assertEquals("Cover must be uploaded exactly once", 1, uploads.size)
        val saved = stored()
        assertEquals("https://media.example.test/cover-new.png", saved.coverUrl)
        assertFalse(saved.coverPending); assertEquals(local.absolutePath, saved.localCover)
        assertTrue("Leaving metadata open must not submit", api.requests.isEmpty())
        local.delete()
    }

    @Test fun coverFailureAbortsUpdateKeepsTheOldCoverStateAndUploadsNothing() {
        val local = coverFile()
        val metadata = NovelMetadata("更新作品", "更新简介", accepted = true, localCover = local.absolutePath,
            coverUrl = "https://media.example.test/old.png", coverPending = true)
        val work = create(metadata)
        NovelEditorStorage(app, account).use { it.putRemoteChapter(work.chapterId(), "published", null, null, 1) }
        coverResult = { throw NovelCoverUploadException(CosMediaUpload.UploadException(CosMediaUpload.Stage.PUT, 503, CosMediaUpload.Category.NETWORK, null)) }
        val controller = model(); open(controller, work)
        controller.metadata(controller.state.value.metadata.copy(title = "尚未保存的新标题"))
        var selected: List<String>? = null
        controller.updateAndPublish { selected = it }; drain()
        assertNull(selected)
        assertNotNull(controller.state.value.error)
        assertTrue(requireNotNull(controller.state.value.error).contains("封面"))
        val saved = stored()
        assertEquals("更新作品", saved.title)
        assertEquals("https://media.example.test/old.png", saved.coverUrl)
        assertTrue(saved.coverPending); assertEquals(local.absolutePath, saved.localCover)
        assertEquals("A failing cover is attempted once, then aborted", 1, uploads.size)
        assertTrue(api.requests.isEmpty())
        local.delete()
    }

    @Test fun explicitChapterSubmitAutoUploadsAPendingCoverOnceBeforeStart() {
        val local = coverFile()
        val metadata = NovelMetadata("提交作品", "提交简介", accepted = true, localCover = local.absolutePath, coverPending = true)
        val work = create(metadata)
        val controller = model()
        controller.open(work.workId(), null, "edit"); drain()
        controller.submit(listOf(work.chapterId())); drain()
        assertNull(controller.state.value.error)
        assertEquals(1, uploads.size)
        val start = api.requests.single { it.path == "/submissions/start" }
        assertEquals("https://media.example.test/cover-new.png", start.json!!.getString("cover_url"))
        assertTrue(api.requests.none { it.json?.has("local_cover") == true })
        local.delete()
    }

    @Test fun explicitChapterSubmitStopsBeforeStartWhenTheAutoCoverUploadFails() {
        val local = coverFile()
        val metadata = NovelMetadata("提交作品", "提交简介", accepted = true, localCover = local.absolutePath,
            coverUrl = "https://media.example.test/old.png", coverPending = true)
        val work = create(metadata)
        coverResult = { throw NovelCoverUploadException(CosMediaUpload.UploadException(CosMediaUpload.Stage.AUTHORIZE, 401, CosMediaUpload.Category.AUTHENTICATION, null)) }
        val controller = model()
        controller.open(work.workId(), null, "edit"); drain()
        controller.submit(listOf(work.chapterId())); drain()
        assertNotNull(controller.state.value.error); assertTrue(requireNotNull(controller.state.value.error).contains("封面"))
        assertEquals(1, uploads.size)
        assertTrue("No submission may start after a cover failure", api.requests.isEmpty())
        val saved = stored()
        assertEquals("https://media.example.test/old.png", saved.coverUrl); assertTrue(saved.coverPending)
        NovelEditorStorage(app, account).use { assertNull(it.pendingAppSubmission(work.workId())) }
        local.delete()
    }

    @Test fun serverCodesMapToAccurateMessagesInsteadOfOneGenericConflict() {
        val fallback = R.string.novel_en_request_incomplete
        fun message(error: NovelAppApiException) = error.novelUserMessage(app, fallback)
        assertEquals(app.getString(R.string.novel_en_candidate_too_large), message(NovelAppApiException(413, "candidate_too_large", null, "x")))
        assertEquals(app.getString(R.string.novel_en_request_too_large), message(NovelAppApiException(413, "request_too_large", null, "x")))
        assertEquals(app.getString(R.string.novel_en_invalid_chapters), message(NovelAppApiException(422, "invalid_chapters", null, "x")))
        assertEquals(app.getString(R.string.novel_en_response_validation_failed), message(NovelAppApiException(422, "other_validation", null, "x")))
        assertEquals(app.getString(R.string.novel_en_version_updated), message(NovelAppApiException(409, "version_conflict", null, "x")))
        assertEquals(app.getString(R.string.novel_en_conflict_rejected), message(NovelAppApiException(409, "validation_failed", null, "x")))
        assertEquals(app.getString(R.string.novel_en_submission_in_review_conflict), message(NovelAppApiException(409, "submission_frozen", null, "x")))
    }

    @Test fun chapterLimitPrecheckCountsInheritedPublishedChaptersAndStopsLocally() {
        val published = NovelEditorStorage.RemoteChapter("published", null, null, 1)
        val draft = NovelEditorStorage.RemoteChapter("draft", null, null, 1)
        val remote = (0 until MAX_SUBMISSION_CHAPTERS).associate { "published-$it" to published } + ("draft" to draft)
        assertEquals(MAX_SUBMISSION_CHAPTERS, inheritedPublishedChapterCount(remote, listOf("draft")))
        assertTrue(1 + inheritedPublishedChapterCount(remote, listOf("draft")) > MAX_SUBMISSION_CHAPTERS)
        assertEquals(0, inheritedPublishedChapterCount(mapOf("draft" to draft), listOf("draft")))

        val work = create(NovelMetadata("上限作品", "上限简介", accepted = true))
        val tooMany = (0..MAX_SUBMISSION_CHAPTERS).map { "chapter-$it" }
        val error = kotlinx.coroutines.runBlocking { runCatching { repository.prepare(work.workId(), tooMany) }.exceptionOrNull() }
        assertTrue(error is IllegalArgumentException)
        assertEquals("novel_i18n_candidate_too_large", error?.message)
        assertEquals(app.getString(R.string.novel_en_candidate_too_large), requireNotNull(error as? Exception).novelUserMessage(app, R.string.novel_en_request_incomplete))
        assertTrue("The precheck must run before any network call", api.requests.isEmpty())
    }

    @Test fun metadataEditsAreDebounceSavedLocallyWithoutUploads() {
        val work = create(NovelMetadata("旧标题", "旧简介", accepted = true))
        val controller = model(); open(controller, work)
        controller.metadata(controller.state.value.metadata.copy(title = "防抖新标题"))
        drain()
        assertEquals("旧标题", stored().title)
        main.advanceTimeBy(799); drain()
        assertEquals("旧标题", stored().title)
        main.advanceTimeBy(1); drain()
        assertEquals("防抖新标题", stored().title)
        assertEquals("防抖新标题", controller.state.value.directory?.metadata?.title)
        assertTrue(api.requests.isEmpty())
    }

    @Test fun leavingTheMetadataPageFlushesTheLocalDraftImmediately() {
        val work = create(NovelMetadata("旧标题", "旧简介", accepted = true))
        val controller = model(); open(controller, work)
        controller.metadata(controller.state.value.metadata.copy(description = "离开页面也要保留的简介"))
        var left = false
        controller.leaveEditor { left = true }; drain()
        assertTrue(left)
        assertEquals("离开页面也要保留的简介", stored().description)
        assertTrue(api.requests.isEmpty())
    }
}
