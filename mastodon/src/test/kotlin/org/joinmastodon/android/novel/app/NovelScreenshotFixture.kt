@file:Suppress("DEPRECATION")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.joinmastodon.android.novel.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.grishka.appkit.utils.V
import org.joinmastodon.android.GlobalUserPreferences
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.api.session.AccountLocalPreferences.ColorPreference
import org.joinmastodon.android.novel.app.design.NovelTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.io.File
import kotlin.math.roundToInt

internal typealias NovelComposeRule = NovelManualComposeHost

/** No ComposeTestRule, Instrumentation, Espresso or waitForIdle. Every frame is explicitly bounded.
 * Production semantics/actions are inspected directly; UI is never reconstructed by the fixture.
 */
internal class NovelManualComposeHost {
    private lateinit var lifecycle: ActivityController<ComponentActivity>
    lateinit var activity: ComponentActivity; private set
    lateinit var attachedComposeView: ComposeView; private set
    private val scheduler = TestCoroutineScheduler()
    private val clock = BroadcastFrameClock()
    private val job = SupervisorJob()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(dispatcher + clock + job)
    private val recomposer = Recomposer(scope.coroutineContext)
    private var frameNanos = 0L

    fun start() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        lifecycle = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        activity = lifecycle.get()
        activity.window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        activity.window.decorView.setViewTreeLifecycleOwner(activity)
        activity.window.decorView.setViewTreeViewModelStoreOwner(activity)
        activity.window.decorView.setViewTreeSavedStateRegistryOwner(activity)
        attachedComposeView = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setParentCompositionContext(recomposer)
        }
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        activity.setContentView(attachedComposeView)
        scheduler.runCurrent()
    }

    fun setContent(content: @Composable () -> Unit) {
        // ComposeView.setContent is immediate and never calls Espresso.onIdle.
        attachedComposeView.setContent(content)
        settle()
        assertTrue("Production composition must be created", attachedComposeView.hasComposition)
    }
    fun <T> runOnUiThread(action: () -> T): T {
        assertEquals("PAUSED Robolectric test owns the Android main thread", Looper.getMainLooper(), Looper.myLooper())
        return action()
    }
    fun settle(frames: Int = 40) {
        repeat(frames) {
            Snapshot.sendApplyNotifications()
            scheduler.runCurrent()
            frameNanos += 16_000_000L
            clock.sendFrame(frameNanos)
            scheduler.runCurrent()
            // Real Dialog/Popup ViewRootImpl attaches on its first scheduled native traversal.
            // Advance one bounded native frame as well as the Compose clock; idle-current alone
            // leaves newly registered windows unattached, so their production composition never starts.
            ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS)
            windows().forEach(::layoutWindow)
        }
        Snapshot.sendApplyNotifications(); scheduler.runCurrent()
    }
    private fun layoutWindow(view: View) {
        if (!view.isAttachedToWindow) return
        val (width, height) = viewport()
        val isMain = view === activity.window.decorView
        val w = if (isMain || view.width == 0) width else view.width
        val h = if (isMain || view.height == 0) height else view.height
        view.measure(exact(w), exact(h)); view.layout(0, 0, w, h)
        if (isMain) {
            assertTrue("The actual ComposeView must stay attached", attachedComposeView.isAttachedToWindow)
            attachedComposeView.measure(exact(width), exact(height)); attachedComposeView.layout(0, 0, width, height)
        }
        roots(view).forEach { it.measureAndLayoutForTest() }
        view.viewTreeObserver.dispatchOnPreDraw()
    }
    fun windows(): List<View> {
        // Registry membership precedes ViewRootImpl's initial attach. Never filter such windows
        // out before the bounded native traversal has had a chance to attach/compose them.
        val global = ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.view.WindowManagerGlobal"), "getInstance")
        val globalViews = ReflectionHelpers.getField<List<View>>(global, "mViews")
        val managerViews = Shadow.extract<ShadowWindowManagerImpl>(activity.windowManager).views
        val dialogViews = ShadowDialog.getShownDialogs().filter { it.isShowing }.mapNotNull { it.window?.decorView }
        return (listOf(activity.window.decorView) + globalViews + managerViews + dialogViews).distinct()
    }
    fun assertDialogShowing() {
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "Production click did not create a Dialog" }
        assertTrue("Production Dialog was created but not shown", dialog.isShowing)
        settle()
        val decor = requireNotNull(dialog.window).decorView
        assertTrue("Real Dialog window must attach before semantic inspection: ${windowDiagnostic()}", decor.isAttachedToWindow)
        assertTrue("Real Dialog must expose its production Compose owner: ${windowDiagnostic()}", roots(decor).isNotEmpty())
    }
    fun windowDiagnostic(): String = windows().joinToString("; ") { view ->
        "${view.javaClass.simpleName}[attached=${view.isAttachedToWindow},size=${view.width}x${view.height},owners=${roots(view).size}]"
    }
    private fun roots(view: View): List<RootForTest> = buildList {
        if (view is RootForTest) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(roots(view.getChildAt(i)))
    }
    fun nodes(unmerged: Boolean = false): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return windows().flatMap(::roots).flatMap { root ->
            flatten(if (unmerged) root.semanticsOwner.unmergedRootSemanticsNode else root.semanticsOwner.rootSemanticsNode)
        }
    }
    fun onNode(matcher: SemanticsMatcher, useUnmergedTree: Boolean = false) = NovelManualNode(this, matcher, 0, true, useUnmergedTree)
    fun onNodeWithText(text: String, substring: Boolean = false, useUnmergedTree: Boolean = false) = onNode(hasText(text, substring = substring), useUnmergedTree)
    fun onNodeWithContentDescription(text: String) = onNode(hasContentDescription(text))
    fun onAllNodes(matcher: SemanticsMatcher, useUnmergedTree: Boolean = false): List<NovelManualNode> =
        nodes(useUnmergedTree).filter(matcher::matches).indices.map { NovelManualNode(this, matcher, it, false, useUnmergedTree) }
    fun onAllNodesWithText(text: String) = onAllNodes(hasText(text))
    fun scrollTo(matcher: SemanticsMatcher): Unit {
        repeat(28) {
            val target = nodes().firstOrNull { matcher.matches(it) && visible(it) }
            if (target != null) return
            val scroller = nodes().firstOrNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null }
                ?: error("No production scroll action for ${matcher.description}")
            assertTrue("Real scroll semantics rejected scroll", scroller.config[SemanticsActions.ScrollBy].action!!.invoke(0f, viewport().second * .45f))
            settle()
        }
        error("Bounded scroll could not display ${matcher.description}")
    }
    fun visible(node: SemanticsNode): Boolean = node.layoutInfo.isAttached && node.layoutInfo.isPlaced &&
        node.boundsInWindow.let { it.width > 1 && it.height > 1 && it.bottom > 0 && it.top < viewport().second && it.right > 0 && it.left < viewport().first }
    fun viewport(): Pair<Int, Int> = activity.resources.let { r ->
        (r.configuration.screenWidthDp * r.displayMetrics.density).roundToInt() to (r.configuration.screenHeightDp * r.displayMetrics.density).roundToInt()
    }
    fun close() {
        windows().filter { it !== activity.window.decorView }.forEach { view ->
            fun dispose(v: View) { if (v is AbstractComposeView) v.disposeComposition(); if (v is ViewGroup) for (i in 0 until v.childCount) dispose(v.getChildAt(i)) }
            dispose(view)
        }
        ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.dismiss()
        attachedComposeView.disposeComposition()
        recomposer.cancel(); scope.cancel(); scheduler.runCurrent()
        lifecycle.pause().stop().destroy()
        Dispatchers.resetMain()
    }
    private fun exact(n: Int) = View.MeasureSpec.makeMeasureSpec(n, View.MeasureSpec.EXACTLY)
}

