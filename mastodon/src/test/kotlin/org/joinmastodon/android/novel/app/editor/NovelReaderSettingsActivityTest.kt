@file:Suppress("DEPRECATION")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.joinmastodon.android.novel.app.editor

import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONObject
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.E
import org.joinmastodon.android.GlobalUserPreferences
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.api.HomeTimelineSnapshotCacheTest
import org.joinmastodon.android.api.MastodonAPIController
import org.joinmastodon.android.api.session.AccountLocalPreferences.ColorPreference
import org.joinmastodon.android.api.session.AccountSession
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.events.AccountLoggedOutEvent
import org.joinmastodon.android.novel.NovelAccountDataCleaner
import org.joinmastodon.android.novel.app.NovelAppActivity
import org.joinmastodon.android.novel.app.NovelAppApi
import org.joinmastodon.android.novel.app.NovelAppTestFixtures
import org.joinmastodon.android.novel.app.NovelAppViewModel
import org.joinmastodon.android.novel.app.NovelPage
import org.joinmastodon.android.security.AppGatekeeper
import org.joinmastodon.android.security.AppSecurity
import org.joinmastodon.android.security.AuthTracker
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.ui.FakeSecurityRepository
import org.joinmastodon.android.security.ui.securityState
import org.joinmastodon.android.ui.seamless.SeamlessTransitions
import org.joinmastodon.noveleditor.EditorText
import org.joinmastodon.noveleditor.NovelEditorStorage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit

