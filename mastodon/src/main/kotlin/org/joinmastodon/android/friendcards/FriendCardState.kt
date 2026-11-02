package org.joinmastodon.android.friendcards

import org.joinmastodon.android.R
import com.google.gson.GsonBuilder
import org.joinmastodon.android.api.requests.friendcards.FriendCardInput
import org.joinmastodon.android.api.requests.friendcards.FriendCardResponses
import org.joinmastodon.android.model.FriendCard
import java.util.UUID

/** Pure editor rules. Placeholders never enter the input and optional fields stay optional. */
object FriendCardRules {
    val gson = GsonBuilder().serializeNulls().create()
    // Stable wire keys and their order never depend on the display language.
    val fields = linkedMapOf("gender" to R.string.fc_field_gender, "age" to R.string.fc_field_age, "zodiac" to R.string.fc_field_zodiac, "region" to R.string.fc_field_region, "school" to R.string.fc_field_school, "mbti" to R.string.fc_field_mbti, "interaction_habits" to R.string.fc_field_habits, "interests" to R.string.fc_field_interests, "games" to R.string.fc_field_games, "diapers" to R.string.fc_field_diapers, "looking_for" to R.string.fc_field_looking_for, "boundaries" to R.string.fc_field_boundaries)
    @androidx.annotation.StringRes fun fieldLabel(key: String): Int = fields.getValue(key)
    fun copy(input: FriendCardInput): FriendCardInput = gson.fromJson(gson.toJson(input), FriendCardInput::class.java)
    fun length(value: String) = value.codePointCount(0, value.length)
    fun validate(input: FriendCardInput): String? {
        val strings = listOf(Triple(input.title, 40, R.string.fc_field_title), Triple(input.gender, 24, fieldLabel("gender")), Triple(input.age, 12, fieldLabel("age")), Triple(input.zodiac, 12, fieldLabel("zodiac")), Triple(input.region, 60, fieldLabel("region")), Triple(input.school, 60, fieldLabel("school")), Triple(input.mbti, 16, fieldLabel("mbti")), Triple(input.interactionHabits, 120, fieldLabel("interaction_habits")), Triple(input.lookingFor, 300, fieldLabel("looking_for")), Triple(input.boundaries, 200, fieldLabel("boundaries")))
        if (input.title.isNullOrBlank()) return FriendCardText.get(R.string.fc_title_required)
        if (input.lookingFor.isNullOrBlank()) return FriendCardText.get(R.string.fc_looking_for_required)
        strings.forEach { (value, max, label) -> if (length(value.orEmpty()) > max) return FriendCardText.get(R.string.fc_field_length, FriendCardText.get(label), max) }
        if (input.interests.size > 12 || input.interests.any { it.isBlank() || length(it) > 16 }) return FriendCardText.get(R.string.fc_interests_invalid)
        if (input.games.size > 8 || input.games.any { it.isBlank() || length(it) > 30 }) return FriendCardText.get(R.string.fc_games_invalid)
        if (input.diaperIds.size > 8 || input.diaperIds.any { it <= 0 } || input.diaperIds.distinct().size != input.diaperIds.size) return FriendCardText.get(R.string.fc_diapers_invalid)
        FriendCardSocialRules.validate(input.socialAccounts)?.let { return it }
        if (input.publicFields.any { it !in fields }) return FriendCardText.get(R.string.fc_public_fields_invalid)
        if (!input.coverFocusX.isFinite() || !input.coverFocusY.isFinite() || input.coverFocusX !in 0f..1f || input.coverFocusY !in 0f..1f) return FriendCardText.get(R.string.fc_cover_focus_invalid)
        return null
    }
    data class TagResult(val values: List<String>, val remainingInput: String, val error: String? = null)
    fun addTag(values: List<String>, raw: String, maximum: Int, maxLength: Int): TagResult {
        val tag = raw.trim()
        val error = when {
            tag.isEmpty() -> FriendCardText.get(R.string.fc_tag_required)
            length(tag) > maxLength -> FriendCardText.get(R.string.fc_tag_length, maxLength)
            values.any { it.trim().equals(tag, ignoreCase = true) } -> FriendCardText.get(R.string.fc_tag_duplicate)
            values.size >= maximum -> FriendCardText.get(R.string.fc_tag_limit, maximum)
            else -> null
        }
        return if (error == null) TagResult(values + tag, "") else TagResult(values, raw, error)
    }
    fun gameName(value: String, games: List<FriendCardResponses.Game>, context: android.content.Context = org.joinmastodon.android.MastodonApp.context): String =
        FriendCardGames.gameName(value, games.firstOrNull { it.id == value }?.name, context)
    fun gameKey(value: String, games: List<FriendCardResponses.Game>): String {
        val text = value.trim()
        return games.firstOrNull { it.id.equals(text, true) || it.name.equals(text, true) }?.id ?: FriendCardGames.gameKey(text)
    }
    fun addGame(values: List<String>, raw: String, games: List<FriendCardResponses.Game>): TagResult = addTag(values.map { gameKey(it, games) }.distinct(), gameKey(raw, games), 8, 30)
    fun publicPreview(input: FriendCardInput, diapers: List<FriendCard.Diaper>, coverUrl: String?): FriendCard = FriendCard().apply {
        schemaVersion = 1; id = "preview"; title = input.title; publicFields = input.publicFields.toList()
        fun visible(key: String, value: String?) = if (key in input.publicFields) value.orEmpty() else ""
        gender = visible("gender", input.gender); age = visible("age", input.age); zodiac = visible("zodiac", input.zodiac)
        region = visible("region", input.region); school = visible("school", input.school); mbti = visible("mbti", input.mbti)
        interactionHabits = visible("interaction_habits", input.interactionHabits)
        interests = if ("interests" in input.publicFields) input.interests.toList() else emptyList()
        this.games = if ("games" in input.publicFields) input.games.toList() else emptyList()
        this.diapers = if ("diapers" in input.publicFields) diapers.filter { it.id in input.diaperIds } else emptyList()
        lookingFor = visible("looking_for", input.lookingFor); boundaries = visible("boundaries", input.boundaries)
        socialAccounts = input.socialAccounts.orEmpty().filter { it.isPublic }.map(FriendCardSocialRules::copy)
        this.coverUrl = coverUrl; coverFocusX = input.coverFocusX; coverFocusY = input.coverFocusY
    }
    fun legacyText(card: FriendCard, games: List<FriendCardResponses.Game>, context: android.content.Context = org.joinmastodon.android.MastodonApp.context): String = buildList {
        add(context.getString(R.string.fc_legacy_heading, card.title))
        fun line(key: String, value: String?) { if (card.isPublic(key) && !value.isNullOrBlank()) add(context.getString(R.string.fc_legacy_line, context.getString(fieldLabel(key)), value)) }
        line("gender", card.gender); line("age", card.age); line("zodiac", card.zodiac); line("region", card.region); line("school", card.school); line("mbti", card.mbti); line("interaction_habits", card.interactionHabits)
        line("interests", card.interests.orEmpty().joinToString(context.getString(R.string.fc_list_separator))); line("games", card.games.orEmpty().joinToString(context.getString(R.string.fc_list_separator)) { gameName(it, games, context) }); line("diapers", card.diapers.orEmpty().joinToString(context.getString(R.string.fc_list_separator)) { it.name.orEmpty() })
        line("looking_for", card.lookingFor); line("boundaries", card.boundaries)
        card.socialAccounts.orEmpty().filter { it.isPublic }.forEach { add(context.getString(R.string.fc_legacy_line, FriendCardSocialRules.platformName(it, context), it.account.orEmpty())) }
    }.joinToString("\n")
}

