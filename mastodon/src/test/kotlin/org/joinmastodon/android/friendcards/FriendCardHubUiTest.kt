@file:Suppress("DEPRECATION")

package org.joinmastodon.android.friendcards

import android.app.Activity
import android.app.Fragment
import android.os.Bundle
import android.util.Pair
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import me.grishka.appkit.Nav
import me.grishka.appkit.api.APIRequest
import me.grishka.appkit.api.Callback
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.R
import org.joinmastodon.android.api.MastodonAPIRequest
import org.joinmastodon.android.api.requests.friendcards.FriendCardResponses
import org.joinmastodon.android.api.session.AccountSession
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.fragments.friendcards.FriendCardBaseFragment
import org.joinmastodon.android.fragments.friendcards.FriendCardDetailFragment
import org.joinmastodon.android.fragments.friendcards.FriendCardFeedFragment
import org.joinmastodon.android.fragments.friendcards.MyFriendCardsFragment
import org.joinmastodon.android.model.Account
import org.joinmastodon.android.model.FriendCard
import org.joinmastodon.android.ui.compose.friendcards.FriendCardHubFilter
import org.joinmastodon.android.ui.compose.friendcards.FriendCardHubFilters
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import java.lang.reflect.Field

/** Real hub Fragment, ComposeView, local tab/list UI and production callbacks; no replacement Screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w393dp-h852dp-mdpi", application = CompatibilityTestApplication::class,
    shadows = [FriendCardHubUiTest.LocalRequests::class, FriendCardHubUiTest.LocalNavigation::class])
@LooperMode(LooperMode.Mode.PAUSED)
class FriendCardHubUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var account: String
    private lateinit var sessions: MutableMap<String, AccountSession>
    private var previous: AccountSession? = null
    private var previousActive: String? = null
    private lateinit var hub: MyFriendCardsFragment
    private var containerId = View.NO_ID

    @Implements(MastodonAPIRequest::class)
    class LocalRequests {
        @RealObject private lateinit var request: MastodonAPIRequest<*>
        @Implementation fun exec(account: String): MastodonAPIRequest<*> { requests.add(request); return request }
        @Implementation fun cancel() = Unit // Deliberately allow late callbacks to exercise generation guards.
        companion object { val requests = mutableListOf<MastodonAPIRequest<*>>() }
    }
    @Implements(Nav::class)
    class LocalNavigation {
        companion object {
            var target: Class<out Fragment>? = null
            var args: Bundle? = null
            @JvmStatic @Implementation fun go(activity: Activity, type: Class<out Fragment>, bundle: Bundle?) {
                target = type; args = bundle?.let(::Bundle)
            }
            @JvmStatic @Implementation fun finish(fragment: Fragment) = Unit
        }
    }

    @Before @Suppress("UNCHECKED_CAST")
    fun setUp() {
        MastodonApp.context = RuntimeEnvironment.getApplication()
        val manager = AccountSessionManager.getInstance()
        sessions = field(AccountSessionManager::class.java, "sessions").get(manager) as MutableMap<String, AccountSession>
        previousActive = field(AccountSessionManager::class.java, "lastActiveAccountID").get(manager) as String?
        val session = AccountSession::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance().apply {
            domain = "hub-offline-fixture.example.test"; self = Account().apply { id = "42" }
        }
        account = session.getID(); previous = sessions.put(account, session)
        field(AccountSessionManager::class.java, "lastActiveAccountID").set(manager, account)
        LocalRequests.requests.clear(); LocalNavigation.target = null; LocalNavigation.args = null
        compose.activityRule.scenario.onActivity { activity ->
            activity.setTheme(R.style.Theme_Mastodon_Light)
            val container = FrameLayout(activity).apply { id = View.generateViewId() }
            containerId = container.id; activity.setContentView(container)
        }
    }

    @After fun tearDown() {
        if (::hub.isInitialized) compose.activityRule.scenario.onActivity { activity ->
            activity.fragmentManager.beginTransaction().remove(hub).commit(); activity.fragmentManager.executePendingTransactions()
        }
        if (::sessions.isInitialized) {
            previous?.let { sessions[account] = it } ?: sessions.remove(account)
            field(AccountSessionManager::class.java, "lastActiveAccountID").set(AccountSessionManager.getInstance(), previousActive)
        }
        LocalRequests.requests.clear(); LocalNavigation.target = null; LocalNavigation.args = null
    }

    @Test fun myEntrySwitchesLocallyAndRetainsBothListPositions() {
        mount(MyFriendCardsFragment())
        compose.onNodeWithTag("friend-card-hub-tab-my").assertIsSelected()
        assertEquals(1, LocalRequests.requests.size)
        success(request("/me"), FriendCardResponses.Me().apply { card = card("current", "当前真实回包") })
        success(request("/history"), page(null, *(1..8).map { card("history_$it", "历史回包 $it") }.toTypedArray()))
        compose.onNodeWithTag("friend-card-hub-my-list").performScrollToNode(hasText("历史回包 6"))
        val myScroll = scroll("friend-card-hub-my-list")
        assertTrue(myScroll > 0)
        compose.onNodeWithTag("friend-card-hub-tab-plaza").performClick()
        compose.onNodeWithTag("friend-card-hub-tab-plaza").assertIsSelected()
        assertNull("Local tabs must not push a Fragment", LocalNavigation.target)
        success(request("/feed"), page("next", *(1..9).map { card("feed_$it", "广场回包 $it") }.toTypedArray()))
        compose.onNodeWithTag("friend-card-hub-plaza-list").performScrollToNode(hasText("广场回包 7"))
        val feedScroll = scroll("friend-card-hub-plaza-list")
        assertTrue(feedScroll > 0)
        val requests = LocalRequests.requests.size
        compose.onNodeWithTag("friend-card-hub-tab-my").performClick()
        assertEquals(myScroll, scroll("friend-card-hub-my-list"), .00001f)
        compose.onNodeWithTag("friend-card-hub-tab-plaza").performClick()
        assertEquals(feedScroll, scroll("friend-card-hub-plaza-list"), .00001f)
        assertEquals("Local switching must not reset/reload either destination", requests, LocalRequests.requests.size)
        assertNull(LocalNavigation.target)
    }

    @Test fun filteredZeroKeepsNextCursorActionAndPrivacyNeverEntersSearch() {
        mount(FriendCardFeedFragment())
        val hiddenFields = card("private", "公开标题").apply {
            interests = listOf("绝密关键词"); region = "绝密城市"; lookingFor = "绝密关键词"
            socialAccounts = listOf(FriendCard.SocialAccount().apply { platform = "qq"; account = "绝密关键词"; isPublic = true })
            publicFields = emptyList()
        }
        success(request("/feed"), page("second", hiddenFields))
        compose.onNodeWithTag("friend-card-hub-search").performTextInput("绝密关键词")
        compose.onNodeWithText("已加载的卡片中没有匹配项").assertIsDisplayed()
        compose.onNodeWithText("公开标题").assertDoesNotExist()
        compose.onNodeWithTag("friend-card-hub-plaza-list").performScrollToNode(hasText("换一批，遇见新朋友"))
        compose.onNodeWithText("换一批，遇见新朋友").performClick()
        assertEquals("second", cursor(request("/feed")))
        success(request("/feed"), page(null, card("matching", "绝密关键词是公开宣言")))
        compose.onNodeWithTag("friend-card-hub-plaza-list").performScrollToNode(hasText("绝密关键词是公开宣言"))
        compose.onNodeWithText("绝密关键词是公开宣言").assertIsDisplayed()
        compose.onNodeWithText("在线").assertDoesNotExist()
        compose.onNodeWithText("公开标题").assertDoesNotExist()
    }

    @Test fun clickingNextAtTheFooterShowsNewCardsInsteadOfFollowingTheFooterPastThem() {
        mount(FriendCardFeedFragment())
        success(request("/feed"), page("second", *(1..8).map { card("first_$it", "首批卡片 $it") }.toTypedArray()))
        compose.onNodeWithTag("friend-card-hub-plaza-list").performScrollToNode(hasText("换一批，遇见新朋友"))
        compose.onNodeWithText("换一批，遇见新朋友").performClick()
        assertEquals("second", cursor(request("/feed")))
        success(request("/feed"), page("third", *(1..8).map { card("new_$it", "追加卡片 $it") }.toTypedArray()))
        compose.onNodeWithText("追加卡片 1").assertIsDisplayed()
        compose.onNodeWithText("追加卡片 8").assertDoesNotExist()
        assertEquals("Appending uses only the requested next cursor, not a first-page reset", 2, paths().count { it.endsWith("/feed") })
    }

    @Test fun currentHistoryOverlapReconcilesReplacementOnceAndNeverRendersDuplicateKeys() {
        mount(MyFriendCardsFragment())
        val original = card("old", "旧的当前卡")
        success(request("/me"), FriendCardResponses.Me().apply { card = original })
        // A duplicate snapshot without a newer state is harmless, but may only render once.
        success(request("/history"), page("replacement", original))
        compose.onNodeWithTag("friend-card-hub-my-list").performScrollToNode(hasText("旧的当前卡"))
        compose.onNodeWithText("旧的当前卡").assertIsDisplayed()
        assertEquals(1, compose.onAllNodes(hasText("旧的当前卡")).fetchSemanticsNodes().size)
        compose.onNodeWithTag("friend-card-hub-my-list").performScrollToNode(hasText("加载更多历史"))
        compose.onNodeWithText("加载更多历史").performClick()
        success(request("/history"), page(null, card("old", "旧的当前卡").apply { version = 2; state = "archived" }))
        compose.onNodeWithText("旧的当前卡").assertDoesNotExist()
        assertEquals(2, paths().count { it.endsWith("/me") })
        success(request("/me"), FriendCardResponses.Me().apply { card = card("new", "新的当前卡") })
        success(request("/history"), page(null, card("old", "归档的旧卡").apply { version = 2; state = "archived" }))
        compose.onNodeWithTag("friend-card-hub-my-list").performScrollToNode(hasText("新的当前卡"))
        compose.onNodeWithText("新的当前卡").assertIsDisplayed()
        compose.onNodeWithTag("friend-card-hub-my-list").performScrollToNode(hasText("归档的旧卡"))
        compose.onNodeWithText("归档的旧卡").assertIsDisplayed()
    }

    @Test fun repeatedCurrentHistoryRaceStopsAfterOneAutomaticRevalidation() {
        mount(MyFriendCardsFragment())
        repeat(2) {
            success(request("/me"), FriendCardResponses.Me().apply { card = card("racy", "不能继续管理的卡") })
            success(request("/history"), page(null, card("racy", "不能继续管理的卡").apply { version = 2; state = "archived" }))
        }
        assertEquals(2, paths().count { it.endsWith("/me") })
        assertEquals(2, paths().count { it.endsWith("/history") })
        compose.onNodeWithText("不能继续管理的卡").assertDoesNotExist()
        compose.onNodeWithTag("friend-card-hub-my-list").performScrollToNode(hasText("当前卡片在其他位置发生变化，请刷新后再管理"))
        compose.onNodeWithText("当前卡片在其他位置发生变化，请刷新后再管理").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w393dp-h180dp-mdpi")
    fun laterPageFooterPersistsPrecedingCardAnchorAndRevalidatesUntilItReturns() {
        mount(FriendCardFeedFragment())
        success(request("/feed"), page("second", card("first", "第一页卡片")))
        compose.onNodeWithTag("friend-card-hub-plaza-list").performScrollToNode(hasText("换一批，遇见新朋友"))
        compose.onNodeWithText("换一批，遇见新朋友").performClick()
        success(request("/feed"), page("third", card("last", "第二页最后一张卡")))
        // The short viewport makes this non-card row, not the preceding card, the saved first item.
        val pagesBefore = hubField("pages").get(hub) as FriendCardPages
        val footerIndex = 4 + pagesBefore.cards.size
        compose.onNodeWithTag("friend-card-hub-plaza-list").performScrollToIndex(footerIndex)
        val list = hubField("feedPosition").get(hub) as org.joinmastodon.android.ui.compose.friendcards.FriendCardHubPosition
        assertEquals("last", list.cardId)
        assertTrue("A non-card footer must retain its row delta", list.anchorDelta > 0)
        val saved = Bundle()
        compose.runOnIdle { hub.onSaveInstanceState(saved) }
        assertEquals("last", saved.getString("hub_feed_card"))
        assertEquals(list.anchorDelta, saved.getInt("hub_feed_anchor_delta"))
        // Navigate to the real detail route without replacing the footer scroll position.
        compose.runOnIdle {
            val method = FriendCardBaseFragment::class.java.getDeclaredMethod("detail", FriendCard::class.java).apply { isAccessible = true }
            method.invoke(hub, card("last", "第二页最后一张卡"))
        }
        assertEquals(FriendCardDetailFragment::class.java, LocalNavigation.target)
        hideAndShowHub()
        assertNull(cursor(request("/feed")))
        success(request("/feed"), page("second", card("first", "校验后的第一页卡")))
        assertEquals("second", cursor(request("/feed")))
        success(request("/feed"), page("third", card("last", "校验后的第二页卡")))
        compose.onNodeWithText("换一批，遇见新朋友").assertIsDisplayed()
        val restored = hubField("feedPosition").get(hub) as org.joinmastodon.android.ui.compose.friendcards.FriendCardHubPosition
        assertEquals("last", restored.cardId)
        assertEquals(list.anchorDelta, restored.anchorDelta)
        assertEquals(4, paths().count { it.endsWith("/feed") })

        // Deleted/hidden anchors plus repeated cursors must stop, never create a request loop.
        hideAndShowHub()
        success(request("/feed"), page("repeat", card("first", "唯一剩余卡")))
        success(request("/feed"), page("repeat"))
        assertEquals(6, paths().count { it.endsWith("/feed") })
        val pages = hubField("pages").get(hub) as FriendCardPages
        assertFalse(pages.loading)
        assertNull(pages.nextCursor)
    }

    @Test fun sameCityRequiresCurrentPublicRegionAndNeverUsesPrivateRegion() {
        mount(FriendCardFeedFragment())
        success(request("/feed"), page(null, card("public_city", "同城公开卡").apply {
            region = "杭州"; publicFields = listOf("region")
        }))
        compose.onNodeWithText("同城").performClick()
        success(request("/me"), FriendCardResponses.Me().apply { card = card("mine", "我的卡").apply {
            region = "杭州"; publicFields = emptyList()
        } })
        compose.onNodeWithText("同城公开卡").assertDoesNotExist()
        compose.onNodeWithText("同城仅匹配你当前卡片的公开地区。请先在卡片中填写并公开地区；不会使用定位。").assertIsDisplayed()
        assertEquals("The compatibility plaza does not fetch private history", 0, paths().count { it.endsWith("/history") })
    }

    @Test fun detailReturnClearsOldCardsAndRejectsLatePageCallbackBeforeRevalidation() {
        mount(FriendCardFeedFragment())
        success(request("/feed"), page("second", card("before", "返回前的旧标题")))
        compose.onNodeWithTag("friend-card-hub-plaza-list").performScrollToNode(hasText("换一批，遇见新朋友"))
        compose.onNodeWithText("换一批，遇见新朋友").performClick()
        val late = request("/feed")
        compose.onNodeWithTag("friend-card-hub-plaza-list").performScrollToNode(hasText("认识一下"))
        compose.onNodeWithText("认识一下").performClick()
        assertEquals(FriendCardDetailFragment::class.java, LocalNavigation.target)
        assertEquals("before", LocalNavigation.args?.getString("card_id"))
        compose.activityRule.scenario.onActivity { activity ->
            activity.fragmentManager.beginTransaction().hide(hub).commit(); activity.fragmentManager.executePendingTransactions()
        }
        success(late, page(null, card("late", "不可出现的延迟回包")))
        compose.activityRule.scenario.onActivity { activity ->
            activity.fragmentManager.beginTransaction().show(hub).commit(); activity.fragmentManager.executePendingTransactions()
        }
        compose.waitForIdle()
        compose.onNodeWithText("返回前的旧标题").assertDoesNotExist()
        compose.onNodeWithText("不可出现的延迟回包").assertDoesNotExist()
        assertNull("Return must revalidate from the first cursor", cursor(request("/feed")))
        success(request("/feed"), page(null, card("after", "重新校验后的标题")))
        compose.onNodeWithTag("friend-card-hub-plaza-list").performScrollToNode(hasText("重新校验后的标题"))
        compose.onNodeWithText("重新校验后的标题").assertIsDisplayed()
    }

    @Test fun filtersOnlyUsePublicFieldsAndRetainServerOrder() {
        val value = card("filter", "公开标题").apply {
            region = "杭州"; interests = listOf("电影"); games = listOf("minecraft"); lookingFor = "一起开黑"
            publicFields = emptyList()
        }
        assertFalse(FriendCardHubFilters.matches(value, "电影", FriendCardHubFilter.Recommended, null))
        assertFalse(FriendCardHubFilters.matches(value, "", FriendCardHubFilter.SameCity, "杭州"))
        assertFalse(FriendCardHubFilters.matches(value, "", FriendCardHubFilter.Games, null))
        value.publicFields = listOf("region", "interests", "games", "looking_for")
        assertTrue(FriendCardHubFilters.matches(value, "电影 杭州", FriendCardHubFilter.Movies, null))
        assertTrue(FriendCardHubFilters.matches(value, "", FriendCardHubFilter.SameCity, "杭州"))
        assertTrue(FriendCardHubFilters.matches(value, "", FriendCardHubFilter.Games, null))
        assertFalse("Game fields may power the game filter but are not searched", FriendCardHubFilters.matches(value, "minecraft", FriendCardHubFilter.Recommended, null))
    }

    private fun hideAndShowHub() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.fragmentManager.beginTransaction().hide(hub).commit(); activity.fragmentManager.executePendingTransactions()
            activity.fragmentManager.beginTransaction().show(hub).commit(); activity.fragmentManager.executePendingTransactions()
        }
        compose.waitForIdle()
    }
    private fun hubField(name: String): Field = generateSequence<Class<*>>(hub.javaClass) { it.superclass }
        .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
        .first().apply { isAccessible = true }

    private fun mount(value: MyFriendCardsFragment) {
        hub = value; value.arguments = Bundle().apply { putString("account", account) }
        compose.activityRule.scenario.onActivity { activity ->
            activity.fragmentManager.beginTransaction().add(containerId, value, "hub-under-test").commit()
            activity.fragmentManager.executePendingTransactions()
        }
        compose.waitForIdle()
        assertEquals(View.GONE, hub.view?.findViewById<View>(R.id.toolbar)?.visibility)
    }
    private fun scroll(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun paths() = LocalRequests.requests.map { field(MastodonAPIRequest::class.java, "path").get(it) as String }
    private fun request(suffix: String) = LocalRequests.requests.last { (field(MastodonAPIRequest::class.java, "path").get(it) as String).endsWith(suffix) }
    private fun cursor(request: MastodonAPIRequest<*>): String? = (field(MastodonAPIRequest::class.java, "queryParams").get(request) as? List<*>)
        ?.filterIsInstance<Pair<*, *>>()?.firstOrNull { it.first == "cursor" }?.second as? String
    @Suppress("UNCHECKED_CAST") private fun success(request: MastodonAPIRequest<*>, result: Any) {
        compose.runOnIdle { (field(APIRequest::class.java, "callback").get(request) as Callback<Any>).onSuccess(result) }
        compose.waitForIdle()
    }
    private fun page(cursor: String?, vararg values: FriendCard) = FriendCardResponses.Page().apply { cards = values.toList(); nextCursor = cursor }
    private fun card(id: String, title: String) = FriendCard().apply {
        this.id = id; this.title = title; schemaVersion = 1; version = 1; state = "active"; ownerId = "42"
        lookingFor = "寻找同好"; publicFields = listOf("looking_for")
    }
    private fun field(type: Class<*>, name: String): Field = type.getDeclaredField(name).apply { isAccessible = true }
}