/** App-module runtime graph, real Activity.onCreate/setContent, production pointer/semantic clicks
 * and the real Material3 Dialog. In particular, actually composing Settings exercises reader-core's
 * FlowRow linkage against the app's Foundation version, unlike source contracts or visibility tests.
 * Only HTTP responses and the PIN repository are fictional; no socket, account gate or UI bypass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w393dp-h852dp-notnight-mdpi", application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelReaderSettingsActivityTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val scheduler = TestCoroutineScheduler()
    private val clock = BroadcastFrameClock()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler) + clock + SupervisorJob())
    private val recomposer = Recomposer(scope.coroutineContext)
    private val activities = mutableListOf<ActivityController<out ComponentActivity>>()
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val novelRequests = Collections.synchronizedList(mutableListOf<String>())
    private val metadataRequests = Collections.synchronizedList(mutableListOf<String>())
    private val unexpected = Collections.synchronizedList(mutableListOf<String>())
    private var frameNanos = 0L
    private lateinit var session: AccountSession
    private lateinit var sessions: MutableMap<String, AccountSession>
    private lateinit var gate: AppGatekeeper
    private var oldContext: Context? = null
    private var oldHttp: OkHttpClient? = null
    private var oldTracker: Any? = null
    private var oldGate: Any? = null
    private var oldActive: String? = null
    private var oldTheme = GlobalUserPreferences.theme
    private var oldColor = GlobalUserPreferences.color
    private var oldLiquid = false
    private var oldVsync = true
    private var oldPaused = false
    private val work = NovelAppTestFixtures.work().copy(chapterCount = 2)
    private val chapterId get() = work.chapters.last().id
    private val body = (1..180).joinToString("\n\n") { paragraph ->
        "【设置回归段落$paragraph】保留真实 source $paragraph、Unicode e\u0301 和标点；这是同一作品同一章节的第 $paragraph 段正文。"
    }
    private fun field(type: Class<*>, name: String) = HomeTimelineSnapshotCacheTest.field(type, name)

    @Before @Suppress("UNCHECKED_CAST") fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        oldContext = MastodonApp.context; MastodonApp.context = app
        me.grishka.appkit.utils.V.setApplicationContext(app)
        oldTheme = GlobalUserPreferences.theme
        oldColor = GlobalUserPreferences.color
        oldLiquid = GlobalUserPreferences.useIosLiquidNavigation
        GlobalUserPreferences.theme = GlobalUserPreferences.ThemePreference.LIGHT
        // CompatibilityTestApplication omits MastodonApp.onCreate/load(); use its ordinary
        // default so a missing account still resolves the real non-account theme preferences.
        GlobalUserPreferences.color = ColorPreference.NORD
        GlobalUserPreferences.useIosLiquidNavigation = false
        oldVsync = ReflectionHelpers.getStaticField(Choreographer::class.java, "USE_VSYNC")
        oldPaused = ShadowChoreographer.isPaused()
        ShadowChoreographer.setPaused(true)
        ReflectionHelpers.setStaticField(Choreographer::class.java, "USE_VSYNC", true)
        checkNotNull(ReflectionHelpers.getField<Any?>(Choreographer.getInstance(), "mDisplayEventReceiver"))
        ReflectionHelpers.setStaticField(Choreographer::class.java, "USE_VSYNC", false)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }

        val manager = AccountSessionManager.getInstance()
        sessions = field(AccountSessionManager::class.java, "sessions").get(manager) as MutableMap<String, AccountSession>
        session = HomeTimelineSnapshotCacheTest.session("reader-settings-${UUID.randomUUID()}.example.test", "42")
        sessions[session.id] = session
        oldActive = manager.lastActiveAccountID
        field(AccountSessionManager::class.java, "lastActiveAccountID").set(manager, session.id)
        oldHttp = MastodonAPIController.getHttpClient()
        val offline = requireNotNull(oldHttp).newBuilder().apply {
            interceptors().clear(); networkInterceptors().clear()
        }.dns { throw AssertionError("Settings tests must never resolve a network host") }
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url()
                val encodedPath = url.encodedPath()
                val novelApi = url.scheme() == "https" && url.host() == "api.abdl-space.top" &&
                    encodedPath.startsWith("/api/v1/novels/app/")
                val path = if (novelApi) encodedPath.removePrefix("/api/v1/novels/app") else encodedPath
                val key = request.method() + " " + path
                requests += key
                if (novelApi) novelRequests += key
                val metadata = url.scheme() == "https" && url.host() == session.domain &&
                    request.method() == "GET" && encodedPath in setOf("/api/v2/instance", "/api/v1/instance")
                var status = 200
                val json = when {
                    metadata -> {
                        metadataRequests += key
                        // Model unavailable public instance metadata explicitly. A 404 would
                        // trigger the V1 fallback; a 200 starts unrelated custom-emoji loading.
                        status = 503
                        JSONObject().put("error", "Instance metadata unavailable in this offline reader fixture")
                    }
                    novelApi && key == "GET /works" -> NovelAppTestFixtures.page(work)
                    novelApi && key == "GET /works/${work.id}" -> NovelAppTestFixtures.workJson(work)
                        .put("progress", JSONObject().put("chapter_id", chapterId).put("offset", 2).put("release_id", work.releaseId))
                    novelApi && key == "GET /authors/${work.author.id}" -> JSONObject().put("author", NovelAppTestFixtures.authorJson(work.author))
                        .put("stats", JSONObject()).put("items", org.json.JSONArray())
                    novelApi && key == "GET /works/${work.id}/comments" -> NovelAppTestFixtures.page()
                    novelApi && key in work.chapters.map { "GET /works/${work.id}/chapters/${it.id}" } -> JSONObject()
                        .put("body", body).put("release_id", work.releaseId).put("body_sha256", EditorText.sha256(body))
                    novelApi && (key == "POST /works/${work.id}/read" || key == "PUT /progress/${work.id}") -> JSONObject()
                    else -> { unexpected += key; status = 404; JSONObject().put("error", "Unexpected offline fixture route") }
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(status).message("Offline reader fixture")
                    .body(ResponseBody.create(MediaType.parse("application/json"), json.toString())).build()
            }.build()
        field(MastodonAPIController::class.java, "httpClient").set(null, offline)
        oldTracker = field(AppSecurity::class.java, "trackerInstance").get(null)
        oldGate = field(AppSecurity::class.java, "gatekeeper").get(null)
        val tracker = AuthTracker(FakeSecurityRepository(SecurityResult.Success(securityState(LockMethod.Pin))))
        tracker.onAppCreate()
        val immediate = UnconfinedTestDispatcher(scheduler)
        gate = AppGatekeeper(app, tracker, immediate, immediate, {})
        field(AppSecurity::class.java, "trackerInstance").set(null, tracker)
        field(AppSecurity::class.java, "gatekeeper").set(null, gate)
        gate.install()
    }

    @After fun cleanup() {
        try {
            windows().forEach(::dispose)
            ShadowDialog.getShownDialogs().filter { it.isShowing }.forEach(Dialog::dismiss)
            activities.asReversed().forEach { controller ->
                controller.get().viewModelStore.clear()
                if (!controller.get().isDestroyed) controller.close()
            }
            recomposer.cancel(); scope.cancel(); scheduler.runCurrent()
            assertTrue("All offline requests must match an exact modeled route: $unexpected", unexpected.isEmpty())
        } finally {
            app.unregisterActivityLifecycleCallbacks(gate)
            sessions.remove(session.id)
            app.deleteDatabase(NovelEditorStorage.databaseName(session.id))
            app.getSharedPreferences("novel_app_search", Context.MODE_PRIVATE).edit().remove(session.id).commit()
            field(MastodonAPIController::class.java, "httpClient").set(null, oldHttp)
            field(AccountSessionManager::class.java, "lastActiveAccountID").set(AccountSessionManager.getInstance(), oldActive)
            field(AppSecurity::class.java, "trackerInstance").set(null, oldTracker)
            field(AppSecurity::class.java, "gatekeeper").set(null, oldGate)
            GlobalUserPreferences.theme = oldTheme
            GlobalUserPreferences.color = oldColor
            GlobalUserPreferences.useIosLiquidNavigation = oldLiquid
            MastodonApp.context = oldContext
            ReflectionHelpers.setStaticField(Choreographer::class.java, "USE_VSYNC", oldVsync)
            ShadowChoreographer.setPaused(oldPaused)
            Dispatchers.resetMain()
        }
    }

    /** Install only a bounded frame clock, never replace the production Activity's composition. */
    private fun withFrames(block: () -> Unit) = WindowRecomposerPolicy.withFactory(WindowRecomposerFactory { recomposer }, block)

    private fun <T : ComponentActivity> open(type: Class<T>, intent: Intent): ActivityController<T> =
        Robolectric.buildActivity(type, intent).also { activities += it; it.create().start().resume().visible(); frames() }

    private fun parent(external: Boolean = false, chapter: String? = null) = open(NovelAppActivity::class.java,
        NovelAppActivity.intent(app, session.id, work.id, chapter).putExtra(SeamlessTransitions.DETAIL_ENTRY, external))

    private fun model(activity: NovelReaderActivity) = requireNotNull(field(NovelReaderActivity::class.java, "controller").get(activity) as NovelReaderController?)
    private fun appModel(activity: NovelAppActivity) = field(NovelAppActivity::class.java, "model").get(activity) as NovelAppViewModel?
    private fun shadow(activity: ComponentActivity) = Shadow.extract<ShadowActivity>(activity)

    private fun readerFrom(parent: ActivityController<NovelAppActivity>, explicitChapter: String?): ActivityController<NovelReaderActivity> {
        val intent = requireNotNull(shadow(parent.get()).nextStartedActivity) { "Actual entry did not start the reader" }
        assertEquals(NovelReaderActivity::class.java.name, intent.component?.className)
        assertEquals(session.id, intent.getStringExtra("account"))
        assertEquals(work.id, intent.getStringExtra("work_id"))
        assertEquals(explicitChapter, intent.getStringExtra("chapter_id"))
        assertNull("One entry must launch exactly one reader", shadow(parent.get()).nextStartedActivity)
        parent.pause().stop()
        return open(NovelReaderActivity::class.java, intent)
    }

    @Test fun detailContinueReadingSettingsBackAndOutsideDismissKeepReaderAndParentStack() = withFrames {
        detailRoundTrip(external = false)
    }

    @Test fun externalDetailRootSettingsCannotTriggerTheParentReturnFinish() = withFrames {
        detailRoundTrip(external = true)
    }

    private fun detailRoundTrip(external: Boolean) {
        gate.onUnlocked()
        val parent = parent(external)
        await { appModel(parent.get())?.state?.value?.work?.value != null }
        val pages = requireNotNull(appModel(parent.get())).state.value.pages
        click(parent.get().window.decorView, app.getString(org.joinmastodon.android.R.string.novel_en_continue_reading))
        val reader = readerFrom(parent, null)
        val before = source(reader.get())
        assertEquals("Continue reading must resume the same nonzero source page", 2, before.page)
        assertTrue(before.start > 0)
        settingsRoundTrip(reader.get(), before)
        assertFalse(parent.get().isFinishing)
        assertEquals(pages, requireNotNull(appModel(parent.get())).state.value.pages)
        assertEquals(listOf(NovelPage.Store, NovelPage.Detail(work.id)), pages)
        assertNull(shadow(parent.get()).nextStartedActivity)
        // Only a separate, intentional reader Back may now close the host, revealing the same detail.
        reader.get().onBackPressedDispatcher.onBackPressed()
        await { reader.get().isFinishing }
        assertFalse(parent.get().isFinishing)
        assertEquals(pages, requireNotNull(appModel(parent.get())).state.value.pages)
    }

    @Test fun directExternalChapterIntentSettingsDoesNotReplayEntryOrCloseEitherActivity() = withFrames {
        gate.onUnlocked()
        val parent = parent(external = true, chapter = chapterId)
        val reader = readerFrom(parent, chapterId)
        val initial = source(reader.get())
        assertEquals("Explicit chapter entry must retain its normal page-zero semantics", 0, initial.page)
        val pager = nodes(reader.get().window.decorView).single { it.config.getOrNull(SemanticsActions.ScrollToIndex)?.action != null }
        assertTrue(requireNotNull(pager.config[SemanticsActions.ScrollToIndex].action).invoke(2))
        val before = awaitSource(reader.get()) { it.page == 2 && it.start > 0 }
        settingsRoundTrip(reader.get(), before)
        assertFalse(parent.get().isFinishing)
        assertNull(shadow(parent.get()).nextStartedActivity)
        assertNull(shadow(reader.get()).nextStartedActivity)
        assertEquals(chapterId, reader.get().intent.getStringExtra("chapter_id"))
    }

    @Test fun realSettingsSlidersApplyAndReopenWithoutLosingTheCurrentSourceAnchor() = withFrames {
        gate.onUnlocked()
        val reader = open(NovelReaderActivity::class.java, NovelReaderActivity.intent(app, session.id, work.id))
        val before = source(reader.get())
        showControls(reader.get())
        val dialog = openSettings(reader.get())
        val sliders = nodes(requireNotNull(dialog.window).decorView).filter { it.config.getOrNull(SemanticsActions.SetProgress)?.action != null }
            .sortedBy { it.boundsInWindow.top }
        assertEquals(2, sliders.size)
        assertTrue(requireNotNull(sliders[0].config[SemanticsActions.SetProgress].action).invoke(24f))
        frames()
        val lineSlider = nodes(requireNotNull(dialog.window).decorView).filter { it.config.getOrNull(SemanticsActions.SetProgress)?.action != null }
            .maxBy { it.boundsInWindow.top }
        assertTrue(requireNotNull(lineSlider.config[SemanticsActions.SetProgress].action).invoke(1.9f))
        val changed = awaitSource(reader.get()) { it.layout.layoutInput.style.fontSize.value == 24f && it.layout.layoutInput.style.lineHeight.value > 45f }
        assertTrue("Typography reflow must retain the original source anchor", before.start in changed.start until changed.start + changed.text.length)
        assertEquals(before.workId, changed.workId); assertEquals(before.chapterId, changed.chapterId)
        click(requireNotNull(dialog.window).decorView, app.getString(org.joinmastodon.reader.R.string.novel_reader_palette_white))
        (dialog as ComponentDialog).onBackPressedDispatcher.onBackPressed(); frames()
        assertFalse(dialog.isShowing); assertFalse(reader.get().isFinishing)
        val reopened = openSettings(reader.get())
        assertNotNull(text(requireNotNull(reopened.window).decorView, app.getString(org.joinmastodon.reader.R.string.novel_reader_font_size, 24)))
        dismissOutside(reopened)
        assertFalse(reader.get().isFinishing)
        val after = source(reader.get())
        assertEquals(changed.start, after.start); assertEquals(changed.text, after.text)
    }

    @Test fun lockedDetailWaitsForUnlockAndRevokedAccountStillClosesAnOpenSettingsReader() = withFrames {
        val parent = parent()
        assertTrue(AppSecurity.isLocked())
        assertNull(appModel(parent.get()))
        assertNotNull(parent.get().window.decorView.findViewWithTag<View>("abdl_security_shield"))
        assertEquals("The real AppLock gate must defer every novel/content request", 0, novelRequests.size)
        assertEquals("Only exact public instance metadata may bootstrap while locked", metadataRequests.toList(), requests.toList())
        gate.onUnlocked()
        await { appModel(parent.get())?.state?.value?.work?.value != null }
        assertNull(parent.get().window.decorView.findViewWithTag<View>("abdl_security_shield"))
        click(parent.get().window.decorView, app.getString(org.joinmastodon.android.R.string.novel_en_continue_reading))
        val reader = readerFrom(parent, null)
        source(reader.get()); showControls(reader.get())
        val dialog = openSettings(reader.get())
        val original = model(reader.get())
        NovelAccountDataCleaner.revoke(session.id) { sessions.remove(session.id) }
        E.post(AccountLoggedOutEvent(session.id)); frames()
        assertTrue("Settings must never weaken the captured session/logout fence", reader.get().isFinishing)
        assertFalse(original.viewModelScope.isActive)
        assertEquals(0, reader.get().findViewById<ViewGroup>(android.R.id.content).childCount)
        assertFalse("Logout must dispose private modal content as well", dialog.isShowing)
    }

    @Test fun missingAccountCannotOpenReaderContentOrSettings() = withFrames {
        gate.onUnlocked()
        val invalid = open(NovelReaderActivity::class.java, NovelReaderActivity.intent(app, "missing-account", work.id, chapterId))
        assertTrue(invalid.get().isFinishing)
        assertEquals(0, invalid.get().findViewById<ViewGroup>(android.R.id.content).childCount)
        assertTrue(requests.isEmpty())
        assertTrue(ShadowDialog.getShownDialogs().none { it.isShowing })
    }

    private fun settingsRoundTrip(activity: NovelReaderActivity, before: Source) {
        showControls(activity)
        assertSource(before, source(activity))
        val dialog = openSettings(activity)
        assertSource(before, source(activity))
        assertFalse(activity.isFinishing)
        assertTrue((model(activity).repository.api as NovelAppApi).isSessionActive())
        (dialog as ComponentDialog).onBackPressedDispatcher.onBackPressed(); frames()
        assertFalse("Dialog Back dismisses Settings, never the reader host", dialog.isShowing)
        assertFalse(activity.isFinishing); assertSource(before, source(activity))
        val second = openSettings(activity)
        dismissOutside(second)
        assertFalse(activity.isFinishing); assertSource(before, source(activity))
        assertNull(shadow(activity).nextStartedActivity)
    }

    private fun openSettings(activity: NovelReaderActivity): Dialog {
        click(activity.window.decorView, app.getString(org.joinmastodon.reader.R.string.novel_reader_settings))
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        frames()
        assertTrue("The production Settings click must show its real Dialog", dialog.isShowing)
        val decor = requireNotNull(dialog.window).decorView
        assertTrue(decor.isAttachedToWindow)
        assertNotNull(text(decor, app.getString(org.joinmastodon.reader.R.string.novel_reader_reading_settings)))
        assertNotNull(text(decor, app.getString(org.joinmastodon.reader.R.string.novel_reader_palette_paper)))
        assertNotNull(text(decor, app.getString(org.joinmastodon.reader.R.string.novel_reader_palette_white)))
        assertNotNull(text(decor, app.getString(org.joinmastodon.reader.R.string.novel_reader_palette_night)))
        return dialog
    }

    private fun dismissOutside(dialog: Dialog) {
        val decor = requireNotNull(dialog.window).decorView
        val heading = requireNotNull(text(decor, app.getString(org.joinmastodon.reader.R.string.novel_reader_reading_settings)))
        assertTrue("Use an actual scrim point above the sheet, not a Settings callback", heading.boundsInWindow.top > 70f)
        tap(decor, decor.width / 2f, 12f)
        frames()
        assertFalse("Outside click must dismiss only the real Settings window", dialog.isShowing)
    }

    private data class Source(val workId: String, val chapterId: String, val page: Int, val start: Int,
        val text: String, val node: SemanticsNode, val layout: TextLayoutResult)

    private fun currentSource(activity: NovelReaderActivity): Source? {
        val state = model(activity).state.value
        val chapter = state.chapter ?: return null
        return nodes(activity.window.decorView, unmerged = true).mapNotNull { node ->
            val value = node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text ?: return@mapNotNull null
            if (value.length < 30 || !body.contains(value) || !visible(node)) return@mapNotNull null
            val layouts = mutableListOf<TextLayoutResult>()
            if (node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) != true) return@mapNotNull null
            val layout = layouts.singleOrNull() ?: return@mapNotNull null
            Source(requireNotNull(state.book).id, chapter.id, state.page, body.indexOf(value), value, node, layout)
        }.maxByOrNull { it.node.boundsInWindow.width }
    }
    private fun source(activity: NovelReaderActivity) = awaitSource(activity) { true }
    private fun awaitSource(activity: NovelReaderActivity, accept: (Source) -> Boolean): Source {
        await { currentSource(activity)?.let(accept) == true }
        return requireNotNull(currentSource(activity)).also {
            assertEquals("public:${work.id}", it.workId); assertEquals(chapterId, it.chapterId)
            assertTrue(it.start >= 0); assertFalse(it.layout.hasVisualOverflow)
        }
    }
    private fun assertSource(before: Source, after: Source) {
        assertEquals(before.workId, after.workId); assertEquals(before.chapterId, after.chapterId)
        assertEquals("Settings visibility/dismiss must not reset persisted position", before.page, after.page)
        assertEquals("Keep the exact original Unicode source range", before.start, after.start)
        assertEquals(before.text, after.text)
        assertEquals("Do not replace the original reader page", before.node.id, after.node.id)
        assertEquals(before.node.boundsInWindow, after.node.boundsInWindow)
    }

    private fun showControls(activity: NovelReaderActivity) {
        val root = activity.window.decorView
        if (text(root, app.getString(org.joinmastodon.reader.R.string.novel_reader_settings)) != null) return
        tap(root, root.width / 2f, root.height * .6f)
        assertNotNull(text(root, app.getString(org.joinmastodon.reader.R.string.novel_reader_settings)))
    }
    private fun tap(view: View, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        fun dispatch(action: Int, elapsed: Long) {
            val event = MotionEvent.obtain(down, down + elapsed, action, x, y, 0)
            try { assertTrue("Production window must receive the touch", view.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        dispatch(MotionEvent.ACTION_DOWN, 0); frames(2)
        dispatch(MotionEvent.ACTION_UP, 64); frames()
    }
    private fun click(root: View, label: String) {
        val node = requireNotNull(text(root, label)) { "Missing production click target: $label" }
        assertTrue(visible(node))
        val action = generateSequence(node) { it.parent }.mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }.firstOrNull()
        assertTrue("Actual production OnClick must accept $label", requireNotNull(action).invoke())
        frames()
    }
    private fun text(root: View, label: String) = nodes(root).singleOrNull { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
    }
    private fun roots(view: View): List<RootForTest> = buildList {
        if (view is RootForTest) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(roots(view.getChildAt(i)))
    }
    private fun nodes(view: View, unmerged: Boolean = false): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return roots(view).flatMap { flatten(if (unmerged) it.semanticsOwner.unmergedRootSemanticsNode else it.semanticsOwner.rootSemanticsNode) }
    }
    private fun visible(node: SemanticsNode) = node.layoutInfo.isAttached && node.layoutInfo.isPlaced && node.boundsInWindow.let {
        it.width > 1 && it.height > 1 && it.left < width && it.right > 0 && it.top < height && it.bottom > 0
    }
    private val width get() = app.resources.configuration.screenWidthDp
    private val height get() = app.resources.configuration.screenHeightDp
    private fun windows(): List<View> = (activities.filterNot { it.get().isDestroyed }.map { it.get().window.decorView } +
        ShadowDialog.getShownDialogs().filter { it.isShowing }.mapNotNull { it.window?.decorView }).distinct()
    private fun frames(count: Int = 40) {
        repeat(count) {
            Snapshot.sendApplyNotifications(); scheduler.runCurrent()
            frameNanos += 16_000_000L; clock.sendFrame(frameNanos); scheduler.runCurrent()
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
            windows().filter { it.isAttachedToWindow }.forEach { view ->
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, height)
                roots(view).forEach { it.measureAndLayoutForTest() }
                view.viewTreeObserver.dispatchOnPreDraw()
            }
        }
        Snapshot.sendApplyNotifications(); scheduler.runCurrent()
    }
    private fun await(ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            frames(2)
            if (ready()) return
            Thread.sleep(5) // Only await real local SQLite/HTTP-interceptor/pagination jobs, never unbounded idle.
        }
        fail("Timed out waiting for actual Activity/Compose state; unexpected=$unexpected")
    }
    private fun dispose(view: View) {
        if (view is AbstractComposeView) view.disposeComposition()
        if (view is ViewGroup) for (i in 0 until view.childCount) dispose(view.getChildAt(i))
    }
}
