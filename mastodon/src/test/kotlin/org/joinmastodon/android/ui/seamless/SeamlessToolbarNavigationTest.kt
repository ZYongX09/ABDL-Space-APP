package org.joinmastodon.android.ui.seamless

import android.app.Activity
import android.app.Fragment
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toolbar
import me.grishka.appkit.Nav
import me.grishka.appkit.fragments.AppKitFragment
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.fragments.ThreadFragment
import org.joinmastodon.android.fragments.albums.AlbumDetailFragment
import org.joinmastodon.android.fragments.diapers.DiaperDetailFragment
import org.joinmastodon.android.fragments.friendcards.FriendCardDetailFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivity
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** Real attached Toolbar/AppKit navigation; restore platform back dispatch omitted by Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class, qualifiers = "en-w393dp-h852dp-mdpi", shadows = [SeamlessAttachedFixture.OfflineRequests::class, SeamlessToolbarNavigationTest.PlatformBackActivity::class])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("DEPRECATION")
class SeamlessToolbarNavigationTest : SeamlessAttachedFixture() {
    @Implements(Activity::class)
    class PlatformBackActivity : ShadowActivity() {
        // ShadowActivity.onBackPressed calls its own shadow finish(), only setting mFinished and
        // bypassing Activity subclasses' finish hooks. Execute Android's real back implementation
        // instead: platform FragmentManager -> finishAfterTransition -> virtual Activity.finish.
        @Implementation
        override fun onBackPressed() {
            Shadow.directlyOn<Unit, Activity>(realActivity, Activity::class.java, "onBackPressed")
        }
    }

    private val routes = linkedMapOf(
        SeamlessDetailActivity.POST to ThreadFragment::class.java,
        SeamlessDetailActivity.ALBUM to AlbumDetailFragment::class.java,
        SeamlessDetailActivity.DIAPER to DiaperDetailFragment::class.java,
        SeamlessDetailActivity.FRIEND_CARD to FriendCardDetailFragment::class.java,
    )

    private fun routeArgs(route: String): Bundle = when (route) {
        SeamlessDetailActivity.POST -> postArgs(post()).apply { putBoolean("noAutoLoad", true) }
        else -> Bundle().apply {
            putString("account", account)
            when (route) {
                SeamlessDetailActivity.ALBUM -> putString("albumId", "album-toolbar")
                SeamlessDetailActivity.DIAPER -> putInt("diaper_id", 17)
                SeamlessDetailActivity.FRIEND_CARD -> putString("card_id", "card-toolbar")
                else -> error("Unknown test route")
            }
        }
    }

    private fun createHost(intent: Intent): ActivityController<SeamlessDetailActivity> {
        val c = Robolectric.buildActivity(SeamlessDetailActivity::class.java, intent)
        children.add(c)
        c.create().start().resume().visible()
        gate.onActivityResumed(c.get()) // CompatibilityTestApplication does not register AppSecurity callbacks.
        settleHost(c.get())
        assertFalse(c.get().isFinishing)
        return c
    }

    private fun settleHost(host: SeamlessDetailActivity) {
        host.fragmentManager.executePendingTransactions()
        layout(host.window.decorView, dp(393), dp(852))
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        host.fragmentManager.executePendingTransactions()
    }

    private fun root(host: SeamlessDetailActivity): Fragment = host.fragmentManager.fragments.filterIsInstance<AppKitFragment>().single()

    private fun canGoBack(fragment: Fragment): Boolean = AppKitFragment::class.java
        .getDeclaredMethod("canGoBack").apply { isAccessible = true }.invoke(fragment) as Boolean

    private fun toolbar(fragment: Fragment): Toolbar = requireNotNull(fragment.view)
        .findViewById<Toolbar>(me.grishka.appkit.R.id.toolbar).also {
            assertNotNull("The production root must contain its AppKit Toolbar", it)
            assertTrue("Use a real attached Toolbar, not a detached draw surrogate", it.isAttachedToWindow)
            assertNotNull("AppKit must initialise the native back drawable", it.navigationIcon)
        }

    private fun clickNavigation(fragment: Fragment) {
        val bar = toolbar(fragment)
        assertTrue("Click only the visible native navigation button", bar.isShown)
        val button = (0 until bar.childCount).map { bar.getChildAt(it) }
            .filterIsInstance<ImageButton>().single { it.drawable === bar.navigationIcon }
        assertTrue(button.isShown)
        assertTrue("Exercise AppKit's installed navigation listener", button.performClick())
    }

    private fun launchedAlbum(): SeamlessDetailActivity {
        assertTrue(SeamlessTransitions.openAlbum(activity, plainCard("album-toolbar"), account,
            "album-toolbar", false, routeArgs(SeamlessDetailActivity.ALBUM)))
        val intent = Shadow.extract<ShadowActivity>(activity).nextStartedActivity
        assertNotNull(intent)
        assertEquals(SeamlessDetailActivity::class.java.name, intent.component!!.className)
        return createHost(intent).get()
    }

    private fun pushThread(host: SeamlessDetailActivity): ThreadFragment {
        Nav.go(host, ThreadFragment::class.java, routeArgs(SeamlessDetailActivity.POST))
        settleHost(host)
        return host.fragmentManager.fragments.filterIsInstance<ThreadFragment>().single()
    }

