package org.joinmastodon.android.ui.compose.friendcards

import org.joinmastodon.android.model.FriendCard

/** Local destinations only: changing these never adds a navigation-stack entry. */
enum class FriendCardHubTab { Plaza, MyCards }
enum class FriendCardHubFilter(val label: String) { Recommended("推荐"), SameCity("同城"), Movies("电影"), Games("游戏") }

/** A footer/section keeps its preceding card anchor and row delta, not just a first-page index. */
data class FriendCardHubPosition(val index: Int = 0, val offset: Int = 0, val cardId: String? = null, val anchorDelta: Int = 0)

data class FriendCardHubState(
    val tab: FriendCardHubTab,
    val card: FriendCard?,
    val loaded: Boolean,
    val loading: Boolean,
    val busy: Boolean,
    val error: String?,
    val history: List<FriendCard>,
    val historyLoaded: Boolean,
    val historyLoading: Boolean,
    val historyError: String?,
    val historyCursor: String?,
    val cards: List<FriendCard>,
    val feedLoaded: Boolean,
    val feedLoading: Boolean,
    val feedError: String?,
    val feedCursor: String?,
    val query: String,
    val filter: FriendCardHubFilter,
    val myPosition: FriendCardHubPosition,
    val feedPosition: FriendCardHubPosition,
    val restoringMy: Boolean,
    val restoringFeed: Boolean,
    val refreshToken: Int,
) {
    /** /me and history are separate snapshots; never give their overlap duplicate LazyColumn keys. */
    val historyCards: List<FriendCard> get() = history.filterNot { it.id == card?.id }
}

/** All callbacks use live Fragment state, not a mutable DTO captured by a stale composition. */
data class FriendCardHubActions(
    val back: () -> Unit,
    val tab: (FriendCardHubTab) -> Unit,
    val refresh: () -> Unit,
    val query: (String) -> Unit,
    val filter: (FriendCardHubFilter) -> Unit,
    val create: () -> Unit,
    val edit: (FriendCard) -> Unit,
    val detail: (FriendCard) -> Unit,
    val profile: (FriendCard) -> Unit,
    val state: (FriendCard) -> Unit,
    val post: (FriendCard) -> Unit,
    val delete: (FriendCard) -> Unit,
    val history: () -> Unit,
    val next: () -> Unit,
    val myPosition: (FriendCardHubPosition) -> Unit,
    val feedPosition: (FriendCardHubPosition) -> Unit,
)

/** Search is deliberately restricted to the four public fields advertised by the hub. */
object FriendCardHubFilters {
    fun publicRegion(card: FriendCard?): String? = card?.takeIf { it.isSupported && it.isPublic("region") }
        ?.region?.trim()?.takeIf(String::isNotEmpty)

    private fun publicText(card: FriendCard): List<String> = buildList {
        add(card.title.orEmpty())
        if (card.isPublic("interests")) addAll(card.interests.orEmpty())
        if (card.isPublic("looking_for")) add(card.lookingFor.orEmpty())
    }

    fun matches(card: FriendCard, query: String, filter: FriendCardHubFilter, ownPublicRegion: String?): Boolean {
        if (!card.isSupported) return false
        val text = publicText(card)
        val queryParts = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        val search = text + listOfNotNull(publicRegion(card))
        if (queryParts.any { part -> search.none { it.contains(part, ignoreCase = true) } }) return false
        return when (filter) {
            FriendCardHubFilter.Recommended -> true
            FriendCardHubFilter.SameCity -> !ownPublicRegion.isNullOrBlank() &&
                publicRegion(card)?.equals(ownPublicRegion.trim(), ignoreCase = true) == true
            FriendCardHubFilter.Movies -> text.any { value ->
                listOf("电影", "影视", "movie", "cinema", "film").any { value.contains(it, ignoreCase = true) }
            }
            FriendCardHubFilter.Games -> (card.isPublic("games") && !card.games.isNullOrEmpty()) || text.any { value ->
                listOf("游戏", "开黑", "game", "gaming").any { value.contains(it, ignoreCase = true) }
            }
        }
    }
}

fun friendCardHubStateName(state: String?): String = when (state) {
    "active" -> "展示中"
    "paused" -> "已暂停"
    "archived" -> "已归档"
    else -> "${state ?: "状态未知"} · 以服务端审核状态为准"
}
