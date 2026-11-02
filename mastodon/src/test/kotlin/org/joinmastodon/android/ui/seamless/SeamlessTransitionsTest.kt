package org.joinmastodon.android.ui.seamless

import android.app.Activity
import android.app.ActivityOptions
import android.app.Fragment
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.GlobalUserPreferences
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.R
import org.joinmastodon.android.api.MastodonAPIRequest
import org.joinmastodon.android.api.session.AccountSession
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.fragments.StatusListFragment
import org.joinmastodon.android.fragments.ThreadFragment
import org.joinmastodon.android.fragments.albums.AlbumDetailFragment
import org.joinmastodon.android.fragments.settings.SettingsDisplayFragment
import org.joinmastodon.android.model.Account
import org.joinmastodon.android.model.FilterContext
import org.joinmastodon.android.model.Instance
import org.joinmastodon.android.model.InstanceV1
import org.joinmastodon.android.model.NovelAttachment
import org.joinmastodon.android.model.Quote
import org.joinmastodon.android.model.Status
import org.joinmastodon.android.model.StatusPrivacy
import org.joinmastodon.android.model.albums.AlbumModels.AlbumUpdate
import org.joinmastodon.android.model.viewmodel.CheckableListItem
import org.joinmastodon.android.security.AppGatekeeper
import org.joinmastodon.android.security.AppSecurity
import org.joinmastodon.android.security.AuthTracker
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.ui.FakeSecurityRepository
import org.joinmastodon.android.security.ui.securityState
import org.joinmastodon.android.ui.displayitems.AlbumUpdateStatusDisplayItem
import org.joinmastodon.android.ui.displayitems.NovelStatusDisplayItem
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
import org.robolectric.shadows.ShadowLooper
import me.grishka.appkit.FragmentStackActivity
import me.grishka.appkit.utils.V
import java.time.Instant

