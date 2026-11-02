package org.joinmastodon.android.friendcards

import androidx.annotation.StringRes
import org.joinmastodon.android.MastodonApp

/** Display-only resource adapter for rules/storage APIs that intentionally have no Context parameter.
 * Resolve on each call, rather than caching a language or putting labels into durable input.
 */
internal object FriendCardText {
    fun get(@StringRes resource: Int, vararg arguments: Any): String =
        MastodonApp.context.getString(resource, *arguments)
}
