@file:Suppress("DEPRECATION")

package org.joinmastodon.android.friendcards

import android.app.Activity
import android.app.Fragment
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Pair
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.Toolbar
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import me.grishka.appkit.Nav
import me.grishka.appkit.api.APIRequest
import me.grishka.appkit.api.Callback
import me.grishka.appkit.fragments.AppKitFragment
import me.grishka.appkit.views.FragmentRootLinearLayout
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.R
import org.joinmastodon.android.api.MastodonAPIRequest
import org.joinmastodon.android.api.MastodonErrorResponse
import org.joinmastodon.android.api.requests.friendcards.FriendCardInput
import org.joinmastodon.android.api.requests.friendcards.FriendCardResponses
import org.joinmastodon.android.api.session.AccountSession
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.fragments.diapers.DiaperDetailFragment
import org.joinmastodon.android.fragments.friendcards.FriendCardBaseFragment
import org.joinmastodon.android.fragments.friendcards.FriendCardCreateFragment
import org.joinmastodon.android.fragments.friendcards.FriendCardDetailFragment
import org.joinmastodon.android.fragments.friendcards.FriendCardDiaperSelectFragment
import org.joinmastodon.android.fragments.friendcards.FriendCardFeedFragment
import org.joinmastodon.android.fragments.friendcards.MyFriendCardsFragment
import org.joinmastodon.android.model.Account
import org.joinmastodon.android.model.FriendCard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import java.lang.reflect.Field
import java.util.concurrent.TimeUnit

