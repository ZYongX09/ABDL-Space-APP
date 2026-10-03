package org.joinmastodon.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendUniverseHomeFeedContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))

	@Test
	fun aggregateFriendRequestsUseDedicatedDisplayItem() {
		val factory = source("src/main/java/org/joinmastodon/android/ui/displayitems/StatusDisplayItem.java")
		assertTrue(factory.contains("Status statusForContent=status.getContentStatus()"))
		assertTrue(factory.contains("if(statusForContent.friendRequest!=null)"))
		assertTrue(factory.contains("new FriendRequestStatusDisplayItem(parentID, callbacks, context, statusForContent, accountID)"))
		assertTrue(factory.contains("case FRIEND_REQUEST_ITEM -> new FriendRequestStatusDisplayItem.Holder"))
		assertTrue(factory.contains("EMOJI_REACTIONS,\n\t\t\tFRIEND_REQUEST_ITEM"))
	}

	@Test
	fun friendCardRestoresFinalLayoutAndActions() {
		val card = source("src/main/java/org/joinmastodon/android/ui/displayitems/FriendRequestStatusDisplayItem.java")
		val layout = source("src/main/res/layout/item_friend_request_timeline.xml")
		assertTrue(card.contains("return Type.FRIEND_REQUEST_ITEM"))
		assertTrue(card.contains("FriendRequestDetailFragment.class"))
		assertTrue(card.contains("chatIntent.putExtra(\"navigate_to\", \"chat\")"))
		assertTrue(card.contains("new DeleteFriendRequest(fr.id)"))
		assertTrue(card.contains("FriendRequestReportFragment.class"))
		for (id in listOf(
			"fr_timeline_avatar",
			"fr_timeline_username",
			"fr_timeline_chip",
			"fr_timeline_title",
			"fr_timeline_desc",
			"fr_timeline_basic_info",
			"fr_timeline_meta_container",
			"fr_timeline_publish_time",
			"fr_timeline_cta",
			"fr_timeline_menu",
		)) {
			assertTrue("missing $id", layout.contains("android:id=\"@+id/$id\""))
		}
	}

	@Test
	fun standaloneFriendTimelineIsMigratedOutButRemainsParseCompatible() {
		val home = source("src/main/java/org/joinmastodon/android/fragments/HomeTabFragment.java")
		val definition = source("src/main/java/org/joinmastodon/android/model/TimelineDefinition.java")
		val defaultBlock = definition.substringAfter("private static final List<TimelineDefinition> DEFAULT_TIMELINES").substringBefore("private static final List<TimelineDefinition> ALL_TIMELINES")
		val allBlock = definition.substringAfter("private static final List<TimelineDefinition> ALL_TIMELINES")

		assertTrue(definition.contains("FRIEND_UNIVERSE,"))
		assertTrue(definition.contains("case FRIEND_UNIVERSE -> new FriendRequestListFragment()"))
		assertFalse(defaultBlock.contains("FRIEND_UNIVERSE_TIMELINE"))
		assertFalse(allBlock.contains("FRIEND_UNIVERSE_TIMELINE"))
		assertTrue(home.contains("t.getType()==TimelineDefinition.TimelineType.FRIEND_UNIVERSE"))
		assertTrue(home.contains("t.getType()!=TimelineDefinition.TimelineType.FRIEND_UNIVERSE"))
		assertFalse(home.contains("newList.add(TimelineDefinition.FRIEND_UNIVERSE_TIMELINE.copy())"))
	}

	@Test
	fun restoredPageIndexIsClampedAfterTimelineMigration() {
		val home = source("src/main/java/org/joinmastodon/android/fragments/HomeTabFragment.java")
		assertTrue(home.contains("if (savedInstanceState == null || count==0) return"))
		assertTrue(home.contains("Math.max(0, Math.min(savedInstanceState.getInt(\"selectedTab\"), count-1))"))
	}

	private fun source(path: String) = File(projectDir, path).readText()
}
