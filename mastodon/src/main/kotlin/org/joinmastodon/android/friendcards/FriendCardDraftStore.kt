package org.joinmastodon.android.friendcards

import org.joinmastodon.android.R
import android.content.Context
import java.io.IOException
import java.security.MessageDigest

/** The commit must succeed BEFORE any network mutation is dispatched. */
interface FriendCardDraftStorage {
    fun read(key: String): String?
    fun commit(key: String, value: String?): Boolean
}
class FriendCardDraftStore(private val storage: FriendCardDraftStorage) {
    constructor(context: Context) : this(object : FriendCardDraftStorage {
        private val prefs = context.applicationContext.getSharedPreferences("friend_card_drafts_v1", Context.MODE_PRIVATE)
        override fun read(key: String) = prefs.getString(key, null)
        override fun commit(key: String, value: String?) = prefs.edit().let { if (value == null) it.remove(key) else it.putString(key, value) }.commit()
    })
    companion object { private val lock = Any() }
    private fun key(account: String, cardId: String?) = MessageDigest.getInstance("SHA-256").digest("$account\u0000${cardId ?: "new"}".toByteArray()).joinToString("") { "%02x".format(it) }
    fun load(account: String, cardId: String?): FriendCardDraft? = synchronized(lock) {
        val raw = storage.read(key(account, cardId)) ?: return@synchronized null
        val draft = try { FriendCardRules.gson.fromJson(raw, FriendCardDraft::class.java) } catch (e: Exception) { throw IOException(FriendCardText.get(R.string.fc_draft_corrupt), e) }
        if (draft == null || draft.account != account || draft.cardId != cardId || draft.draftId.isNullOrBlank() || draft.input == null || (draft.pending != null && (draft.pending.account != account || draft.pending.draftId != draft.draftId || draft.pending.cardId != cardId))) throw IOException(FriendCardText.get(R.string.fc_draft_owner_mismatch))
        draft
    }
    fun save(draft: FriendCardDraft): FriendCardDraft = synchronized(lock) {
        val previous = load(draft.account, draft.cardId)
        if (previous != null && previous.draftId != draft.draftId) throw IOException(FriendCardText.get(R.string.fc_draft_changed_elsewhere))
        if (previous?.pending != null && (FriendCardRules.gson.toJson(previous.pending) != FriendCardRules.gson.toJson(draft.pending) || !samePendingInput(previous.pending.inputJson, draft.input))) throw IOException(FriendCardText.get(R.string.fc_pending_input_locked))
        persist(draft); draft
    }
    fun prepare(draft: FriendCardDraft): FriendCardDraft = synchronized(lock) {
        val saved = load(draft.account, draft.cardId)
        if (saved != null && saved.draftId != draft.draftId) throw IOException(FriendCardText.get(R.string.fc_draft_replaced))
        saved?.pending?.let { return@synchronized saved }
        if (!draft.initialized || (draft.cardId == null && !draft.currentLoaded) || (draft.cardId != null && draft.version <= 0)) throw IOException(FriendCardText.get(R.string.fc_reload_current_version))
        FriendCardRules.validate(draft.input)?.let { throw IOException(it) }
        val ready = draft.copy(pending = FriendCardOperation.snapshot(draft))
        persist(ready); ready
    }
    fun reject(operation: FriendCardOperation): FriendCardDraft? = synchronized(lock) {
        val draft = load(operation.account, operation.cardId) ?: return@synchronized null
        if (draft.pending?.operationId != operation.operationId) return@synchronized draft
        draft.copy(pending = draft.pending.copy(rejected = true)).also { persist(it) }
    }
    /** Only a definitive server rejection permits unlocking/editing; timeouts retain the snapshot. */
    fun unlockRejected(draft: FriendCardDraft): FriendCardDraft = synchronized(lock) {
        val saved = load(draft.account, draft.cardId) ?: draft
        if (saved.pending?.rejected != true) throw IOException(FriendCardText.get(R.string.fc_operation_unknown))
        saved.copy(pending = null, currentLoaded = saved.cardId != null, version = if (saved.cardId != null) 0 else saved.version).also { persist(it) }
    }
    fun complete(operation: FriendCardOperation): Boolean = synchronized(lock) {
        val draft = load(operation.account, operation.cardId) ?: return@synchronized true
        if (draft.draftId != operation.draftId || draft.pending?.operationId != operation.operationId) return@synchronized false
        if (!storage.commit(key(operation.account, operation.cardId), null)) throw IOException(FriendCardText.get(R.string.fc_draft_cleanup_failed))
        true
    }
    fun cover(account: String, cardId: String?, draftId: String, uploadId: String, url: String): FriendCardDraft? = synchronized(lock) {
        val draft = load(account, cardId) ?: return@synchronized null
        if (draft.draftId != draftId || draft.pending != null) return@synchronized null
        val input = FriendCardRules.copy(draft.input).apply { coverUploadId = uploadId }
        draft.copy(input = input, coverUrl = url, dirty = true).also { persist(it) }
    }
    fun discard(draft: FriendCardDraft) = synchronized(lock) {
        val previous = load(draft.account, draft.cardId)
        if (previous?.pending != null) throw IOException(FriendCardText.get(R.string.fc_pending_discard_locked))
        if (previous != null && previous.draftId != draft.draftId) throw IOException(FriendCardText.get(R.string.fc_draft_replaced_short))
        if (!storage.commit(key(draft.account, draft.cardId), null)) throw IOException(FriendCardText.get(R.string.fc_draft_delete_failed))
    }
    private fun samePendingInput(snapshot: String, input: org.joinmastodon.android.api.requests.friendcards.FriendCardInput): Boolean {
        fun normalized(value: com.google.gson.JsonElement): com.google.gson.JsonObject {
            val objectValue = value.asJsonObject
            if (objectValue.get("social_accounts")?.isJsonNull == true) objectValue.remove("social_accounts")
            return objectValue
        }
        return try {
            normalized(com.google.gson.JsonParser.parseString(snapshot)) == normalized(com.google.gson.JsonParser.parseString(FriendCardRules.gson.toJson(input)))
        } catch (_: RuntimeException) { false }
    }
    private fun persist(draft: FriendCardDraft) {
        if (!storage.commit(key(draft.account, draft.cardId), FriendCardRules.gson.toJson(draft))) throw IOException(FriendCardText.get(R.string.fc_draft_commit_failed))
    }
}
