package org.joinmastodon.android.friendcards

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
        val draft = try { FriendCardRules.gson.fromJson(raw, FriendCardDraft::class.java) } catch (e: Exception) { throw IOException("草稿无法读取；为避免丢失内容，未覆盖草稿", e) }
        if (draft == null || draft.account != account || draft.cardId != cardId || draft.draftId.isNullOrBlank() || draft.input == null || (draft.pending != null && (draft.pending.account != account || draft.pending.draftId != draft.draftId || draft.pending.cardId != cardId))) throw IOException("草稿归属不匹配，未覆盖草稿")
        draft
    }
    fun save(draft: FriendCardDraft): FriendCardDraft = synchronized(lock) {
        val previous = load(draft.account, draft.cardId)
        if (previous != null && previous.draftId != draft.draftId) throw IOException("草稿已在另一页面更新，请重新进入")
        if (previous?.pending != null && (FriendCardRules.gson.toJson(previous.pending) != FriendCardRules.gson.toJson(draft.pending) || previous.pending.inputJson != FriendCardRules.gson.toJson(draft.input))) throw IOException("请先处理尚未确认结果的发布操作，不能修改其输入")
        persist(draft); draft
    }
    fun prepare(draft: FriendCardDraft): FriendCardDraft = synchronized(lock) {
        val saved = load(draft.account, draft.cardId)
        if (saved != null && saved.draftId != draft.draftId) throw IOException("草稿已更换，请重新进入")
        saved?.pending?.let { return@synchronized saved }
        if (!draft.initialized || (draft.cardId == null && !draft.currentLoaded) || (draft.cardId != null && draft.version <= 0)) throw IOException("请先重试读取当前卡和最新版本")
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
        if (saved.pending?.rejected != true) throw IOException("结果尚不确定，只能用同一操作重试")
        saved.copy(pending = null, currentLoaded = saved.cardId != null, version = if (saved.cardId != null) 0 else saved.version).also { persist(it) }
    }
    fun complete(operation: FriendCardOperation): Boolean = synchronized(lock) {
        val draft = load(operation.account, operation.cardId) ?: return@synchronized true
        if (draft.draftId != operation.draftId || draft.pending?.operationId != operation.operationId) return@synchronized false
        if (!storage.commit(key(operation.account, operation.cardId), null)) throw IOException("结果已成功，但本机草稿清理失败；重新进入后可安全重试确认")
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
        if (previous?.pending != null) throw IOException("发布结果尚未确认，不能丢弃操作快照")
        if (previous != null && previous.draftId != draft.draftId) throw IOException("草稿已更换")
        if (!storage.commit(key(draft.account, draft.cardId), null)) throw IOException("草稿删除失败，请重试")
    }
    private fun persist(draft: FriendCardDraft) {
        if (!storage.commit(key(draft.account, draft.cardId), FriendCardRules.gson.toJson(draft))) throw IOException("草稿未能保存，请释放存储空间后重试；未发送发布请求")
    }
}