/** Fluent assertions/actions on the actual production merged semantics, without an Espresso adapter. */
internal class NovelManualNode(private val host: NovelManualComposeHost, private val matcher: SemanticsMatcher,
    private val index: Int, private val unique: Boolean, private val unmerged: Boolean) {
    private fun matches() = host.nodes(unmerged).filter(matcher::matches)
    private fun node(): SemanticsNode {
        val found = matches()
        if (unique) assertEquals("Expected one actual semantic node: ${matcher.description}; windows=${host.windowDiagnostic()}", 1, found.size)
        return found.getOrNull(index) ?: error("Missing actual semantic node: ${matcher.description}")
    }
    private fun ancestors(node: SemanticsNode) = generateSequence(node) { it.parent }
    private fun enabled(node: SemanticsNode) = ancestors(node).none { it.config.contains(SemanticsProperties.Disabled) }
    fun assertExists() = apply { node() }
    fun assertDoesNotExist() { assertTrue("Unexpected production text: ${matcher.description}", matches().isEmpty()) }
    fun assertIsDisplayed() = apply { assertTrue("Not displayed: ${matcher.description}", host.visible(node())) }
    fun assertIsEnabled() = apply { assertTrue("Disabled: ${matcher.description}", enabled(node())) }
    fun assertIsNotEnabled() = apply { assertFalse("Unexpectedly enabled: ${matcher.description}", enabled(node())) }
    fun assertHasClickAction() = apply { assertNotNull(ancestors(node()).firstOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.action != null }) }
    fun assertIsOn() = apply { assertEquals(ToggleableState.On, node().config.getOrNull(SemanticsProperties.ToggleableState)) }
    fun assertIsOff() = apply { assertEquals(ToggleableState.Off, node().config.getOrNull(SemanticsProperties.ToggleableState)) }
    fun performClick() = apply {
        val found = node(); assertTrue("Cannot click disabled production node", enabled(found))
        val action = ancestors(found).mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }.firstOrNull()
        assertNotNull("No production OnClick: ${matcher.description}", action)
        assertTrue(action!!.invoke()); host.settle()
    }
    fun performTextReplacement(value: String) = apply {
        val found = node(); assertTrue(enabled(found))
        assertTrue(requireNotNull(found.config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(value)))
        host.settle()
    }
    fun performImeAction() = apply {
        assertTrue(requireNotNull(node().config.getOrNull(SemanticsActions.OnImeAction)?.action).invoke()); host.settle()
    }
    fun performScrollTo() = apply { host.scrollTo(matcher) }
    fun performScrollToNode(target: SemanticsMatcher) = apply { node(); host.scrollTo(target) }
}

