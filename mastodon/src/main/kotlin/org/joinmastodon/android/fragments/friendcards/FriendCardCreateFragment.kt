package org.joinmastodon.android.fragments.friendcards

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import me.grishka.appkit.Nav
import org.joinmastodon.android.R
import org.joinmastodon.android.api.MastodonAPIController
import org.joinmastodon.android.api.requests.friendcards.FriendCardInput
import org.joinmastodon.android.api.requests.friendcards.FriendCardRequest
import org.joinmastodon.android.api.requests.friendcards.FriendCardResponses
import org.joinmastodon.android.friendcards.*
import org.joinmastodon.android.fragments.settings.BabyVerificationFragment
import org.joinmastodon.android.model.FriendCard
import org.joinmastodon.android.ui.compose.friendcards.*

class FriendCardCreateFragment : FriendCardBaseFragment() {
    companion object { private const val DIAPERS = 6281; private const val COVER = 6282 }
    private lateinit var store: FriendCardDraftStore
    private var draft: FriendCardDraft? = null
    private var loadError: String? = null
    private var storageError: String? = null
    private var options: FriendCardResponses.Options? = null
    private var eligibilityError: String? = null
    private var currentError: String? = null
    private var checking = false
    private var busy = false
    private var preview = false
    private var completed = false
    private var uploader: FriendCardCoverUpload? = null
    private var coverBusy = false
    private var coverGeneration = 0
    private var pickerDraft: String? = null
    private var pickerRequestDraft: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val back = Runnable { leaveEditor() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setTitle(if (arguments?.getString("card_id") == null) R.string.friend_cards_create_title else R.string.friend_cards_edit_title)
        store = FriendCardDraftStore(activity)
        preview = state?.getBoolean("preview") ?: false
        pickerRequestDraft = state?.getString("cover_picker_draft")
        pickerDraft = state?.getString("diaper_picker_draft")
        restore()
    }
    private fun restore() {
        try {
            val id = arguments?.getString("card_id")
            draft = store.load(account, id) ?: FriendCardDraft(account, id).also { store.save(it) }
            loadError = null
        } catch (error: Exception) { loadError = error.message ?: "草稿读取失败，未覆盖原草稿" }
    }
    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state); state.putBoolean("preview", preview); state.putString("cover_picker_draft", pickerRequestDraft); state.putString("diaper_picker_draft", pickerDraft)
        persist()
    }
    override fun refresh() {
        removeBackCallback(back); addBackCallback(back)
        if (completed || busy) return
        cancelRequests()
        if (draft == null) { restore(); if (draft == null) { changed(); return } }
        busy = false; globalError = null
        if (storageError == null) try { store.load(account, draft!!.cardId)?.takeIf { it.draftId == draft!!.draftId }?.let { draft = it } } catch (error: Exception) { storageError = error.message }
        checking = true; options = null; eligibilityError = null; currentError = null; changed()
        execute(FriendCardRequest.options(), { result ->
            checking = false
            if (result.schemaVersion != 1 || result.verificationState !in listOf("active", "not_started", "pending", "rejected", "revoked") || result.games == null || result.interestPresets == null) eligibilityError = "创建资格或选项响应不完整，请重试"
            else { options = result; FriendCardGames.updateOptions(result.games) }
            changed()
        }, { checking = false; eligibilityError = message(it); changed() })
        val d = draft!!
        if (d.cardId == null) {
            execute(FriendCardRequest.me(), { result ->
                if (result.card != null && !result.card.isSupported) currentError = "当前卡响应不受支持，请重试"
                else if (draft?.pending == null && draft?.currentLoaded == false) updateDraft { it.copy(expectedCurrentId = result.card?.id, currentLoaded = true) }
                changed()
            }, { currentError = message(it); changed() })
        } else if (!d.initialized || d.version <= 0) {
            execute(FriendCardRequest.detail(d.cardId), { result ->
                val value = result.card
                if (value?.isSupported == true && value.id == d.cardId && owner(value)) {
                    if (d.initialized) { updateDraft { it.copy(version = value.version) }; globalError = "已重新读取最新版本并保留你的输入，请核对预览后保存。" }
                    else updateDraft { it.copy(input = FriendCardInput.fromCard(value), version = value.version, initialized = true, currentLoaded = true, coverUrl = value.coverUrl, diapers = value.diapers.orEmpty(), dirty = false) }
                }
                else loadError = "无法读取本人卡片用于编辑"
                changed()
            }, { loadError = message(it); changed() })
        }
    }
    private fun updateDraft(transform: (FriendCardDraft) -> FriendCardDraft) {
        val current = draft ?: return
        val next = transform(current)
        draft = next
        try { store.save(next); storageError = null } catch (error: Exception) { storageError = error.message ?: "草稿未保存，请重试" }
        changed()
    }
    private fun persist(): Boolean {
        if (completed) return true
        val d = draft ?: return false
        return try { store.save(d); storageError = null; changed(); true } catch (error: Exception) { storageError = error.message ?: "草稿保存失败"; changed(); false }
    }
    private fun edit(change: (FriendCardInput) -> Unit) {
        val d = draft ?: return
        if (d.pending != null || busy) return
        val next = FriendCardRules.copy(d.input); change(next)
        updateDraft { it.copy(input = next, dirty = true) }
    }
    private fun stage(value: Int) { if (preview) preview = false; updateDraft { it.copy(stage = value.coerceIn(0, 2)) } }
    private fun addInterest(raw: String): String? {
        val d = draft ?: return "草稿不可用"
        val result = FriendCardRules.addTag(d.input.interests, raw, 12, 16)
        if (result.error == null) edit { it.interests = result.values }
        return result.error
    }
    private fun toggleInterest(value: String) { if (draft?.input?.interests?.contains(value) == true) edit { it.interests = it.interests - value } else addInterest(value)?.let { toast(it) } }
    private fun addGame(raw: String): String? {
        val d = draft ?: return "草稿不可用"
        val result = FriendCardRules.addGame(d.input.games, raw, options?.games.orEmpty())
        if (result.error == null) edit { it.games = result.values }
        return result.error
    }
    private fun toggleGame(value: String) {
        val games = options?.games.orEmpty()
        val selected = draft?.input?.games.orEmpty()
        val normalized = FriendCardRules.gameKey(value, games)
        if (selected.any { FriendCardRules.gameKey(it, games) == normalized }) edit { input -> input.games = input.games.filterNot { FriendCardRules.gameKey(it, games) == normalized } }
        else addGame(value)?.let { toast(it) }
    }
    private fun selectDiapers() {
        val d = draft ?: return
        if (d.pending != null || !persist()) return
        pickerDraft = d.draftId
        Nav.goForResult(activity, FriendCardDiaperSelectFragment::class.java, args().apply {
            putString("draft_id", d.draftId); putIntegerArrayList("selected_ids", ArrayList(d.input.diaperIds)); putString("diaper_json", FriendCardRules.gson.toJson(d.diapers))
        }, DIAPERS, this)
    }
    override fun onFragmentResult(requestCode: Int, success: Boolean, result: Bundle?) {
        super.onFragmentResult(requestCode, success, result)
        if (requestCode != DIAPERS) return
        val expected = pickerDraft; pickerDraft = null
        if (!success || result == null || !accountValid()) return
        val d = draft ?: return
        if (d.pending != null || result.getString("account") != account || result.getString("draft_id") != d.draftId || expected != d.draftId) return
        val ids = result.getIntegerArrayList("diaper_ids") ?: return
        if (ids.size > 8 || ids.any { it <= 0 } || ids.distinct().size != ids.size) { toast("商品选择结果无效"); return }
        val products = try { FriendCardRules.gson.fromJson(result.getString("diaper_json"), Array<FriendCard.Diaper>::class.java)?.toList().orEmpty() } catch (_: Exception) { emptyList() }
        val known = (d.diapers + products).associateBy { it.id }
        val input = FriendCardRules.copy(d.input).apply { diaperIds = ids.toList() }
        updateDraft { it.copy(input = input, diapers = ids.map { id -> known[id] ?: FriendCard.Diaper().apply { this.id = id; name = "纸尿裤 #$id"; available = true } }, dirty = true) }
    }
    private fun pickCover() {
        val d = draft ?: return
        if (busy || coverBusy || d.pending != null || !persist()) return
        pickerRequestDraft = d.draftId
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, COVER) }
        catch (error: Exception) { pickerRequestDraft = null; toast("无法打开图片选择器") }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != COVER) return
        val ticket = pickerRequestDraft; pickerRequestDraft = null
        val d = draft ?: return
        val uri = data?.data ?: return
        if (resultCode != Activity.RESULT_OK || ticket != d.draftId || d.pending != null || !accountValid() || isHidden) return
        val token = ++coverGeneration
        val upload = FriendCardCoverUpload(activity.applicationContext, account)
        uploader = upload; coverBusy = true; changed()
        MastodonAPIController.runInBackground {
            val outcome = runCatching { upload.upload(uri) }
            main.post {
                if (coverGeneration != token || !accountValid() || completed) return@post
                coverBusy = false; uploader = null
                outcome.onSuccess { (id, url) ->
                    val latest = draft
                    if (latest?.draftId == d.draftId && latest.pending == null) {
                        val input = FriendCardRules.copy(latest.input).apply { coverUploadId = id }
                        updateDraft { it.copy(input = input, coverUrl = url, dirty = true) }
                    }
                }.onFailure { globalError = it.message ?: "封面上传失败，请重试" }
                changed()
            }
        }
    }
    private fun publish() {
        val d = draft ?: return
        if (busy || coverBusy || !accountValid() || !shown) return
        if (d.pending == null) {
            if (!d.initialized || (d.cardId != null && d.version <= 0)) { globalError = "请先重试读取原卡最新版本"; changed(); return }
            if (d.coverUrl != null && d.input.coverUploadId.isNullOrBlank()) { globalError = "封面资源ID不可用，请重新选择或明确移除封面"; changed(); return }
            if (d.cardId == null && (options?.canCreate != true || options?.verificationState != "active" || !d.currentLoaded || currentError != null)) { globalError = "请重试读取创建资格与当前卡"; changed(); return }
            FriendCardRules.validate(d.input)?.let { globalError = it; changed(); return }
        }
        if (!persist()) return
        val ready = try { store.prepare(d) } catch (error: Exception) { storageError = error.message; changed(); return }
        draft = ready
        val operation = ready.pending ?: return
        if (operation.rejected) { globalError = "此操作已被服务端明确拒绝，请先返回修改"; changed(); return }
        busy = true; globalError = null; changed()
        val request = if (operation.cardId == null) FriendCardRequest.create(operation.operationId, operation.expectedCurrentId, operation.input()) else FriendCardRequest.edit(operation.cardId, operation.expectedVersion, operation.input())
        execute(request, { result ->
            busy = false
            val value = result.card
            if (value?.isSupported != true || (operation.cardId != null && operation.cardId != value.id)) { globalError = "发布结果尚未确认，请使用同一操作重试"; changed(); return@execute }
            try { store.complete(operation); completed = true; removeBackCallback(back) }
            catch (error: Exception) { storageError = error.message; changed(); return@execute }
            toast(if (value.state == "active") if (operation.cardId == null) "扩列卡已创建并发布" else "已更新原卡与原帖" else "已保存，当前状态：${value.state}；${value.reason ?: "请在我的卡中查看审核或展示状态"}")
            val host = activity; val bundle = args(value.id); Nav.finish(this); if (host != null) Nav.go(host, FriendCardDetailFragment::class.java, bundle)
        }, { error ->
            busy = false
            if (definitive(error)) {
                try { store.reject(operation)?.let { draft = it } } catch (failure: Exception) { storageError = failure.message }
                globalError = message(error) + "。服务端已明确拒绝，可返回修改。"
            } else globalError = message(error) + "。结果尚不确定：已保存原操作与输入，只能按同一操作重试，不会创建第二条帖子。"
            changed()
        })
    }
    private fun unlock() {
        val d = draft ?: return
        try { draft = store.unlockRejected(d); preview = false; storageError = null; refresh() } catch (error: Exception) { storageError = error.message; changed() }
    }
    private fun openVerification() { if (persist()) go(BabyVerificationFragment::class.java) }
    private fun showPreview() {
        draft?.let { FriendCardRules.validate(it.input)?.let { error -> globalError = error; changed(); return } }
        if (persist()) { preview = true; changed() }
    }
    private fun leaveEditor() {
        if (completed) { removeBackCallback(back); finish(); return }
        if (preview && draft?.pending == null) { preview = false; changed(); return }
        val d = draft
        confirm("离开编辑", if (d?.pending != null) "原操作快照会保留。返回后请使用同一操作重试确认结果。" else "本机草稿按账号保存，尚未发布。", "保存并离开") { if (persist()) { removeBackCallback(back); finish() } }
    }
    override fun onToolbarNavigationClick() { leaveEditor() }
    override fun onHiddenChanged(hidden: Boolean) { super.onHiddenChanged(hidden); if (hidden) pickerRequestDraft = null }
    override fun onHidden() {
        persist(); removeBackCallback(back); busy = false; coverGeneration++; uploader?.cancel(); uploader = null; coverBusy = false; super.onHidden()
    }
    override fun onDestroyView() { removeBackCallback(back); coverGeneration++; uploader?.cancel(); uploader = null; coverBusy = false; super.onDestroyView() }
    @Composable override fun Screen() {
        val d = draft
        if (d == null || loadError != null) {
            FriendCardNotice(loadError ?: "草稿不可用", "重试读取", { loadError = null; restore(); refresh() }); return
        }
        FriendCardEditorScreen(
            draft = d, options = options, checking = checking, eligibilityError = eligibilityError,
            error = listOfNotNull(storageError, currentError, globalError).joinToString("\n").takeIf { it.isNotBlank() },
            preview = preview, busy = busy, coverBusy = coverBusy,
            onRetry = { if (storageError != null) persist(); refresh() }, onVerification = { openVerification() },
            onStage = { stage(it) }, onEdit = { edit(it) }, onPreview = { showPreview() }, onBackToEdit = { preview = false; changed() },
            onPublish = { publish() }, onUnlock = { unlock() }, onSave = { if (persist()) toast("草稿已保存，尚未发布") }, onCover = { pickCover() },
            onRemoveCover = { edit { it.coverUploadId = null }; updateDraft { it.copy(coverUrl = null, dirty = true) } },
            onInterest = { addInterest(it) }, onToggleInterest = { toggleInterest(it) }, onGame = { addGame(it) }, onToggleGame = { toggleGame(it) },
            onDiapers = { selectDiapers() }, onDiaperOpen = { diaper(it) }, onDiaperRemove = { id -> edit { it.diaperIds = it.diaperIds - id }; updateDraft { it.copy(diapers = it.diapers.filterNot { item -> item.id == id }) } },
        )
    }
}
