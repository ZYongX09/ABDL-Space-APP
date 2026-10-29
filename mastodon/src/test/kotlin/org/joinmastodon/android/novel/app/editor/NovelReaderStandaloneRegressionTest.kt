@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.joinmastodon.android.novel.app.editor

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.joinmastodon.android.novel.app.NovelAppTransport
import org.joinmastodon.noveleditor.EditorText
import org.joinmastodon.noveleditor.NovelEditorStorage
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
class NovelReaderStandaloneRegressionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val account = "standalone-reader-${UUID.randomUUID()}"
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val writes = mutableListOf<JSONObject>()
    private val bodies = mutableListOf<String>()
    private val headers get() = JSONArray().put(JSONObject().put("id", "one").put("title", "第一章").put("word_count", 100)).put(JSONObject().put("id", "two").put("title", "第二章").put("word_count", 100))
    private val api = object : NovelAppTransport {
        override suspend fun raw(method: String, path: String, body: JSONObject?, idempotencyKey: String?): JSONObject = when {
            path == "/works/public" -> JSONObject().put("title", "百分比与继续阅读回归").put("release_id", "release").put("chapters", headers).put("progress", JSONObject().put("chapter_id", "two").put("offset", 7).put("percent", 50.0).put("completed", false).put("release_id", "release"))
            path.contains("/chapters/") -> {
                val id = path.substringAfter("/chapters/").substringBefore('?'); bodies += id
                val text = "第 $id 章足够多页的正文。\n".repeat(1200)
                JSONObject().put("id", id).put("body", text).put("body_sha256", EditorText.sha256(text)).put("release_id", "release")
            }
            path == "/progress/public" && method == "PUT" -> { writes += JSONObject(requireNotNull(body).toString()); JSONObject().put("progress", body) }
            path.endsWith("/read") -> JSONObject().put("counted", true)
            else -> error("Unexpected $method $path")
        }
    }
    private fun controller() = NovelReaderController(app, NovelEditorRepository(app, account, api, dispatcher), "public", null, null).also { models.put(UUID.randomUUID().toString(), it) }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.clear(); Dispatchers.resetMain(); app.deleteDatabase(NovelEditorStorage.databaseName(account)) }

    @Test fun continueWithoutExplicitChapterRestoresPageSevenAndWritesFiftyPercentNotPointFive() = runTest(dispatcher) {
        val controller = controller(); controller.start(); advanceUntilIdle()
        assertEquals("two", controller.state.value.chapter!!.id); assertEquals(7, controller.state.value.page); assertEquals(listOf("two"), bodies)
        assertTrue("Loading must not immediately overwrite the cloud mark with page zero", writes.isEmpty())
        // The real pager's initial callback is built from the restored state.page, not an explicit chapter reset.
        controller.position(ReaderPosition(0, controller.state.value.page)); advanceUntilIdle()
        val progress = writes.single(); assertEquals(7, progress.getInt("offset")); assertEquals(50.0, progress.getDouble("percent"), 0.0); assertEquals("two", progress.getString("chapter_id"))
        NovelEditorStorage(app, account).use { s -> val mark = requireNotNull(s.readingMark("public:public")); assertEquals(7, mark.page()); assertEquals("two", mark.chapterId()) }
        val restarted = controller(); restarted.start(); advanceUntilIdle(); assertEquals(7, restarted.state.value.page)
        assertTrue(writes.none { it.getInt("offset") == 0 })
    }

    @Test fun explicitDirectoryChapterStillStartsAtZeroInsteadOfSilentlyResumingSavedPage() = runTest(dispatcher) {
        NovelEditorStorage(app, account).use { it.putReadingMark("public:public", "two", 7, "release") }
        val controller = controller(); controller.start("two"); advanceUntilIdle()
        assertEquals("two", controller.state.value.chapter!!.id); assertEquals(0, controller.state.value.page); assertTrue(writes.isEmpty())
        controller.position(ReaderPosition(0, 0)); advanceUntilIdle()
        assertEquals(0, writes.single().getInt("offset")); assertEquals(50.0, writes.single().getDouble("percent"), 0.0)
    }
}
