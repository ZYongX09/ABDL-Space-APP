package org.joinmastodon.android.ui.seamless

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import me.grishka.appkit.FragmentStackActivity
import me.grishka.appkit.utils.BindableViewHolder
import me.grishka.appkit.utils.V
import me.grishka.appkit.views.UsableRecyclerView
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.GlobalUserPreferences
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.R
import org.joinmastodon.android.api.MastodonAPIRequest
import org.joinmastodon.android.api.session.AccountSession
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.fragments.StatusListFragment
import org.joinmastodon.android.fragments.ThreadFragment
import org.joinmastodon.android.model.Account
import org.joinmastodon.android.model.Card
import org.joinmastodon.android.model.FilterContext
import org.joinmastodon.android.model.Instance
import org.joinmastodon.android.model.InstanceV1
import org.joinmastodon.android.model.Quote
import org.joinmastodon.android.model.Status
import org.joinmastodon.android.model.StatusPrivacy
import org.joinmastodon.android.security.AppGatekeeper
import org.joinmastodon.android.security.AppSecurity
import org.joinmastodon.android.security.AuthTracker
import org.joinmastodon.android.security.ui.FakeSecurityRepository
import org.joinmastodon.android.ui.compose.installComposeLifecycle
import org.joinmastodon.android.ui.displayitems.StatusDisplayItem
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.parceler.Parcels
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowLooper
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Common real security/account/window fixture. Only ROM and outbound requests are substituted. */
abstract class SeamlessAttachedFixture {
    @Implements(MastodonAPIRequest::class)
    class OfflineRequests {
        @Implementation fun exec(account: String?): MastodonAPIRequest<*>? = null
        @Implementation fun cancel() = Unit
    }
    /** Mirrors MainActivity's Compose-capable host; fallback fragments attach real Compose views. */
    class Host : FragmentStackActivity(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        private val models = ViewModelStore()
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = models
        override fun onCreate(state: Bundle?) {
            savedState.performAttach(); savedState.performRestore(state)
            super.onCreate(state)
            registry.currentState = Lifecycle.State.CREATED
            installComposeLifecycle()
        }
        override fun onResume() { super.onResume(); registry.currentState = Lifecycle.State.RESUMED }
        override fun onPause() { super.onPause(); registry.currentState = Lifecycle.State.STARTED }
        override fun onDestroy() { super.onDestroy(); registry.currentState = Lifecycle.State.DESTROYED }
    }
    class Timeline : StatusListFragment() {
        override fun getFilterContext() = FilterContext.HOME
        override fun getWebUri(base: android.net.Uri.Builder) = base.path("/").build()
        override fun doLoadData(offset: Int, count: Int) = Unit
        override fun loadRelationships(ids: Set<String>) = Unit
        fun recycler(): RecyclerView = list
        fun install(vararg posts: Status) {
            data.clear(); displayItems.clear(); data.addAll(posts)
            posts.forEach { displayItems.addAll(buildDisplayItems(it)) }
            loaded = true; adapter.notifyDataSetChanged()
            content?.visibility = View.VISIBLE; list.visibility = View.VISIBLE
        }
    }
    protected lateinit var controller: ActivityController<Host>
    protected lateinit var activity: Host
    protected lateinit var session: AccountSession
    protected lateinit var rom: RecordingRom
    protected lateinit var gate: AppGatekeeper
    protected val account get() = session.id
    protected val children = mutableListOf<ActivityController<*>>()
    private lateinit var accounts: MutableMap<String, AccountSession>
    private lateinit var instances: MutableMap<String, Instance>
    private var oldAccount: AccountSession? = null
    private var oldInstance: Instance? = null
    private var oldActive: String? = null
    private var oldTracker: Any? = null
    private var oldGate: Any? = null
    private var oldPreference = false
    private var oldMotion = false
    private var oldFontScale = 1f

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Before fun setupAttachedSource() {
        MastodonApp.context = RuntimeEnvironment.getApplication()
        V.setApplicationContext(MastodonApp.context)
        // appkit's V only stores the first context it ever sees; a per-test qualifier (e.g. xxxhdpi)
        // must be reflected in V.dp instead of a stale mdpi context from an earlier test method.
        field(V::class.java, "appContext").set(null, MastodonApp.context)
        org.joinmastodon.android.ui.utils.UiUtils.updateLocalizedDateFormatters(MastodonApp.context)
        GlobalUserPreferences.load()
        oldPreference = GlobalUserPreferences.useSeamlessTransitions; oldMotion = GlobalUserPreferences.reduceMotion
        GlobalUserPreferences.useSeamlessTransitions = true; GlobalUserPreferences.reduceMotion = false
        session = AccountSession::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance().apply {
            domain = "rom-click.offline.example.test"
            token = org.joinmastodon.android.model.Token().apply { accessToken = "offline-only-rom-fixture" }
            self = Account().apply { id = "42"; username = "offline"; acct = "offline"; displayName = "Offline"; emojis = listOf() }
        }
        val manager = AccountSessionManager.getInstance()
        @Suppress("UNCHECKED_CAST")
        accounts = field(AccountSessionManager::class.java, "sessions").get(manager) as MutableMap<String, AccountSession>
        @Suppress("UNCHECKED_CAST")
        instances = field(AccountSessionManager::class.java, "instances").get(manager) as MutableMap<String, Instance>
        oldAccount = accounts.put(account, session)
        oldInstance = instances.put(session.domain, InstanceV1().apply { uri = session.domain; normalizedUri = session.domain; version = "4.3.0" })
        oldActive = manager.lastActiveAccountID; field(AccountSessionManager::class.java, "lastActiveAccountID").set(manager, account)
        val tracker = AuthTracker(FakeSecurityRepository()); runBlocking { tracker.shouldAuthenticate() }
        oldTracker = field(AppSecurity::class.java, "trackerInstance").get(null)
        oldGate = field(AppSecurity::class.java, "gatekeeper").get(null)
        field(AppSecurity::class.java, "trackerInstance").set(null, tracker)
        val dispatcher = UnconfinedTestDispatcher()
        gate = AppGatekeeper(RuntimeEnvironment.getApplication(), tracker, dispatcher, dispatcher, {})
        field(AppSecurity::class.java, "gatekeeper").set(null, gate)
        assertFalse(AppSecurity.isLocked())
        SeamlessTransitions.clear(); rom = RecordingRom(); SharedViewRom.setTransportForTests(rom)
        // Native attached windows must actually traverse; do not replace an async production Handler.
        ShadowChoreographer.setPaused(false)
        controller = Robolectric.buildActivity(Host::class.java)
        activity = controller.get(); activity.setTheme(R.style.Theme_Mastodon_Light); controller.setup().visible()
        oldFontScale = activity.resources.configuration.fontScale
        settle()
    }
    @After fun cleanupAttachedSource() {
        SeamlessTransitions.clear()
        children.reversed().forEach { it.close() }; controller.close()
        SharedViewRom.setTransportForTests(null)
        field(AppSecurity::class.java, "trackerInstance").set(null, oldTracker)
        field(AppSecurity::class.java, "gatekeeper").set(null, oldGate)
        if (oldAccount == null) accounts.remove(account) else accounts[account] = oldAccount!!
        if (oldInstance == null) instances.remove(session.domain) else instances[session.domain] = oldInstance!!
        field(AccountSessionManager::class.java, "lastActiveAccountID").set(AccountSessionManager.getInstance(), oldActive)
        GlobalUserPreferences.useSeamlessTransitions = oldPreference; GlobalUserPreferences.reduceMotion = oldMotion
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(android.content.res.Configuration(activity.resources.configuration).apply { fontScale = oldFontScale }, activity.resources.displayMetrics)
        ShadowChoreographer.setPaused(true)
    }
    protected fun settle() { ShadowLooper.idleMainLooper(64, TimeUnit.MILLISECONDS) }
    protected fun layout(view: View, width: Int = dp(393), height: Int = dp(700)) {
        val decor = activity.window.decorView
        decor.measure(exact(dp(393)), exact(dp(852))); decor.layout(0, 0, dp(393), dp(852))
        settle()
        view.measure(exact(width), exact(height)); view.layout(0, 0, width, height)
        settle()
        assertTrue("A fixture must be genuinely attached, not a detached draw surrogate", view.isAttachedToWindow)
    }
    protected fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    /** appkit V.dp is Float; fixture geometry below uses integer pixels. */
    protected fun dp(value: Int) = V.dp(value.toFloat()).toInt()
    protected fun timeline(vararg posts: Status): Timeline = Timeline().apply {
        arguments = Bundle().apply { putString("account", account) }
        this@SeamlessAttachedFixture.activity.showFragment(this)
        this@SeamlessAttachedFixture.activity.fragmentManager.executePendingTransactions()
        install(*posts); layout(recycler())
        assertTrue(recycler() is UsableRecyclerView)
        assertFalse(recycler().hasPendingAdapterUpdates()); assertFalse(recycler().isComputingLayout)
    }
    protected fun holder(fragment: Timeline, id: String, type: StatusDisplayItem.Type = StatusDisplayItem.Type.TEXT, quote: Boolean = false): RecyclerView.ViewHolder {
        val list = fragment.recycler()
        return (0 until list.childCount).map { list.getChildViewHolder(list.getChildAt(it)) }.first {
            val item = (it as? BindableViewHolder<*>)?.getItem() as? StatusDisplayItem
            item != null && item.parentID == id && item.type == type && item.isQuote == quote
        }
    }
    protected fun item(holder: RecyclerView.ViewHolder) = itemOrNull(holder)!!
    /** UsableRecyclerView also hosts Footer/EmptyView holders; only real display items are rows. */
    protected fun itemOrNull(holder: RecyclerView.ViewHolder): StatusDisplayItem? =
        ((holder as? BindableViewHolder<*>)?.getItem()) as? StatusDisplayItem
    protected fun click(holder: RecyclerView.ViewHolder, quote: Boolean = false) {
        val clickable = holder as UsableRecyclerView.Clickable
        if (quote) clickable.onClick((holder.itemView.width - dp(30)).toFloat(), (holder.itemView.height / 2).toFloat())
        else clickable.onClick()
    }
    protected fun post(id: String = "post", lines: Int = 1) = Status.ofFake(id, "<p>" + (1..lines).joinToString("<br>") { "Visible body line $it" } + "</p>", Instant.parse("2026-11-01T00:00:00Z")).apply {
        visibility = StatusPrivacy.PUBLIC
        account = Account().apply {
            this.id = "99"; username = "author"; acct = "author"; displayName = "Author"; emojis = listOf()
            // The real reblog/reply line builds AvatarSpan -> Emoji(shortcode=avatarStatic), so a
            // remotely-shaped account always carries non-null avatar URLs.
            avatar = "https://offline.example.test/avatar/99.png"
            avatarStatic = "https://offline.example.test/avatar/99-static.png"
        }
    }
    protected fun quoted(outer: Status, quote: Status): Status = outer.apply {
        this.quote = Quote().apply { state = Quote.State.ACCEPTED; quotedStatus = quote; quotedStatusId = quote.id }
    }
    protected fun postArgs(status: Status) = Bundle().apply { putString("account", account); putParcelable("status", Parcels.wrap(status.clone())) }
    protected fun plainCard(entity: String = "book"): View {
        val card = View(activity).apply { setBackgroundColor(Color.BLUE) }
        val root = FrameLayout(activity).apply { setBackgroundColor(Color.WHITE); addView(card, FrameLayout.LayoutParams(140, 90)) }
        activity.setContentView(root); layout(root); SeamlessTransitions.bindCard(card, account, entity, true); return card
    }
    protected fun target(entity: String): SeamlessDetailActivity {
        val c = Robolectric.buildActivity(SeamlessDetailActivity::class.java); children.add(c)
        return c.get().also { SeamlessTransitions.attach(it, account, entity) }
    }
    protected fun field(owner: Class<*>, name: String) = owner.getDeclaredField(name).apply { isAccessible = true }
    protected fun pending(): Any? = field(SeamlessTransitions::class.java, "pending").get(null)
    protected fun watchdogScheduled(): Boolean {
        val handler = field(SeamlessTransitions::class.java, "main").get(null) as Handler
        val task = field(SeamlessTransitions::class.java, "pendingTimeout").get(null) as Runnable
        // Handler.hasCallbacks is API29; this suite intentionally exercises API28 as well.
        var message = field(android.os.MessageQueue::class.java, "mMessages").get(handler.looper.queue) as? android.os.Message
        var remaining = 4096
        while (message != null && remaining-- > 0) {
            if (message.callback === task) return true
            message = field(android.os.Message::class.java, "next").get(message) as? android.os.Message
        }
        return false
    }
    protected fun noPrivateMarker(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertFalse("Neighbour magenta pixels must never enter an authorised row bitmap", pixels.any { Color.red(it) > 200 && Color.blue(it) > 200 && Color.green(it) < 180 })
    }
    class RecordingRom : SharedViewRom.Transport {
        var supported = true; var enabled = true; var nullOptions = false; var throwOptions = false
        var calls = 0; var overrides = 0; var cancels = 0
        var bitmap: Bitmap? = null; var view: View? = null; var bounds: Rect? = null
        var config: Bundle? = null; var lastOverride: Bundle? = null
        var duringMake: (() -> Unit)? = null
        val metadata = mapOf(SharedViewRom.BOUNDS to "rect|WINDOW|bounds", SharedViewRom.BACK to "int|VIEW_ANIM_FADE_OUT,VIEW_ANIM_FOLLOW_SYSTEM|back")
        override fun available() = supported
        override fun enabled() = enabled
        override fun extras() = metadata
        override fun fromView(view: View, config: Bundle): ActivityOptions? { this.view = view; this.config = Bundle(config); return options() }
        override fun fromBitmap(bitmap: Bitmap, bounds: Rect, radius: Float, config: Bundle): ActivityOptions? {
            this.bitmap = bitmap; this.bounds = Rect(bounds); this.config = Bundle(config); return options()
        }
        private fun options(): ActivityOptions? {
            calls++; duringMake?.invoke()
            if (throwOptions) throw IllegalStateException("Offline ROM refused options")
            return if (nullOptions) null else ActivityOptions.makeBasic()
        }
        override fun override(activity: Activity, config: Bundle) { assertFalse(activity.isFinishing); overrides++; lastOverride = Bundle(config) }
        override fun cancel(): Boolean { cancels++; return true }
    }
}

