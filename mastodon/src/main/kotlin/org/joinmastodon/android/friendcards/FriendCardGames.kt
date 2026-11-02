package org.joinmastodon.android.friendcards

import android.content.Context
import androidx.annotation.StringRes
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.R
import org.joinmastodon.android.api.requests.friendcards.FriendCardResponses

/** Stable v1 wire IDs and legacy aliases. Only built-in display labels are localized. */
object FriendCardGames {
    private data class BuiltIn(val alias: String, val englishAlias: String, @StringRes val label: Int)
    private val versionOne = linkedMapOf(
        "honor-of-kings" to BuiltIn("王者荣耀", "Honor of Kings", R.string.fc_game_honor_of_kings),
        "peace-elite" to BuiltIn("和平精英", "Peace Elite", R.string.fc_game_peace_elite),
        "genshin-impact" to BuiltIn("原神", "Genshin Impact", R.string.fc_game_genshin_impact),
        "honkai-star-rail" to BuiltIn("崩坏：星穹铁道", "Honkai: Star Rail", R.string.fc_game_honkai_star_rail),
        "zenless-zone-zero" to BuiltIn("绝区零", "Zenless Zone Zero", R.string.fc_game_zenless_zone_zero),
        "identity-v" to BuiltIn("第五人格", "Identity V", R.string.fc_game_identity_v),
        "egg-party" to BuiltIn("蛋仔派对", "Eggy Party", R.string.fc_game_egg_party),
        "sky" to BuiltIn("光·遇", "Sky: Children of the Light", R.string.fc_game_sky),
        "minecraft" to BuiltIn("我的世界", "Minecraft", R.string.fc_game_minecraft),
        "stardew-valley" to BuiltIn("星露谷物语", "Stardew Valley", R.string.fc_game_stardew_valley),
        "league-of-legends" to BuiltIn("英雄联盟", "League of Legends", R.string.fc_game_league_of_legends),
        "delta-force" to BuiltIn("三角洲行动", "Delta Force", R.string.fc_game_delta_force),
    )
    @Volatile private var suppliedLabels: Map<String, String> = emptyMap()

    // Keep the one-argument Java/Kotlin entry point; UI callers can supply their effective Context.
    @JvmStatic fun gameName(id: String?): String = gameName(id, MastodonApp.context)
    @JvmStatic fun gameName(id: String?, context: Context): String = gameName(id, suppliedLabels[id], context)
    @JvmStatic fun gameName(id: String?, suppliedLabel: String?, context: Context): String {
        if (id == null) return ""
        val builtIn = versionOne[id]
        val label = suppliedLabel?.takeIf { it.isNotBlank() } ?: suppliedLabels[id]
        // A server/admin override is business content, not a string to translate. The unchanged
        // bundled Chinese label is the only supplied label treated as the built-in preset.
        if (!label.isNullOrBlank() && label != builtIn?.alias) return label
        return builtIn?.let { context.getString(it.label) } ?: label ?: id
    }

    @JvmStatic fun gameKey(value: String): String {
        val text = value.trim()
        // Keep Chinese aliases even after an admin supplies a different label. English built-in
        // labels resolve to the same ID, never a translated value in the persisted game list.
        return versionOne.entries.firstOrNull { (id, game) ->
            id.equals(text, true) || game.alias.equals(text, true) || game.englishAlias.equals(text, true)
        }?.key ?: suppliedLabels.entries.firstOrNull { it.key.equals(text, true) || it.value.equals(text, true) }?.key ?: text
    }

    @JvmStatic fun updateOptions(games: List<FriendCardResponses.Game>) {
        suppliedLabels = games.filter { !it.id.isNullOrBlank() && !it.name.isNullOrBlank() }.associate { it.id to it.name }
    }
}
