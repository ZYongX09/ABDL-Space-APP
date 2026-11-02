@file:Suppress("DEPRECATION")

package org.joinmastodon.android.friendcards

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import me.grishka.appkit.api.APIRequest
import me.grishka.appkit.api.Callback
import me.grishka.appkit.utils.V
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.GlobalUserPreferences
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.R
import org.joinmastodon.android.api.MastodonAPIRequest
import org.joinmastodon.android.api.MastodonErrorResponse
import org.joinmastodon.android.api.requests.friendcards.FriendCardResponses
import org.joinmastodon.android.api.session.AccountSession
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.fragments.friendcards.FriendCardBaseFragment
import org.joinmastodon.android.fragments.friendcards.FriendCardCreateFragment
import org.joinmastodon.android.fragments.friendcards.FriendCardDetailFragment
import org.joinmastodon.android.fragments.friendcards.FriendCardDiaperSelectFragment
import org.joinmastodon.android.fragments.friendcards.MyFriendCardsFragment
import org.joinmastodon.android.model.Account
import org.joinmastodon.android.model.FriendCard
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.lang.reflect.Field

/** Native en-US production Fragment/Compose hierarchy. Only HTTP dispatch and navigation are
 * intercepted by the existing offline seams; no Screen replacement, English fixture UI or API IO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28], qualifiers = "en-rUS-w393dp-h852dp-mdpi",
    application = CompatibilityTestApplication::class,
    shadows = [FriendCardCompositionBehaviorTest.LocalRequests::class, FriendCardCompositionBehaviorTest.LocalNavigation::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FriendCardEnglishNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var account: String
    private lateinit var sessions: MutableMap<String, AccountSession>
    private var previousSession: AccountSession? = null
    private var previousActive: String? = null
    private var previousFontScale = 1f
    private var previousTheme = GlobalUserPreferences.theme
    private var containerId = View.NO_ID
    private val mounted = mutableListOf<FriendCardBaseFragment>()

    @Before @Suppress("UNCHECKED_CAST")
    fun prepareRealHost() {
        MastodonApp.context = RuntimeEnvironment.getApplication()
        V.setApplicationContext(MastodonApp.context)
        FriendCardGames.updateOptions(emptyList())
        assertEquals("en", compose.activity.resources.configuration.locales[0].language)
        assertEquals("US", compose.activity.resources.configuration.locales[0].country)
        previousFontScale = compose.activity.resources.configuration.fontScale
        previousTheme = GlobalUserPreferences.theme
        GlobalUserPreferences.theme = GlobalUserPreferences.ThemePreference.LIGHT
        val manager = AccountSessionManager.getInstance()
        sessions = field(AccountSessionManager::class.java, "sessions").get(manager) as MutableMap<String, AccountSession>
        previousActive = field(AccountSessionManager::class.java, "lastActiveAccountID").get(manager) as String?
        val session = AccountSession::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance().apply {
            domain = "friendcards-english.example.test"
            self = Account().apply { id = "42" }
        }
        account = session.getID()
        previousSession = sessions.put(account, session)
        field(AccountSessionManager::class.java, "lastActiveAccountID").set(manager, account)
        compose.activity.getSharedPreferences("friend_card_drafts_v1", 0).edit().clear().commit()
        FriendCardCompositionBehaviorTest.LocalRequests.requests.clear()
        FriendCardCompositionBehaviorTest.LocalNavigation.reset()
        compose.activityRule.scenario.onActivity { activity ->
            activity.setTheme(R.style.Theme_Mastodon_Light)
            val container = FrameLayout(activity).apply {
                id = View.generateViewId()
                layoutParams = ViewGroup.LayoutParams(-1, -1)
            }
            containerId = container.id
            activity.setContentView(container)
        }
    }

    @After fun releaseHost() {
        compose.activityRule.scenario.onActivity { activity ->
            mounted.filter { it.isAdded }.forEach { activity.fragmentManager.beginTransaction().remove(it).commit() }
            activity.fragmentManager.executePendingTransactions()
            activity.getSharedPreferences("friend_card_drafts_v1", 0).edit().clear().commit()
        }
        if (::sessions.isInitialized && ::account.isInitialized) {
            previousSession?.let { sessions[account] = it } ?: sessions.remove(account)
            field(AccountSessionManager::class.java, "lastActiveAccountID").set(AccountSessionManager.getInstance(), previousActive)
        }
        GlobalUserPreferences.theme = previousTheme
        RuntimeEnvironment.setFontScale(previousFontScale)
        FriendCardGames.updateOptions(emptyList())
        FriendCardCompositionBehaviorTest.LocalRequests.requests.clear()
        FriendCardCompositionBehaviorTest.LocalNavigation.reset()
    }

    @Test fun englishEditorPrivacyGamesPreviewAndValidationKeepStableDraftInput() {
        mount(FriendCardCreateFragment())
        compose.onNodeWithText("Checking your current baby verification status…").assertIsDisplayed()
        success(request("/options"), options())
        success(request("/me"), FriendCardResponses.Me())
        input("Introduce yourself in one sentence").performTextInput("原始自填宣言")
        click("Next step")
        click("Next step")
        click("Next step")
        inList(hasText("Describe who you would like to meet")).assertIsDisplayed()
        input("What kind of friends would you like to meet? (required)").performTextInput("Find thoughtful adult friends")
        click("Interests & experiences")
        inList(hasText("Genshin Impact")).assertIsDisplayed().performClick()
        val store = FriendCardDraftStore(compose.activity)
        assertEquals(listOf("genshin-impact"), store.load(account, null)!!.input.games)
        click("Games I play visibility")
        val privateInput = store.load(account, null)!!.input
        assertFalse(privateInput.publicFields.contains("games"))
        assertEquals(listOf("genshin-impact"), privateInput.games)
        click("Looking for")
        click("Next step")
        inList(hasText("Public card preview")).assertIsDisplayed()
        compose.onNodeWithText("Genshin Impact", substring = true).assertDoesNotExist()
        inList(hasText("Friend card · 原始自填宣言\nLooking for: Find thoughtful adult friends")).assertIsDisplayed()
        val stored = store.load(account, null)!!
        assertEquals("原始自填宣言", stored.input.title)
        assertEquals(listOf("genshin-impact"), stored.input.games)
        assertNull(stored.pending)
        assertEquals("Preview must not publish", 2, FriendCardCompositionBehaviorTest.LocalRequests.requests.size)
        capture("editor-public-preview")
    }

    @Test fun englishSocialPlatformPickerAccountAndPrivacyUseStablePlatformKeys() {
        mount(FriendCardCreateFragment())
        success(request("/options"), options().apply {
            capabilities = FriendCardResponses.Capabilities().apply { socialAccounts = true }
        })
        success(request("/me"), FriendCardResponses.Me())
        click("+  Add a social account")
        compose.onNodeWithText("Choose a social platform").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close social platform picker").assertIsDisplayed()
        compose.onNodeWithText("WeChat").assertIsDisplayed().performClick()
        val input = compose.onNodeWithContentDescription("Social account 1: WeChat")
        input.performScrollTo().performTextInput("original_wechat_id")
        val store = FriendCardDraftStore(compose.activity)
        val added = store.load(account, null)!!.input.socialAccounts.single()
        assertEquals("wechat", added.platform)
        assertEquals("original_wechat_id", added.account)
        val id = added.id
        compose.onNodeWithContentDescription("Visibility of social account 1: WeChat").performScrollTo().performClick()
        val saved = store.load(account, null)!!.input.socialAccounts.single()
        assertEquals(id, saved.id)
        assertEquals("wechat", saved.platform)
        assertEquals("", saved.name)
        assertFalse(saved.isPublic)
        assertFalse(FriendCardRules.gson.toJson(saved).contains("WeChat"))
        capture("social-editor")
    }

    @Test fun englishPublicDetailsReportAndBlockDialogsAreRealNativeViews() {
        val fragment = mount(FriendCardDetailFragment(), Bundle().apply { putString("card_id", "visitor-card") })
        compose.onNodeWithText("Loading card…").assertIsDisplayed()
        success(request("/visitor-card"), FriendCardResponses.Card().apply { card = card("visitor-card", "43") })
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
        compose.onNodeWithContentDescription("Manage my friend cards").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send a message").assertIsDisplayed()
        inList(hasText("WeChat")).assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy WeChat account").assertIsDisplayed()
        click("Report")
        val report = activeDialog(fragment)
        compose.runOnIdle {
            assertEquals("Report friend card", allViews(report.window!!.decorView).filterIsInstance<TextView>().first { it.text.toString() == "Report friend card" }.text.toString())
            assertEquals("Submit report", report.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            assertEquals("Cancel", report.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
            val reason = allViews(report.window!!.decorView).filterIsInstance<EditText>().single()
            assertEquals("Enter a report reason (1–500 characters)", reason.hint.toString())
            // An empty report exercises local validation, never a real POST.
            report.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            // Native AlertController posts the listener and dismissal to its Handler.
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals("Enter a report reason of 1–500 characters", ShadowToast.getTextOfLatestToast())
        assertEquals(1, FriendCardCompositionBehaviorTest.LocalRequests.requests.size)
        click("Block user")
        val block = activeDialog(fragment)
        compose.runOnIdle {
            assertEquals("Block", block.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            assertTrue(allViews(block.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("This affects only this user.") })
            block.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(1, FriendCardCompositionBehaviorTest.LocalRequests.requests.size)
        capture("visitor-details")
    }

    @Test fun englishMyCardPlazaAndServiceErrorRenderFromCallbacks() {
        val fragment = mount(MyFriendCardsFragment())
        compose.onNodeWithText("Loading your friend card…").assertIsDisplayed()
        success(request("/me"), FriendCardResponses.Me().apply { card = card("my-card", "42") })
        success(request("/history"), FriendCardResponses.Page().apply { cards = emptyList() })
        compose.onNodeWithContentDescription("Current card · Visible").assertIsDisplayed()
        inList(hasText("Edit card")).assertIsDisplayed()
        capture("my-cards")
        compose.onNodeWithTag("friend-card-hub-tab-plaza").performClick()
        success(request("/feed"), FriendCardResponses.Page().apply { cards = listOf(card("other-card", "43")) })
        inList(hasText("Filter loaded cards · 1 loaded")).assertIsDisplayed()
        inList(hasText("Get to know them")).assertIsDisplayed()
        inList(hasText("Genshin Impact")).assertIsDisplayed()
        capture("plaza")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithContentDescription("Refresh").performClick()
        failure(request("/feed"), 404)
        inList(hasText("The friend card service or this card is unavailable. Please retry later.")).assertIsDisplayed()
        inList(hasText("Retry next page")).assertIsDisplayed()
        assertTrue(realComposeView(fragment).hasComposition)
    }

    @Test fun englishProductSelectionAndDisplayOnlyFallbackKeepCatalogIds() {
        val fragment = mount(FriendCardDiaperSelectFragment(), Bundle().apply {
            putString("draft_id", "english-picker")
            putIntegerArrayList("selected_ids", arrayListOf(7))
            putString("diaper_json", "[]")
        })
        compose.onNodeWithText("Product #7 ×").assertIsDisplayed()
        compose.onNodeWithText("Done (1)").assertIsDisplayed()
        compose.onNodeWithText("Loading products…").assertIsDisplayed()
        success(request("/diapers"), mapOf("diapers" to listOf(mapOf("id" to 7, "brand" to "原始品牌", "model" to "Original model")), "pagination" to mapOf("total" to 1)))
        inList(hasText("原始品牌 Original model")).assertIsDisplayed()
        inList(hasText("Selected · tap to deselect")).assertIsDisplayed()
        inList(hasText("Deselect")).assertIsDisplayed()
        capture("product-selection")
        var result: Bundle? = null
        compose.runOnIdle { fragment.setResultCallback { accepted, value -> assertTrue(accepted); result = Bundle(value) } }
        compose.onNodeWithText("Done (1)").performClick()
        compose.runOnIdle {
            assertEquals(listOf(7), result!!.getIntegerArrayList("diaper_ids"))
            val json = result!!.getString("diaper_json")!!
            assertTrue(json.contains("原始品牌 Original model"))
            assertFalse(json.contains("Product #"))
        }
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h568dp-mdpi")
    fun englishLongLabelsAt320dpAnd160PercentKeepNativeControlsInsideViewport() {
        compose.runOnIdle { RuntimeEnvironment.setFontScale(1.6f) }
        // The font change recreates ComponentActivity. Restore the fixture's native container
        // on that actual host before mounting any production Fragment; it has no layout to restore.
        compose.activityRule.scenario.onActivity { activity ->
            activity.setTheme(R.style.Theme_Mastodon_Light)
            activity.setContentView(FrameLayout(activity).apply {
                id = containerId
                layoutParams = ViewGroup.LayoutParams(-1, -1)
            })
            assertEquals(320, activity.resources.configuration.screenWidthDp)
            assertEquals(1.6f, activity.resources.configuration.fontScale, .001f)
            val metrics = activity.resources.displayMetrics
            assertEquals(1.6f, metrics.scaledDensity / metrics.density, .001f)
        }
        val fragment = mount(FriendCardCreateFragment())
        success(request("/options"), options())
        success(request("/me"), FriendCardResponses.Me())
        compose.runOnIdle {
            val content = realComposeView(fragment)
            assertEquals(1.6f, content.resources.configuration.fontScale, .001f)
            assertEquals(320f, content.width / content.resources.displayMetrics.density, .01f)
        }
        assertEquals(320f, compose.onRoot().fetchSemanticsNode().boundsInRoot.width, .01f)
        assertFits("Next step", text = true)
        inList(hasText("Interaction preferences visibility")).assertIsDisplayed()
        compose.onNodeWithText("Interaction preferences visibility").performClick()
        assertFalse(FriendCardDraftStore(compose.activity).load(account, null)!!.input.publicFields.contains("interaction_habits"))
        click("Interests & experiences")
        assertFits("Next step", text = true)
        inList(hasText("Genshin Impact")).assertIsDisplayed()
        capture("editor-320dp-font160")
        remove(fragment)
        mount(FriendCardDetailFragment(), Bundle().apply { putString("card_id", "long-details") })
        success(request("/long-details"), FriendCardResponses.Card().apply { card = card("long-details", "43") })
        assertFits("Close")
        assertFits("Manage my friend cards")
        assertFits("Send a message")
        capture("details-320dp-font160")
    }

    private fun assertFits(value: String, text: Boolean = false) {
        val target = if (text) compose.onNodeWithText(value) else compose.onNodeWithContentDescription(value)
        val bounds = target.assertIsDisplayed().getUnclippedBoundsInRoot()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("$value must fit horizontally", bounds.left.value >= 0 && bounds.right.value <= root.right + 1)
        assertTrue("$value must fit vertically", bounds.top.value >= 0 && bounds.bottom.value <= root.bottom + 1)
        assertTrue("$value keeps its touch target", bounds.bottom.value - bounds.top.value >= 44)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        lateinit var image: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
        }
        try {
            val samples = (1..10).flatMap { x -> (1..10).map { y -> image.getPixel(x * (image.width - 1) / 11, y * (image.height - 1) / 11) } }
            assertTrue("Native production UI must actually render", samples.distinct().size > 2)
            val directory = File(System.getProperty("newEnglishRenderDir").orEmpty().ifBlank {
                "/home/ZYongX/.local/share/abdl-post-translation-20261102/android-ui-renders/english"
            })
            assertTrue(directory.isDirectory || directory.mkdirs())
            File(directory, "friendcards-english-$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }

    private fun <T : FriendCardBaseFragment> mount(fragment: T, args: Bundle = Bundle()): T {
        fragment.arguments = Bundle(args).apply { putString("account", account) }
        compose.activityRule.scenario.onActivity { activity ->
            activity.fragmentManager.beginTransaction().add(containerId, fragment, "english-${mounted.size}").commit()
            activity.fragmentManager.executePendingTransactions()
            mounted.add(fragment)
        }
        compose.waitForIdle()
        assertTrue(realComposeView(fragment).isAttachedToWindow)
        return fragment
    }
    private fun remove(fragment: FriendCardBaseFragment) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.fragmentManager.beginTransaction().remove(fragment).commit()
            activity.fragmentManager.executePendingTransactions()
        }
        compose.waitForIdle()
    }
    private fun realComposeView(fragment: FriendCardBaseFragment) = allViews(requireNotNull(fragment.view)).filterIsInstance<ComposeView>().single()
    private fun allViews(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()
    private fun inList(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val target = compose.onNode(matcher)
        if (runCatching { target.assertIsDisplayed() }.isSuccess) return target
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0).performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }
    private fun click(text: String) { inList(hasText(text)).performClick(); compose.waitForIdle() }
    private fun input(hint: String) = inList(hasSetTextAction() and (hasText(hint) or hasAnyDescendant(hasText(hint))))
    private fun request(suffix: String) = FriendCardCompositionBehaviorTest.LocalRequests.requests.lastOrNull {
        (field(MastodonAPIRequest::class.java, "path").get(it) as String).endsWith(suffix)
    } ?: error("Missing offline request $suffix")
    @Suppress("UNCHECKED_CAST") private fun success(request: MastodonAPIRequest<*>, response: Any) {
        compose.runOnIdle { (field(APIRequest::class.java, "callback").get(request) as Callback<Any>).onSuccess(response) }
        compose.waitForIdle()
    }
    @Suppress("UNCHECKED_CAST") private fun failure(request: MastodonAPIRequest<*>, status: Int) {
        compose.runOnIdle { (field(APIRequest::class.java, "callback").get(request) as Callback<Any>).onError(MastodonErrorResponse("", status, null)) }
        compose.waitForIdle()
    }
    private fun activeDialog(fragment: FriendCardBaseFragment) = field(FriendCardBaseFragment::class.java, "activeDialog").get(fragment) as AlertDialog
    private fun field(type: Class<*>, name: String): Field = type.getDeclaredField(name).apply { isAccessible = true }
    private fun options() = FriendCardResponses.Options().apply {
        schemaVersion = 1; canCreate = true; verificationState = "active"
        games = listOf(FriendCardResponses.Game().apply { id = "genshin-impact"; name = "原神" })
        interestPresets = listOf("原始兴趣")
    }
    private fun card(id: String, ownerId: String) = FriendCard().apply {
        this.id = id; schemaVersion = 1; version = 1; state = "active"; this.ownerId = ownerId
        title = "Synthetic adult friends: quiet walks, thoughtful conversations and cooperative games"
        owner = FriendCard.Owner().apply { this.id = ownerId; username = "synthetic_adult"; displayName = "Synthetic adult" }
        lookingFor = "Adults who respect boundaries"
        games = listOf("genshin-impact")
        publicFields = listOf("looking_for", "games")
        socialAccounts = listOf(FriendCard.SocialAccount().apply { this.id = "wechat-row"; platform = "wechat"; account = "synthetic_contact"; isPublic = true })
    }
}