/** Durable envelope, including the exact operation input, not a mutable form reference. */
data class FriendCardDraft(
    val account: String,
    val cardId: String? = null,
    val draftId: String = UUID.randomUUID().toString(),
    val input: FriendCardInput = FriendCardInput(),
    val version: Int = 0,
    val expectedCurrentId: String? = null,
    val currentLoaded: Boolean = false,
    val initialized: Boolean = cardId == null,
    val stage: Int = 0,
    val coverUrl: String? = null,
    val diapers: List<FriendCard.Diaper> = emptyList(),
    val pending: FriendCardOperation? = null,
    val dirty: Boolean = false,
)
data class FriendCardOperation(
    val operationId: String,
    val account: String,
    val draftId: String,
    val cardId: String?,
    val expectedVersion: Int,
    val expectedCurrentId: String?,
    val inputJson: String,
    val rejected: Boolean = false,
) {
    fun input(): FriendCardInput = FriendCardRules.gson.fromJson(inputJson, FriendCardInput::class.java)
    companion object {
        fun snapshot(draft: FriendCardDraft) = FriendCardOperation(UUID.randomUUID().toString(), draft.account, draft.draftId, draft.cardId, draft.version, draft.expectedCurrentId, FriendCardRules.gson.toJson(draft.input))
    }
}

