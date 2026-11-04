package org.joinmastodon.android.ui.seamless

import android.app.Activity
import android.app.ActivityOptions
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import me.grishka.appkit.FragmentStackActivity
import me.grishka.appkit.fragments.BaseRecyclerFragment
import me.grishka.appkit.utils.V
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.GlobalUserPreferences
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.R
import org.joinmastodon.android.api.session.AccountSession
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.fragments.settings.BaseSettingsFragment
import org.joinmastodon.android.fragments.settings.SettingsDisplayFragment
import org.joinmastodon.android.model.Account
import org.joinmastodon.android.model.Instance
import org.joinmastodon.android.model.InstanceV1
import org.joinmastodon.android.model.viewmodel.CheckableListItem
import org.joinmastodon.android.ui.adapters.GenericListItemsAdapter
import org.joinmastodon.android.ui.viewholders.SwitchListItemViewHolder
import org.joinmastodon.android.ui.views.M3Switch
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
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/** Attached production settings rows; only the optional ROM transport is faked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w320dp-h720dp-mdpi", application = CompatibilityTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsDisplaySeamlessTransitionsTest {
    private val choice = "useSeamlessTransitions"
    private val explicit = "seamlessTransitionsExplicitlyChosen"
    private lateinit var prefs: SharedPreferences
    private lateinit var session: AccountSession
    private lateinit var accounts: MutableMap<String, AccountSession>
    private lateinit var instances: MutableMap<String, Instance>
    private var previousAccount: AccountSession? = null
    private var previousInstance: Instance? = null
    private var controller: ActivityController<FragmentStackActivity>? = null
    private lateinit var settings: SettingsDisplayFragment
    private lateinit var list: RecyclerView
    private lateinit var item: CheckableListItem<*>
    private var position = -1
    private val rom = Capability()
    private val activity get() = controller!!.get()

    @Before fun setup() {
        ShadowChoreographer.setPaused(true)
        MastodonApp.context = RuntimeEnvironment.getApplication()
        V.setApplicationContext(MastodonApp.context)
        prefs = GlobalUserPreferences.getPrefs()
        assertTrue(prefs.edit().clear().putBoolean("perAccountMigrationDone", true).commit())
        GlobalUserPreferences.load()
        session = AccountSession::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance().apply {
            domain = "seamless-setting.offline.example.test"
            self = Account().apply { id = "42"; username = "offline"; acct = "offline" }
        }
        val manager = AccountSessionManager.getInstance()
        @Suppress("UNCHECKED_CAST")
        accounts = field(AccountSessionManager::class.java, "sessions").get(manager) as MutableMap<String, AccountSession>
        @Suppress("UNCHECKED_CAST")
        instances = field(AccountSessionManager::class.java, "instances").get(manager) as MutableMap<String, Instance>
        previousAccount = accounts.put(session.id, session)
        previousInstance = instances.put(session.domain, InstanceV1().apply {
            uri = session.domain; normalizedUri = session.domain; version = "4.3.0"
        })
        SharedViewRom.setTransportForTests(rom)
    }

    @After fun cleanup() {
        controller?.close()
        if (previousAccount == null) accounts.remove(session.id) else accounts[session.id] = previousAccount!!
        if (previousInstance == null) instances.remove(session.domain) else instances[session.domain] = previousInstance!!
        SharedViewRom.setTransportForTests(null)
        prefs.edit().clear().putBoolean("perAccountMigrationDone", true).commit()
        GlobalUserPreferences.load()
    }

    @Test fun noStoredChoiceDefaultsOnAndUnrelatedSavesNeverInventAnExplicitSelection() {
        open()
        assertChoice(true, stored = null)
        assertTrue(switch().isEnabled)
        repeat(3) { resumeSettings() }
        assertChoice(true, stored = null)
        GlobalUserPreferences.useSeamlessTransitions = false // an unrelated/stale runtime field
        GlobalUserPreferences.save()
        assertFalse(prefs.contains(choice)); assertFalse(prefs.contains(explicit))
        GlobalUserPreferences.load()
        resumeSettings()
        assertChoice(true, stored = null)
    }

    @Test fun saveBeforeLoadCannotOverwriteAnExistingLegacyOptOut() {
        assertTrue(prefs.edit().putBoolean(choice, false).commit())
        GlobalUserPreferences.useSeamlessTransitions = true
        GlobalUserPreferences.save()
        assertFalse(prefs.getBoolean(choice, true)); assertFalse(prefs.contains(explicit))
        GlobalUserPreferences.load()
        open()
        repeat(3) { resumeSettings() }
        assertChoice(false, stored = false)
        assertFalse("Migration must not invent explicit provenance", prefs.contains(explicit))
    }

    @Test fun actualSwitchOptOutSurvivesReloadResumeAndSystemCapabilityRecovery() {
        open()
        // CompoundButton.performClick() returns the OnClickListener result, not the toggle result;
        // a real Switch has no click listener, so assert the persisted/checked outcome instead.
        switch().performClick(); settle()
        assertChoice(false, stored = false)
        assertTrue(prefs.getBoolean(explicit, false))
        rom.result = SharedViewRom.Availability.DISABLED
        resumeSettings()
        assertFalse(item.isEnabled); assertFalse(switch().isEnabled)
        GlobalUserPreferences.load()
        rom.result = SharedViewRom.Availability.SUPPORTED
        repeat(3) { resumeSettings() }
        assertTrue(item.isEnabled)
        assertChoice(false, stored = false)
        holder().onClick(); settle() // the real RecyclerView holder's row callback
        assertChoice(true, stored = true)
        GlobalUserPreferences.load(); resumeSettings()
        assertChoice(true, stored = true)
    }

    @Test fun unavailableWithoutAChoiceRemainsVisibleAndCannotPersistItsUncheckedPresentation() {
        rom.result = SharedViewRom.Availability.UNSUPPORTED
        open()
        val reasons = mapOf(
            SharedViewRom.Availability.UNSUPPORTED to R.string.settings_seamless_transitions_unsupported,
            SharedViewRom.Availability.INTERFACE_INCOMPATIBLE to R.string.settings_seamless_transitions_incompatible,
            SharedViewRom.Availability.DISABLED to R.string.settings_seamless_transitions_disabled,
            SharedViewRom.Availability.QUERY_FAILED to R.string.settings_seamless_transitions_query_failed,
        )
        for ((availability, reason) in reasons) {
            rom.result = availability; resumeSettings()
            assertFalse(item.isEnabled); assertFalse(item.checked)
            assertFalse(switch().isEnabled); assertFalse(switch().isChecked)
            assertTrue(holder().itemView.isAttachedToWindow)
            assertEquals(reason, item.subtitleRes)
            assertEquals(activity.getString(reason), holder().itemView.findViewById<TextView>(R.id.subtitle).text.toString())
            holder().onClick(); switch().performClick(); item.checkedChangeListener.accept(false); settle()
            GlobalUserPreferences.save()
            assertTrue(GlobalUserPreferences.useSeamlessTransitions)
            assertFalse(prefs.contains(choice)); assertFalse(prefs.contains(explicit))
        }
        rom.result = SharedViewRom.Availability.SUPPORTED
        resumeSettings()
        assertChoice(true, stored = null)
    }

    @Test fun wrappingTheSeamlessTitleDoesNotLeakIntoARecycledSettingsRow() {
        open()
        val bound = holder()
        val title = bound.itemView.findViewById<TextView>(R.id.title)
        val ellipsize = title.ellipsize
        assertEquals(Int.MAX_VALUE, title.maxLines)
        val other = CheckableListItem<Void>(R.string.settings_show_cws, 0, CheckableListItem.Style.SWITCH, true, R.drawable.ic_warning_24px, { })
        bound.onBind(other)
        assertEquals(2, title.maxLines); assertEquals(ellipsize, title.ellipsize)
        bound.onBind(item)
        assertEquals(Int.MAX_VALUE, title.maxLines)
        assertEquals(ellipsize, title.ellipsize)
    }

    @Test fun aStaleEnabledCallbackCannotSaveAfterTheLiveProbeFails() {
        open()
        rom.result = SharedViewRom.Availability.QUERY_FAILED
        switch().performClick(); settle()
        assertFalse(item.isEnabled); assertFalse(item.checked)
        assertTrue(GlobalUserPreferences.useSeamlessTransitions)
        assertFalse(prefs.contains(choice)); assertFalse(prefs.contains(explicit))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun englishNameAndAutoEnabledSwitchRenderAttachedAt320Font160Light() {
        render(dark = false, supported = true, language = "en")
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "zh-rCN-w320dp-h720dp-mdpi")
    fun chineseNameAndDisabledReasonRenderAttachedAt320Font160Dark() {
        render(dark = true, supported = false, language = "zh")
    }

    private fun render(dark: Boolean, supported: Boolean, language: String) {
        val config = android.content.res.Configuration(MastodonApp.context.resources.configuration).apply { fontScale = 1.6f }
        MastodonApp.context.resources.updateConfiguration(config, MastodonApp.context.resources.displayMetrics)
        GlobalUserPreferences.theme = if (dark) GlobalUserPreferences.ThemePreference.DARK else GlobalUserPreferences.ThemePreference.LIGHT
        if (!supported) rom.result = SharedViewRom.Availability.UNSUPPORTED
        open(dark)
        list.scrollToPosition(position); settle(12)
        val title = holder().itemView.findViewById<TextView>(R.id.title)
        assertEquals(if (language == "zh") "系统级无缝动画" else "System-level seamless animations", title.text.toString())
        assertNotNull(title.layout)
        for (line in 0 until title.lineCount) assertEquals(0, title.layout.getEllipsisCount(line))
        assertEquals(1.6f, title.resources.configuration.fontScale, .001f)
        assertTrue(title.isAttachedToWindow)
        val visible = Rect(); assertTrue(holder().itemView.getGlobalVisibleRect(visible))
        assertTrue("Switch is really in the attached viewport", visible.height() >= switch().height)
        assertEquals(supported, switch().isEnabled); assertEquals(supported, switch().isChecked)
        assertFalse(prefs.contains(choice)); assertFalse(prefs.contains(explicit))
        val output = System.getProperty("beta35RenderDir") ?: return
        val file = File(output, "seamless/settings-$language-${if (dark) "dark" else "light"}-320-font160.png")
        assertTrue(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun open(dark: Boolean = false) {
        controller = Robolectric.buildActivity(FragmentStackActivity::class.java)
        activity.setTheme(if (dark) R.style.Theme_Mastodon_Dark else R.style.Theme_Mastodon_Light)
        controller!!.create()
        settings = SettingsDisplayFragment().apply { arguments = Bundle().apply { putString("account", session.id) } }
        activity.showFragment(settings)
        controller!!.start().resume().visible()
        activity.fragmentManager.executePendingTransactions(); settle()
        list = field(BaseRecyclerFragment::class.java, "list").get(settings) as RecyclerView
        @Suppress("UNCHECKED_CAST")
        val adapter = field(BaseSettingsFragment::class.java, "itemsAdapter").get(settings) as GenericListItemsAdapter<*>
        val positions = (0 until adapter.itemCount).filter { adapter.getItem(it).titleRes == R.string.settings_seamless_transitions }
        assertEquals("One visible setting, regardless of capability", 1, positions.size)
        position = positions.single()
        item = adapter.getItem(position) as CheckableListItem<*>
        list.scrollToPosition(position); settle()
        assertSame(item, holder().getItem())
        assertTrue(holder().itemView.isAttachedToWindow)
    }

    private fun resumeSettings() {
        controller!!.pause(); settle()
        controller!!.resume(); settle()
        list.scrollToPosition(position); settle()
    }

    private fun settle(frames: Int = 6) {
        repeat(frames) {
            var vsync = false
            android.view.Choreographer.getInstance().postFrameCallback { vsync = true }
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
            assertTrue("Advance real vsync before attached window traversal", vsync)
            val root = activity.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 320, 720)
            root.viewTreeObserver.dispatchOnPreDraw()
        }
    }

    private fun holder() = list.findViewHolderForAdapterPosition(position) as SwitchListItemViewHolder
    private fun switch() = findSwitch(holder().itemView)!!
    private fun assertChoice(requested: Boolean, stored: Boolean?) {
        assertEquals(requested, GlobalUserPreferences.useSeamlessTransitions)
        assertEquals(requested, item.checked); assertEquals(requested, switch().isChecked)
        if (stored == null) assertFalse(prefs.contains(choice)) else assertEquals(stored, prefs.getBoolean(choice, !stored))
    }
    private fun findSwitch(view: View): M3Switch? = when (view) {
        is M3Switch -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findSwitch(view.getChildAt(it)) }
        else -> null
    }
    private fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }

    private class Capability : SharedViewRom.Transport {
        var result = SharedViewRom.Availability.SUPPORTED
        override fun available() = result == SharedViewRom.Availability.SUPPORTED
        override fun abiAvailability() = result
        override fun enabled() = true
        override fun extras() = emptyMap<String, String>()
        override fun fromView(view: View, config: Bundle): ActivityOptions? = error("Settings must not launch a ROM animation")
        override fun fromBitmap(bitmap: Bitmap, bounds: Rect, radius: Float, config: Bundle): ActivityOptions? = error("Settings must not capture content")
        override fun override(activity: Activity, config: Bundle) = Unit
        override fun cancel() = true
    }
}