/** Real card clicks/AppKit navigation with a fake ROM transport; production lock gates remain intact. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class, qualifiers = "en-w393dp-h852dp-mdpi", shadows = [SeamlessTransitionsTest.OfflineRequests::class])
@LooperMode(LooperMode.Mode.PAUSED)
class SeamlessTransitionsTest {
    @Implements(MastodonAPIRequest::class)
    class OfflineRequests {
        @Implementation fun exec(account: String?): MastodonAPIRequest<*>? = null
        @Implementation fun cancel() = Unit
    }
    class Host : FragmentStackActivity()
    class Timeline : StatusListFragment() {
        override fun getFilterContext() = FilterContext.HOME
        override fun getWebUri(base: android.net.Uri.Builder) = base.path("/").build()
        override fun doLoadData(offset: Int, count: Int) = Unit
        override fun loadRelationships(ids: Set<String>) = Unit
        fun recycler(): RecyclerView = list
        fun install(status: Status) {
            data.clear(); displayItems.clear(); data.add(status); displayItems.addAll(buildDisplayItems(status)); loaded = true
            adapter.notifyDataSetChanged(); content?.visibility = View.VISIBLE; list.visibility = View.VISIBLE
        }
    }
    private lateinit var controller: ActivityController<Host>
    private lateinit var activity: Host
    private lateinit var session: AccountSession
    private lateinit var tracker: AuthTracker
    private lateinit var rom: FakeRom
    private lateinit var accounts: MutableMap<String, AccountSession>
    private lateinit var instances: MutableMap<String, Instance>
    private var oldAccount: AccountSession? = null
    private var oldInstance: Instance? = null
    private var oldActive: String? = null
    private var oldTracker: Any? = null
    private var oldGate: Any? = null
    private val account get() = session.id
    private val children = mutableListOf<ActivityController<*>>()

    @Before fun setup() {
        MastodonApp.context = RuntimeEnvironment.getApplication()
        V.setApplicationContext(MastodonApp.context)
        org.joinmastodon.android.ui.utils.UiUtils.updateLocalizedDateFormatters(MastodonApp.context)
        GlobalUserPreferences.getPrefs().edit().clear().putBoolean("perAccountMigrationDone", true).commit()
        GlobalUserPreferences.load(); GlobalUserPreferences.reduceMotion = false; GlobalUserPreferences.useSeamlessTransitions = true
        session = AccountSession::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance().apply {
            domain = "seamless.offline.example.test"
            token = org.joinmastodon.android.model.Token().apply { accessToken = "offline-secret-token-one" }
            self = Account().apply { id = "42"; username = "offline"; acct = "offline"; displayName = "Offline"; emojis = listOf() }
        }
        val manager = AccountSessionManager.getInstance()
        @Suppress("UNCHECKED_CAST")
        accounts = field(AccountSessionManager::class.java, "sessions").get(manager) as MutableMap<String, AccountSession>
        @Suppress("UNCHECKED_CAST")
        instances = field(AccountSessionManager::class.java, "instances").get(manager) as MutableMap<String, Instance>
        oldAccount = accounts.put(account, session)
        oldInstance = instances.put(session.domain, InstanceV1().apply { uri = session.domain; normalizedUri = session.domain; version = "4.3.0" })
        oldActive = manager.lastActiveAccountID
        field(AccountSessionManager::class.java, "lastActiveAccountID").set(manager, account)
        tracker = AuthTracker(FakeSecurityRepository())
        runBlocking { tracker.shouldAuthenticate() }
        oldTracker = field(AppSecurity::class.java, "trackerInstance").get(null)
        oldGate = field(AppSecurity::class.java, "gatekeeper").get(null)
        field(AppSecurity::class.java, "trackerInstance").set(null, tracker)
        field(AppSecurity::class.java, "gatekeeper").set(null, AppGatekeeper(RuntimeEnvironment.getApplication(), tracker))
        assertFalse(AppSecurity.isLocked())
        rom = FakeRom(); SharedViewRom.setTransportForTests(rom)
        controller = Robolectric.buildActivity(Host::class.java)
        activity = controller.get(); activity.setTheme(R.style.Theme_Mastodon_Light); controller.setup()
    }
    @After fun cleanup() {
        SeamlessTransitions.clear(); SharedViewRom.setTransportForTests(null)
        children.reversed().forEach { it.close() }; controller.close()
        field(AppSecurity::class.java, "trackerInstance").set(null, oldTracker)
        field(AppSecurity::class.java, "gatekeeper").set(null, oldGate)
        if (oldAccount == null) accounts.remove(account) else accounts[account] = oldAccount!!
        if (oldInstance == null) instances.remove(session.domain) else instances[session.domain] = oldInstance!!
        field(AccountSessionManager::class.java, "lastActiveAccountID").set(AccountSessionManager.getInstance(), oldActive)
        GlobalUserPreferences.useSeamlessTransitions = true; GlobalUserPreferences.reduceMotion = false
    }

    @Test fun capabilityNeedsEnabledFullAbiAndNoRomIsSafeOnApi26() {
        assertEquals(100, SharedViewRom.supportedProtocol())
        rom.enabled = false; assertFalse(SeamlessTransitions.isSupported())
        rom.enabled = true; rom.available = false; assertFalse(SeamlessTransitions.isSupported())
        SharedViewRom.setTransportForTests(null); assertFalse(SeamlessTransitions.isSupported())
    }
    @Test @Config(sdk = [26]) fun api26UnsupportedNeverStartsAnActivity() {
        SharedViewRom.setTransportForTests(null)
        assertFalse(SeamlessTransitions.openNovel(activity, card(), account, "work"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun incompletePublicAbiIsRejectedAndSystemAnimationZeroDisablesCapture() {
        assertThrows(NoSuchMethodException::class.java) { SharedViewRom.ReflectionTransport.inspect(String::class.java, Runnable::class.java) }
        val scale = android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).apply { isAccessible = true }
        try {
            scale.invoke(null, 0f)
            assertFalse(SeamlessTransitions.openNovel(activity, card(), account, "work"))
            assertEquals(0, rom.calls)
        } finally { scale.invoke(null, 1f) }
    }
    @Test fun coordinatesAndSupportedEnumsFollowMetadataNotBrand() {
        assertFalse(SharedViewRom.windowCoordinates(mapOf(SharedViewRom.BOUNDS to "rect|SCREEN|WINDOW in description")))
        assertTrue(SharedViewRom.windowCoordinates(mapOf(SharedViewRom.BOUNDS to "rect|WINDOW|bounds")))
        assertFalse(SharedViewRom.windowCoordinates(emptyMap()))
        assertTrue(SharedViewRom.permits(rom.metadata, SharedViewRom.BACK, SharedViewRom.FADE))
        assertFalse(SharedViewRom.permits(mapOf(SharedViewRom.FALLBACK to "int|VIEW_ANIM_NONE|fallback"), SharedViewRom.FALLBACK, SharedViewRom.FADE))
    }
    @Test fun largeBundleRejectsBeforeAnyScreenshotOrOptions() {
        val args = Bundle().apply { putString("account", account); putString("albumId", "album"); putByteArray("large", ByteArray(128 * 1024)) }
        assertFalse(SeamlessTransitions.argumentsFit(args))
        assertFalse(SeamlessTransitions.openAlbum(activity, card("album"), account, "album", false, args))
        assertEquals(0, rom.calls); assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun lockSecureReducedMotionAndPreferenceAreFailClosed() {
        val source = card()
        GlobalUserPreferences.reduceMotion = true; assertFalse(SeamlessTransitions.openNovel(activity, source, account, "work"))
        GlobalUserPreferences.reduceMotion = false; GlobalUserPreferences.useSeamlessTransitions = false
        assertFalse(SeamlessTransitions.openNovel(activity, source, account, "work"))
        GlobalUserPreferences.useSeamlessTransitions = true; activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        assertFalse(SeamlessTransitions.openNovel(activity, source, account, "work")); activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        field(AppSecurity::class.java, "trackerInstance").set(null, null)
        assertTrue(AppSecurity.isLocked()); assertFalse(SeamlessTransitions.openNovel(activity, source, account, "work"))
        assertEquals(0, rom.calls)
        field(AppSecurity::class.java, "trackerInstance").set(null, tracker)
    }
    @Test fun optionsNullAndExceptionPreserveOriginalNovelNavigationWithoutDoubleLaunch() {
        val status = novelPost()
        val holder = NovelStatusDisplayItem.Holder(activity, FrameLayout(activity))
        holder.bind(NovelStatusDisplayItem(status.id, StatusDisplayItem.NoOpCallbacks(activity), activity, status, account, false))
        show(holder.itemView)
        rom.nullOptions = true; holder.onClick()
        assertEquals("org.joinmastodon.android.novel.app.NovelAppActivity", shadowOf(activity).nextStartedActivity.component!!.className)
        assertNull(shadowOf(activity).nextStartedActivity)
        rom.nullOptions = false; rom.throwOptions = true; holder.onClick()
        assertNotNull(shadowOf(activity).nextStartedActivity); assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun realNovelCardUsesItsCardViewAndExternalDetailRoot() {
        val status = novelPost()
        val holder = NovelStatusDisplayItem.Holder(activity, FrameLayout(activity))
        holder.bind(NovelStatusDisplayItem(status.id, StatusDisplayItem.NoOpCallbacks(activity), activity, status, account, false))
        show(holder.itemView); holder.onClick()
        val launched = shadowOf(activity).nextStartedActivity
        assertTrue(launched.getBooleanExtra(SeamlessTransitions.DETAIL_ENTRY, false))
        assertEquals("work", launched.getStringExtra("work_id")); assertNotSame(holder.itemView, rom.source)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun realAlbumCardUsesBoundedNativeHostAndProtectedCardNeverCaptures() {
        val status = albumPost()
        val holder = AlbumUpdateStatusDisplayItem.Holder(activity, FrameLayout(activity))
        holder.bind(AlbumUpdateStatusDisplayItem(status.id, StatusDisplayItem.NoOpCallbacks(activity), activity, status, account))
        show(holder.itemView); holder.onClick()
        assertEquals(SeamlessDetailActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertEquals(1, rom.calls)
        SeamlessTransitions.clear(); status.albumUpdate.downloadProtected = true
        holder.bind(AlbumUpdateStatusDisplayItem(status.id, StatusDisplayItem.NoOpCallbacks(activity), activity, status, account))
        show(holder.itemView); holder.onClick()
        assertEquals(1, rom.calls); assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.fragmentManager.fragments.any { it is AlbumDetailFragment })
    }
    @Test fun actualBuilderHeaderTextFooterResolveTypedStatusAndOpenPostWithBitmapOptions() {
        val status = post()
        val rows = StatusDisplayItem.buildItems(StatusDisplayItem.NoOpCallbacks(activity), activity, status, account, status, emptyMap(), StatusDisplayItem.FLAG_NO_TRANSLATE)
        assertEquals(listOf(StatusDisplayItem.Type.HEADER, StatusDisplayItem.Type.TEXT, StatusDisplayItem.Type.FOOTER), rows.map { it.type })
        rows.forEach {
            assertNull("The legacy base field is intentionally not the concrete row's status", it.status)
            assertSame(status, SeamlessSource.statusOf(it))
        }
        val list = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity) }
        list.adapter = object : RecyclerView.Adapter<StatusDisplayItem.Holder<StatusDisplayItem>>() {
            override fun getItemCount() = rows.size
            override fun getItemViewType(position: Int) = position
            @Suppress("UNCHECKED_CAST")
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): StatusDisplayItem.Holder<StatusDisplayItem> =
                StatusDisplayItem.createViewHolder(rows[type].type, activity, parent, null) as StatusDisplayItem.Holder<StatusDisplayItem>
            override fun onBindViewHolder(holder: StatusDisplayItem.Holder<StatusDisplayItem>, position: Int) { holder.bind(rows[position]) }
        }
        activity.setContentView(list); layout(list, 393, 700)
        assertEquals("All three real production holders must be laid out", 3, list.childCount)
        val source = SeamlessSource.post(activity, list, account, status)
        assertNotNull("A complete real Header/Text/Footer card must be capturable", source)
        assertTrue(source!!.bindingValid()); assertTrue(source.valid())
        val args = Bundle().apply { putString("account", account); putParcelable("status", Parcels.wrap(status.clone())) }
        assertTrue(SeamlessTransitions.openPost(activity, list, account, status, args))
        assertEquals(1, rom.calls); assertNotNull(rom.bitmap); assertNull(rom.source)
        val launched = shadowOf(activity).nextStartedActivity
        assertNotNull("A post must really launch the narrow host, not silently fall back", launched)
        assertEquals(SeamlessDetailActivity::class.java.name, launched.component!!.className)
        assertEquals("post", launched.getStringExtra("detail_route"))
        assertNull(shadowOf(activity).nextStartedActivity)
        status.spoilerText = "New warning"
        assertFalse("Return validation must use those same concrete row statuses", source.bindingValid())
    }
    @Test fun multiRowPostClickUsesBitmapAndLargePostKeepsSameActivityThread() {
        val fragment = Timeline().apply { arguments = Bundle().apply { putString("account", account) } }
        activity.showFragment(fragment); activity.fragmentManager.executePendingTransactions()
        val status = post(); fragment.install(status); layout(fragment.recycler(), 393, 700)
        fragment.navigateToStatus(status)
        assertEquals(SeamlessDetailActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertNotNull(rom.bitmap); assertTrue(rom.bitmap!!.byteCount <= 4 * 1024 * 1024)
        assertTrue(rom.bounds!!.height() > 0)
        SeamlessTransitions.clear()
        status.content = "x".repeat(140 * 1024)
        fragment.navigateToStatus(status)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.fragmentManager.fragments.any { it is ThreadFragment })
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun adjacentPrivateRemovalPixelsNeverEnterPostTransition() {
        val publicPost = post()
        val privatePost = post().apply { id = "private-neighbour"; visibility = StatusPrivacy.PRIVATE }
        fun rows(status: Status) = StatusDisplayItem.buildItems(StatusDisplayItem.NoOpCallbacks(activity), activity,
            status, account, status, emptyMap(), StatusDisplayItem.FLAG_NO_TRANSLATE)
        val privateRows = rows(privatePost)
        val visibleRows = (privateRows + rows(publicPost)).toMutableList()
        val removal = HeldRemovalAnimator()
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            itemAnimator = removal
            setBackgroundColor(Color.WHITE)
        }
        list.adapter = object : RecyclerView.Adapter<StatusDisplayItem.Holder<StatusDisplayItem>>() {
            override fun getItemCount() = visibleRows.size
            override fun onCreateViewHolder(parent: ViewGroup, type: Int) = object : StatusDisplayItem.Holder<StatusDisplayItem>(
                View(activity).apply { layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40) }
            ) {
                override fun onBind(item: StatusDisplayItem) {
                    itemView.setBackgroundColor(if (item.parentID == privatePost.id) Color.MAGENTA else Color.TRANSPARENT)
                }
            }
            override fun onBindViewHolder(holder: StatusDisplayItem.Holder<StatusDisplayItem>, position: Int) {
                holder.bind(visibleRows[position])
            }
        }
        hostRoot().addView(list); layout(list, 393, 700)
        val source = SeamlessSource.post(activity, list, account, publicPost)!!
        assertTrue(source.valid())
        visibleRows.subList(0, privateRows.size).clear()
        list.adapter!!.notifyItemRangeRemoved(0, privateRows.size)
        layout(list, 393, 700)
        assertTrue("The disappearing private rows must still be drawing", removal.isRunning())
        assertFalse(list.isComputingLayout); assertFalse(list.hasPendingAdapterUpdates())
        val area = source.screenBounds()!!
        val raw = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888)
        try {
            val location = IntArray(2); list.getLocationOnScreen(location)
            val canvas = Canvas(raw); canvas.translate((location[0] - area.left).toFloat(), (location[1] - area.top).toFloat())
            list.draw(canvas)
            val pixels = IntArray(raw.width * raw.height); raw.getPixels(pixels, 0, raw.width, 0, 0, raw.width, raw.height)
            assertTrue("The unguarded crop really contains the fading magenta private marker", pixels.any {
                Color.red(it) > 200 && Color.blue(it) > 200 && Color.green(it) < 200
            })
        } finally { raw.recycle() }
        assertFalse(source.valid()); assertNull(source.snapshot())
        val args = Bundle().apply { putString("account", account); putParcelable("status", Parcels.wrap(publicPost.clone())) }
        assertFalse(SeamlessTransitions.openPost(activity, list, account, publicPost, args))
        assertEquals(0, rom.calls); assertNull(rom.bitmap); assertNull(shadowOf(activity).nextStartedActivity)
        removal.endAnimations()
        assertTrue(source.valid())
        val clean = source.snapshot()!!
        try {
            val pixels = IntArray(clean.width * clean.height); clean.getPixels(pixels, 0, clean.width, 0, 0, clean.width, clean.height)
            assertFalse("After removal no private marker may remain", pixels.any {
                Color.red(it) > 200 && Color.blue(it) > 200 && Color.green(it) < 200
            })
        } finally { clean.recycle() }
    }
    private class HeldRemovalAnimator : SimpleItemAnimator() {
        private val disappearing = mutableListOf<RecyclerView.ViewHolder>()
        override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
            disappearing.add(holder); holder.itemView.alpha = .5f; dispatchRemoveStarting(holder); return true
        }
        override fun runPendingAnimations() = Unit
        override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean { dispatchAddFinished(holder); return false }
        override fun animateMove(holder: RecyclerView.ViewHolder, fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
            dispatchMoveFinished(holder); return false
        }
        override fun animateChange(old: RecyclerView.ViewHolder, next: RecyclerView.ViewHolder?, fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
            dispatchChangeFinished(old, true); if (next != null && next !== old) dispatchChangeFinished(next, false); return false
        }
        override fun endAnimation(holder: RecyclerView.ViewHolder) {
            if (disappearing.remove(holder)) { holder.itemView.alpha = 1f; dispatchRemoveFinished(holder) }
        }
        override fun endAnimations() { disappearing.toList().forEach(::endAnimation); dispatchAnimationsFinished() }
        override fun isRunning() = disappearing.isNotEmpty()
    }
    @Test fun reusedBindingAndPrivacyChangeNeverReturnToTheWrongCard() {
        val source = card("album")
        assertTrue(SeamlessTransitions.openAlbum(activity, source, account, "album", false, albumArgs()))
        val target = target()
        SeamlessTransitions.bindCard(source, account, "different", true)
        SeamlessTransitions.beforeFinish(target)
        assertFalse(rom.lastOverride!!.containsKey(SharedViewRom.BOUNDS))
        assertEquals(SharedViewRom.FADE, rom.lastOverride!!.getInt(SharedViewRom.BACK))
        SeamlessTransitions.beforeFinish(target); assertEquals(1, rom.overrides)
    }
    @Test fun validReturnUsesFreshBoundsBeforeFinishAndSecureReturnOnlyCancels() {
        val source = card("album")
        assertTrue(SeamlessTransitions.openAlbum(activity, source, account, "album", false, albumArgs()))
        val target = target()
        source.translationY = 7f
        SeamlessTransitions.beforeFinish(target)
        assertNotNull(rom.lastOverride!!.getParcelable<Rect>(SharedViewRom.BOUNDS))
        assertFalse(target.isFinishing)
        assertTrue(SeamlessTransitions.openAlbum(activity, source, account, "album", false, albumArgs()))
        val secureTarget = target(); secureTarget.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val before = rom.overrides; SeamlessTransitions.beforeFinish(secureTarget)
        assertEquals(before, rom.overrides); assertTrue(rom.cancels > 0)
    }
    @Test fun freshProtectedAlbumCancelsEvenPendingBeforeAttach() {
        val source = card("album")
        assertTrue(SeamlessTransitions.openAlbum(activity, source, account, "album", false, albumArgs()))
        val c = Robolectric.buildActivity(SeamlessDetailActivity::class.java); children.add(c)
        SeamlessTransitions.protectedContent(c.get())
        assertTrue(rom.cancels > 0)
        assertTrue(SeamlessTransitions.openAlbum(activity, source, account, "album", false, albumArgs()))
    }
    @Test fun nestedPrivateCycleAndChangedCwAreNeverCapturable() {
        val status = post(); assertTrue(SeamlessSource.contentAllowed(status))
        status.reblog = post().apply { visibility = StatusPrivacy.DIRECT }
        assertFalse(SeamlessSource.contentAllowed(status)); status.reblog = status
        assertFalse(SeamlessSource.contentAllowed(status)); status.reblog = null
        val source = card()
        SeamlessTransitions.bindCard(source, account, "work", true, status)
        val binding = SeamlessSource.card(activity, source, account, "work")!!
        status.spoilerText = "Now hidden"; assertFalse(binding.valid())
    }
    @Test fun settingsOfferOnlySupportedRuntimeDefaultOnAndPersistOptOut() {
        fun settings(): SettingsDisplayFragment = SettingsDisplayFragment().apply {
            arguments = Bundle().apply { putString("account", account) }
            this@SeamlessTransitionsTest.activity.showFragment(this)
            this@SeamlessTransitionsTest.activity.fragmentManager.executePendingTransactions()
        }
        val unsupported = settings()
        assertNotNull(field(SettingsDisplayFragment::class.java, "seamlessTransitionsItem").get(unsupported))
        val item = field(SettingsDisplayFragment::class.java, "seamlessTransitionsItem").get(unsupported) as CheckableListItem<*>
        assertTrue(item.checked)
        SettingsDisplayFragment::class.java.getDeclaredMethod("setSeamlessTransitions", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(unsupported, false)
        assertFalse(GlobalUserPreferences.getPrefs().getBoolean("useSeamlessTransitions", true))
        GlobalUserPreferences.load(); assertFalse(GlobalUserPreferences.useSeamlessTransitions)
        rom.enabled = false
        assertNull(field(SettingsDisplayFragment::class.java, "seamlessTransitionsItem").get(settings()))
    }
    @Test fun hostRestoresSameCredentialIdentityWithoutAnAnimationSession() {
        val state = savedHostWithPrivateChild()
        val c = Robolectric.buildActivity(SeamlessDetailActivity::class.java, Intent())
        children.add(c); c.create(state).start().resume().visible()
        assertFalse(c.get().isFinishing)
        c.get().fragmentManager.executePendingTransactions(); layout(c.get().window.decorView, 393, 852)
        assertTrue("AppKit must restore the authenticated child hierarchy", c.get().fragmentManager.fragments.any { it is ThreadFragment })
        assertTrue(hasText(c.get().window.decorView, "private-restore-marker"))
        SeamlessTransitions.beforeFinish(c.get()); assertEquals(0, rom.overrides)
    }
    @Test fun replacedSameAccountCredentialsRejectStateBeforeAppKitCanRestorePrivateBody() {
        val state = savedHostWithPrivateChild()
        val replacement = AccountSession::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance().apply {
            domain = session.domain; self = session.self
            token = org.joinmastodon.android.model.Token().apply { accessToken = "offline-secret-token-two" }
        }
        assertEquals(account, replacement.id)
        accounts[account] = replacement
        val c = Robolectric.buildActivity(SeamlessDetailActivity::class.java, Intent()); children.add(c); c.create(state)
        assertTrue(c.get().isFinishing)
        assertTrue(c.get().fragmentManager.fragments.isEmpty())
        assertFalse(hasText(c.get().window.decorView, "private-restore-marker"))
        assertEquals(0, rom.overrides)
    }
    @Test fun sameSessionTokenMutationRejectsOldState() {
        val state = savedHostWithPrivateChild()
        session.token.accessToken = "offline-secret-token-three"
        val changed = Robolectric.buildActivity(SeamlessDetailActivity::class.java, Intent()); children.add(changed); changed.create(state)
        assertTrue(changed.get().isFinishing); assertTrue(changed.get().fragmentManager.fragments.isEmpty())
        session.token.accessToken = "offline-secret-token-one"
    }
    @Test fun coldUnknownNoLockRetainsShieldAndFlatStateUntilPublicRecreationRestoresChildren() {
        val state = savedHostWithPrivateChild()
        val gate = coldGate(LockMethod.NoLock)
        assertTrue(AppSecurity.isLocked())
        val c = Robolectric.buildActivity(SeamlessDetailActivity::class.java, Intent()); children.add(c)
        c.create(state).start().restoreInstanceState(state).resume().visible()
        val waiting = c.get()
        assertFalse(waiting.isFinishing); assertTrue(waiting.fragmentManager.fragments.isEmpty())
        assertFalse(hasText(waiting.window.decorView, "private-restore-marker"))
        assertNotNull(waiting.window.decorView.findViewWithTag<View>("abdl_security_shield"))
        val retained = Bundle(); c.saveInstanceState(retained)
        assertEquals(state.keySet(), retained.keySet())
        assertArrayEquals(state.getByteArray("detail_auth_digest"), retained.getByteArray("detail_auth_digest"))
        assertEquals(32, retained.getByteArray("detail_auth_digest")!!.size)
        assertNull(retained.getBundle("detail_arguments")); assertNull(waiting.intent.getBundleExtra("detail_arguments"))
        gate.onActivityResumed(waiting); ShadowLooper.idleMainLooper()
        val restored = c.get()
        assertNotSame("Unlock must use one public Activity.recreate, not repeat super.onCreate", waiting, restored)
        assertTrue(waiting.isDestroyed); assertFalse(restored.isFinishing)
        restored.fragmentManager.executePendingTransactions(); layout(restored.window.decorView, 393, 852)
        assertTrue(restored.fragmentManager.fragments.any { it is ThreadFragment })
        assertTrue(hasText(restored.window.decorView, "private-restore-marker"))
        gate.onActivityResumed(restored); ShadowLooper.idleMainLooper(); assertSame(restored, c.get())
        SeamlessTransitions.beforeFinish(restored); assertEquals(0, rom.overrides)
    }
    @Test fun coldPinWaitsForResumeAndChangedCredentialsNeverRecreateOrRestorePrivateChildren() {
        val state = savedHostWithPrivateChild()
        val gate = coldGate(LockMethod.Pin)
        val c = Robolectric.buildActivity(SeamlessDetailActivity::class.java, Intent()); children.add(c)
        c.create(state).start().resume().visible(); val waiting = c.get()
        gate.onActivityResumed(waiting)
        assertFalse(waiting.isFinishing); assertTrue(waiting.fragmentManager.fragments.isEmpty())
        assertNotNull(waiting.window.decorView.findViewWithTag<View>("abdl_security_shield"))
        gate.onActivityPaused(waiting); c.pause()
        gate.onUnlocked(); ShadowLooper.idleMainLooper(); assertSame(waiting, c.get())
        assertTrue(waiting.fragmentManager.fragments.isEmpty())
        session.token.accessToken = "changed-while-locked"
        c.resume(); gate.onActivityResumed(waiting); ShadowLooper.idleMainLooper()
        assertSame(waiting, c.get()); assertTrue(waiting.isFinishing)
        assertTrue(waiting.fragmentManager.fragments.isEmpty()); assertFalse(hasText(waiting.window.decorView, "private-restore-marker"))
        val discarded = Bundle(); c.saveInstanceState(discarded)
        assertFalse(discarded.containsKey("android:fragments"))
        gate.onActivityDestroyed(waiting); c.pause().stop().destroy(); gate.onUnlocked(); ShadowLooper.idleMainLooper()
        assertSame(waiting, c.get()); assertEquals(0, rom.overrides)
        session.token.accessToken = "offline-secret-token-one"
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun coldGate(method: LockMethod): AppGatekeeper {
        tracker = AuthTracker(FakeSecurityRepository(SecurityResult.Success(securityState(method))))
        tracker.onAppCreate()
        val dispatcher = UnconfinedTestDispatcher()
        val gate = AppGatekeeper(RuntimeEnvironment.getApplication(), tracker, dispatcher, dispatcher, {})
        field(AppSecurity::class.java, "trackerInstance").set(null, tracker)
        field(AppSecurity::class.java, "gatekeeper").set(null, gate)
        return gate
    }
    private fun savedHostWithPrivateChild(): Bundle {
        val c = Robolectric.buildActivity(SeamlessDetailActivity::class.java, SeamlessDetailActivity.intent(activity, SeamlessDetailActivity.ALBUM, albumArgs()))
        children.add(c); c.create().start().resume().visible()
        assertFalse(c.get().isFinishing)
        val status = post().apply { content = "<p>private-restore-marker</p>"; visibility = StatusPrivacy.PRIVATE }
        val child = ThreadFragment().apply { arguments = Bundle().apply { putString("account", account); putBoolean("noAutoLoad", true); putParcelable("status", Parcels.wrap(status)) } }
        c.get().showFragment(child); c.get().fragmentManager.executePendingTransactions(); layout(c.get().window.decorView, 393, 852)
        val state = Bundle(); c.saveInstanceState(state)
        assertEquals(32, state.getByteArray("detail_auth_digest")!!.size)
        assertNull(state.getBundle("detail_arguments"))
        val parcel = android.os.Parcel.obtain()
        try {
            parcel.writeBundle(state)
            val wire = String(parcel.marshall(), Charsets.UTF_16LE)
            assertFalse(wire.contains("offline-secret-token-one"))
        } finally { parcel.recycle() }
        return state
    }
    private fun hasText(view: View, marker: String): Boolean =
        (view is android.widget.TextView && view.text.toString().contains(marker)) ||
            (view is ViewGroup && (0 until view.childCount).any { hasText(view.getChildAt(it), marker) })

    @Test fun hostRejectsUnknownRouteAndIsNotExported() {
        val c = Robolectric.buildActivity(SeamlessDetailActivity::class.java, Intent().putExtra("detail_route", "arbitrary.Class"))
        children.add(c); c.create(); assertTrue(c.get().isFinishing)
        val info = activity.packageManager.getActivityInfo(android.content.ComponentName(activity, SeamlessDetailActivity::class.java), 0)
        assertFalse(info.exported)
        assertThrows(IllegalArgumentException::class.java) { SeamlessDetailActivity.intent(activity, "any.Class", Bundle()) }
    }

    private fun post() = Status.ofFake("post", "<p>Visible body</p>", Instant.parse("2026-11-01T00:00:00Z")).apply {
        visibility = StatusPrivacy.PUBLIC
        account = Account().apply { id = "99"; username = "author"; acct = "author"; displayName = "Author"; emojis = listOf() }
    }
    private fun albumPost() = post().apply { albumUpdate = AlbumUpdate().apply { albumId = "album"; albumName = "Album"; photoCount = 2; width = 400; height = 300 } }
    private fun novelPost() = post().apply {
        novel = org.joinmastodon.android.api.MastodonAPIController.gson.fromJson("{\"schema_version\":1,\"mode\":\"link\",\"available\":true,\"work_id\":\"work\",\"title\":\"Book\",\"author\":{\"id\":99,\"username\":\"author\"},\"chapter_count\":2,\"word_count\":200}", NovelAttachment::class.java)
    }
    private fun albumArgs() = Bundle().apply { putString("account", account); putString("albumId", "album") }
    private fun card(entity: String = "work"): View {
        val card = View(activity); card.setBackgroundColor(android.graphics.Color.BLUE)
        val root = FrameLayout(activity); root.setBackgroundColor(android.graphics.Color.WHITE)
        root.addView(card, FrameLayout.LayoutParams(140, 90)); hostRoot().addView(root); layout(root, 393, 700)
        SeamlessTransitions.bindCard(card, account, entity, true); return card
    }
    private fun show(view: View) { (view.parent as? ViewGroup)?.removeView(view); hostRoot().addView(view); layout(view, 393, 700) }
    private fun hostRoot(): ViewGroup {
        if (activity.fragmentManager.fragments.isEmpty()) {
            val timeline = Timeline().apply { arguments = Bundle().apply { putString("account", account) } }
            activity.showFragment(timeline); activity.fragmentManager.executePendingTransactions()
        }
        return activity.findViewById(me.grishka.appkit.R.id.fragment_wrap)
    }
    private fun layout(view: View, width: Int, height: Int) {
        activity.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY))
        activity.window.decorView.layout(0, 0, 393, 852)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); view.layout(0, 0, width, height)
        ShadowLooper.idleMainLooper()
    }
    private fun target(): SeamlessDetailActivity {
        shadowOf(activity).nextStartedActivity
        val c = Robolectric.buildActivity(SeamlessDetailActivity::class.java); children.add(c)
        val target = c.get(); SeamlessTransitions.attach(target, account, "album"); return target
    }
    private fun field(owner: Class<*>, name: String) = owner.getDeclaredField(name).apply { isAccessible = true }
    private class FakeRom : SharedViewRom.Transport {
        var available = true; var enabled = true; var nullOptions = false; var throwOptions = false
        var calls = 0; var cancels = 0; var overrides = 0
        var source: View? = null; var bitmap: Bitmap? = null; var bounds: Rect? = null; var lastOverride: Bundle? = null
        val metadata = mapOf(SharedViewRom.BOUNDS to "rect|WINDOW|bounds", SharedViewRom.BACK to "int|VIEW_ANIM_FADE_OUT,VIEW_ANIM_FOLLOW_SYSTEM|return")
        override fun available() = available
        override fun enabled() = enabled
        override fun extras() = metadata
        override fun fromView(view: View, config: Bundle): ActivityOptions? { source = view; return options() }
        override fun fromBitmap(bitmap: Bitmap, bounds: Rect, radius: Float, config: Bundle): ActivityOptions? { this.bitmap = bitmap; this.bounds = Rect(bounds); return options() }
        private fun options(): ActivityOptions? { calls++; if (throwOptions) throw IllegalStateException("Unavailable"); return if (nullOptions) null else ActivityOptions.makeBasic() }
        override fun override(activity: Activity, config: Bundle) { overrides++; lastOverride = Bundle(config) }
        override fun cancel(): Boolean { cancels++; return true }
    }
}