/** Cursor pages accept only their generation/cursor; duplicate IDs never move an existing card. */
class FriendCardPages {
    data class Ticket(val generation: Int, val cursor: String?)
    var generation = 0; private set
    var cards: List<FriendCard> = emptyList(); private set
    var nextCursor: String? = null; private set
    var loaded = false; private set
    var loading = false; private set
    var error: String? = null; private set
    private val seenCursors = mutableSetOf<String>()
    fun reset() { generation++; cards = emptyList(); nextCursor = null; loaded = false; loading = false; error = null; seenCursors.clear() }
    fun begin(): Ticket? {
        if (loading || (loaded && nextCursor == null)) return null
        loading = true; error = null
        generation++
        return Ticket(generation, nextCursor)
    }
    fun accept(ticket: Ticket, values: List<FriendCard>, next: String?): Boolean {
        if (ticket.generation != generation || ticket.cursor != nextCursor || !loading) return false
        if (values.any { !it.isSupported }) { loading = false; error = FriendCardText.get(R.string.fc_unsupported_format); return false }
        cards = (cards + values).distinctBy { it.id }
        ticket.cursor?.let { seenCursors.add(it) }
        nextCursor = next?.takeIf { it.isNotBlank() && it != ticket.cursor && it !in seenCursors }
        loaded = true; loading = false; error = null; return true
    }
    fun fail(ticket: Ticket, message: String) { if (ticket.generation == generation && ticket.cursor == nextCursor) { loading = false; error = message } }
    fun interrupt() { generation++; loading = false }
    fun removeOwner(owner: String) { cards = cards.filterNot { it.ownerId == owner } }
}

/** Search generation and ordered selection are deliberately independent of visible search results. */
class FriendCardDiaperSelection(val account: String, val draftId: String, selected: List<Int> = emptyList()) {
    private val selected = LinkedHashSet(selected.filter { it > 0 }.take(8))
    val ids: List<Int> get() = selected.toList()
    var query = ""; private set
    var generation = 0; private set
    var nextPage = 1; private set
    var hasMore = true; private set
    var loading = false; private set
    var results: List<FriendCard.Diaper> = emptyList(); private set
    var error: String? = null; private set
    val known = linkedMapOf<Int, FriendCard.Diaper>()
    data class Ticket(val generation: Int, val page: Int, val query: String)
    fun search(value: String) { generation++; query = value; nextPage = 1; results = emptyList(); hasMore = true; loading = false; error = null }
    fun begin(): Ticket? { if (loading || !hasMore) return null; loading = true; error = null; generation++; return Ticket(generation, nextPage, query) }
    fun accept(ticket: Ticket, items: List<FriendCard.Diaper>, total: Int): Boolean {
        if (ticket.generation != generation || ticket.page != nextPage || ticket.query != query || !loading) return false
        val previousSize = results.size
        results = (results + items).distinctBy { it.id }; items.forEach { known[it.id] = it }
        nextPage++; hasMore = items.isNotEmpty() && results.size < total && results.size > previousSize
        loading = false; error = null; return true
    }
    fun fail(ticket: Ticket, message: String) { if (ticket.generation == generation && ticket.query == query && ticket.page == nextPage) { loading = false; error = message } }
    fun interrupt() { generation++; loading = false }
    fun toggle(id: Int): String? {
        if (selected.remove(id)) return null
        if (selected.size >= 8) return FriendCardText.get(R.string.fc_diapers_limit)
        if (id <= 0) return FriendCardText.get(R.string.fc_product_invalid)
        selected.add(id); return null
    }
    fun resultMatches(account: String?, draft: String?) = this.account == account && draftId == draft
}
