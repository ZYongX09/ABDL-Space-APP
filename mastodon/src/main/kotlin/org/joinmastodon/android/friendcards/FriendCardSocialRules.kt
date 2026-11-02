package org.joinmastodon.android.friendcards

import org.joinmastodon.android.R
import java.net.URI
import java.net.IDN
import org.joinmastodon.android.model.FriendCard

object FriendCardSocialRules {
    fun validate(accounts: List<FriendCard.SocialAccount>?): String? {
        if (accounts == null) return null
        if (accounts.size > 5) return FriendCardText.get(R.string.fc_social_limit_error)
        if (accounts.map { it.id }.distinct().size != accounts.size) return FriendCardText.get(R.string.fc_social_duplicate_id)
        for (item in accounts) {
            if (!item.id.orEmpty().matches(Regex("[A-Za-z0-9_-]{1,64}"))) return FriendCardText.get(R.string.fc_social_invalid_id)
            if (item.platform !in listOf("qq", "wechat", "custom")) return FriendCardText.get(R.string.fc_social_invalid_platform)
            val name = item.name.orEmpty().trim()
            val account = item.account.orEmpty().trim()
            if (FriendCardRules.length(name) > 20 || name.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return FriendCardText.get(R.string.fc_social_invalid_name)
            if (item.platform == "custom" && name.isEmpty()) return FriendCardText.get(R.string.fc_social_name_required)
            if (account.isEmpty() || FriendCardRules.length(account) > 120 || account.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return FriendCardText.get(R.string.fc_social_invalid_account)
            if (item.platform == "custom" && !safeAccount(account)) return FriendCardText.get(R.string.fc_social_invalid_url)
        }
        return null
    }

    fun platformName(item: FriendCard.SocialAccount): String = platformName(item, org.joinmastodon.android.MastodonApp.context)
    fun platformName(item: FriendCard.SocialAccount, context: android.content.Context): String = when (item.platform) {
        "qq" -> "QQ"
        "wechat" -> context.getString(R.string.fc_wechat)
        else -> item.name.orEmpty().ifBlank { context.getString(R.string.fc_social_other_platform) }
    }

    fun copy(item: FriendCard.SocialAccount) = FriendCard.SocialAccount().apply {
        id = item.id; platform = item.platform; account = item.account; name = item.name; isPublic = item.isPublic
    }

    private fun safeAccount(value: String): Boolean {
        if (value.startsWith("//") || value.contains('\\')) return false
        if (!Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value)) return true
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in listOf("http", "https") || uri.rawUserInfo != null) return false
        val authority = uri.rawAuthority ?: return false
        if (authority.contains('@') || authority.contains('[') || authority.contains(']') || authority.count { it == ':' } > 1) return false
        val rawHost = authority.substringBefore(':')
        val host = runCatching { IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).lowercase().trimEnd('.') }.getOrNull() ?: return false
        val asciiUri = runCatching { URI(uri.scheme + "://" + host + authority.removePrefix(rawHost) + uri.rawPath.orEmpty()) }.getOrNull() ?: return false
        if (asciiUri.host == null || asciiUri.port > 65535) return false
        val reserved = listOf("localhost", "localdomain", "local", "internal", "home", "home.arpa", "test", "invalid", "example", "onion", "alt", "arpa", "example.com", "example.net", "example.org", "metadata.google.com")
        if (!host.contains('.') || reserved.any { host == it || host.endsWith(".$it") }) return false
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(value) || value.any { it.isWhitespace() }) return false
        if (!host.split('.').all { it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) }) return false
        if (host.startsWith("[") || host.contains(':')) return false
        if (host.all { it.isDigit() || it == '.' }) return false
        return true
    }
}
