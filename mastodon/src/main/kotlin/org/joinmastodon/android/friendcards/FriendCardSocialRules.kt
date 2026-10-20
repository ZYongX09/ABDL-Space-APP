package org.joinmastodon.android.friendcards

import java.net.URI
import java.net.IDN
import org.joinmastodon.android.model.FriendCard

object FriendCardSocialRules {
    fun validate(accounts: List<FriendCard.SocialAccount>?): String? {
        if (accounts == null) return null
        if (accounts.size > 5) return "社交账号最多添加5项"
        if (accounts.map { it.id }.distinct().size != accounts.size) return "社交账号编号重复，请重新添加"
        for (item in accounts) {
            if (!item.id.orEmpty().matches(Regex("[A-Za-z0-9_-]{1,64}"))) return "社交账号编号无效，请重新添加"
            if (item.platform !in listOf("qq", "wechat", "custom")) return "请选择支持的社交平台"
            val name = item.name.orEmpty().trim()
            val account = item.account.orEmpty().trim()
            if (FriendCardRules.length(name) > 20 || name.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return "自定义平台名称最多20字且不能含控制字符"
            if (item.platform == "custom" && name.isEmpty()) return "请填写自定义平台名称"
            if (account.isEmpty() || FriendCardRules.length(account) > 120 || account.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return "请填写1–120字的社交账号或主页链接"
            if (item.platform == "custom" && !safeAccount(account)) return "社交主页仅支持公开的 HTTP 或 HTTPS 地址"
        }
        return null
    }

    fun platformName(item: FriendCard.SocialAccount): String = when (item.platform) {
        "qq" -> "QQ"
        "wechat" -> "微信"
        else -> item.name.orEmpty().ifBlank { "其他平台" }
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
