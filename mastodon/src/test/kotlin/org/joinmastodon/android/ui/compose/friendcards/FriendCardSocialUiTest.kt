@file:Suppress("DEPRECATION")

package org.joinmastodon.android.ui.compose.friendcards

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.UUID
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.api.requests.friendcards.FriendCardInput
import org.joinmastodon.android.api.requests.friendcards.FriendCardResponses
import org.joinmastodon.android.friendcards.FriendCardDraft
import org.joinmastodon.android.friendcards.FriendCardRules
import org.joinmastodon.android.model.FriendCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Real production components, synthetic contacts, native Skia captures; no network or copied UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w393dp-h852dp-mdpi", application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FriendCardSocialUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun fullscreen() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    @Test
    fun capabilityGateKeepsAbsentNullAndPreservesExistingRowsReadOnlyUntilSupported() {
        var supported by mutableStateOf(false)
        var draft by mutableStateOf(FriendCardDraft(account = "synthetic-social-ui", input = FriendCardInput()))
        var edits = 0
        compose.setContent {
            FixtureTheme {
                Editor(draft, supported) { change ->
                    edits++
                    draft = draft.copy(input = FriendCardRules.copy(draft.input).also(change))
                }
            }
        }
        compose.onNodeWithText("社交账号").assertDoesNotExist()
        compose.runOnIdle {
            assertNull(draft.input.socialAccounts)
            draft = draft.copy(input = FriendCardRules.copy(draft.input).apply { socialAccounts = listOf(social("persisted", "qq", "synthetic_qq")) })
        }
        scrollEditorTo("社交账号")
        compose.onNodeWithText("当前未确认服务端支持社交账号，已有内容已保留，暂不可编辑。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("第1项QQ账号").assertIsNotEnabled()
        compose.onNodeWithContentDescription("第1项QQ账号可见范围").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithContentDescription("移除第1项QQ账号").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, edits); assertEquals("synthetic_qq", draft.input.socialAccounts.single().account); supported = true }
        compose.onNodeWithContentDescription("第1项QQ账号可见范围").performScrollTo().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(1, edits); assertTrue(draft.input.socialAccounts.single().isPublic) }
        compose.onNodeWithText("当前未确认服务端支持社交账号，已有内容已保留，暂不可编辑。").assertDoesNotExist()
    }

    @Test
    fun searchableSelectorAddsOnlyRealPresetsOrCustomWithPrivateUuidRowsAndFiveItemLimit() {
        var entries by mutableStateOf<List<FriendCard.SocialAccount>>(emptyList())
        compose.setContent { FixtureTheme { EditorPanel { FriendCardSocialEditor(entries, true) { entries = it } } } }
        compose.onNodeWithText("+  添加社交账号").performClick()
        compose.onNodeWithText("选择社交平台").assertIsDisplayed()
        listOf("QQ", "微信", "其他 / 自定义").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        listOf("GitHub", "微博", "抖音").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithContentDescription("搜索社交平台").performTextInput("WECHAT")
        compose.onNodeWithText("QQ").assertDoesNotExist()
        compose.onNodeWithText("微信").performClick()
        compose.runOnIdle {
            assertEquals("wechat", entries.single().platform)
            assertFalse(entries.single().isPublic)
            assertEquals(entries.single().id, UUID.fromString(entries.single().id).toString())
        }
        val firstId = entries.single().id
        compose.onNodeWithContentDescription("第1项微信账号").performTextInput("synthetic_wechat")
        compose.onNodeWithContentDescription("第1项微信账号可见范围").performClick().assertIsOn()
        compose.onNodeWithContentDescription("选择第1项社交平台，当前微信").performClick()
        compose.onNodeWithText("其他 / 自定义").performClick()
        compose.onNodeWithContentDescription("第1项平台名称").performTextInput("虚构社区")
        compose.runOnIdle {
            assertEquals(firstId, entries.single().id)
            assertEquals("synthetic_wechat", entries.single().account)
            assertEquals("虚构社区", entries.single().name)
            assertTrue(entries.single().isPublic)
        }
        repeat(4) {
            compose.onNodeWithText("+  添加社交账号").performScrollTo().performClick()
            compose.onNodeWithContentDescription("搜索社交平台").performTextInput("qq")
            platformOption("QQ").performClick()
        }
        compose.onNodeWithText("已达上限（最多5个账号）").performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle {
            assertEquals(5, entries.size)
            assertEquals(5, entries.map { it.id }.distinct().size)
            assertTrue(entries.drop(1).all { !it.isPublic })
        }
        compose.onNodeWithContentDescription("移除第1项虚构社区账号").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(4, entries.size); assertTrue(entries.none { it.id == firstId }) }
        compose.onNodeWithText("+  添加社交账号").performScrollTo().performClick()
        compose.onNodeWithContentDescription("搜索社交平台").performTextInput("不存在的平台")
        compose.onNodeWithText("未找到预设平台，可选择其他 / 自定义。").assertIsDisplayed()
        compose.onNodeWithText("其他 / 自定义").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭社交平台选择").performClick()
        compose.runOnIdle { assertEquals(4, entries.size) }
    }

    @Test
    fun ordinaryCopiesPrivacyChangesAndSiblingRemovalKeepInputNodeFocusAndSelection() {
        val original = listOf(social("row-a", "qq", "synthetic_a"), social("row-b", "wechat", "synthetic_b"))
        var entries by mutableStateOf(original)
        compose.setContent { FixtureTheme { EditorPanel { FriendCardSocialEditor(entries, true) { entries = it } } } }
        val input = compose.onNodeWithContentDescription("第2项微信账号")
        input.performClick().performTextInputSelection(TextRange(2, 5))
        val nodeId = input.fetchSemanticsNode().id
        compose.runOnIdle { entries = entries.map { clone(it) } }
        val sameNode = { compose.onNode(SemanticsMatcher("original social input $nodeId") { it.id == nodeId }, useUnmergedTree = true) }
        sameNode().assertIsFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        compose.onNodeWithContentDescription("第2项微信账号可见范围").performClick()
        sameNode().assertIsFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        compose.onNodeWithContentDescription("移除第1项QQ账号").performClick()
        sameNode().assertIsFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        sameNode().performTextInput("替换")
        sameNode().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("sy替换etic_b")))
        compose.runOnIdle {
            assertEquals("row-b", entries.single().id)
            assertEquals("sy替换etic_b", entries.single().account)
            assertTrue(entries.single().isPublic)
            assertEquals("synthetic_b", original[1].account)
            assertFalse("Editing must not mutate the prior snapshot", original[1].isPublic)
        }
    }

    @Test
    fun detailCopiesOnlyAnExplicitPublicRowWithSensitiveClipboardAndCompactNeverLeaksContacts() {
        val rows = listOf(social("visible", "qq", "synthetic_public", visible = true), social("private", "wechat", "synthetic_secret"),
            social("custom", "custom", "https://example.invalid/synthetic", "虚构社区", true))
        var mode by mutableStateOf(0)
        compose.setContent {
            FixtureTheme {
                EditorPanel {
                    when (mode) {
                        0 -> FriendCardSocialDetails(rows, MiuixTheme.colorScheme.onSurface)
                        1 -> FriendCardPortrait(card(rows), compact = true)
                        else -> FriendCardPortrait(card(rows))
                    }
                }
            }
        }
        compose.onNodeWithText("synthetic_secret", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("复制微信账号").assertDoesNotExist()
        compose.onNodeWithContentDescription("复制QQ账号").performClick()
        compose.runOnIdle {
            val clip = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip
            assertNotNull(clip)
            assertEquals("synthetic_public", clip!!.getItemAt(0).text.toString())
            assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
            assertUntintedBitmaps(compose.activity.window.decorView)
        }
        compose.onNodeWithContentDescription("复制虚构社区账号").performClick()
        compose.runOnIdle {
            assertEquals("https://example.invalid/synthetic", compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
            mode = 1
        }
        compose.onNodeWithText("社交账号").assertDoesNotExist()
        compose.onNodeWithText("synthetic_public", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { mode = 2 }
        compose.onNodeWithText("synthetic_public").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("synthetic_secret", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun productionSocialSectionSelectorAndFullPublicPortraitRenderInBothMiuixModes() {
        val rows = listOf(social("render-wechat", "wechat", "synthetic_wechat", visible = true),
            social("render-qq", "qq", "synthetic_private_qq"), social("render-custom", "custom", "https://example.invalid/u/demo", "虚构社区", true))
        var dark by mutableStateOf(false)
        var detail by mutableStateOf(false)
        var draft by mutableStateOf(FriendCardDraft(account = "synthetic-social-render", input = FriendCardInput.fromCard(card(rows))))
        compose.setContent {
            FixtureTheme(dark) {
                if (detail) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { FriendCardPortrait(card(rows)) }
                else Editor(draft, supported = true) { change -> draft = draft.copy(input = FriendCardRules.copy(draft.input).also(change)) }
            }
        }
        for (isDark in listOf(false, true)) {
            val suffix = if (isDark) "dark" else "light"
            compose.runOnIdle { dark = isDark; detail = false }
            scrollEditorTo("+  添加社交账号")
            compose.onNodeWithText("+  添加社交账号").performScrollTo().assertIsDisplayed()
            capture("social-editor-$suffix")
            compose.onNodeWithText("+  添加社交账号").performClick()
            compose.onNodeWithText("选择社交平台").assertIsDisplayed()
            capture("social-selector-$suffix", dialogWindow = true)
            compose.onNodeWithContentDescription("关闭社交平台选择").performClick()
            compose.runOnIdle { detail = true }
            compose.onNodeWithText("synthetic_wechat").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("synthetic_private_qq", useUnmergedTree = true).assertDoesNotExist()
            capture("social-public-full-portrait-$suffix")
        }
    }

    private fun platformOption(name: String) = compose.onNode(
        hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
    )

    private fun scrollEditorTo(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).performScrollTo()
    }

    @Composable
    private fun Editor(draft: FriendCardDraft, supported: Boolean, onEdit: ((FriendCardInput) -> Unit) -> Unit) {
        FriendCardEditorScreen(
            draft = draft, options = FriendCardResponses.Options().apply {
                canCreate = true; verificationState = "active"
                capabilities = FriendCardResponses.Capabilities().apply { socialAccounts = supported }
            }, checking = false, eligibilityError = null, error = null, preview = false, busy = false, coverBusy = false,
            onRetry = {}, onVerification = {}, onStage = {}, onEdit = onEdit,
            onPreview = {}, onBackToEdit = {}, onPublish = {}, onUnlock = {}, onSave = {}, onCover = {}, onRemoveCover = {},
            onInterest = { null }, onToggleInterest = {}, onGame = { null }, onToggleGame = {}, onDiapers = {}, onDiaperOpen = {}, onDiaperRemove = {},
        )
    }

    @Composable
    private fun FixtureTheme(dark: Boolean = false, content: @Composable () -> Unit) {
        val controller = remember(dark) {
            ThemeController(colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
                lightColors = lightColorScheme(), darkColors = darkColorScheme())
        }
        CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalLayoutDirection provides LayoutDirection.Ltr) {
            MiuixTheme(controller = controller) { Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) { content() } }
        }
    }

    @Composable
    private fun EditorPanel(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) { content() }
    }

    private fun capture(name: String, dialogWindow: Boolean = false) {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view = if (dialogWindow) {
                val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "Selector must own a native Dialog window" }
                assertTrue("Capture must use the showing selector, not an underlying activity", dialog.isShowing)
                requireNotNull(dialog.window).decorView.also {
                    assertTrue("Selector capture must not reuse activity decor", it !== compose.activity.window.decorView)
                }
            } else compose.activity.window.decorView
            assertTrue("The actual window must have measured content", view.width > 0 && view.height > 0)
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        assertEquals(393, bitmap.width)
        if (!dialogWindow) assertEquals(852, bitmap.height)
        else assertTrue("Dialog must fill usable height without exceeding the screen", bitmap.height in 400..852)
        val samples = (1..12).flatMap { x -> (1..12).map { y -> bitmap.getPixel(x * (bitmap.width - 1) / 13, y * (bitmap.height - 1) / 13) } }
        assertTrue("Native capture must contain actual UI", samples.distinct().size > 2)
        assertTrue(samples.any { android.graphics.Color.alpha(it) > 0 })
        val directory = File(System.getProperty("friendCard.renderDir").orEmpty().trim().ifBlank { "/tmp/abdl-friend-card-ui-renders/socials" })
        assertTrue(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "$name.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        assertTrue(file.length() > 1024)
    }

    private fun assertUntintedBitmaps(view: View) {
        if (view is ImageView) { assertNull(view.imageTintList); assertNotNull(view.drawable) }
        if (view is ViewGroup) for (index in 0 until view.childCount) assertUntintedBitmaps(view.getChildAt(index))
    }

    private fun social(id: String, platform: String, account: String, name: String = "", visible: Boolean = false) = FriendCard.SocialAccount().apply {
        this.id = id; this.platform = platform; this.account = account; this.name = name; isPublic = visible
    }

    private fun clone(entry: FriendCard.SocialAccount) = social(entry.id, entry.platform, entry.account, entry.name.orEmpty(), entry.isPublic)

    private fun card(rows: List<FriendCard.SocialAccount>) = FriendCard().apply {
        id = "synthetic-social-card"; schemaVersion = 1; version = 1; title = "虚构样例：慢慢认识彼此"
        region = "虚构城市"; mbti = "INFP"; interests = listOf("散步", "音乐"); lookingFor = "一起分享日常，尊重彼此边界。"
        publicFields = listOf("region", "mbti", "interests", "looking_for"); socialAccounts = rows
    }
}