/** Real production UsableRecyclerView, models and click callbacks; not a hand-built three-row adapter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class, qualifiers = "en-w393dp-h852dp-mdpi", shadows = [SeamlessAttachedFixture.OfflineRequests::class])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeamlessPostClickTest : SeamlessAttachedFixture() {
    @Test fun realUsableRecyclerTouchOpensOrdinaryPostExactlyOnce() {
        val status = post(); val timeline = timeline(status); val list = timeline.recycler()
        val body = holder(timeline, status.id)
        // Tap the ordinary row's unused margin, not a link/translation/media child action.
        val x = dp(20).toFloat(); val y = body.itemView.y + body.itemView.height / 2
        val time = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_UP, x, y, 0)
        try { assertTrue(list.dispatchTouchEvent(down)); assertTrue(list.dispatchTouchEvent(up)) }
        finally { down.recycle(); up.recycle() }
        settle()
        val launched = shadowOf(activity).nextStartedActivity
        assertNotNull("The production touch -> Holder -> StatusListFragment chain must use ROM options", launched)
        assertEquals(SeamlessDetailActivity::class.java.name, launched.component!!.className)
        assertEquals("post", launched.getStringExtra("detail_route")); assertEquals(1, rom.calls)
        assertNotNull(rom.bitmap); assertNull(rom.view); assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(SeamlessTransitions.retainedPixels() in 1..SeamlessSource.MAX_PIXELS)
    }
    @Test @Config(qualifiers = "en-w393dp-h852dp-xxxhdpi")
    fun realHighDensityOrdinaryPostDownsamplesInsteadOfRejectingArea() {
        assertEquals("This case must run on a real xxxhdpi window, not an mdpi surrogate", 4f, activity.resources.displayMetrics.density, 0.001f)
        val status = post(lines = 8); val timeline = timeline(status)
        val source = SeamlessSource.post(activity, timeline.recycler(), account, status, status.id, status, timeline.getDisplayItems())!!
        val area = source.screenBounds()!!
        assertTrue("Exercise the old >1M physical source-area failure, not an mdpi surrogate", area.width().toLong() * area.height() > SeamlessSource.MAX_PIXELS)
        click(holder(timeline, status.id))
        assertNotNull(shadowOf(activity).nextStartedActivity)
        val bitmap = rom.bitmap!!
        assertTrue(bitmap.width.toLong() * bitmap.height <= SeamlessSource.MAX_PIXELS)
        assertTrue(bitmap.byteCount <= 4 * SeamlessSource.MAX_PIXELS)
        assertTrue(bitmap.width < area.width()); assertEquals(1, rom.calls)
        assertEquals(area.width(), rom.bounds!!.width()); assertEquals(area.height(), rom.bounds!!.height())
        assertFalse(rom.config!!.containsKey("sharedView.bitmap"))
    }
    @Test fun longPartiallyExposedPostNeedsNeitherVisibleHeaderNorFooter() {
        val status = post(lines = 90); val timeline = timeline(status); val list = timeline.recycler()
        list.scrollBy(0, dp(300)); settle()
        val body = holder(timeline, status.id)
        assertNull("Header has scrolled away", (0 until list.childCount).map { list.getChildViewHolder(list.getChildAt(it)) }.firstOrNull { itemOrNull(it)?.type == StatusDisplayItem.Type.HEADER })
        assertTrue(body.itemView.height > list.height)
        val source = SeamlessSource.post(activity, list, account, status, status.id, status, timeline.getDisplayItems())!!
        assertTrue(source.screenBounds()!!.height() <= list.height)
        click(body); assertNotNull(shadowOf(activity).nextStartedActivity); assertEquals(1, rom.calls)
        assertTrue(rom.bitmap!!.height < body.itemView.height)
    }
    @Test fun quoteClickCapturesOnlyQuoteAndOuterClickRetainsSafeQuoteRows() {
        val inner = post("inner"); val outer = quoted(post("outer"), inner)
        val timeline = timeline(outer)
        val quoteBody = holder(timeline, outer.id, quote = true)
        val source = SeamlessSource.post(activity, timeline.recycler(), account, inner, outer.id, outer, timeline.getDisplayItems())!!
        assertTrue(source.screenBounds()!!.width() < timeline.recycler().width)
        click(quoteBody, quote = true)
        val args = shadowOf(activity).nextStartedActivity.getBundleExtra("detail_arguments")!!
        assertEquals(inner.id, Parcels.unwrap<Status>(args.getParcelable("status")).id)
        assertEquals(1, rom.calls)
        SeamlessTransitions.clear(); click(holder(timeline, outer.id))
        assertNotNull(shadowOf(activity).nextStartedActivity); assertEquals(2, rom.calls)
    }
    @Test fun duplicateReblogContentUsesTheClickedOccurrenceNotAnyMatchingId() {
        val original = post("original")
        val boost = post("boost").apply { reblog = original }
        val timeline = timeline(original, boost)
        val expected = SeamlessSource.post(activity, timeline.recycler(), account, original, boost.id, boost, timeline.getDisplayItems())!!.bounds(true)
        click(holder(timeline, boost.id))
        assertEquals(expected, rom.bounds); assertEquals(1, rom.calls)
        val args = shadowOf(activity).nextStartedActivity.getBundleExtra("detail_arguments")!!
        assertEquals(original.id, Parcels.unwrap<Status>(args.getParcelable("status")).id)
        assertNotNull("No residual click parent/status may be retained by the Fragment", field(org.joinmastodon.android.fragments.BaseStatusListFragment::class.java, "seamlessClickParent"))
        assertNull(field(org.joinmastodon.android.fragments.BaseStatusListFragment::class.java, "seamlessClickParent").get(timeline))
        assertNull(field(org.joinmastodon.android.fragments.BaseStatusListFragment::class.java, "seamlessClickVisibility").get(timeline))
    }
    @Test fun privateReblogOrQuotedAncestorStillUsesTheOriginalThreadFallback() {
        val original = post("original"); val boost = post("boost").apply { reblog = original; visibility = StatusPrivacy.PRIVATE }
        val timeline = timeline(boost)
        click(holder(timeline, boost.id))
        assertEquals(0, rom.calls); assertNull(shadowOf(activity).nextStartedActivity)
        activity.fragmentManager.executePendingTransactions()
        assertTrue(activity.fragmentManager.fragments.any { it is ThreadFragment })
        val inner = post("inner"); val outer = quoted(post("outer"), inner).apply { visibility = StatusPrivacy.DIRECT }
        assertNull(SeamlessSource.post(activity, timeline.recycler(), account, inner, outer.id, outer, timeline.getDisplayItems()))
    }
    @Test fun privateNeighbourDecorationAndOverflowNeverEnterTargetBitmap() {
        val target = post("target"); val neighbour = post("private-neighbour").apply { visibility = StatusPrivacy.PRIVATE }
        val timeline = timeline(target, neighbour); val list = timeline.recycler()
        // Private sibling overdraw and a selector-like decoration are intentionally inside the target bounds.
        (0 until list.childCount).map { list.getChildAt(it) }.filter { itemOrNull(list.getChildViewHolder(it))?.parentID == neighbour.id }.forEach {
            it.setBackgroundColor(Color.MAGENTA); it.translationY = -dp(100).toFloat()
        }
        list.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) { c.drawColor(Color.MAGENTA) }
        })
        val source = SeamlessSource.post(activity, list, account, target, target.id, target, timeline.getDisplayItems())!!
        val clean = source.snapshot()!!
        try { noPrivateMarker(clean) } finally { clean.recycle() }
        click(holder(timeline, target.id)); noPrivateMarker(rom.bitmap!!)
        assertNotNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun alphaTransformPendingUpdatesAndRecycledBindingStayFailClosed() {
        val status = post(); val timeline = timeline(status); val list = timeline.recycler()
        val source = SeamlessSource.post(activity, list, account, status, status.id, status, timeline.getDisplayItems())!!
        list.alpha = .9f; assertFalse(source.valid()); assertNull(source.snapshot()); list.alpha = 1f
        val body = holder(timeline, status.id).itemView
        body.scaleX = .9f; assertFalse(source.valid()); body.scaleX = 1f
        list.adapter!!.notifyItemChanged(0); assertTrue(list.hasPendingAdapterUpdates()); assertFalse(source.valid()); assertNull(source.snapshot())
        layout(list); settle()
        timeline.install(post("replacement")); layout(list)
        assertFalse(source.valid()); assertNull(source.snapshot())
    }
    @Test fun linkCardUsesActualBindableHolderAndTypedPrivateStatusField() {
        val status = post().apply { card = Card().apply { type = Card.Type.LINK; title = "Public link"; url = "https://offline.example.test/page" } }
        val timeline = timeline(status)
        val card = holder(timeline, status.id, StatusDisplayItem.Type.CARD_COMPACT)
        // LinkCardHolder is the real BindableViewHolder; it intentionally extends StatusDisplayItem.Holder.
        assertTrue(card is org.joinmastodon.android.ui.viewholders.LinkCardHolder<*>)
        assertSame(status, SeamlessSource.statusOf(item(card)))
        click(holder(timeline, status.id)); assertNotNull(shadowOf(activity).nextStartedActivity); assertEquals(1, rom.calls)
    }
    @Test fun optionsFailureDropsPendingAndWatchdogAndCanRetryOnce() {
        val status = post(); val timeline = timeline(status); rom.nullOptions = true
        assertFalse(SeamlessTransitions.openPost(activity, timeline.recycler(), account, status, postArgs(status), status.id, status, timeline.getDisplayItems()))
        assertNotNull(rom.bitmap); assertNull(pending()); assertFalse(watchdogScheduled()); assertEquals(0, SeamlessTransitions.retainedPixels())
        rom.nullOptions = false; rom.throwOptions = true
        assertFalse(SeamlessTransitions.openPost(activity, timeline.recycler(), account, status, postArgs(status), status.id, status, timeline.getDisplayItems()))
        assertNull(pending()); assertFalse(watchdogScheduled()); assertEquals(0, SeamlessTransitions.retainedPixels())
        rom.throwOptions = false
        assertTrue(SeamlessTransitions.openPost(activity, timeline.recycler(), account, status, postArgs(status), status.id, status, timeline.getDisplayItems()))
        assertEquals(3, rom.calls); assertNotNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun oneClickDrawsEachAuthorisedRowOnceAndReturnNeverDrawsAgain() {
        val status = post(); val timeline = timeline(status); val list = timeline.recycler()
        val counters = mutableListOf<IntArray>()
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            if (itemOrNull(list.getChildViewHolder(row))?.parentID != status.id || SeamlessSource.visible(row) == null) continue
            val count = intArrayOf(0); counters.add(count)
            row.background = object : android.graphics.drawable.Drawable() {
                override fun draw(canvas: Canvas) { count[0]++ }
                override fun setAlpha(alpha: Int) = Unit
                override fun setColorFilter(filter: android.graphics.ColorFilter?) = Unit
                @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSPARENT
            }
        }
        counters.forEach { it[0] = 0 }
        click(holder(timeline, status.id))
        assertNotNull(shadowOf(activity).nextStartedActivity); assertEquals(1, rom.calls)
        assertTrue(counters.isNotEmpty()); counters.forEach { assertEquals("Each row is captured exactly once", 1, it[0]) }
        val target = target(status.id)
        SeamlessTransitions.beforeFinish(target)
        counters.forEach { assertEquals("A return only refreshes geometry", 1, it[0]) }
    }
    @Test fun retainedBitmapBudgetIsGlobalAndFailingBudgetDoesNotAllocateOrCallRom() {
        val status = post(); val timeline = timeline(status); val list = timeline.recycler()
        val source = SeamlessSource.post(activity, list, account, status, status.id, status, timeline.getDisplayItems())!!
        assertTrue(SeamlessTransitions.openPost(activity, list, account, status, postArgs(status), status.id, status, timeline.getDisplayItems()))
        assertNotNull(shadowOf(activity).nextStartedActivity)
        val first = target(status.id)
        @Suppress("UNCHECKED_CAST")
        val sessions = field(SeamlessTransitions::class.java, "sessions").get(null) as MutableMap<Activity, Any>
        val held = sessions[first]!!
        // A correctly budgeted maximum-size retained snapshot simulates an existing deep-detail session.
        val maximum = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        field(held.javaClass, "bitmap").set(held, maximum)
        assertTrue(SeamlessTransitions.openPost(activity, list, account, status, postArgs(status), status.id, status, timeline.getDisplayItems()))
        assertNotNull(shadowOf(activity).nextStartedActivity)
        val second = target(status.id); val heldSecond = sessions[second]!!
        field(heldSecond.javaClass, "bitmap").set(heldSecond, maximum)
        assertEquals(SeamlessTransitions.MAX_RETAINED_PIXELS, SeamlessTransitions.retainedPixels())
        assertFalse(SeamlessTransitions.openPost(activity, list, account, status, postArgs(status), status.id, status, timeline.getDisplayItems()))
        assertEquals(2, rom.calls); assertNull(pending()); assertFalse(watchdogScheduled())
        assertNull(source.snapshot(source.screenBounds(), 0))
        SeamlessTransitions.clear(); assertEquals(0, SeamlessTransitions.retainedPixels())
        maximum.recycle()
    }
    @Test fun attachRemovesTimeoutAndReturnUsesFreshBoundsWithoutSecondCapture() {
        val status = post(); val timeline = timeline(status)
        click(holder(timeline, status.id)); assertNotNull(shadowOf(activity).nextStartedActivity)
        assertNotNull(pending()); assertTrue(watchdogScheduled())
        val target = target(status.id); assertNull(pending()); assertFalse(watchdogScheduled())
        timeline.recycler().translationY = 7f
        SeamlessTransitions.beforeFinish(target)
        assertEquals(1, rom.calls); assertEquals(1, rom.overrides)
        assertNotNull(rom.lastOverride!!.getParcelable<Rect>(SharedViewRom.BOUNDS)); assertEquals(0, SeamlessTransitions.retainedPixels())
        assertFalse(target.isFinishing)
        SeamlessTransitions.beforeFinish(target); assertEquals(1, rom.overrides)
    }
    @Test fun changedAccountDuringOptionsNeverLaunchesOrRetainsPendingBitmap() {
        val status = post(); val timeline = timeline(status)
        rom.duringMake = { field(AccountSessionManager::class.java, "lastActiveAccountID").set(AccountSessionManager.getInstance(), "another-account") }
        assertFalse(SeamlessTransitions.openPost(activity, timeline.recycler(), account, status, postArgs(status), status.id, status, timeline.getDisplayItems()))
        assertEquals(1, rom.calls); assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(pending()); assertFalse(watchdogScheduled()); assertEquals(0, SeamlessTransitions.retainedPixels())
    }
    @Test fun snapshotScaleHasHardBudgetEvenForExtremeAspectRatios() {
        for ((width, height) in listOf(1572 to 3408, 1440 to 2000, 1 to Int.MAX_VALUE, Int.MAX_VALUE to 1)) {
            val size = SeamlessSource.snapshotSize(width, height, SeamlessSource.MAX_PIXELS)!!
            assertTrue(size[0] > 0 && size[1] > 0)
            assertTrue(size[0].toLong() * size[1] <= SeamlessSource.MAX_PIXELS)
            assertTrue(size[0] <= width && size[1] <= height)
        }
        assertNull(SeamlessSource.snapshotSize(0, 100, 100)); assertNull(SeamlessSource.snapshotSize(100, 100, 0))
    }
    @Test fun largeFontStillCapturesVisibleRowsAndRecycledReturnUsesFadeNotNewHolder() {
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 1.8f }, activity.resources.displayMetrics)
        val status = post(lines = 8); val timeline = timeline(status)
        click(holder(timeline, status.id)); assertNotNull(shadowOf(activity).nextStartedActivity)
        val target = target(status.id)
        timeline.install(post("other")); layout(timeline.recycler())
        SeamlessTransitions.beforeFinish(target)
        assertFalse(rom.lastOverride!!.containsKey(SharedViewRom.BOUNDS)); assertEquals(SharedViewRom.FADE, rom.lastOverride!!.getInt(SharedViewRom.BACK))
        assertEquals(1, rom.calls)
    }
}
