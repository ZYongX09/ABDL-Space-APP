@file:Suppress("DEPRECATION")

package org.joinmastodon.android.ui.compose.friendcards

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import me.grishka.appkit.imageloader.ViewImageLoader
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.R
import org.joinmastodon.android.api.requests.friendcards.FriendCardInput
import org.joinmastodon.android.api.requests.friendcards.FriendCardResponses
import org.joinmastodon.android.friendcards.FriendCardDraft
import org.joinmastodon.android.friendcards.FriendCardRules
import org.joinmastodon.android.model.FriendCard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.io.File
import kotlin.math.roundToInt

/**
 * Real production composables on a real ComponentActivity with native Skia and Canvas capture.
 * These are component regressions, not substitutes for FriendCardCompositionBehaviorTest's real
 * fragment lifecycle/navigation tests. Owner/visitor footer arrangements are explicitly fixtures;
 * their routing/authorization still belongs to the fragments. Deterministic Miuix light/dark schemes
 * are used instead of MiuixAppTheme's process-wide UiUtils preference/Android-attribute adapter.
 *
 * Every render is synthetic adult data. No account session, network, user photograph, or copied
 * screenshot is involved. The optional geometric cover replaces only ViewImageLoader.load;
 * AndroidView, FocusImage, its drawable callback, aspect ratio and crop matrix remain production.
 * Native Canvas draws the actual decor hierarchy; PixelCopy needs an unavailable window frame callback.
 * PNGs always go outside the repository, to friendCard.renderDir when nonblank, otherwise
 * /tmp/abdl-friend-card-ui-renders. These are review captures, not approved golden baselines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    qualifiers = "w393dp-h852dp-mdpi",
    application = CompatibilityTestApplication::class,
    shadows = [FriendCardRedesignComposeTest.SyntheticImages::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FriendCardRedesignComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun prepareHost() {
        SyntheticImages.loadedViews.clear()
        compose.activityRule.scenario.onActivity { activity ->
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    @After
    fun releaseLoaderObservations() {
        SyntheticImages.loadedViews.clear()
    }

    @Test
    fun fullPortraitShowsSchoolAndHabitsOnlyWhilePublic() {
        var shown by mutableStateOf(card())
        compose.setContent {
            FixtureTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    FriendCardPortrait(shown, games = gameLabels)
                }
            }
        }
        compose.onNodeWithText(school).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(habits).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            shown = card().apply { publicFields = publicFields - "school" - "interaction_habits" }
        }
        assertAbsent(school, habits)
        // A privacy change must not discard an unrelated, still-public field.
        compose.onNodeWithText(lookingFor).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun populatedHiddenFieldsNeverEnterFullOrCompactPortraitSemantics() {
        var compact by mutableStateOf(false)
        val privateCard = privateCard()
        compose.setContent {
            FixtureTheme {
                key(compact) {
                    if (compact) FriendCardPortrait(privateCard, compact = true, games = gameLabels)
                    else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        FriendCardPortrait(privateCard, games = gameLabels)
                    }
                }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { compact = mode }
            compose.onNodeWithText(privateCard.title).assertIsDisplayed()
            assertAbsent(*privateValues.toTypedArray())
            assertAbsent("关于我", "我喜欢", "我在玩", "我想找", "我的雷区", "我穿过")
        }
    }

    @Test
    fun gameIdsResolveToNamesWithoutManufacturedRanksCountsOrActivity() {
        val gamesOnly = card().apply {
            games = listOf("server-only-synthetic-game", "stardew-valley", "自填桌游")
            publicFields = listOf("games")
        }
        compose.setContent {
            FixtureTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    FriendCardPortrait(gamesOnly, games = mapOf("server-only-synthetic-game" to "虚构合作游戏"))
                }
            }
        }
        listOf("虚构合作游戏", "星露谷物语", "自填桌游").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        assertAbsent("server-only-synthetic-game", "stardew-valley")
        assertAbsent("胜率", "段位", "在线", "活跃", "游戏时长", "小时", "等级", "%")
    }

    @Test
    fun primaryIdentityAndHelloExposeAccessibleLabelsRolesAndDisabledState() {
        var enabled by mutableStateOf(true)
        var primaryClicks = 0
        var helloClicks = 0
        var profileClicks = 0
        compose.setContent {
            FixtureTheme {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    FriendCardIdentity(card(), light = false, onClick = { profileClicks++ })
                    FriendCardPrimaryButton("编辑卡片", { primaryClicks++ }, enabled = enabled)
                    FriendCardHello(enabled = enabled) { helloClicks++ }
                }
            }
        }
        val buttonRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        compose.onNodeWithText(ownerName).assert(buttonRole).assertHasClickAction().performClick()
        compose.onNodeWithText("编辑卡片").assert(buttonRole).assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("发私信").assert(buttonRole).assertIsEnabled().performClick()
        assertAbsent("Hi", "发私信")
        val hello = compose.onNodeWithContentDescription("发私信").getUnclippedBoundsInRoot()
        assertEquals("Private-message control remains circular", hello.right.value - hello.left.value,
            hello.bottom.value - hello.top.value, .01f)
        assertEquals(54f, hello.right.value - hello.left.value, .01f)
        compose.runOnIdle {
            assertEquals(1, primaryClicks)
            assertEquals(1, helloClicks)
            assertEquals(1, profileClicks)
            enabled = false
        }
        compose.onNodeWithText("编辑卡片").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("发私信").assertIsNotEnabled().performClick()
        compose.runOnIdle {
            assertEquals("Disabled primary callback must not fire", 1, primaryClicks)
            assertEquals("Disabled private-message callback must not fire", 1, helloClicks)
        }
    }

    @Test
    fun chatAndCloseRenderVisibleSvgStrokesMatchingNativeDrawablesInLightAndDark() {
        var dark by mutableStateOf(false)
        val panel = androidx.compose.ui.graphics.Color(0xFF263F43)
        compose.setContent {
            FixtureTheme(dark = dark) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    FixtureCaption("SVG像素回归 · 原生资源参考与实际控件")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("Android ImageView", modifier = Modifier.weight(1f))
                        NativeIconReference(R.drawable.ic_friend_card_chat, 29, 0xFF233036.toInt(), "native-chat", androidx.compose.ui.graphics.Color.White)
                        NativeIconReference(R.drawable.ic_fluent_dismiss_24_regular, 20, android.graphics.Color.WHITE, "native-close", panel)
                    }
                    Box(Modifier.fillMaxWidth().background(panel)) {
                        FriendCardDetailControls(backdrop = null, enabled = true, onClose = {}, onManage = {})
                    }
                    FriendCardHello {}
                }
            }
        }
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark = isDark }
            val chat = compose.onNodeWithContentDescription("发私信").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val close = compose.onNodeWithContentDescription("关闭").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val nativeChat = compose.onNodeWithTag("native-chat").fetchSemanticsNode().boundsInRoot
            val nativeClose = compose.onNodeWithTag("native-close").fetchSemanticsNode().boundsInRoot
            capture("chat-close-svg-pixels-${mode(isDark)}-393x852", 393, 852) { bitmap ->
                fun ink(bounds: Rect, darkInk: Boolean): Set<Pair<Int, Int>> = buildSet {
                    for (y in 0 until bounds.height.roundToInt()) for (x in 0 until bounds.width.roundToInt()) {
                        val pixel = bitmap.getPixel(bounds.left.roundToInt() + x, bounds.top.roundToInt() + y)
                        val channels = listOf(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))
                        if (if (darkInk) channels.max() < 130 else channels.min() > 220) add(x to y)
                    }
                }
                fun center(bounds: Rect, size: Float) = Rect(bounds.center.x - size / 2, bounds.center.y - size / 2,
                    bounds.center.x + size / 2, bounds.center.y + size / 2)
                fun coverage(expected: Set<Pair<Int, Int>>, actual: Set<Pair<Int, Int>>) = expected.count { (x, y) ->
                    (-1..1).any { dx -> (-1..1).any { dy -> (x + dx to y + dy) in actual } }
                }.toFloat() / expected.size.coerceAtLeast(1)
                fun verify(label: String, reference: Rect, actual: Rect, size: Float, darkInk: Boolean) {
                    val nativeInk = ink(reference, darkInk)
                    val actualInk = ink(center(actual, size), darkInk)
                    val counts = "$label ${mode(isDark)}: native=${nativeInk.size}, production=${actualInk.size}"
                    assertTrue("Native ImageView must draw the supplied SVG resource, $counts", nativeInk.size >= 20)
                    assertTrue("The actual control must contain strokes, not a blank or filled circle, $counts",
                        actualInk.size >= nativeInk.size * .6f && actualInk.size <= nativeInk.size * 1.8f)
                    assertTrue("Production must preserve the supplied SVG paths (1px raster tolerance), $counts",
                        coverage(nativeInk, actualInk) >= .8f && coverage(actualInk, nativeInk) >= .8f)
                }
                verify("dark chat", nativeChat, chat, 29f, darkInk = true)
                verify("white close", nativeClose, close, 20f, darkInk = false)
            }
        }
    }

    @Test
    fun quotationCanvasRendersTwoCompleteMarksWithRoomInsideItsBounds() {
        compose.setContent {
            FixtureTheme(fontScale = 1.6f) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
                    FriendCardQuotation(Modifier.testTag("quotation"))
                }
            }
        }
        val quotation = compose.onNodeWithTag("quotation").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(36f, quotation.width, .01f)
        assertEquals(26f, quotation.height, .01f)
        assertAbsent("“")
        capture("quotation-canvas-393x852-font160", 393, 852) { bitmap ->
            val left = quotation.left.toInt()
            val top = quotation.top.toInt()
            fun lit(x: Int, y: Int) = android.graphics.Color.red(bitmap.getPixel(left + x, top + y)) > 10
            for (x in listOf(8, 25)) {
                assertTrue("Each quotation shoulder must render above the body", lit(x, 5))
                assertTrue("Each quotation body must reach its designed bottom", lit(x, 22))
            }
            assertFalse("Keep the gap between both marks", lit(17, 18))
            assertFalse("Top of the Canvas must leave breathing room", lit(8, 0))
            assertFalse("Bottom of the Canvas must not clip the quotation body", lit(8, 25))
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-mdpi")
    fun longTitleAtSmallWidthAndLargeFontKeepsFeedActionsInTheViewport() {
        val long = card().apply {
            title = "虚构成人样例：慢慢认识彼此，周末一起散步、读书和玩合作游戏"
        }
        compose.setContent { FixtureTheme(fontScale = 1.6f) { FeedFixture(long) } }
        compose.onNodeWithText(long.title).assertIsDisplayed()
        compose.onNodeWithText("完整资料").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription("发私信").assertIsDisplayed().assertHasClickAction()

        val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText(long.title).fetchSemanticsNode().boundsInRoot
        val detail = compose.onNodeWithText("完整资料").getUnclippedBoundsInRoot()
        val hello = compose.onNodeWithContentDescription("发私信").getUnclippedBoundsInRoot()
        // LocalDensity is explicitly mdpi, so these unclipped Dp bounds are root pixels as well.
        listOf("detail" to detail, "hello" to hello).forEach { (name, bounds) ->
            assertTrue("$name must fit horizontally", bounds.left.value >= 0 && bounds.right.value <= viewport.right + 1)
            assertTrue("$name must fit vertically", bounds.top.value >= 0 && bounds.bottom.value <= viewport.bottom + 1)
            assertTrue("$name must retain a minimum 48dp touch height", bounds.bottom.value - bounds.top.value >= 48)
            assertTrue("Long title must not overlap $name", title.bottom <= bounds.top.value)
        }
        assertFalse("Detail and hello touch regions must not overlap", Rect(detail.left.value, detail.top.value,
            detail.right.value, detail.bottom.value).overlaps(Rect(hello.left.value, hello.top.value, hello.right.value, hello.bottom.value)))
        capture("feed-no-cover-light-320x568-font160", 320, 568)
    }

    @Test
    fun productionEditorStagesAndCompactFeedRenderInLightAndDark() {
        var dark by mutableStateOf(false)
        var stage by mutableStateOf<Int?>(0)
        var cover by mutableStateOf(false)
        compose.setContent {
            FixtureTheme(dark = dark) {
                key(dark, stage, cover) {
                    if (stage == null) FeedFixture(card(cover))
                    else EditorFixture(stage = requireNotNull(stage), onStage = { stage = it })
                }
            }
        }
        for (isDark in listOf(false, true)) {
            for (index in 0..2) {
                compose.runOnIdle { dark = isDark; stage = index }
                val expected = when (index) {
                    0 -> hasSetTextAction() and hasText(title)
                    1 -> hasText("自定义兴趣")
                    else -> hasSetTextAction() and hasText(lookingFor)
                }
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(expected)
                compose.onNode(expected).assertIsDisplayed()
                assertEquals("All stages have just one pinned next footer", 1,
                    compose.onAllNodes(hasText("下一阶段")).fetchSemanticsNodes().size)
                compose.onNodeWithText("下一阶段").assertIsDisplayed().assertHasClickAction()
                assertAbsent("保存本机草稿", "发布前预览")
                // Capture a stable initial viewport, not whichever field the assertion scrolled to.
                compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                capture("editor-stage$index-no-cover-${mode(isDark)}-393x852", 393, 852)
            }
            for (hasCover in listOf(false, true)) {
                compose.runOnIdle { dark = isDark; stage = null; cover = hasCover }
                compose.onNodeWithText(title).assertIsDisplayed()
                compose.onNodeWithText("完整资料").assertIsDisplayed()
                compose.onNodeWithContentDescription("发私信").assertIsDisplayed()
                if (hasCover) compose.runOnIdle {
                    assertTrue("Compact portrait must exercise the real FocusImage success path",
                        SyntheticImages.loadedViews.any { it is FocusImage && it.isAttachedToWindow && it.drawable != null })
                }
                capture("feed-${if (hasCover) "synthetic-loader-cover" else "no-cover"}-${mode(isDark)}-393x852", 393, 852)
            }
        }
    }

    @Test
    @Config(qualifiers = "w393dp-h1800dp-mdpi")
    fun fullPortraitRendersOwnerAndVisitorFooterFixturesWithAndWithoutSyntheticCover() {
        var dark by mutableStateOf(false)
        var owner by mutableStateOf(true)
        var cover by mutableStateOf(false)
        compose.setContent {
            FixtureTheme(dark = dark) {
                key(dark, owner, cover) { DetailFixture(card(cover), owner) }
            }
        }
        for (isDark in listOf(false, true)) for (isOwner in listOf(true, false)) for (hasCover in listOf(false, true)) {
            compose.runOnIdle { dark = isDark; owner = isOwner; cover = hasCover }
            compose.onNodeWithText(title).assertIsDisplayed()
            // These contents must be really composed, even when the tall detail needs scrolling.
            compose.onNodeWithText(school).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(habits).performScrollTo().assertIsDisplayed()
            if (isOwner) {
                compose.onNodeWithText("编辑卡片").assertIsDisplayed().assertHasClickAction()
                compose.onNodeWithContentDescription("发私信").assertDoesNotExist()
            } else {
                compose.onNodeWithContentDescription("发私信").assertIsDisplayed().assertHasClickAction()
                compose.onNodeWithText("编辑卡片").assertDoesNotExist()
            }
            compose.onNodeWithContentDescription("关闭").assertIsDisplayed().assertHasClickAction()
            compose.onNodeWithContentDescription("管理我的扩列卡").assertIsDisplayed().assertHasClickAction()
            assertAbsent("Hi", "发私信")
            compose.onNodeWithText(title).performScrollTo()
            if (hasCover) compose.runOnIdle {
                assertTrue("Only the loader is replaced; the real FocusImage must receive the synthetic drawable",
                    SyntheticImages.loadedViews.any { it is FocusImage && it.isAttachedToWindow && it.drawable != null })
            }
            capture("detail-${if (isOwner) "owner" else "visitor"}-footer-fixture-${if (hasCover) "synthetic-loader-cover" else "no-cover"}-${mode(isDark)}-393x1800", 393, 1800)
        }
    }

    private fun assertAbsent(vararg values: String) {
        values.forEach { compose.onNodeWithText(it, substring = true, useUnmergedTree = true).assertDoesNotExist() }
    }

    private fun capture(name: String, width: Int, height: Int, verifyPixels: ((Bitmap) -> Unit)? = null) {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        assertEquals("Capture must use the requested mdpi viewport width", width, bitmap.width)
        assertEquals("Capture must use the requested fullscreen viewport height", height, bitmap.height)
        // Fail loudly rather than publishing a blank/transparent placeholder as a screenshot.
        val samples = (1..12).flatMap { x -> (1..12).map { y -> bitmap.getPixel(x * (width - 1) / 13, y * (height - 1) / 13) } }
        assertTrue("Native render must contain nontransparent pixels", samples.any { android.graphics.Color.alpha(it) > 0 })
        if (verifyPixels == null) assertTrue("Native render must contain actual UI, not one flat color", samples.distinct().size > 2)
        val directory = File(System.getProperty("friendCard.renderDir").orEmpty().trim().ifBlank { "/tmp/abdl-friend-card-ui-renders" })
        assertTrue("Cannot create screenshot directory $directory", directory.isDirectory || directory.mkdirs())
        val output = File(directory, "$name.png")
        output.outputStream().use { assertTrue("PNG compression failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("Screenshot is empty: $output", output.length() > 1024)
        try {
            verifyPixels?.invoke(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    @Composable
    private fun NativeIconReference(resource: Int, size: Int, tint: Int, tag: String, background: androidx.compose.ui.graphics.Color) {
        // Independent native reference: supplied XML paths, real ImageView.draw, no substituted bitmap.
        AndroidView(factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                imageTintList = ColorStateList.valueOf(tint)
                setImageResource(resource)
            }
        }, modifier = Modifier.size(size.dp).background(background).testTag(tag))
    }

    @Composable
    private fun FixtureTheme(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val controller = remember(dark) {
            ThemeController(colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
                lightColors = lightColorScheme(), darkColors = darkColorScheme())
        }
        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale), LocalLayoutDirection provides LayoutDirection.Ltr) {
            MiuixTheme(controller = controller) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) { content() }
            }
        }
    }

    @Composable
    private fun FeedFixture(value: FriendCard) {
        val palette = friendCardPalette(value)
        Column(Modifier.fillMaxSize().background(palette.background)) {
            FixtureCaption("紧凑推荐组件样例 · 虚构成人 · ${if (value.coverUrl == null) "无封面" else "合成几何封面"}")
            FriendCardPortrait(value, Modifier.weight(1f), games = gameLabels, compact = true)
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FriendCardIdentity(value)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FriendCardPrimaryButton("完整资料", {}, modifier = Modifier.weight(1f), light = true)
                    FriendCardHello {}
                }
            }
        }
    }

    @Composable
    private fun DetailFixture(value: FriendCard, owner: Boolean) {
        Column(Modifier.fillMaxSize()) {
            FixtureCaption("详情组件 · ${if (owner) "主人" else "访客"}样例 · API28非玻璃回退 · 虚构成人 · ${if (value.coverUrl == null) "无封面" else "合成几何封面"}")
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        FriendCardPortrait(value, games = gameLabels, topClearance = 64.dp)
                    }
                    Column(Modifier.fillMaxWidth().background(friendCardPalette(value).background).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (owner) {
                            FriendCardIdentity(value)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FriendCardPrimaryButton("编辑卡片", {}, modifier = Modifier.weight(1f), outlined = true, light = true)
                                FriendCardPrimaryButton("管理我的卡", {}, modifier = Modifier.weight(1f), outlined = true, light = true)
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                FriendCardIdentity(value, Modifier.weight(1f))
                                FriendCardHello {}
                            }
                        }
                    }
                }
                FriendCardDetailControls(backdrop = null, enabled = true, onClose = {}, onManage = {})
            }
        }
    }

    @Composable
    private fun FixtureCaption(label: String) {
        Text(label, fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface).padding(8.dp))
    }

    @Composable
    private fun EditorFixture(stage: Int, onStage: (Int) -> Unit) {
        var draft by remember(stage) { mutableStateOf(FriendCardDraft(account = "synthetic-adult-fixture", draftId = "synthetic-render-draft",
            stage = stage, input = FriendCardInput.fromCard(card()), coverUrl = null, diapers = card().diapers)) }
        FriendCardEditorScreen(
            draft = draft, options = options(), checking = false, eligibilityError = null, error = null,
            preview = false, busy = false, coverBusy = false,
            onRetry = {}, onVerification = {}, onStage = onStage,
            onEdit = { change -> draft = draft.copy(input = FriendCardRules.copy(draft.input).also(change)) },
            onPreview = {}, onBackToEdit = {}, onPublish = {}, onUnlock = {}, onSave = {}, onCover = {}, onRemoveCover = {},
            onInterest = { null }, onToggleInterest = {}, onGame = { null }, onToggleGame = {}, onDiapers = {}, onDiaperOpen = {}, onDiaperRemove = {},
        )
    }

    /** Synchronous image seam, scoped to this class. Unexpected URLs fail instead of using a network. */
    @Implements(ViewImageLoader::class)
    class SyntheticImages {
        companion object {
            val loadedViews = mutableListOf<ImageView>()

            @JvmStatic
            @Implementation
            fun load(view: ImageView, placeholder: Drawable?, request: ImageLoaderRequest) {
                check(request is UrlImageLoaderRequest && request.uri.toString() == coverUri) {
                    "The friend-card UI fixture must never request external images: $request"
                }
                view.setImageDrawable(GeometricCover())
                loadedViews.add(view)
            }
        }
    }

    /** A new, purely geometric drawable; it contains no person, photo, text or borrowed artwork. */
    class GeometricCover : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun getIntrinsicWidth() = 900
        override fun getIntrinsicHeight() = 600
        override fun draw(canvas: Canvas) {
            paint.shader = LinearGradient(0f, 0f, 900f, 600f, intArrayOf(0xFF3B6973.toInt(), 0xFFD8C6A0.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint)
            paint.shader = null
            paint.color = 0xFFF4E7BD.toInt()
            canvas.drawCircle(665f, 155f, 72f, paint)
            paint.color = 0xFF547D70.toInt()
            canvas.drawCircle(220f, 670f, 400f, paint)
            paint.color = 0xFF315A64.toInt()
            canvas.drawCircle(800f, 755f, 490f, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    companion object {
        private const val title = "虚构成人样例：周末一起散步"
        private const val ownerName = "虚构成年用户 · 林岚"
        private const val school = "虚构成人夜校 · 已毕业"
        private const val habits = "下班后慢回复，先文字沟通。"
        private const val lookingFor = "认识尊重边界的成年朋友，一起散步和玩合作游戏。"
        private const val coverUri = "file:///synthetic-friend-card-fixture/geometric-cover.png"
        private val gameLabels = mapOf("stardew-valley" to "星露谷物语", "minecraft" to "我的世界")
        private val privateValues = listOf("私密性别样例", "私密年龄样例", "私密星座样例", "私密地区样例", "私密样例学校", "私密性格样例",
            "私密样例互动", "私密兴趣样例", "私密游戏样例", "私密商品样例", "私密找朋友样例", "私密边界样例")

        private fun mode(dark: Boolean) = if (dark) "dark" else "light"

        private fun card(cover: Boolean = false) = FriendCard().apply {
            id = "synthetic-adult-card"
            schemaVersion = 1
            version = 1
            state = "active"
            ownerId = "synthetic-adult-owner"
            owner = FriendCard.Owner().apply { id = "synthetic-adult-owner"; username = "synthetic_adult"; displayName = ownerName; avatar = null }
            title = FriendCardRedesignComposeTest.title
            gender = "自愿填写"
            age = "28岁 · 虚构成人"
            zodiac = "天秤座"
            region = "虚构城市"
            school = FriendCardRedesignComposeTest.school
            mbti = "INFP"
            interactionHabits = habits
            interests = listOf("摄影", "散步", "纸质书")
            games = listOf("stardew-valley", "minecraft")
            diapers = listOf(FriendCard.Diaper().apply { id = 901; name = "虚构库内商品样例"; available = true; imageUrl = null })
            lookingFor = FriendCardRedesignComposeTest.lookingFor
            boundaries = "请先征得同意；不接受催促或未经邀请的联系。"
            publicFields = FriendCardRules.fields.keys.toList()
            coverUrl = if (cover) coverUri else null
        }

        private fun privateCard() = card().apply {
            gender = privateValues[0]; age = privateValues[1]; zodiac = privateValues[2]; region = privateValues[3]
            school = privateValues[4]; mbti = privateValues[5]; interactionHabits = privateValues[6]
            interests = listOf(privateValues[7]); games = listOf(privateValues[8])
            diapers = listOf(FriendCard.Diaper().apply { id = 902; name = privateValues[9]; available = true; imageUrl = null })
            lookingFor = privateValues[10]; boundaries = privateValues[11]
            publicFields = emptyList()
        }

        private fun options() = FriendCardResponses.Options().apply {
            schemaVersion = 1
            canCreate = true
            verificationState = "active"
            games = gameLabels.map { (key, label) -> FriendCardResponses.Game().apply { id = key; name = label } }
            interestPresets = listOf("摄影", "积木", "音乐")
        }
    }
}
