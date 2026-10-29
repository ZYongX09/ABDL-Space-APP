@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.joinmastodon.android.novel.app.editor

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.joinmastodon.android.novel.NovelAccountDataCleaner
import org.joinmastodon.android.novel.app.NovelAppApiException
import org.joinmastodon.noveleditor.EditorText
import org.joinmastodon.noveleditor.NovelEditorStorage
import org.joinmastodon.reader.data.*
import org.joinmastodon.reader.domain.ReaderPosition
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class NovelReaderBehaviorTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val account = "reader-behavior-${UUID.randomUUID()}"
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val api = EditorTestTransport()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.clear(); Dispatchers.resetMain(); NovelDatabase.closeAccount(account); app.deleteDatabase(NovelEditorStorage.databaseName(account)); app.deleteDatabase(NovelDatabase.databaseName(account)) }
    private fun repository() = NovelEditorRepository(app, account, api, dispatcher)
    private fun controller(public: String? = null, local: String? = null, privateBook: String? = null) = NovelReaderController(app, repository(), public, local, privateBook).also { models.put(UUID.randomUUID().toString(), it) }
    private suspend fun <T> db(block: suspend (NovelDatabase) -> T): T = withContext(Dispatchers.IO) { NovelDatabase.open(app, account).let { database -> try { block(database) } finally { database.close() } } }
    private fun headers(vararg ids: String) = JSONArray().apply { ids.forEach { id -> put(JSONObject().put("id", id).put("title", "章节$id").put("word_count", 100)) } }

    @Test fun localReaderLoadsOnlyCurrentChapterAndPersistsPositionBookmarkAndNoteAcrossRestart() = runTest(dispatcher) {
        val work = NovelEditorStorage(app, account).use { storage -> storage.createWork("本地预览", "简介", "fiction").also { created -> storage.checkpoint(created.chapterId(), "本地第一章正文", 0, 0, 0, true); storage.addChapter(created.workId(), created.volumeId(), "第二章").also { storage.checkpoint(it.id(), "第二章正文", 0, 0, 0, true) } } }
        val model = controller(local = work.workId()); model.start(); advanceUntilIdle()
        assertEquals("本地第一章正文", model.state.value.chapter!!.content); assertEquals(2, model.state.value.headers.size)
        model.position(ReaderPosition(0, 4)); model.annotation(); model.annotation("真实本机笔记"); advanceUntilIdle()
        NovelEditorStorage(app, account).use { storage -> val mark = requireNotNull(storage.readingMark("local:${work.workId()}")); assertEquals(work.chapterId(), mark.chapterId()); assertEquals(4, mark.page()); assertEquals(2, storage.readingAnnotations("local:${work.workId()}").size) }
        val reopened = controller(local = work.workId()); reopened.start(); advanceUntilIdle(); assertEquals(4, reopened.state.value.page); assertEquals(2, reopened.state.value.annotations.size)
        reopened.open(1); advanceUntilIdle(); assertEquals("第二章正文", reopened.state.value.chapter!!.content); assertEquals(0, reopened.state.value.page)
        assertTrue(api.requests.isEmpty())
    }

    @Test fun publicReaderIsLazyUsesStableWorkIdentityAndRealCallbacksPersistOnlyProgress() = runTest(dispatcher) {
        var release = "release-1"; var ordered = listOf("one", "two", "three")
        api.reply = { request -> when {
            request.path == "/works/public" -> JSONObject().put("title", "公开作品").put("release_id", release).put("chapters", headers(*ordered.toTypedArray()))
            request.path == "/progress/public" && request.method == "GET" -> JSONObject().put("progress", JSONObject.NULL)
            request.path.contains("/chapters/") -> { val id = request.path.substringAfter("/chapters/").substringBefore('?'); val body = "只加载$id 的完整正文"; JSONObject().put("id", id).put("release_id", release).put("body", body).put("body_sha256", EditorText.sha256(body)) }
            else -> JSONObject()
        } }
        val model = controller(public = "public"); model.start("two"); advanceUntilIdle()
        assertEquals(listOf("two"), api.requests.filter { it.path.contains("/chapters/") }.map { it.path.substringAfter("/chapters/").substringBefore('?') })
        assertEquals("public:public", model.state.value.book!!.id)
        model.position(ReaderPosition(0, 7)); model.annotation(); model.annotation("公开作品的本机笔记"); advanceUntilIdle()
        val put = api.requests.last { it.method == "PUT" }; assertEquals("two", put.json!!.getString("chapter_id")); assertEquals(7, put.json.getInt("offset")); assertEquals("release-1", put.json.getString("release_id"))
        assertTrue(api.requests.none { it.path.contains("bookmark") || it.path.contains("note") || it.path.contains("submissions") })
        release = "release-2"; ordered = listOf("three", "two", "one")
        val reopened = controller(public = "public"); reopened.start(); advanceUntilIdle()
        assertEquals("public:public", reopened.state.value.book!!.id); assertEquals("two", reopened.state.value.chapter!!.id); assertEquals(7, reopened.state.value.page); assertNotNull(reopened.state.value.notice); assertEquals(2, reopened.state.value.annotations.size)
        reopened.open(0); advanceUntilIdle(); assertEquals("three", reopened.state.value.chapter!!.id)
    }

    @Test fun forbiddenOrChangedPublicReleaseClearsOldTextAndPreservesLocalMarksAndAnnotations() = runTest(dispatcher) {
        var failure = 0
        api.reply = { request -> when {
            request.path == "/works/public" -> JSONObject().put("title", "公开作品").put("release_id", "release").put("chapters", headers("one", "two"))
            request.path == "/progress/public" -> JSONObject().put("progress", JSONObject.NULL)
            request.path.contains("/chapters/") -> if (failure != 0) throw NovelAppApiException(failure, "changed", JSONObject().put("release_id", "next"), "changed") else JSONObject().put("body", "不可继续展示的旧正文").put("release_id", "release")
            else -> JSONObject()
        } }
        val model = controller(public = "public"); model.start(); advanceUntilIdle(); model.position(ReaderPosition(0, 3)); model.annotation("旧版本笔记"); advanceUntilIdle()
        failure = 409; model.open(1); advanceUntilIdle(); assertNull(model.state.value.chapter); assertTrue(model.state.value.changed); assertNotNull(model.state.value.error)
        failure = 404; model.start(); advanceUntilIdle(); assertNull(model.state.value.chapter); assertFalse(model.state.value.changed); assertTrue(model.state.value.error!!.contains("不可用"))
        NovelEditorStorage(app, account).use { storage -> assertEquals("one", requireNotNull(storage.readingMark("public:public")).chapterId()); assertEquals(1, storage.readingAnnotations("public:public").size) }
    }

    @Test fun privateRoomReaderResumesLegacyProgressAndSavesRealBookmarksNotesAndUnicodeChunks() = runTest(dispatcher) {
        val book = "private-local"; val chapter = "private-two"; val body = "🦊".repeat(130000) + "最后一段不能跳过"
        db { database ->
            database.novelBookDao().upsert(NovelBookEntity(book, account, "私人书库"))
            database.novelChapterDao().upsert(listOf(NovelChapterEntity("private-one", book, "第一章", "一", 0), NovelChapterEntity(chapter, book, "第二章", body, 1)))
            database.syncDao().upsertProgress(NovelProgressEntity("progress:$book", account, book, JSONObject().put("chapterIndex", 1).put("pageIndex", 6).toString(), 123, null))
        }
        val model = controller(privateBook = book)
        suspend fun awaitRoomOperations() {
            // Room's own executor is real IO, outside TestCoroutineScheduler.advanceUntilIdle().
            // Joining the actual controller jobs waits for their DAO continuations and state updates.
            do {
                val pending = requireNotNull(model.viewModelScope.coroutineContext[Job]).children.toList()
                pending.joinAll()
            } while (requireNotNull(model.viewModelScope.coroutineContext[Job]).children.any { it.isActive })
        }
        model.start(); awaitRoomOperations()
        assertNull(model.state.value.error); assertEquals(chapter, model.state.value.chapter!!.id); assertEquals(body, model.state.value.chapter!!.content); assertEquals(6, model.state.value.page)
        model.position(ReaderPosition(0, 8)); model.annotation(); model.annotation("Room真实笔记"); awaitRoomOperations()
        db { database -> assertEquals(8, database.bookmarkDao().getByBookId(account, book).single().position); assertEquals("Room真实笔记", database.annotationDao().getByBookId(account, book).single().note); assertEquals(8, JSONObject(database.syncDao().progress(account, "progress:$book")!!.payload).getInt("pageIndex")); assertTrue(database.syncDao().pending(account).isEmpty()) }
        assertTrue(api.requests.isEmpty())
    }

    @Test fun privateReaderCannotRecaptureGenerationAfterLogoutAndRecreateDeletedRoomDatabase() = runTest(dispatcher) {
        val model = controller(privateBook = "book")
        NovelAccountDataCleaner.invalidate(account)
        model.start(); advanceUntilIdle()
        assertNotNull(model.state.value.error); assertFalse(app.getDatabasePath(NovelDatabase.databaseName(account)).exists()); assertFalse(app.getDatabasePath(NovelEditorStorage.databaseName(account)).exists()); assertTrue(api.requests.isEmpty())
    }
}
