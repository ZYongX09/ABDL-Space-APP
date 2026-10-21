package org.joinmastodon.android.fragments.friendcards

import org.joinmastodon.android.ui.compose.friendcards.FriendCardHubTab

/** Compatibility route for old feed entry points; both tabs live in this same Fragment. */
class FriendCardFeedFragment : MyFriendCardsFragment() {
    override val initialTab = FriendCardHubTab.Plaza
}
