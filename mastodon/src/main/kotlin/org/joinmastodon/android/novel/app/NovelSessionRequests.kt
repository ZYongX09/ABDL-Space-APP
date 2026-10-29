package org.joinmastodon.android.novel.app

import java.io.InterruptedIOException
import okhttp3.Call
import org.joinmastodon.android.api.session.AccountSession
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.novel.NovelAccountDataCleaner

/** A permanently revoked captured session, not a transient transport failure. */
class NovelSessionExpiredException : InterruptedIOException("Novel account session expired")

/**
 * Uses the cleaner's existing account generation and lock; there is no second logout epoch.
 * Only active call handles are retained, never a separate token/body log or a retry queue.
 */
object NovelSessionRequests {
    private val inFlightCalls = mutableMapOf<String, MutableSet<Call>>()

    class Authority internal constructor(
        internal val accountId: String,
        private val generation: Long,
        private val sessionIsCurrent: () -> Boolean,
    ) {
        private var expired = false

        fun isActive(): Boolean = NovelAccountDataCleaner.withAccountLock { activeLocked() }

        internal fun requireActive() = NovelAccountDataCleaner.withAccountLock { requireActiveLocked() }

        internal fun requireActiveLocked() {
            if (!activeLocked()) throw NovelSessionExpiredException()
        }

        private fun activeLocked(): Boolean {
            if (!expired && (!NovelAccountDataCleaner.isGenerationValid(accountId, generation) || !sessionIsCurrent())) expired = true
            return !expired
        }
    }

    /** Production authority always pins the actual session object AND the token used by this API. */
    @JvmStatic
    fun capture(session: AccountSession): Authority = capture(session, session.token.accessToken)

    internal fun capture(session: AccountSession, accessToken: String): Authority {
        val accountId = session.getID()
        return capture(accountId) {
            val current = AccountSessionManager.getInstance().tryGetAccount(accountId)
            current === session && current.token.accessToken == accessToken
        }
    }

    /** Loopback tests must supply a controlled session authority; the real generation gate still applies. */
    internal fun capture(accountId: String, sessionIsCurrent: () -> Boolean): Authority = NovelAccountDataCleaner.withAccountLock {
        require(accountId.isNotBlank())
        Authority(accountId, NovelAccountDataCleaner.captureGeneration(accountId), sessionIsCurrent)
    }

    internal fun register(authority: Authority, call: Call) = NovelAccountDataCleaner.withAccountLock {
        try {
            authority.requireActiveLocked()
            inFlightCalls.getOrPut(authority.accountId) { mutableSetOf() }.add(call)
            Unit
        } catch (expired: NovelSessionExpiredException) {
            call.cancel()
            throw expired
        }
    }

    /** Enqueue is linearized with revoke; blocking execute must run outside the account lock. */
    internal fun dispatch(authority: Authority, call: Call, enqueue: () -> Unit) = NovelAccountDataCleaner.withAccountLock {
        try {
            authority.requireActiveLocked()
            if (call.isCanceled()) throw InterruptedIOException("Novel request cancelled")
            enqueue()
        } catch (error: Exception) {
            call.cancel()
            throw error
        }
    }

    internal fun unregister(authority: Authority, call: Call) = NovelAccountDataCleaner.withAccountLock {
        inFlightCalls[authority.accountId]?.let { active ->
            active.remove(call)
            if (active.isEmpty()) inFlightCalls.remove(authority.accountId)
        }
        Unit
    }

    /** Called only after the cleaner increments its generation, while holding the same lock. */
    internal fun cancelAccount(accountId: String) = NovelAccountDataCleaner.withAccountLock {
        inFlightCalls.remove(accountId)?.toList()?.forEach(Call::cancel)
        Unit
    }
}
