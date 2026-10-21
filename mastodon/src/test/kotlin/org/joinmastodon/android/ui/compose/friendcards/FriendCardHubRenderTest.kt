package org.joinmastodon.android.ui.compose.friendcards

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import me.grishka.appkit.imageloader.ViewImageLoader
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.model.FriendCard
import org.junit.Assert.assertEquals
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
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.io.File

/** Native component renders. Real Fragment lifecycle/routing lives in FriendCardHubUiTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w393dp-h852dp-mdpi", application = CompatibilityTestApplication::class,
    shadows = [FriendCardHubRenderTest.Images::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FriendCardHubRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun fullscreen() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    @Test fun bothHubTabsRenderRealCoverAndNoCoverStatesInLightAndDark() {
        var dark by mutableStateOf(false)
        var tab by mutableStateOf(FriendCardHubTab.MyCards)
        var empty by mutableStateOf(false)
        compose.setContent {
            val theme = remember(dark) { ThemeController(colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
                lightColors = lightColorScheme(), darkColors = darkColorScheme()) }
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MiuixTheme(controller = theme) {
                    key(dark, tab, empty) {
                        val current = card("current", "以热爱为开场，遇见有趣的你", true)
                        val cards = listOf(card("plaza-a", "周末看展吗", true), card("plaza-b", "今晚一起开黑", false))
                        val state = FriendCardHubState(tab, if (empty) null else current, true, false, false, null,
                            emptyList(), true, false, null, null, if (empty) emptyList() else cards, true, false, null, "next-page",
                            "", FriendCardHubFilter.Recommended, FriendCardHubPosition(), FriendCardHubPosition(), false, false, 0)
                        val actions = FriendCardHubActions(back = {}, tab = { tab = it }, refresh = {}, query = {}, filter = {}, create = {},
                            edit = {}, detail = {}, profile = {}, state = {}, post = {}, delete = {}, history = {}, next = {}, myPosition = {}, feedPosition = {})
                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) { FriendCardHubScreen(state, actions) }
                    }
                }
            }
        }
        for (mode in listOf(false, true)) for (destination in FriendCardHubTab.entries) for (isEmpty in listOf(false, true)) {
            compose.runOnIdle { dark = mode; tab = destination; empty = isEmpty }
            if (!isEmpty) compose.onAllNodesWithText(if (destination == FriendCardHubTab.MyCards) "查看详情" else "认识一下")[0].assertIsEnabled()
            capture("${if (destination == FriendCardHubTab.MyCards) "my" else "plaza"}-${if (isEmpty) "empty" else "cards"}-${if (mode) "dark" else "light"}")
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        lateinit var image: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
        }
        assertEquals(393, image.width); assertEquals(852, image.height)
        val target = File("/tmp/abdl-friend-card-hub-renders").apply { mkdirs() }
        File(target, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }

    private fun card(id: String, title: String, cover: Boolean) = FriendCard().apply {
        this.id = id; this.title = title; schemaVersion = 1; version = 1; state = "active"; ownerId = "42"
        owner = FriendCard.Owner().apply { this.id = "42"; displayName = "小岚 · 虚构用户"; username = "fixture" }
        region = "杭州"; interests = listOf("电影", "城市漫游", "游戏"); games = listOf("minecraft")
        lookingFor = "分享日常，认识同频的朋友。"
        publicFields = listOf("interests", "games", "region", "looking_for")
        coverUrl = if (cover) "file:///friend-card-hub-fixture/cover.png" else null
    }

    @Implements(ViewImageLoader::class)
    class Images {
        companion object {
            @JvmStatic @Implementation fun load(view: ImageView, placeholder: Drawable?, request: ImageLoaderRequest) {
                check(request is UrlImageLoaderRequest && request.uri.toString() == "file:///friend-card-hub-fixture/cover.png")
                view.setImageDrawable(Cover())
            }
        }
    }
    class Cover : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun getIntrinsicWidth() = 1000
        override fun getIntrinsicHeight() = 600
        override fun draw(canvas: Canvas) {
            val b = bounds
            paint.color = 0xFF263C50.toInt(); canvas.drawRect(b, paint)
            paint.color = 0xFFBC8C80.toInt(); canvas.drawCircle(b.width() * .76f, b.height() * .28f, b.height() * .17f, paint)
            paint.color = 0xFF192C3A.toInt()
            for (x in 0..12) {
                val width = b.width() / 13f
                val top = b.height() * (.48f + (x % 4) * .07f)
                canvas.drawRect(x * width, top, (x + 1) * width - 2, b.height().toFloat(), paint)
            }
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        override fun getOpacity() = PixelFormat.OPAQUE
    }
}