    @Test fun allFourRealRootsReceiveTheSameCanGoBackContractAsNavGo() {
        routes.forEach { (route, type) ->
            // A caller cannot accidentally suppress the standalone detail's back affordance.
            val args = routeArgs(route).apply { putBoolean("_can_go_back", false) }
            val host = createHost(SeamlessDetailActivity.intent(activity, route, args)).get()
            val fragment = root(host)
            assertEquals(type, fragment.javaClass)
            assertEquals(account, fragment.arguments.getString("account"))
            assertTrue(fragment.arguments.getBoolean("_can_go_back"))
            assertTrue("AppKit canGoBack must see the actual arguments for $route", canGoBack(fragment))
            assertNull(host.intent.getBundleExtra("detail_arguments"))
            if (route != SeamlessDetailActivity.FRIEND_CARD) {
                assertTrue(toolbar(fragment).isShown)
            }
            // FriendCardDetailFragment intentionally hides its native toolbar for its Compose header.
        }
    }

    @Test fun ordinaryNavGoStillPushesAndPopsWithoutMakingItsRootBackNavigable() {
        val timeline = timeline(post())
        assertFalse(canGoBack(timeline))
        assertNull(timeline.view!!.findViewById<Toolbar>(me.grishka.appkit.R.id.toolbar).navigationIcon)
        Nav.go(activity, ThreadFragment::class.java, routeArgs(SeamlessDetailActivity.POST))
        activity.fragmentManager.executePendingTransactions()
        layout(activity.window.decorView, dp(393), dp(852))
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        val thread = activity.fragmentManager.fragments.filterIsInstance<ThreadFragment>().single()
        assertTrue(canGoBack(thread))
        clickNavigation(thread)
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        activity.fragmentManager.executePendingTransactions()
        assertFalse(activity.isFinishing)
        assertSame(timeline, activity.fragmentManager.fragments.filterIsInstance<AppKitFragment>().single())
        assertFalse(canGoBack(timeline))
        assertEquals(0, rom.calls)
        assertEquals(0, rom.overrides)
    }

    @Test fun rootToolbarClickFinishesThroughTheExistingReturnAnimationHook() {
        val host = launchedAlbum()
        assertEquals(1, rom.calls)
        assertEquals(0, rom.overrides)
        clickNavigation(root(host))
        assertTrue(host.isFinishing)
        assertEquals(1, rom.overrides)
        assertNotNull(rom.lastOverride!!.getParcelable<Rect>(SharedViewRom.BOUNDS))
        assertFalse(rom.lastOverride!!.containsKey(SharedViewRom.BACK))
        assertEquals("Returning must not capture the source again", 1, rom.calls)
    }

    @Test fun toolbarHonoursFragmentBackCallbacksAndInternalPopBeforeRootFinish() {
        val host = launchedAlbum()
        val album = root(host)
        val child = pushThread(host)
        var consumed = 0
        val callback = Runnable { consumed++ }
        host.addBackCallback(child, callback)
        clickNavigation(child)
        assertEquals(1, consumed)
        assertTrue(host.fragmentManager.fragments.contains(child))
        assertFalse(host.isFinishing)
        assertEquals(0, rom.overrides)
        host.removeBackCallback(callback)

        clickNavigation(child)
        settleHost(host)
        assertSame(album, root(host))
        assertFalse(host.isFinishing)
        assertEquals(0, rom.overrides)

        val nextChild = pushThread(host)
        Nav.finish(nextChild)
        settleHost(host)
        assertSame(album, root(host))
        assertFalse(host.isFinishing)
        assertEquals(0, rom.overrides)

        clickNavigation(album)
        assertTrue(host.isFinishing)
        assertEquals(1, rom.overrides)
    }

    @Test fun systemBackStillPopsAChildAndOnlyFinishesAtTheRoot() {
        val host = launchedAlbum()
        val album = root(host)
        pushThread(host)
        host.onBackPressed()
        settleHost(host)
        assertSame(album, root(host))
        assertFalse(host.isFinishing)
        assertEquals(0, rom.overrides)
        host.onBackPressed()
        assertTrue(host.isFinishing)
        assertEquals(1, rom.overrides)
    }

    @Test fun navFinishAtTheRootStillUsesActivityFinishAndTheReturnHook() {
        val host = launchedAlbum()
        Nav.finish(root(host))
        assertTrue(host.isFinishing)
        assertEquals(1, rom.overrides)
    }

    @Test fun activityRecreationRestoresAllFourRootArgumentsAndNativeBackIcons() {
        routes.forEach { (route, type) ->
            val c = createHost(SeamlessDetailActivity.intent(activity, route, routeArgs(route)))
            val previous = c.get()
            c.recreate()
            val restored = c.get()
            gate.onActivityResumed(restored)
            settleHost(restored)
            assertNotSame(previous, restored)
            assertFalse(restored.isFinishing)
            val fragment = root(restored)
            assertEquals(type, fragment.javaClass)
            assertTrue(fragment.arguments.getBoolean("_can_go_back"))
            assertTrue(canGoBack(fragment))
            assertNull(restored.intent.getBundleExtra("detail_arguments"))
            if (route != SeamlessDetailActivity.FRIEND_CARD) {
                assertTrue(toolbar(fragment).isShown)
                clickNavigation(fragment)
                assertTrue(restored.isFinishing)
            }
        }
        assertEquals("Recreation has no in-memory animation session to replay", 0, rom.overrides)
        assertEquals(0, rom.calls)
    }
}