/** Native production rendering only. API28 fallback is NOT evidence for hardware glass/blur. */
internal class NovelScreenshotFixture(private val group: String) : ExternalResource() {
    val host = NovelManualComposeHost()
    private var oldContext: android.content.Context? = null
    private var oldTheme = GlobalUserPreferences.theme
    private var oldColor = GlobalUserPreferences.color
    private var oldExplicitColor = GlobalUserPreferences.colorExplicitlyChosen
    private var oldVsync = true
    private var oldPaused = false
    private var testClass = ""
    private var testMethod = ""
    private var dark by mutableStateOf(false)
    private val navigationOwner = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher { } }
    override fun apply(base: Statement, description: Description): Statement {
        testClass = description.className; testMethod = description.methodName.orEmpty(); return super.apply(base, description)
    }
    override fun before() {
        oldContext = MastodonApp.context; oldTheme = GlobalUserPreferences.theme; oldColor = GlobalUserPreferences.color
        oldExplicitColor = GlobalUserPreferences.colorExplicitlyChosen
        oldVsync = ReflectionHelpers.getStaticField(Choreographer::class.java, "USE_VSYNC"); oldPaused = ShadowChoreographer.isPaused()
        ShadowChoreographer.setPaused(true)
        // Robolectric 4.14 resetState unconditionally Shadow.extracts mDisplayEventReceiver.
        // Construct the real main instance with a receiver BEFORE disabling vsync scheduling;
        // creating it with USE_VSYNC=false leaves a null receiver and crashes runner cleanup.
        ReflectionHelpers.setStaticField(Choreographer::class.java, "USE_VSYNC", true)
        val choreographer = Choreographer.getInstance()
        checkNotNull(ReflectionHelpers.getField<Any?>(choreographer, "mDisplayEventReceiver")) { "Robolectric reset requires an initialized main frame receiver" }
        ReflectionHelpers.setStaticField(Choreographer::class.java, "USE_VSYNC", false)
        MastodonApp.context = RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context)
        GlobalUserPreferences.theme = GlobalUserPreferences.ThemePreference.LIGHT
        GlobalUserPreferences.color = ColorPreference.BLUE; GlobalUserPreferences.colorExplicitlyChosen = false
        host.start()
    }
    override fun after() {
        try { host.close() } finally {
            GlobalUserPreferences.theme = oldTheme; GlobalUserPreferences.color = oldColor; GlobalUserPreferences.colorExplicitlyChosen = oldExplicitColor
            MastodonApp.context = oldContext
            ReflectionHelpers.setStaticField(Choreographer::class.java, "USE_VSYNC", oldVsync); ShadowChoreographer.setPaused(oldPaused)
        }
    }
    fun setDark(compose: NovelComposeRule, value: Boolean) = compose.runOnUiThread {
        GlobalUserPreferences.theme = if (value) GlobalUserPreferences.ThemePreference.DARK else GlobalUserPreferences.ThemePreference.LIGHT; dark = value
    }
    fun show(compose: NovelComposeRule, content: @Composable () -> Unit) {
        compose.setContent {
            key(dark) { CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalNovelAccount provides ACCOUNT, LocalNavigationEventDispatcherOwner provides navigationOwner) {
                NovelTheme(ACCOUNT) { Box(Modifier.fillMaxSize().background(NovelTheme.colors.background)) { content() } }
            } }
        }
    }
    fun settle(compose: NovelComposeRule) = compose.settle()
    fun capture(compose: NovelComposeRule, name: String, reference: Int? = null, dialog: Boolean = false,
        sparseTextRegions: List<String> = emptyList()) {
        compose.settle()
        val (width, height) = compose.viewport()
        val attachedComposeView = compose.attachedComposeView
        assertTrue(attachedComposeView.isAttachedToWindow)
        assertEquals(width, attachedComposeView.width); assertEquals(height, attachedComposeView.height)
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image)
            attachedComposeView.draw(canvas) // Includes real production NovelManuscriptView / EditText.draw.
            if (dialog) {
                val actualDialog = requireNotNull(ShadowDialog.getLatestDialog())
                assertTrue("The production modal must really be showing", actualDialog.isShowing)
                val overlay = requireNotNull(actualDialog.window).decorView
                assertTrue(overlay.isAttachedToWindow)
                val location = IntArray(2); overlay.getLocationOnScreen(location)
                canvas.save(); canvas.translate(location[0].toFloat(), location[1].toFloat()); overlay.draw(canvas); canvas.restore()
            }
            val directory = File(renderRoot(), group).apply { assertTrue(isDirectory || mkdirs()) }
            // Persist the exact real bitmap BEFORE pixel gates, even when a gate fails. Only a
            // successfully validated capture is promoted to the normal PNG and manifest below.
            val diagnostic = File(directory, "$name-diagnostic.png")
            diagnostic.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue("Diagnostic PNG must not be empty: $diagnostic", diagnostic.length() > 2048)
            val samples = buildList { for (y in 1 until height step 5) for (x in 1 until width step 5) add(image.getPixel(x, y)) }
            assertTrue("Reject transparent/blank screenshots: $name; diagnostic=$diagnostic", samples.count { Color.alpha(it) > 240 } > samples.size * .9)
            assertTrue("Reject flat placeholders: $name; diagnostic=$diagnostic", samples.distinct().size > 24)
            if (sparseTextRegions.isEmpty()) {
                assertTrue("Real content must occupy the viewport: $name; diagnostic=$diagnostic", samples.count { it != samples.first() } > samples.size / 50)
            } else {
                // Only the explicitly opted-in supplemental chapter directory may be sparse.
                // Validate actual heading/chapter glyphs within production semantic text bounds,
                // never an arbitrary percentage of its intentionally empty remaining viewport.
                assertNull("Reference captures must retain the generic occupancy gate", reference)
                assertEquals("Sparse validation is scoped to the supplemental directory", "chapters-light-393x852", name)
                assertTrue("Require a heading plus real chapter text", sparseTextRegions.size >= 2)
                sparseTextRegions.forEach { text ->
                    val textNode = compose.nodes(unmerged = true).single { node ->
                        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
                    }
                    assertTrue("Sparse text must really be displayed: $text", compose.visible(textNode))
                    val bounds = textNode.boundsInWindow
                    val left = kotlin.math.floor(bounds.left).toInt().coerceIn(0, width)
                    val right = kotlin.math.ceil(bounds.right).toInt().coerceIn(0, width)
                    val top = kotlin.math.floor(bounds.top).toInt().coerceIn(0, height)
                    val bottom = kotlin.math.ceil(bounds.bottom).toInt().coerceIn(0, height)
                    assertTrue("Production text must have nonempty pixel bounds: $text", right > left && bottom > top)
                    val pixels = buildList { for (y in top until bottom) for (x in left until right) add(image.getPixel(x, y)) }
                    val paper = pixels.groupingBy { it }.eachCount().maxBy { it.value }.key
                    val ink = pixels.count { pixel -> Color.alpha(pixel) > 240 &&
                        maxOf(kotlin.math.abs(Color.red(pixel) - Color.red(paper)), kotlin.math.abs(Color.green(pixel) - Color.green(paper)), kotlin.math.abs(Color.blue(pixel) - Color.blue(paper))) >= 24 }
                    assertTrue("Actual glyph rasterization is missing for '$text': ink=$ink; bounds=$bounds; diagnostic=$diagnostic", ink >= 20)
                    assertTrue("Text region must contain antialiased glyph colors: $text", pixels.distinct().size > 8)
                }
            }
            val output = File(directory, "$name.png")
            output.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue("PNG must not be empty: $output", output.length() > 2048)
            writeManifest(directory, output, reference, width, height, dialog)
        } finally { image.recycle() }
    }
    private fun writeManifest(directory: File, output: File, reference: Int?, width: Int, height: Int, dialog: Boolean) {
        val manifest = File(directory, "manifest.json")
        val prior = if (manifest.isFile) JSONObject(manifest.readText()).optJSONArray("captures") ?: JSONArray() else JSONArray()
        val captures = JSONArray()
        for (i in 0 until prior.length()) if (prior.getJSONObject(i).optString("file") != output.name) captures.put(prior.getJSONObject(i))
        captures.put(JSONObject().put("file", output.name).put("path", output.absolutePath).put("reference", reference?.let { "novel-ui$it" } ?: JSONObject.NULL)
            .put("width", width).put("height", height).put("theme", if (dark) "dark" else "light").put("test_class", testClass).put("test_method", testMethod)
            .put("native_dialog_window", dialog).put("rendered_at_ms", System.currentTimeMillis()))
        val mapping = JSONArray()
        references.filter { it.group == group }.forEach { entry -> mapping.put(JSONObject().put("reference", "novel-ui${entry.id}")
            .put("source", "/home/ZYongX/projects/novel-ui/${entry.id}.png").put("state", entry.state).put("file", "novel-ui${entry.id}-${entry.state}-light-393x852.png")) }
        manifest.writeText(JSONObject().put("schema_version", 1).put("group", group).put("sdk", 28)
            .put("renderer", "Robolectric native Skia / manual ActivityController + attached production ComposeView; bounded BroadcastFrameClock; no Espresso")
            .put("hardware_glass_verified", false).put("fixture_data", "fictional, test-only; no real account or production writes")
            .put("references", mapping).put("captures", captures).toString(2))
    }
    companion object {
        const val ACCOUNT = "novel-screenshot-fixture"
        const val DEFAULT_RENDER_DIR = "/home/ZYongX/.local/share/abdl-novel-rebuild-20261028/android-ui-renders"
        fun renderRoot() = File(System.getProperty("novelUiRenderDir").orEmpty().trim().ifBlank { DEFAULT_RENDER_DIR })
        private data class Reference(val id: Int, val group: String, val state: String)
        private val references = listOf(
            Reference(1, "app", "search-results"), Reference(2, "app", "search"), Reference(3, "app", "creator-locked"),
            Reference(4, "app", "messages"), Reference(5, "editor", "work-actions"), Reference(6, "app", "author"),
            Reference(7, "editor", "split"), Reference(8, "editor", "adjust"), Reference(9, "editor", "import-preview"),
            Reference(10, "editor", "import"), Reference(11, "app", "detail"), Reference(12, "editor", "manage"),
            Reference(13, "editor", "manuscript"), Reference(14, "editor", "create"), Reference(15, "app", "creator"),
            Reference(16, "app", "shelf"), Reference(17, "app", "all-works"), Reference(18, "app", "store-ai"),
            Reference(19, "app", "store-repost"), Reference(20, "app", "store-original"), Reference(21, "app", "store-featured"),
        )
    }
}
