package org.joinmastodon.android.friendcards

import org.joinmastodon.android.api.requests.friendcards.FriendCardInput
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FriendCardDraftStoreTest {
    private class Memory : FriendCardDraftStorage {
        val entries = linkedMapOf<String, String>()
        var fail = false
        override fun read(key: String) = entries[key]
        override fun commit(key: String, value: String?): Boolean { if (fail) return false; if (value == null) entries.remove(key) else entries[key] = value; return true }
    }
    private fun draft(account: String = "account-a", card: String? = null) = FriendCardDraft(account, card, input = FriendCardInput().apply { title = "宣言"; lookingFor = "朋友" }, currentLoaded = true, initialized = true, version = if (card != null) 7 else 0, expectedCurrentId = "old-current")
    private fun fails(block: () -> Unit) { try { block(); fail("Expected IOException") } catch (_: IOException) { } }
    @Test fun processRestartRestoresExactInputStageAndAccountBinding() {
        val memory = Memory(); val store = FriendCardDraftStore(memory)
        val a = draft().copy(stage = 2, dirty = true)
        store.save(a); store.save(draft("account-b").copy(stage = 1))
        val restarted = FriendCardDraftStore(memory)
        assertEquals(a.draftId, restarted.load("account-a", null)?.draftId)
        assertEquals(2, restarted.load("account-a", null)?.stage)
        assertEquals("宣言", restarted.load("account-a", null)?.input?.title)
        assertEquals(1, restarted.load("account-b", null)?.stage)
        assertNull(restarted.load("account-a", "some-card"))
    }
    @Test fun commitMustSucceedBeforeOperationCanBeReturned() {
        val memory = Memory(); val store = FriendCardDraftStore(memory); val draft = draft()
        store.save(draft); memory.fail = true
        fails { store.prepare(draft) }
        assertNull(store.load(draft.account, null)?.pending)
    }
    @Test fun timeoutRetryReusesOperationAndImmutableInputAfterRestart() {
        val memory = Memory(); val store = FriendCardDraftStore(memory); val draft = draft()
        store.save(draft)
        val ready = store.prepare(draft); val operation = ready.pending!!
        draft.input.title = "后来修改的表单"
        assertEquals("宣言", operation.input().title)
        val retry = FriendCardDraftStore(memory).prepare(draft).pending!!
        assertEquals(operation.operationId, retry.operationId); assertEquals(operation.inputJson, retry.inputJson)
        assertEquals("old-current", retry.expectedCurrentId)
        fails { store.discard(ready) }
        fails { store.unlockRejected(ready) }
        fails { store.save(ready.copy(pending = null)) }
    }
    @Test fun onlyDefinitiveRejectionAllowsUnlockAndFreshSnapshot() {
        val memory = Memory(); val store = FriendCardDraftStore(memory); val draft = draft()
        store.save(draft); val ready = store.prepare(draft); val first = ready.pending!!
        val rejected = store.reject(first)!!; assertTrue(rejected.pending!!.rejected)
        val unlocked = store.unlockRejected(rejected); assertNull(unlocked.pending); assertFalse(unlocked.currentLoaded)
        val refreshed = unlocked.copy(currentLoaded = true)
        store.save(refreshed)
        assertNotEquals(first.operationId, store.prepare(refreshed).pending!!.operationId)
    }
    @Test fun editSnapshotRetainsVersionAndCardIdentity() {
        val memory = Memory(); val store = FriendCardDraftStore(memory); val draft = draft(card = "card-a")
        store.save(draft); val operation = store.prepare(draft).pending!!
        assertEquals("card-a", operation.cardId); assertEquals(7, operation.expectedVersion)
        assertEquals("account-a", operation.account); assertEquals(draft.draftId, operation.draftId)
        assertTrue(store.complete(operation)); assertNull(store.load("account-a", "card-a"))
    }
    @Test fun staleEditorCannotOverwriteDifferentDraft() {
        val memory = Memory(); val store = FriendCardDraftStore(memory)
        val saved = draft(); store.save(saved)
        fails { store.save(draft()) }; fails { store.prepare(draft()) }
        assertEquals(saved.draftId, store.load(saved.account, null)?.draftId)
    }
    @Test fun genericUploadIdIsDurableAndCannotChangePendingPayload() {
        val memory = Memory(); val store = FriendCardDraftStore(memory); val draft = draft()
        store.save(draft)
        assertNull(store.cover(draft.account, null, "other-draft", "upload", "https://example.com/image.jpg"))
        val covered = store.cover(draft.account, null, draft.draftId, "generic-upload-id", "https://example.com/image.jpg")!!
        assertEquals("generic-upload-id", FriendCardDraftStore(memory).load(draft.account, null)?.input?.coverUploadId)
        val ready = store.prepare(covered)
        assertNull(store.cover(draft.account, null, draft.draftId, "different-upload", "https://example.com/new.jpg"))
        assertEquals("generic-upload-id", ready.pending!!.input().coverUploadId)
    }
    @Test fun malformedDraftIsNeverSilentlyReplaced() {
        val memory = Memory(); val store = FriendCardDraftStore(memory); val draft = draft()
        store.save(draft); val key = memory.entries.keys.single(); memory.entries[key] = "{bad"
        fails { store.load(draft.account, null) }; fails { store.save(draft) }
        assertEquals("{bad", memory.entries[key])
    }
    @Test fun draftRequiresCurrentCardReadAndValidation() {
        val memory = Memory(); val store = FriendCardDraftStore(memory)
        fails { store.prepare(draft().copy(currentLoaded = false)) }
        fails { store.prepare(draft(card = "edit-card").copy(version = 0)) }
        val invalid = draft().apply { input.title = "" }
        fails { store.prepare(invalid) }
        assertTrue(memory.entries.isEmpty())
    }
}