/**
 * Real platform Fragments, Base ComposeView, MiuixAppTheme and production Screens.
 * Only remote execution/cancellation and navigation are shadowed. In particular,
 * callbacks must invalidate Base themselves: there is no test setContent, State,
 * replacement Screen, LocalViews shadow, or manual call to changed()/refresh().
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    qualifiers = "w393dp-h852dp-mdpi",
    application = CompatibilityTestApplication::class,
    shadows = [
        FriendCardCompositionBehaviorTest.LocalRequests::class,
        FriendCardCompositionBehaviorTest.LocalNavigation::class,
    ],
)
@LooperMode(LooperMode.Mode.PAUSED)
class FriendCardCompositionBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var account: String
    private lateinit var sessions: MutableMap<String, AccountSession>
    private var previousSession: AccountSession? = null
    private var previousActive: String? = null
    private var containerId = View.NO_ID
    private val mounted = mutableListOf<FriendCardBaseFragment>()

    @Implements(MastodonAPIRequest::class)
    class LocalRequests {
        @RealObject private lateinit var request: MastodonAPIRequest<*>

        @Implementation
        fun exec(account: String): MastodonAPIRequest<*> {
            requests.add(request)
            return request
        }

        // Preserve the callback so lifecycle tests can still deliver a late response.
        @Implementation fun cancel() = Unit

        companion object {
            val requests = mutableListOf<MastodonAPIRequest<*>>()
        }
    }

    @Implements(Nav::class)
    class LocalNavigation {
        companion object {
            var target: Class<out Fragment>? = null
            var args: Bundle? = null
            var requestCode: Int? = null
            var receiver: AppKitFragment? = null
            var finished: Fragment? = null

            @JvmStatic @Implementation
            fun go(activity: Activity, type: Class<out Fragment>, bundle: Bundle?) {
                target = type
                args = bundle?.let { Bundle(it) }
            }

            @JvmStatic @Implementation
            fun goForResult(
                activity: Activity,
                type: Class<out AppKitFragment>,
                bundle: Bundle?,
                code: Int,
                resultReceiver: AppKitFragment,
            ) {
                target = type
                args = bundle?.let { Bundle(it) }
                requestCode = code
                receiver = resultReceiver
            }

            @JvmStatic @Implementation
            fun finish(fragment: Fragment) {
                finished = fragment
            }

            fun reset() {
                target = null
                args = null
                requestCode = null
                receiver = null
                finished = null
            }
        }
    }

    @Before
    @Suppress("UNCHECKED_CAST")
    fun setUp() {
        MastodonApp.context = RuntimeEnvironment.getApplication()
        val manager = AccountSessionManager.getInstance()
        sessions = field(AccountSessionManager::class.java, "sessions").get(manager) as MutableMap<String, AccountSession>
        previousActive = field(AccountSessionManager::class.java, "lastActiveAccountID").get(manager) as String?
        val session = AccountSession::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance().apply {
            domain = "composition-fixture.example.test"
            self = Account().apply { id = "42" }
        }
        account = session.getID()
        previousSession = sessions.put(account, session)
        field(AccountSessionManager::class.java, "lastActiveAccountID").set(manager, account)
        drafts().edit().clear().commit()
        LocalRequests.requests.clear()
        LocalNavigation.reset()
        compose.activityRule.scenario.onActivity { activity ->
            activity.setTheme(R.style.Theme_Mastodon_Light)
            val container = FrameLayout(activity).apply {
                id = View.generateViewId()
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            containerId = container.id
            // ComponentActivity supplies the real ViewTree lifecycle/saved-state owners.
            activity.setContentView(container)
        }
    }

    @After
    fun tearDown() {
        compose.activityRule.scenario.onActivity { activity ->
            val manager = activity.fragmentManager
            mounted.filter { it.isAdded }.forEach { manager.beginTransaction().remove(it).commit() }
            manager.executePendingTransactions()
            drafts().edit().clear().commit()
        }
        if (::sessions.isInitialized && ::account.isInitialized) {
            previousSession?.let { sessions[account] = it } ?: sessions.remove(account)
            field(AccountSessionManager::class.java, "lastActiveAccountID").set(AccountSessionManager.getInstance(), previousActive)
        }
        LocalRequests.requests.clear()
        LocalNavigation.reset()
    }

    @Test
    fun optionsSuccessLeavesTheFirstLoadingFrameAndMeFailureBecomesVisible() {
        val fragment = mount(FriendCardCreateFragment())
        val content = realComposeView(fragment)
        compose.onNodeWithText("正在读取当前有效宝宝认证状态…").assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
        val me = request("/me")

        success(request("/options"), options())
        inputWithHint("用一句话介绍你").assertIsDisplayed()
        compose.onNodeWithText("正在读取当前有效宝宝认证状态…").assertDoesNotExist()
        assertSame(content, realComposeView(fragment))

        failure(me, "当前卡读取失败")
        inList(hasText("当前卡读取失败")).assertIsDisplayed()
        inList(hasText("重试 / 保存草稿")).assertIsDisplayed()
        assertSame(content, realComposeView(fragment))
    }

    @Test
    fun optionsErrorReplacesLoadingAndTheRealRetryCallbackUnlocksTheEditor() {
        mount(FriendCardCreateFragment())
        compose.onNodeWithText("正在读取当前有效宝宝认证状态…").assertIsDisplayed()
        failure(request("/options"), "资格选项读取失败")
        success(request("/me"), FriendCardResponses.Me())
        inList(hasText("资格选项读取失败")).assertIsDisplayed()
        compose.onNodeWithText("正在读取当前有效宝宝认证状态…").assertDoesNotExist()
        assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())

        clickInList("重试读取资格")
        compose.onNodeWithText("正在读取当前有效宝宝认证状态…").assertIsDisplayed()
        ready()
        inputWithHint("用一句话介绍你").assertIsDisplayed()
        compose.onNodeWithText("资格选项读取失败").assertDoesNotExist()
    }

    @Test
    fun consecutiveEditsEchoImmediatelyAndMeCallbackPreservesFocusSelectionAndScroll() {
        mount(FriendCardCreateFragment())
        val me = request("/me")
        success(request("/options"), options())
        val input = inputWithHint("用一句话介绍你")
        val id = input.fetchSemanticsNode().id
        var expected = ""
        for (part in listOf("连", "续", "输", "入")) {
            expected += part
            node(id).performTextInput(part)
            node(id).assertEditable(expected)
        }
        node(id).performTextInputSelection(TextRange(1, 3))
        node(id).assertIsFocused()
        val scrollBefore = listScroll()

        // This production callback updates plain draft/currentLoaded, then calls changed().
        success(me, FriendCardResponses.Me())
        node(id).assertEditable("连续输入").assertIsFocused().assertSelection(TextRange(1, 3))
        assertEquals(scrollBefore, listScroll(), 0.00001f)
        node(id).performTextInput("替换")
        node(id).assertEditable("连替换入").assertIsFocused()
        assertEquals("连替换入", FriendCardDraftStore(compose.activity).load(account, null)?.input?.title)
    }

    @Test
    fun tagAddRemoveAndRoutineChangedKeepTheUnsubmittedTextAndItsSelection() {
        mount(FriendCardCreateFragment())
        val me = request("/me")
        success(request("/options"), options())
        clickInList("喜欢与经历")
        inList(hasText("2 / 3 · 喜欢与经历")).assertIsDisplayed()
        val input = inputWithHint("自定义兴趣")
        val id = input.fetchSemanticsNode().id
        input.performTextInput("尚未提交兴趣")
        node(id).performTextInputSelection(TextRange(1, 3))
        val scrollBefore = listScroll()
        success(me, FriendCardResponses.Me())
        node(id).assertEditable("尚未提交兴趣").assertIsFocused().assertSelection(TextRange(1, 3))
        assertEquals(scrollBefore, listScroll(), 0.00001f)

        clickInList("摄影")
        inList(hasText("摄影 ×")).assertIsDisplayed()
        node(id).assertEditable("尚未提交兴趣").assertIsFocused().assertSelection(TextRange(1, 3))
        clickInList("摄影 ×")
        compose.onNodeWithText("摄影 ×").assertDoesNotExist()
        node(id).assertEditable("尚未提交兴趣").assertIsFocused().assertSelection(TextRange(1, 3))

        node(id).performImeAction()
        inList(hasText("尚未提交兴趣 ×")).assertIsDisplayed()
        node(id).assertEditable("")
        node(id).performTextInput("第二项")
        compose.onNodeWithContentDescription("添加自定义兴趣").performClick()
        inList(hasText("第二项 ×")).assertIsDisplayed()
        inList(hasText("我喜欢 · 2/12")).assertIsDisplayed()
        clickInList("第二项 ×")
        inList(hasText("我喜欢 · 1/12")).assertIsDisplayed()

        inputWithHint("其他游戏").performTextInput("自填游戏")
        compose.onNodeWithContentDescription("添加其他游戏").performClick()
        inList(hasText("自填游戏 ×")).assertIsDisplayed()
        clickInList("自填游戏 ×")
        compose.runOnIdle { assertTrue("Removing a game must update the durable draft", FriendCardDraftStore(compose.activity).load(account, null)?.input?.games.orEmpty().isEmpty()) }
        list().performScrollToIndex(2)
        inList(hasText("我在玩 · 0/8")).assertIsDisplayed()
    }

    @Test
    fun ordinaryEditsAutoSaveAndMeCallbackDoesNotRecreateTheScrolledEditor() {
        val original = FriendCardDraft(account, input = FriendCardInput().apply {
            title = "自动保存回归"
            interactionHabits = "滚动草稿"
        })
        FriendCardDraftStore(compose.activity).save(original)
        mount(FriendCardCreateFragment())
        val me = request("/me")
        success(request("/options"), options())
        val input = inList(editable("滚动草稿"))
        input.performClick().performTextInputSelection(TextRange(1, 3))
        val inputId = input.fetchSemanticsNode().id
        val listId = list().fetchSemanticsNode().id
        val scrollBefore = listScroll()
        assertTrue("The regression must exercise a genuinely scrolled list", scrollBefore > 0f)

        node(inputId).performTextInput("更新")
        node(inputId).assertEditable("滚更新稿").assertIsFocused()
        assertEquals(listId, list().fetchSemanticsNode().id)
        assertEquals(scrollBefore, listScroll(), 0.00001f)
        node(inputId).performTextInputSelection(TextRange(1, 3))
        success(me, FriendCardResponses.Me())
        node(inputId).assertEditable("滚更新稿").assertIsFocused().assertSelection(TextRange(1, 3))
        assertEquals(listId, list().fetchSemanticsNode().id)
        assertEquals(scrollBefore, listScroll(), 0.00001f)
        val saved = requireNotNull(FriendCardDraftStore(compose.activity).load(account, null))
        assertEquals(original.draftId, saved.draftId)
        assertEquals("滚更新稿", saved.input.interactionHabits)
        assertTrue(saved.dirty)
        assertTrue(saved.currentLoaded)
        assertEditingFooter()
    }

    @Test
    fun stageAndPrivacyEditsRefreshBothPublicAndLegacyPreviewsWithoutLosingTheDraft() {
        val original = FriendCardDraft(account, input = FriendCardInput().apply {
            title = "预览回归宣言"
            school = "默认私密学校"
            region = "后来隐藏的地区"
            lookingFor = "一起认真玩游戏"
        })
        FriendCardDraftStore(compose.activity).save(original)
        mount(FriendCardCreateFragment())
        ready()
        inList(hasText("学校可见范围")).assert(hasText("仅自己 · 点击设为公开"))
        clickInList("地区可见范围")
        compose.onNodeWithText("地区可见范围").assert(hasText("仅自己 · 点击设为公开"))
        nextStage()
        inList(hasText("2 / 3 · 喜欢与经历")).assertIsDisplayed()
        assertEquals(1, FriendCardDraftStore(compose.activity).load(account, null)?.stage)
        nextStage()
        inList(hasText("3 / 3 · 我想找")).assertIsDisplayed()
        inList(editable("一起认真玩游戏")).assertEditable("一起认真玩游戏")
        assertEquals(2, FriendCardDraftStore(compose.activity).load(account, null)?.stage)
        nextStage()
        compose.onNodeWithText("下一阶段").assertDoesNotExist()

        inList(hasText("公开卡片预览")).assertIsDisplayed()
        compose.onNodeWithText("默认私密学校").assertDoesNotExist()
        compose.onNodeWithText("后来隐藏的地区").assertDoesNotExist()
        inList(hasText("扩列 · 预览回归宣言\n我想找：一起认真玩游戏")).assertIsDisplayed()
        clickInList("返回修改")
        inList(editable("一起认真玩游戏")).assertEditable("一起认真玩游戏")
        clickInList("关于我")
        inList(editable("默认私密学校")).assertEditable("默认私密学校")
        clickInList("学校可见范围")
        compose.onNodeWithText("学校可见范围").assert(hasText("公开 · 点击设为仅自己"))
        clickInList("我想找")
        nextStage()
        inList(hasText("默认私密学校")).assertIsDisplayed()
        inList(hasText("扩列 · 预览回归宣言\n学校：默认私密学校\n我想找：一起认真玩游戏")).assertIsDisplayed()
        val saved = requireNotNull(FriendCardDraftStore(compose.activity).load(account, null))
        assertEquals(original.draftId, saved.draftId)
        assertEquals("默认私密学校", saved.input.school)
        assertEquals("后来隐藏的地区", saved.input.region)
        assertTrue("school" in saved.input.publicFields)
        assertTrue("region" !in saved.input.publicFields)
        assertTrue("Preview must not publish or freeze an operation", saved.pending == null)
        assertEquals(2, LocalRequests.requests.size)
    }

    @Test
    fun lastNextStageStillValidatesBeforeOpeningThePublicPreview() {
        FriendCardDraftStore(compose.activity).save(FriendCardDraft(account, input = FriendCardInput().apply {
            title = "保留发布前校验"
        }))
        mount(FriendCardCreateFragment())
        ready()
        nextStage()
        nextStage()
        nextStage()
        inList(hasText("请填写我想找")).assertIsDisplayed()
        compose.onNodeWithText("公开卡片预览").assertDoesNotExist()
        assertEditingFooter()
        inputWithHint("你想认识怎样的朋友？（必填）").performTextInput("寻找尊重边界的朋友")
        nextStage()
        inList(hasText("公开卡片预览")).assertIsDisplayed()
        inList(hasText("扩列 · 保留发布前校验\n我想找：寻找尊重边界的朋友")).assertIsDisplayed()
        assertTrue(FriendCardDraftStore(compose.activity).load(account, null)?.pending == null)
        assertEquals("Only options/me may execute before an explicit publish", 2, LocalRequests.requests.size)
    }

    @Test
    @Config(sdk = [28, 30])
    fun nativeRootConsumesKeyboardInsetsOnceAndKeepsTheSingleFooterPinned() {
        val fragment = mount(FriendCardCreateFragment())
        ready()
        assertEditingFooter()
        val before = dispatchInsetsAndMeasureFooter(fragment, bottom = 24)
        val keyboard = dispatchInsetsAndMeasureFooter(fragment, bottom = 280)
        assertEquals("AppKit must subtract the keyboard only once from Compose height", 256,
            before.composeHeight - keyboard.composeHeight)
        assertEquals("The fixed footer must move by exactly the native root's subtraction", 256f,
            before.footerBottom - keyboard.footerBottom, 1f)
        assertEquals("No extra Compose IME/navigation reservation may open below the footer",
            before.footerGap, keyboard.footerGap, 1f)
        assertEquals(before.footerHeight, keyboard.footerHeight, 1f)
        assertEquals("Footer content padding is 12dp, not an extra system-bar reservation", 12f, keyboard.footerGap, 1f)
        assertTrue("The unclipped footer must remain inside the resized Compose viewport", keyboard.footerTop >= 0f)
        assertEquals(keyboard, dispatchInsetsAndMeasureFooter(fragment, bottom = 280))
        assertEquals(before, dispatchInsetsAndMeasureFooter(fragment, bottom = 24))
        assertEditingFooter()
    }

    @Test
    fun pickerResultAndProductReturnRefreshTheEditorSelectionAndRemoveAction() {
        val fragment = mount(FriendCardCreateFragment())
        ready()
        clickInList("喜欢与经历")
        clickInList("选择库内纸尿裤")
        assertEquals(FriendCardDiaperSelectFragment::class.java, LocalNavigation.target)
        assertSame(fragment, LocalNavigation.receiver)
        val code = requireNotNull(LocalNavigation.requestCode)
        val pickerArgs = requireNotNull(LocalNavigation.args)
        assertEquals(account, pickerArgs.getString("account"))
        val product = product(7, "回包商品")
        val result = Bundle(pickerArgs).apply {
            putIntegerArrayList("diaper_ids", arrayListOf(7))
            putString("diaper_json", FriendCardRules.gson.toJson(listOf(product)))
        }
        compose.activityRule.scenario.onActivity { activity ->
            activity.fragmentManager.beginTransaction().hide(fragment).commit()
            activity.fragmentManager.executePendingTransactions()
            fragment.onFragmentResult(code, true, result)
            activity.fragmentManager.beginTransaction().show(fragment).commit()
            activity.fragmentManager.executePendingTransactions()
        }
        ready()
        inList(hasText("我穿过 · 1/8")).assertIsDisplayed()
        clickInList("回包商品")
        assertEquals(DiaperDetailFragment::class.java, LocalNavigation.target)
        assertEquals(7, LocalNavigation.args?.getInt("diaper_id"))
        returnTo(fragment)
        ready()
        inList(hasText("回包商品")).assertIsDisplayed()
        clickInList("移除")
        inList(hasText("我穿过 · 0/8")).assertIsDisplayed()
        compose.onNodeWithText("回包商品").assertDoesNotExist()
    }

    @Test
    fun detailFailureRetryAndSuccessReplaceTheRealLoadingContent() {
        mount(FriendCardDetailFragment(), Bundle().apply { putString("card_id", "detail_card") })
        compose.onNodeWithText("正在读取卡片…").assertIsDisplayed()
        failure(request("/detail_card"), "详情回包失败")
        inList(hasText("详情回包失败")).assertIsDisplayed()
        compose.onNodeWithText("正在读取卡片…").assertDoesNotExist()
        clickInList("重新读取")
        compose.onNodeWithText("正在读取卡片…").assertIsDisplayed()
        success(request("/detail_card"), FriendCardResponses.Card().apply { card = card("detail_card", "详情回包标题") })
        inList(hasText("详情回包标题")).assertIsDisplayed()
        inList(hasText("编辑卡片")).assertIsDisplayed()
        compose.onNodeWithText("正在读取卡片…").assertDoesNotExist()
        compose.onNodeWithText("详情回包失败").assertDoesNotExist()
    }

    @Test
    fun meAndHistoryCallbacksRefreshCurrentCardHistoryErrorsAndAppendedCards() {
        mount(MyFriendCardsFragment())
        compose.onNodeWithText("正在读取我的扩列卡…").assertIsDisplayed()
        success(request("/me"), FriendCardResponses.Me().apply { card = card("current_card", "当前回包标题") })
        inList(hasText("当前卡 · 展示中")).assertIsDisplayed()
        inList(hasText("当前回包标题")).assertIsDisplayed()
        inList(hasText("正在加载历史…")).assertIsDisplayed()
        success(request("/history"), page("history_next", card("history_1", "历史回包一")))
        inList(hasText("历史回包一")).assertIsDisplayed()
        clickInList("加载更多历史")
        assertEquals("history_next", query(request("/history"), "cursor"))
        failure(request("/history"), "历史下一页失败")
        inList(hasText("历史下一页失败")).assertIsDisplayed()
        clickInList("重试加载历史")
        success(request("/history"), page(null, card("history_2", "历史回包二")))
        inList(hasText("历史回包二")).assertIsDisplayed()
        inList(hasText("历史回包一")).assertIsDisplayed()
        compose.onNodeWithText("历史下一页失败").assertDoesNotExist()
        compose.onNodeWithText("正在读取我的扩列卡…").assertDoesNotExist()
    }

    @Test
    fun feedAppendAndRetryKeepTheCurrentPagerPageAndExposeTheAppendedCards() {
        mount(FriendCardFeedFragment())
        compose.onNodeWithText("正在读取扩列推荐…").assertIsDisplayed()
        val first = (1..6).map { card("feed_$it", "推荐回包 $it") }
        success(request("/feed"), page("feed_next", *first.toTypedArray()))
        compose.onNodeWithText("正在读取扩列推荐…").assertDoesNotExist()
        compose.onNodeWithText("推荐回包 1").assertIsDisplayed()
        val pagerId = list().fetchSemanticsNode().id
        list().performScrollToIndex(3)
        compose.waitForIdle()
        compose.onNodeWithText("推荐回包 4").assertIsDisplayed()
        compose.onNodeWithText("4 / 6 · 上下滑动").assertIsDisplayed()
        assertEquals("feed_next", query(request("/feed"), "cursor"))

        failure(request("/feed"), "推荐下一批失败")
        compose.onNodeWithText("推荐下一批失败").assertIsDisplayed()
        compose.onNodeWithText("推荐回包 4").assertIsDisplayed()
        compose.onNodeWithText("重试下一页").performClick()
        compose.waitForIdle()
        success(request("/feed"), page(null, first[3], card("appended_1", "追加回包一"), card("appended_2", "追加回包二")))
        assertEquals(pagerId, list().fetchSemanticsNode().id)
        compose.onNodeWithText("推荐回包 4").assertIsDisplayed()
        compose.onNodeWithText("4 / 8 · 上下滑动").assertIsDisplayed()
        compose.onNodeWithText("推荐下一批失败").assertDoesNotExist()
        list().performScrollToIndex(6)
        compose.waitForIdle()
        compose.onNodeWithText("追加回包一").assertIsDisplayed()
        compose.onNodeWithText("7 / 8 · 上下滑动").assertIsDisplayed()
    }

    @Test
    fun pickerCallbacksSearchSelectionAndDoneCountStayInSyncWithTheRenderedResults() {
        val fragment = mount(FriendCardDiaperSelectFragment(), Bundle().apply {
            putString("draft_id", "picker_composition_draft")
            putIntegerArrayList("selected_ids", arrayListOf())
            putString("diaper_json", "[]")
        })
        var completed: Bundle? = null
        compose.runOnIdle {
            fragment.setResultCallback { accepted, result ->
                assertTrue(accepted)
                completed = Bundle(result)
            }
        }
        compose.onNodeWithText("正在加载商品…").assertIsDisplayed()
        compose.onNodeWithText("完成（0）").assertIsDisplayed()
        success(request("/diapers"), products(1 to "甲款", 2 to "乙款"))
        compose.onNodeWithText("正在加载商品…").assertDoesNotExist()
        inList(hasText("测试牌 甲款")).performClick()
        compose.onNodeWithText("完成（1）").assertIsDisplayed()
        compose.onNodeWithText("测试牌 甲款 ×").assertIsDisplayed()

        val search = compose.onNode(hasSetTextAction())
        val searchId = search.fetchSemanticsNode().id
        search.performTextInput("乙")
        search.assertEditable("乙")
        compose.onNodeWithText("测试牌 甲款").assertDoesNotExist()
        compose.onNodeWithText("完成（1）").assertIsDisplayed()
        // Only advance the real debounce Handler, not a synthetic Compose invalidation.
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS) }
        compose.waitForIdle()
        assertEquals("乙", query(request("/diapers"), "search"))
        failure(request("/diapers"), "筛选商品失败")
        compose.onNodeWithText("筛选商品失败").assertIsDisplayed()
        compose.onNodeWithText("完成（1）").assertIsDisplayed()
        inList(hasText("重试")).performClick()
        compose.waitForIdle()
        success(request("/diapers"), products(2 to "乙款"))
        node(searchId).assertEditable("乙")
        inList(hasText("测试牌 乙款")).performClick()
        compose.onNodeWithText("完成（2）").assertIsDisplayed()
        compose.onNodeWithText("测试牌 甲款 ×").performClick()
        compose.onNodeWithText("完成（1）").assertIsDisplayed()
        compose.onNodeWithText("测试牌 乙款 ×").assertIsDisplayed()
        compose.onNodeWithText("完成（1）").performClick()
        compose.runOnIdle {
            assertSame(fragment, LocalNavigation.finished)
            val result = requireNotNull(completed)
            assertEquals(account, result.getString("account"))
            assertEquals("picker_composition_draft", result.getString("draft_id"))
            assertEquals(listOf(2), result.getIntegerArrayList("diaper_ids"))
            assertTrue(result.getString("diaper_json").orEmpty().contains("测试牌 乙款"))
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun redesignedRealFragmentHierarchyRendersAndKeepsOwnerAndVisitorActionsPinned() {
        val fixture = card("render_card", "周末散步，认识有趣的朋友").apply {
            owner = FriendCard.Owner().apply { id = "42"; username = "render_fixture"; displayName = "小岚 · 虚构用户" }
            gender = "男"; age = "28岁"; region = "杭州"; mbti = "INFP"; zodiac = "天秤座"
            interests = listOf("摄影", "散步", "音乐", "电影", "旅行")
            games = listOf("stardew-valley", "minecraft")
            lookingFor = "想认识愿意分享日常的朋友，一起散步、玩游戏，慢慢熟悉彼此。"
            boundaries = "尊重彼此的节奏，不催回复。"
            publicFields = listOf("gender", "age", "region", "mbti", "zodiac", "interests", "games", "looking_for", "boundaries")
        }
        val fragment = mount(FriendCardDetailFragment(), Bundle().apply { putString("card_id", "render_card") })
        assertNativeToolbarGone(fragment)
        success(request("/render_card"), FriendCardResponses.Card().apply { card = fixture })
        compose.onNodeWithText("编辑卡片").assertIsDisplayed().performClick()
        assertEquals(FriendCardCreateFragment::class.java, LocalNavigation.target)
        assertEquals("render_card", LocalNavigation.args?.getString("card_id"))
        assertDetailOverlayRouting(fragment)
        compose.onNodeWithText("Hi").assertDoesNotExist()
        captureRealFragment("detail-owner-overlay-native-light-api28-fallback")

        compose.runOnIdle {
            fixture.ownerId = "43"
            fixture.owner.id = "43"
        }
        returnTo(fragment)
        success(request("/render_card"), FriendCardResponses.Card().apply { card = fixture })
        compose.onNodeWithText("编辑卡片").assertDoesNotExist()
        compose.onNodeWithContentDescription("发私信").assertIsDisplayed().performClick()
        assertEquals(org.joinmastodon.android.chat.ui.ChatFragment::class.java, LocalNavigation.target)
        assertEquals(43L, LocalNavigation.args?.getLong("peer_id"))
        assertDetailOverlayRouting(fragment)
        compose.onNodeWithText("Hi").assertDoesNotExist()
        compose.onNodeWithText("发私信").assertDoesNotExist()
        captureRealFragment("detail-visitor-overlay-chat-icon-native-light-api28-fallback")

        compose.activityRule.scenario.onActivity { activity ->
            activity.fragmentManager.beginTransaction().remove(fragment).commit()
            activity.fragmentManager.executePendingTransactions()
        }
        mount(FriendCardFeedFragment())
        success(request("/feed"), page(null, fixture))
        compose.onNodeWithText("扩列精选").assertIsDisplayed()
        compose.onNodeWithContentDescription("发私信").assertIsDisplayed()
        captureRealFragment("feed-native-light")
    }

    private fun assertNativeToolbarGone(fragment: FriendCardDetailFragment) {
        compose.runOnIdle {
            val toolbar = requireNotNull(fragment.view).findViewById<Toolbar>(R.id.toolbar)
            assertEquals("Detail must remove, not merely make transparent, the native toolbar", View.GONE, toolbar.visibility)
        }
    }

    private fun assertDetailOverlayRouting(fragment: FriendCardDetailFragment) {
        assertNativeToolbarGone(fragment)
        val close = compose.onNodeWithContentDescription("关闭").assertIsDisplayed()
        val manage = compose.onNodeWithContentDescription("管理我的扩列卡").assertIsDisplayed()
        val closeBounds = close.getUnclippedBoundsInRoot()
        val manageBounds = manage.getUnclippedBoundsInRoot()
        assertEquals(44f, closeBounds.right.value - closeBounds.left.value, 1f)
        assertEquals(44f, closeBounds.bottom.value - closeBounds.top.value, 1f)
        assertTrue("Manage belongs to the opposite end of the overlay", closeBounds.right < manageBounds.left)
        assertEquals(closeBounds.top.value, manageBounds.top.value, 1f)
        compose.runOnIdle { LocalNavigation.reset() }
        manage.performClick()
        assertEquals("Owner and visitor management always opens the current account's cards",
            MyFriendCardsFragment::class.java, LocalNavigation.target)
        assertEquals(account, LocalNavigation.args?.getString("account"))
        assertTrue("Manage must not carry the viewed card into an edit route", LocalNavigation.args?.containsKey("card_id") != true)
        close.performClick()
        assertSame(fragment, LocalNavigation.finished)
    }

    private data class FooterGeometry(
        val composeHeight: Int,
        val footerTop: Float,
        val footerBottom: Float,
        val footerHeight: Float,
        val footerGap: Float,
    )

    private fun dispatchInsetsAndMeasureFooter(fragment: FriendCardCreateFragment, bottom: Int): FooterGeometry {
        compose.runOnIdle {
            val root = requireNotNull(fragment.view)
            assertTrue("Exercise the production AppKit inset owner", root is FragmentRootLinearLayout)
            val insets = if (Build.VERSION.SDK_INT >= 30) {
                WindowInsets.Builder()
                    // AppKit uses the legacy combined bottom; also deliver the actual typed IME.
                    .setSystemWindowInsets(android.graphics.Insets.of(0, 24, 0, bottom))
                    .setStableInsets(android.graphics.Insets.of(0, 24, 0, 24))
                    .setInsets(WindowInsets.Type.ime(), android.graphics.Insets.of(0, 0, 0, if (bottom > 24) bottom else 0))
                    .setVisible(WindowInsets.Type.ime(), bottom > 24)
                    .build()
            } else {
                WindowInsets::class.java.getDeclaredConstructor(android.graphics.Rect::class.java)
                    .apply { isAccessible = true }.newInstance(android.graphics.Rect(0, 24, 0, bottom))
            }
            assertEquals("Fixture must deliver the requested system/keyboard bottom", bottom, insets.systemWindowInsetBottom)
            // The real AppKit callback dispatches onto its real root; no test padding/inset consumer.
            fragment.onApplyWindowInsets(insets)
            repeat(3) {
                root.forceLayout()
                root.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 393, 852)
                root.viewTreeObserver.dispatchOnPreDraw()
            }
            assertEquals("Native root, not a Compose spacer, owns the inset", bottom, root.paddingBottom)
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithText("下一阶段").assertIsDisplayed().getUnclippedBoundsInRoot()
        val height = realComposeView(fragment).height
        return FooterGeometry(height, bounds.top.value, bounds.bottom.value, bounds.bottom.value - bounds.top.value,
            height.toFloat() - bounds.bottom.value)
    }

    private fun assertEditingFooter() {
        assertEquals("Each edit stage has exactly one fixed next action", 1,
            compose.onAllNodes(hasText("下一阶段")).fetchSemanticsNodes().size)
        compose.onNodeWithText("下一阶段").assertIsDisplayed()
        compose.onNodeWithText("保存本机草稿").assertDoesNotExist()
        compose.onNodeWithText("发布前预览").assertDoesNotExist()
    }

    private fun nextStage() {
        assertEditingFooter()
        compose.onNodeWithText("下一阶段").performClick()
        compose.waitForIdle()
    }

    private fun captureRealFragment(name: String) {
        compose.waitForIdle()
        lateinit var bitmap: android.graphics.Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
        }
        val directory = File("/tmp/abdl-friend-card-ui-renders/real-fragments").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun <T : FriendCardBaseFragment> mount(fragment: T, args: Bundle = Bundle()): T {
        args.putString("account", account)
        fragment.arguments = args
        compose.activityRule.scenario.onActivity { activity ->
            activity.fragmentManager.beginTransaction().add(containerId, fragment, "composition-under-test").commit()
            activity.fragmentManager.executePendingTransactions()
            mounted.add(fragment)
        }
        // AppKitFragment.onResume invokes the real Base.onShown/refresh automatically.
        compose.waitForIdle()
        assertTrue(realComposeView(fragment).isAttachedToWindow)
        assertTrue(realComposeView(fragment).hasComposition)
        return fragment
    }

    private fun returnTo(fragment: FriendCardBaseFragment) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.fragmentManager.beginTransaction().hide(fragment).commit()
            activity.fragmentManager.executePendingTransactions()
            activity.fragmentManager.beginTransaction().show(fragment).commit()
            activity.fragmentManager.executePendingTransactions()
        }
        compose.waitForIdle()
    }

    private fun realComposeView(fragment: FriendCardBaseFragment): ComposeView {
        fun find(view: View): ComposeView? {
            if (view is ComposeView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return requireNotNull(find(requireNotNull(fragment.view)))
    }

    private fun list() = compose.onNode(hasScrollToIndexAction())

    private fun listScroll(): Float = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun inList(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val target = compose.onNode(matcher)
        if (runCatching { target.assertIsDisplayed() }.isSuccess) return target
        list().performScrollToIndex(0)
        list().performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }

    private fun clickInList(text: String) {
        inList(hasText(text)).performClick()
        compose.waitForIdle()
    }

    private fun inputWithHint(hint: String): SemanticsNodeInteraction = inList(
        hasSetTextAction() and (hasText(hint) or hasAnyDescendant(hasText(hint))),
    )

    private fun editable(text: String) = hasSetTextAction() and SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))

    private fun node(id: Int) = compose.onNode(SemanticsMatcher("original semantics node $id") { it.id == id }, useUnmergedTree = true)

    private fun SemanticsNodeInteraction.assertEditable(text: String) = assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))

    private fun SemanticsNodeInteraction.assertSelection(range: TextRange) = assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, range))

    private fun request(suffix: String): MastodonAPIRequest<*> = LocalRequests.requests.lastOrNull {
        (field(MastodonAPIRequest::class.java, "path").get(it) as String).endsWith(suffix)
    } ?: throw AssertionError("Missing production request ending in $suffix")

    private fun query(request: MastodonAPIRequest<*>, name: String): String? {
        val queries = field(MastodonAPIRequest::class.java, "queryParams").get(request) as? List<*> ?: return null
        return queries.filterIsInstance<Pair<*, *>>().firstOrNull { it.first == name }?.second as? String
    }

    @Suppress("UNCHECKED_CAST")
    private fun success(request: MastodonAPIRequest<*>, response: Any) {
        compose.runOnIdle { (field(APIRequest::class.java, "callback").get(request) as Callback<Any>).onSuccess(response) }
        compose.waitForIdle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun failure(request: MastodonAPIRequest<*>, message: String) {
        compose.runOnIdle { (field(APIRequest::class.java, "callback").get(request) as Callback<Any>).onError(MastodonErrorResponse(message, 503, null)) }
        compose.waitForIdle()
    }

    private fun options() = FriendCardResponses.Options().apply {
        schemaVersion = 1
        games = listOf(FriendCardResponses.Game().apply { id = "preset_game"; name = "预设游戏" })
        interestPresets = listOf("摄影", "积木")
        verificationState = "active"
        canCreate = true
    }

    private fun ready() {
        success(request("/options"), options())
        success(request("/me"), FriendCardResponses.Me())
    }

    private fun card(id: String, title: String) = FriendCard().apply {
        this.id = id
        this.title = title
        schemaVersion = 1
        version = 1
        state = "active"
        ownerId = "42"
        owner = FriendCard.Owner().apply { this.id = "42"; username = "fixture"; displayName = "测试用户" }
        lookingFor = "寻找同好"
        publicFields = listOf("looking_for")
    }

    private fun page(cursor: String?, vararg cards: FriendCard) = FriendCardResponses.Page().apply {
        this.cards = cards.toList()
        nextCursor = cursor
    }

    private fun product(id: Int, name: String) = FriendCard.Diaper().apply {
        this.id = id
        this.name = name
        available = true
    }

    private fun products(vararg items: kotlin.Pair<Int, String>): Map<String, Any> = mapOf(
        "diapers" to items.map { (id, model) -> mapOf("id" to id, "brand" to "测试牌", "model" to model, "images" to emptyList<String>()) },
        "pagination" to mapOf("total" to items.size),
    )

    private fun drafts() = RuntimeEnvironment.getApplication().getSharedPreferences("friend_card_drafts_v1", Context.MODE_PRIVATE)

    private fun field(type: Class<*>, name: String): Field = type.getDeclaredField(name).apply { isAccessible = true }
}
