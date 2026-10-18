package org.joinmastodon.android.friendcards

import org.joinmastodon.android.api.requests.friendcards.FriendCardResponses

/** Stable v1 wire IDs; options can update display labels without changing stored IDs. */
object FriendCardGames {
    private val versionOne = linkedMapOf(
        "honor-of-kings" to "王者荣耀", "peace-elite" to "和平精英", "genshin-impact" to "原神",
        "honkai-star-rail" to "崩坏：星穹铁道", "zenless-zone-zero" to "绝区零", "identity-v" to "第五人格",
        "egg-party" to "蛋仔派对", "sky" to "光·遇", "minecraft" to "我的世界", "stardew-valley" to "星露谷物语",
        "league-of-legends" to "英雄联盟", "delta-force" to "三角洲行动",
    )
    @Volatile private var labels: Map<String, String> = versionOne
    @JvmStatic fun gameName(id: String?): String = if (id == null) "" else labels[id] ?: id
    @JvmStatic fun gameKey(value: String): String = labels.entries.firstOrNull { it.key.equals(value.trim(), true) || it.value.equals(value.trim(), true) }?.key ?: value.trim()
    @JvmStatic fun updateOptions(games: List<FriendCardResponses.Game>) {
        labels = versionOne + games.filter { !it.id.isNullOrBlank() && !it.name.isNullOrBlank() }.associate { it.id to it.name }
    }
}
