package org.joinmastodon.android.ui.seamless

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import me.grishka.appkit.Nav
import me.grishka.appkit.utils.V
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.fragments.diapers.DiaperDetailFragment
import org.joinmastodon.android.fragments.diapers.DiaperListFragment
import org.joinmastodon.android.fragments.friendcards.FriendCardDetailFragment
import org.joinmastodon.android.model.Diaper
import org.joinmastodon.android.model.FriendCard
import org.joinmastodon.android.model.FriendCardAttachment
import org.joinmastodon.android.model.Status
import org.joinmastodon.android.model.StatusPrivacy
import org.joinmastodon.android.security.AppSecurity
import org.joinmastodon.android.ui.displayitems.FriendCardStatusDisplayItem
import org.joinmastodon.android.ui.displayitems.StatusDisplayItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Production list/card clicks and the existing native/Compose Fragment roots; no navigation shadow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class, qualifiers = "en-w393dp-h852dp-mdpi", shadows = [SeamlessAttachedFixture.OfflineRequests::class])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeamlessEntryRoutesTest : SeamlessAttachedFixture() {
    private fun diapers(): Pair<DiaperListFragment, RecyclerView> {
        val fragment = DiaperListFragment().apply { arguments = Bundle().apply { putString("account", account) } }
        activity.showFragment(fragment); activity.fragmentManager.executePendingTransactions(); settle()
        @Suppress("UNCHECKED_CAST")
        val data = field(DiaperListFragment::class.java, "data").get(fragment) as MutableList<Diaper>
        data.clear(); data.add(Diaper().apply { id = 17; brand = "Offline brand"; model = "Original model"; images = emptyList() })
        val list = field(DiaperListFragment::class.java, "recyclerView").get(fragment) as RecyclerView
        // LoaderFragment keeps content GONE until its async load completes; the fixture supplies the
        // row directly, so publish content through the production helper instead of an mdpi draw surrogate.
        fragment.loaded = true
        me.grishka.appkit.fragments.LoaderFragment::class.java.getDeclaredMethod("showContent")
            .apply { isAccessible = true }.invoke(fragment)
        val content = field(me.grishka.appkit.fragments.LoaderFragment::class.java, "content").get(fragment) as? View
        if (content != null) {
            // showContent() fades 0 -> 1 over 300ms; the attached-window fixture must not click at alpha<1.
            V.cancelVisibilityAnimation(content)
            content.alpha = 1f; content.visibility = View.VISIBLE
        }
        list.visibility = View.VISIBLE
        list.adapter!!.notifyDataSetChanged(); layout(list)
        return fragment to list
    }
    private fun friendPost(link: Boolean = false): Status = post("friend-publication").apply {
        friendCard = FriendCardAttachment().apply {
            schemaVersion = 1; mode = if (link) "link" else "publication"; available = true; id = "card-17"; title = "Meet new friends"
            card = FriendCard().apply { schemaVersion = 1; id = "card-17"; title = "Meet new friends"; state = "active"; publicFields = emptyList() }
        }
    }
    private fun friendRow(timeline: Timeline, parentID: String, quote: Boolean = false): FriendCardStatusDisplayItem.Holder =
        holder(timeline, parentID, StatusDisplayItem.Type.FRIEND_CARD, quote) as FriendCardStatusDisplayItem.Holder
    private fun innerCard(holder: FriendCardStatusDisplayItem.Holder, link: Boolean = false) =
        (holder.itemView as FrameLayout).getChildAt(if (link) 1 else 0)
    private fun createHost(intent: Intent): SeamlessDetailActivity {
        val c = Robolectric.buildActivity(SeamlessDetailActivity::class.java, intent); children.add(c)
        c.create().start().resume().visible()
        gate.onActivityResumed(c.get()) // The fixture Application does not install production lifecycle callbacks.
        settle(); c.get().fragmentManager.executePendingTransactions()
        return c.get()
    }

    @Test fun actualDiaperListCardOpensNativeHostWithUnchangedIdAndAccount() {
        val (_, list) = diapers()
        val card = list.findViewHolderForAdapterPosition(0)!!.itemView
        assertTrue(card.performClick())
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull(intent); assertEquals(SeamlessDetailActivity::class.java.name, intent.component!!.className)
        assertEquals("diaper", intent.getStringExtra("detail_route"))
        val args = intent.getBundleExtra("detail_arguments")!!
        assertEquals(17, args.getInt("diaper_id")); assertEquals(account, args.getString("account"))
        assertSame(card, rom.view); assertNull(rom.bitmap); assertEquals(1, rom.calls)
        val host = createHost(intent)
        assertFalse(host.isFinishing)
        val root = host.fragmentManager.fragments.filterIsInstance<DiaperDetailFragment>().single()
        assertEquals(17, root.arguments.getInt("diaper_id")); assertEquals(account, root.arguments.getString("account"))
        assertNull(host.intent.getBundleExtra("detail_arguments")); assertNull(pending()); assertFalse(watchdogScheduled())
        val before = rom.overrides
        host.finish(); assertEquals(before + 1, rom.overrides); assertFalse(rom.lastOverride!!.containsKey(SharedViewRom.BACK))
    }
    @Test fun unsupportedDiaperClickKeepsOriginalSameActivityDetailBusiness() {
        val (_, list) = diapers(); rom.supported = false
        assertTrue(list.findViewHolderForAdapterPosition(0)!!.itemView.performClick())
        settle(); activity.fragmentManager.executePendingTransactions()
        assertNull(shadowOf(activity).nextStartedActivity); assertEquals(0, rom.calls)
        val root = activity.fragmentManager.fragments.filterIsInstance<DiaperDetailFragment>().single()
        assertEquals(17, root.arguments.getInt("diaper_id")); assertEquals(account, root.arguments.getString("account"))
    }
    @Test fun actualPublicationCardOpensExistingComposeDetailWithoutLegacyFriendRequestRouting() {
        val status = friendPost(); val timeline = timeline(status)
        val card = innerCard(friendRow(timeline, status.id))
        assertTrue(card.performClick())
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals("friend_card", intent.getStringExtra("detail_route")); assertEquals(1, rom.calls)
        assertSame(card, rom.view); assertNull(rom.bitmap)
        val host = createHost(intent)
        val root = host.fragmentManager.fragments.filterIsInstance<FriendCardDetailFragment>().single()
        assertEquals("card-17", root.arguments.getString("card_id")); assertEquals(account, root.arguments.getString("account"))
        assertNull(host.intent.getBundleExtra("detail_arguments")); assertFalse(host.isFinishing)
    }
    @Test fun actualCompactFriendLinkUsesTheLinkViewAndStableCardIdNotDisplayUrl() {
        val status = friendPost(link = true).apply { friendCard!!.url = "https://offline.example.test/do-not-dispatch" }
        val timeline = timeline(status); val card = innerCard(friendRow(timeline, status.id), link = true)
        assertTrue(card.performClick())
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals("friend_card", intent.getStringExtra("detail_route"))
        assertEquals("card-17", intent.getBundleExtra("detail_arguments")!!.getString("card_id"))
        assertSame(card, rom.view); assertFalse(intent.hasExtra("url")); assertEquals(1, rom.calls)
    }
    @Test fun privateOuterQuoteCannotAuthorizeFriendCardCaptureAndUnavailableDoesNotNavigate() {
        val nested = friendPost(link = true)
        val outer = quoted(post("private-outer"), nested).apply { visibility = StatusPrivacy.PRIVATE }
        val timeline = timeline(outer)
        val row = friendRow(timeline, outer.id, quote = true)
        assertTrue(innerCard(row, link = true).performClick())
        assertEquals(0, rom.calls); assertNull(shadowOf(activity).nextStartedActivity)
        activity.fragmentManager.executePendingTransactions()
        assertTrue(activity.fragmentManager.fragments.any { it is FriendCardDetailFragment })
        nested.friendCard!!.available = false
        val unavailable = FriendCardStatusDisplayItem(outer.id, StatusDisplayItem.NoOpCallbacks(activity), activity, nested, account, false)
        row.bind(unavailable)
        assertFalse(innerCard(row, link = true).isClickable)
        row.onClick(); assertEquals(0, rom.calls); assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun forceHiddenFriendCardFallsBackWithoutCapturingAndPublicReblogAncestorRechecksPrivacy() {
        val status = friendPost()
        val holder = FriendCardStatusDisplayItem.Holder(activity, FrameLayout(activity))
        holder.bind(FriendCardStatusDisplayItem(status.id, StatusDisplayItem.NoOpCallbacks(activity), activity, status, account, true))
        timeline(post("host-placeholder"))
        val frame = activity.findViewById<FrameLayout>(me.grishka.appkit.R.id.fragment_wrap)
        frame.addView(holder.itemView); layout(frame)
        assertTrue(innerCard(holder).performClick())
        assertEquals(0, rom.calls); assertNull(shadowOf(activity).nextStartedActivity)
        // Builder keeps the visibility owner through the actual reblog path.
        val boost = post("boost-card").apply { reblog = status }
        val timeline = timeline(boost); val card = innerCard(friendRow(timeline, boost.id))
        boost.visibility = StatusPrivacy.DIRECT
        assertTrue(card.performClick()); assertEquals(0, rom.calls)
    }
    @Test fun unavailableOrReplacedLiveFriendProjectionCannotUseOldReturnSnapshot() {
        val status = friendPost(); val timeline = timeline(status); val card = innerCard(friendRow(timeline, status.id))
        assertTrue(card.performClick()); assertNotNull(shadowOf(activity).nextStartedActivity)
        val target = target("card-17")
        status.friendCard!!.available = false // No rebind: the live server projection changed in place.
        SeamlessTransitions.beforeFinish(target)
        assertFalse(rom.lastOverride!!.containsKey(SharedViewRom.BOUNDS))
        assertEquals(SharedViewRom.FADE, rom.lastOverride!!.getInt(SharedViewRom.BACK)); assertEquals(1, rom.calls)
        status.friendCard!!.available = true
        assertTrue(card.performClick()); assertNotNull(shadowOf(activity).nextStartedActivity)
        val nextTarget = target("card-17")
        status.friendCard!!.card.state = "archived"
        SeamlessTransitions.beforeFinish(nextTarget)
        assertFalse(rom.lastOverride!!.containsKey(SharedViewRom.BOUNDS)); assertEquals(2, rom.calls)
    }
    @Test fun quoteAncestorReplacingSameIdObjectInvalidatesFriendReturnBinding() {
        val nested = friendPost(link = true); val outer = quoted(post("outer"), nested)
        val timeline = timeline(outer); val card = innerCard(friendRow(timeline, outer.id, quote = true), link = true)
        assertTrue(card.performClick()); assertNotNull(shadowOf(activity).nextStartedActivity)
        val target = target("card-17")
        outer.quote.quotedStatus = friendPost(link = true) // Same ids, different authorised resource instance.
        SeamlessTransitions.beforeFinish(target)
        assertFalse(rom.lastOverride!!.containsKey(SharedViewRom.BOUNDS))
        assertEquals(SharedViewRom.FADE, rom.lastOverride!!.getInt(SharedViewRom.BACK)); assertEquals(1, rom.calls)
    }
    @Test fun postFriendRowProjectionFingerprintRejectsVersionOrPublicFieldChange() {
        val status = friendPost(); val timeline = timeline(status)
        val source = SeamlessSource.post(activity, timeline.recycler(), account, status, status.id, status, timeline.getDisplayItems())!!
        assertTrue(source.valid())
        status.friendCard!!.card.publicFields = listOf("gender")
        assertFalse(source.valid()); assertNull(source.snapshot())
        status.friendCard!!.card.publicFields = emptyList()
        status.friendCard!!.card.version++
        assertFalse(source.valid()); assertNull(source.snapshot()); assertEquals(0, rom.calls)
    }
    @Test fun stillAvailablePresentationChangeInvalidatesCardAndPostSourceWithoutRecapture() {
        val status = friendPost(); val timeline = timeline(status)
        val card = innerCard(friendRow(timeline, status.id))
        val source = SeamlessSource.post(activity, timeline.recycler(), account, status, status.id, status, timeline.getDisplayItems())!!
        assertTrue(card.performClick()); assertNotNull(shadowOf(activity).nextStartedActivity)
        val target = target("card-17")
        status.friendCard!!.mode = "link" // Both modes are public/available; old publication pixels are not this source.
        assertEquals(FriendCardStatusDisplayItem.Presentation.LINK, FriendCardStatusDisplayItem.presentation(status.friendCard))
        assertFalse(source.valid()); assertNull(source.snapshot())
        SeamlessTransitions.beforeFinish(target)
        assertFalse(rom.lastOverride!!.containsKey(SharedViewRom.BOUNDS))
        assertEquals(SharedViewRom.FADE, rom.lastOverride!!.getInt(SharedViewRom.BACK)); assertEquals(1, rom.calls)
    }
    @Test fun boundedRouteArgsAndNonExportedHostRejectArbitraryOrInvalidEntry() {
        val info = activity.packageManager.getActivityInfo(ComponentName(activity, SeamlessDetailActivity::class.java), 0)
        assertFalse(info.exported)
        assertThrows(IllegalArgumentException::class.java) { SeamlessDetailActivity.intent(activity, "any.Class", Bundle()) }
        val bad = createHost(SeamlessDetailActivity.intent(activity, SeamlessDetailActivity.DIAPER, Bundle().apply {
            putString("account", account); putInt("diaper_id", 0)
        }))
        assertTrue(bad.isFinishing); assertTrue(bad.fragmentManager.fragments.isEmpty())
        val card = plainCard("17")
        val tooLarge = Bundle().apply { putString("account", account); putInt("diaper_id", 17); putByteArray("large", ByteArray(128 * 1024)) }
        assertFalse(SeamlessTransitions.openDiaper(activity, card, account, 17, tooLarge)); assertEquals(0, rom.calls)
    }
    @Test fun diaperRebindReturnsWithFadeRatherThanFlyingToReplacementProduct() {
        val (_, list) = diapers(); val card = list.findViewHolderForAdapterPosition(0)!!.itemView
        card.performClick(); assertNotNull(shadowOf(activity).nextStartedActivity)
        val target = target("17")
        SeamlessTransitions.bindCard(card, account, "18", true)
        SeamlessTransitions.beforeFinish(target)
        assertFalse(rom.lastOverride!!.containsKey(SharedViewRom.BOUNDS)); assertEquals(SharedViewRom.FADE, rom.lastOverride!!.getInt(SharedViewRom.BACK))
        assertEquals(1, rom.calls)
    }
    @Test fun newRouteHostStillRejectsReplacedAccountAndLockedStateUsesExistingShield() {
        val args = Bundle().apply { putString("account", account); putString("card_id", "card-17") }
        field(AccountSessionManager::class.java, "lastActiveAccountID").set(AccountSessionManager.getInstance(), "other")
        val rejected = createHost(SeamlessDetailActivity.intent(activity, SeamlessDetailActivity.FRIEND_CARD, args))
        assertTrue(rejected.isFinishing); assertTrue(rejected.fragmentManager.fragments.isEmpty())
        field(AccountSessionManager::class.java, "lastActiveAccountID").set(AccountSessionManager.getInstance(), account)
        val tracker = field(AppSecurity::class.java, "trackerInstance").get(null)
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val dispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()
        val lockedTracker = org.joinmastodon.android.security.AuthTracker(org.joinmastodon.android.security.ui.FakeSecurityRepository(
            org.joinmastodon.android.security.data.SecurityResult.Success(org.joinmastodon.android.security.ui.securityState(org.joinmastodon.android.security.domain.LockMethod.Pin))))
        lockedTracker.onAppCreate()
        gate = org.joinmastodon.android.security.AppGatekeeper(org.robolectric.RuntimeEnvironment.getApplication(), lockedTracker, dispatcher, dispatcher, {})
        field(AppSecurity::class.java, "trackerInstance").set(null, lockedTracker)
        field(AppSecurity::class.java, "gatekeeper").set(null, gate)
        try {
            val locked = createHost(SeamlessDetailActivity.intent(activity, SeamlessDetailActivity.FRIEND_CARD, args))
            assertTrue(AppSecurity.isLocked()); assertTrue(locked.fragmentManager.fragments.isEmpty())
            assertNotNull(locked.window.decorView.findViewWithTag<View>("abdl_security_shield"))
            assertNull(pending()); assertEquals(0, rom.calls)
        } finally { field(AppSecurity::class.java, "trackerInstance").set(null, tracker) }
    }
    @Test fun canceledPendingRouteReleasesWatchdogAndDoesNotRetainPixelsInArguments() {
        val card = plainCard("17"); val args = Bundle().apply { putString("account", account); putInt("diaper_id", 17) }
        assertTrue(SeamlessTransitions.openDiaper(activity, card, account, 17, args))
        assertNotNull(pending()); assertTrue(watchdogScheduled())
        assertEquals(setOf("account", "diaper_id"), shadowOf(activity).nextStartedActivity.getBundleExtra("detail_arguments")!!.keySet())
        SeamlessTransitions.clear()
        assertNull(pending()); assertFalse(watchdogScheduled()); assertEquals(0, SeamlessTransitions.retainedPixels())
        assertTrue(SeamlessTransitions.openDiaper(activity, card, account, 17, args)); assertEquals(2, rom.calls)
    }
}
