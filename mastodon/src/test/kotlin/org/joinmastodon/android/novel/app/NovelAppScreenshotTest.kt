package org.joinmastodon.android.novel.app

import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelStore
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.novel.app.models.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual production NovelAppContent, navigation, VM and repository, with an offline transport only.
 * The numbered PNGs correspond to novel-ui/1..21.png; manifests are review evidence, not goldens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w393dp-h852dp-notnight-mdpi", application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelAppScreenshotTest {
    private val fixture = NovelScreenshotFixture("app")
    private val compose get() = fixture.host
    @get:Rule val rules: org.junit.rules.TestRule = fixture
    private val models = ViewModelStore()
    private val history = MemoryNovelSearchHistory()
    private val backend = ScreenshotNovelBackend()
    private val transport = TestNovelTransport(backend::reply)
    private lateinit var vm: NovelAppViewModel
    private var read: Pair<String, String?>? = null
    private var created: Triple<String?, String?, String>? = null
    private var sharedWork: String? = null
    private var sharedAuthor: Long? = null
    private var openedMessage: String? = null
    private var privateLibrary = 0
    private var verifications = 0
    private var posts = 0
    private var back = 0

    @Before fun prepareModel() {
        compose.runOnUiThread {
            NovelAppTestFixtures.state().history.reversed().forEach { history.remember(NovelScreenshotFixture.ACCOUNT, it) }
            vm = NovelAppViewModel({ NovelRepository(transport) }, history, { it == NovelScreenshotFixture.ACCOUNT }, { listOf(NovelAppTestFixtures.local) })
            models.put("screenshot-app", vm)
            vm.bindAccount(NovelScreenshotFixture.ACCOUNT, NovelAppTestFixtures.author.copy(id = 99, displayName = "本机用户（测试）"))
        }
    }
    @After fun releaseModel() = compose.runOnUiThread { models.clear() }

    private fun show(page: NovelPage = NovelPage.Store, dark: Boolean = false, setup: () -> Unit = {}) {
        fixture.setDark(compose, dark)
        compose.runOnUiThread { vm.navigate(page); setup() }
        fixture.show(compose) {
            val state by vm.state.collectAsState()
            NovelAppContent(state, vm, liquid = false, onBack = { back++ },
                onRead = { work, chapter -> read = work.id to chapter },
                onCreate = { work, local, mode -> created = Triple(work, local, mode) },
                onPrivateLibrary = { privateLibrary++ }, onVerify = { verifications++ }, onPost = { posts++ },
                onShareWork = { sharedWork = it.id }, onShareAuthor = { sharedAuthor = it.author.id },
                onMessage = { openedMessage = it.id })
        }
    }
    private fun shot(id: Int, state: String) = fixture.capture(compose, "novel-ui$id-$state-light-393x852", id)
    private fun title(text: String) = compose.onAllNodesWithText(text)[0].assertIsDisplayed()
    private fun click(text: String) { compose.onNodeWithText(text).performClick(); fixture.settle(compose) }

    @Test fun storeVariantsRenderAndRealTabsRequestTheirOwnTypes() {
        show()
        compose.onNodeWithText("小说").assertIsDisplayed()
        compose.onNodeWithContentDescription("小说消息").assertHasClickAction()
        title(NovelAppTestFixtures.work().title)
        shot(21, "store-featured")
        for ((label, type, reference) in listOf(Triple("原创", "original", 20), Triple("搬运", "repost", 19), Triple("AI创作", "ai", 18))) {
            click(label)
            assertEquals(type, vm.state.value.storeType)
            assertTrue(vm.state.value.works.value!!.items.all { it.workType == type })
            assertTrue(transport.requests.last().path.contains("type=$type"))
            title(vm.state.value.works.value!!.items.first().title)
            shot(reference, "store-$type")
        }
        compose.onNodeWithText("搜索作品、作者、标签").performClick(); fixture.settle(compose)
        assertEquals(NovelPage.Search, vm.state.value.page)
        compose.onNodeWithText("最近搜索").assertIsDisplayed()
    }

    @Test fun searchHistoryAndResultsUseTheRealSearchFieldAndImeAction() {
        show(NovelPage.Search)
        compose.onNodeWithText("最近搜索").assertIsDisplayed()
        compose.onNodeWithText("大家都在搜").assertIsDisplayed()
        compose.onNodeWithText("清空").assertHasClickAction()
        shot(2, "search")
        compose.onNode(hasSetTextAction()).performTextReplacement("日常")
        compose.onNode(hasSetTextAction()).performImeAction(); fixture.settle(compose)
        assertEquals("日常", vm.state.value.query)
        compose.onNodeWithText("“日常” 的搜索结果", substring = true).assertIsDisplayed()
        title(NovelAppTestFixtures.work().title)
        shot(1, "search-results")
        compose.onNodeWithText("清除搜索词", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("清除搜索词").performClick(); fixture.settle(compose)
        click("清空")
        assertTrue(history.read(NovelScreenshotFixture.ACCOUNT).isEmpty())
        compose.onNodeWithText("还没有搜索记录").assertIsDisplayed()
    }

    @Test fun lockedCreatorKeepsApplyDisabledAndVerificationIsAnExplicitCallback() {
        backend.active = false; backend.eligible = false
        show(NovelPage.Creator)
        compose.onNodeWithText("创作者中心").assertIsDisplayed()
        compose.onNodeWithText("已完成 1 / 3 项").assertIsDisplayed()
        shot(3, "creator-locked")
        compose.onNodeWithText("开通创作者身份").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("去认证").performScrollTo().performClick()
        assertEquals(1, verifications)
        assertTrue(transport.requests.none { it.path == "/creator/apply" })
    }

    @Test fun activeCreatorRendersRealStatsAndStartsCreateAndImportOnlyOnClick() {
        show(NovelPage.Creator)
        compose.onNodeWithText("已开通创作者").assertIsDisplayed()
        compose.onNodeWithText("新建作品").assertIsEnabled()
        compose.onNodeWithText("上传文稿").assertIsEnabled()
        shot(15, "creator")
        click("新建作品"); assertEquals(Triple(null, null, "create"), created)
        click("上传文稿"); assertEquals(Triple(null, null, "import"), created)
        compose.onNodeWithText("全部作品  ›").performScrollTo().performClick(); fixture.settle(compose)
        assertEquals(NovelPage.OwnerWorks, vm.state.value.page)
        compose.onNodeWithText("全部创作").assertIsDisplayed()
        fixture.capture(compose, "owner-works-light-393x852")
        click("本地稿 1")
        title(NovelAppTestFixtures.local.title)
        compose.onNodeWithContentDescription("继续编辑本地稿").performClick()
        assertEquals(Triple(null, NovelAppTestFixtures.local.localId, "edit"), created)
    }

    @Test fun shelfRendersActualProgressAndContinueReadingCallback() {
        show(NovelPage.Shelf)
        compose.onNodeWithText("继续阅读").assertIsDisplayed()
        compose.onNodeWithText("已读 36% · 连载中").assertExists()
        compose.onNodeWithText("接着读").assertIsEnabled()
        shot(16, "shelf")
        click("接着读")
        // Let the reader restore account-scoped progress against the current approved release.
        assertEquals("fixture-work-0" to null, read)
        compose.onAllNodesWithText("已读完")[0].performClick(); fixture.settle(compose)
        assertEquals(1, vm.state.value.shelfItems.size)
        assertTrue(vm.state.value.shelfItems.single().progress!!.completed)
        compose.onNodeWithContentDescription("书架更多").performClick()
        click("私人书库")
        assertEquals(1, privateLibrary)
    }

    @Test fun detailAndAuthorUseProductionActionsAndKeepPublicBodiesOutOfTheListFixture() {
        show(NovelPage.Detail("fixture-work-0"))
        compose.onNodeWithText("作品详情").assertIsDisplayed()
        compose.onNodeWithText("开始阅读").assertIsEnabled()
        compose.onNodeWithText("加入书架").assertIsEnabled()
        shot(11, "detail")
        click("开始阅读"); assertEquals("fixture-work-0" to null, read)
        compose.onNodeWithContentDescription("分享作品").performClick(); assertEquals("fixture-work-0", sharedWork)
        click("加入书架")
        assertTrue(vm.state.value.work.value!!.inShelf)
        compose.onNodeWithText("已在书架").assertIsEnabled()
        compose.runOnUiThread { vm.dismissNotice(); vm.navigate(NovelPage.AuthorProfile(42)) }; fixture.settle(compose)
        compose.onNodeWithText("作者主页").assertIsDisplayed()
        compose.onNodeWithText("+ 关注").assertIsEnabled()
        shot(6, "author")
        click("+ 关注"); assertTrue(vm.state.value.author.value!!.following)
        compose.onNodeWithText("已关注").assertIsDisplayed()
        compose.onNodeWithContentDescription("分享作者").performClick(); assertEquals(42L, sharedAuthor)
    }

    @Test fun messagesRenderUnreadRowsAndAllReadDispatchesTheRealMutation() {
        show(NovelPage.Messages)
        compose.onNodeWithText("收藏的作品更新了").assertIsDisplayed()
        compose.onNodeWithText("全部已读").assertHasClickAction()
        shot(4, "messages")
        click("全部已读")
        assertEquals(0, vm.state.value.messages.value!!.unread)
        assertTrue(vm.state.value.messages.value!!.items.all { it.read })
        click("收藏的作品更新了")
        assertEquals("fixture-message-update", openedMessage)
        assertTrue(transport.requests.any { it.method == "POST" && it.path == "/messages/read" })
    }

    @Test fun allWorksAndSupplementaryChaptersCommentsStatisticsHaveRealPageCaptures() {
        show(NovelPage.AllWorks)
        compose.onNodeWithText("全部图书").assertIsDisplayed()
        compose.onNodeWithText("综合排序 ﹀").assertHasClickAction()
        shot(17, "all-works")
        click("综合排序 ﹀")
        // Current production Sorts: updated -> 最近更新, inside the real PopupLayout window.
        compose.onNodeWithText("最近更新").assertIsDisplayed().assertHasClickAction().performClick()
        fixture.settle(compose)
        assertEquals("updated", vm.state.value.sort)
        compose.runOnUiThread { vm.navigate(NovelPage.Chapters("fixture-work-0")) }; fixture.settle(compose)
        compose.onNodeWithText("全部目录").assertIsDisplayed()
        compose.onNodeWithText("第 1 章 · 属于自己的角落").assertIsDisplayed().assertHasClickAction()
        fixture.capture(compose, "chapters-light-393x852", sparseTextRegions = listOf("全部目录", "第 1 章 · 属于自己的角落", "第 13 章 · 留给自己的空间"))
        compose.runOnUiThread { vm.navigate(NovelPage.Comments("fixture-work-0")) }; fixture.settle(compose)
        compose.onNodeWithText(NovelAppTestFixtures.comment.body).assertIsDisplayed()
        compose.onNodeWithText("发布").assertIsNotEnabled()
        fixture.capture(compose, "comments-light-393x852")
        compose.runOnUiThread { vm.navigate(NovelPage.Statistics) }; fixture.settle(compose)
        compose.onNodeWithText("累计表现").assertIsDisplayed()
        compose.onNodeWithText("创作概况").assertIsDisplayed()
        fixture.capture(compose, "statistics-light-393x852")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-night-mdpi")
    fun narrowDarkStoreAndDetailKeepTheirRealNavigationAndReadingActionsVisible() {
        show(dark = true)
        compose.onNodeWithText("小说").assertIsDisplayed()
        compose.onNodeWithText("AI创作").assertIsDisplayed()
        fixture.capture(compose, "store-featured-dark-320x640", 21)
        compose.runOnUiThread { vm.openWork("fixture-work-0") }; fixture.settle(compose)
        compose.onNodeWithText("作品详情").assertIsDisplayed()
        compose.onNodeWithText("开始阅读").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("加入书架").assertIsDisplayed().assertIsEnabled()
        fixture.capture(compose, "detail-dark-320x640", 11)
        click("开始阅读"); assertEquals("fixture-work-0" to null, read)
    }
}

/** JSON transport seam reuses the shared fictional fixture; no UI/layout/theme substitutions. */
private class ScreenshotNovelBackend {
    var active = true
    var eligible = true
    private val base = NovelAppTestFixtures.state()
    private val all = (0..8).map { NovelAppTestFixtures.work(it, when (it) { 3, 6 -> "ai"; 5, 7 -> "repost"; else -> "original" }) }
    private var messages = NovelAppTestFixtures.messages
    private var collected = false
    private var following = false
    suspend fun reply(method: String, path: String, body: JSONObject?, key: String?): JSONObject {
        if (method != "GET") {
            assertNotNull("All test mutations still pass through the real UUID operation seam", key)
            assertEquals(key, java.util.UUID.fromString(key).toString())
            return when {
                path.startsWith("/shelf/") -> JSONObject().put("collected", body!!.getBoolean("collected").also { collected = it })
                path.endsWith("/follow") -> JSONObject().put("following", body!!.getBoolean("following").also { following = it })
                path == "/messages/read" -> {
                    val ids = body!!.optJSONArray("ids")
                    val readIds = if (ids == null) messages.items.map { it.id }.toSet() else (0 until ids.length()).map { ids.getString(it) }.toSet()
                    messages = messages.copy(items = messages.items.map { if (it.id in readIds) it.copy(read = true) else it })
                    messages = messages.copy(unread = messages.items.count { !it.read }); JSONObject().put("success", true)
                }
                path.endsWith("/search-click") -> JSONObject().put("success", true)
                else -> error("Unexpected screenshot fixture mutation: $method $path")
            }
        }
        val uri = Uri.parse("https://example.test$path")
        return when {
            uri.path == "/works" -> {
                val type = uri.getQueryParameter("type") ?: "all"
                val works = if (!uri.getQueryParameter("query").isNullOrBlank()) listOf(all[0], all[3], all[7]) else all
                NovelAppTestFixtures.page(*works.filter { type == "all" || it.workType == type }.toTypedArray())
            }
            path == "/hot" -> NovelAppTestFixtures.page(*all.take(5).toTypedArray())
            path == "/shelf" -> JSONObject().put("items", JSONArray(base.shelf.value!!.items.map(::workJson))).put("continue_work", workJson(base.shelf.value!!.continueWork!!))
            path == "/creator" -> NovelAppTestFixtures.creatorJson(active, eligible).put("works", JSONArray(NovelAppTestFixtures.creator().works.map(::workJson))).put("stats", statsJson())
            path == "/owner/works" -> NovelAppTestFixtures.page(*NovelAppTestFixtures.creator().works.toTypedArray())
            path == "/stats" -> statsJson()
            uri.path == "/authors/42" -> JSONObject().put("author", NovelAppTestFixtures.authorJson()).put("stats", statsJson()).put("following", following).put("items", JSONArray(all.take(2).map(::workJson)))
            uri.path?.endsWith("/comments") == true -> JSONObject().put("items", JSONArray().put(commentJson())).put("next_cursor", JSONObject.NULL)
            uri.path?.startsWith("/works/") == true -> workJson(all.first { it.id == uri.lastPathSegment }.copy(inShelf = collected))
            uri.path == "/messages" -> {
                val category = uri.getQueryParameter("category") ?: "all"
                JSONObject().put("items", JSONArray(messages.items.filter { category == "all" || it.category == category }.map { m -> JSONObject().put("id", m.id).put("category", m.category).put("title", m.title).put("body", m.body).put("work_id", m.workId).put("chapter_id", m.chapterId ?: JSONObject.NULL).put("created_at", m.createdAt).put("read", m.read) }))
                    .put("next_cursor", JSONObject.NULL).put("unread", messages.unread)
            }
            else -> error("Unexpected screenshot fixture GET: $path")
        }
    }
    private fun workJson(work: Work) = NovelAppTestFixtures.workJson(work).apply {
        work.progress?.let { put("progress", it.toJson().put("updated_at", it.updatedAt)) }
    }
    private fun statsJson() = NovelAppTestFixtures.stats.let { JSONObject().put("works", it.works).put("followers", it.followers).put("words", it.words).put("reads", it.reads).put("collections", it.collections).put("comments", it.comments).put("read_delta", it.readDelta).put("collection_delta", it.collectionDelta).put("comment_delta", it.commentDelta) }
    private fun commentJson() = NovelAppTestFixtures.comment.let { JSONObject().put("id", it.id).put("body", it.body).put("author", NovelAppTestFixtures.authorJson(it.author)).put("created_at", it.createdAt).put("can_delete", it.canDelete) }
}
