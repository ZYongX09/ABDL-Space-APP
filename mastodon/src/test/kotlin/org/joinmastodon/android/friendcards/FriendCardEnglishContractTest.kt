package org.joinmastodon.android.friendcards

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.google.gson.JsonParser
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.R
import org.joinmastodon.android.api.requests.friendcards.FriendCardInput
import org.joinmastodon.android.api.requests.friendcards.FriendCardRequest
import org.joinmastodon.android.api.requests.friendcards.FriendCardResponses
import org.joinmastodon.android.model.FriendCard
import org.joinmastodon.android.ui.compose.friendcards.FriendCardHubFilter
import org.joinmastodon.android.ui.compose.friendcards.friendCardHubStateName
import org.joinmastodon.android.ui.displayitems.FriendCardStatusDisplayItem
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Locale

/** Real Android Resources in en-US and zh; labels never enter draft/request/operation JSON. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS", application = CompatibilityTestApplication::class)
class FriendCardEnglishContractTest {
    @Before fun localizedContext() {
        MastodonApp.context = RuntimeEnvironment.getApplication()
        FriendCardGames.updateOptions(emptyList())
    }
    @After fun resetOptions() { FriendCardGames.updateOptions(emptyList()) }

    @Test fun builtInLabelsAndLegacyAliasesLocalizeWithoutChangingStoredIds() {
        val en = context("en-US")
        val zh = context("zh-CN")
        val examples = listOf(
            Triple("honor-of-kings", "王者荣耀", "Honor of Kings"), Triple("peace-elite", "和平精英", "Peace Elite"),
            Triple("genshin-impact", "原神", "Genshin Impact"), Triple("honkai-star-rail", "崩坏：星穹铁道", "Honkai: Star Rail"),
            Triple("zenless-zone-zero", "绝区零", "Zenless Zone Zero"), Triple("identity-v", "第五人格", "Identity V"),
            Triple("egg-party", "蛋仔派对", "Eggy Party"), Triple("sky", "光·遇", "Sky: Children of the Light"),
            Triple("minecraft", "我的世界", "Minecraft"), Triple("stardew-valley", "星露谷物语", "Stardew Valley"),
            Triple("league-of-legends", "英雄联盟", "League of Legends"), Triple("delta-force", "三角洲行动", "Delta Force"),
        )
        for ((id, chinese, english) in examples) {
            val game = game(id, chinese)
            assertEquals(chinese, FriendCardGames.gameName(id, zh))
            assertEquals(english, FriendCardGames.gameName(id, en))
            assertEquals(english, FriendCardRules.gameName(id, listOf(game), en))
            assertEquals(id, FriendCardGames.gameKey(" $chinese "))
            assertEquals(id, FriendCardGames.gameKey(" ${english.uppercase(Locale.ROOT)} "))
            assertEquals(listOf(id), FriendCardRules.addGame(emptyList(), english, listOf(game)).values)
            assertNotNull(FriendCardRules.addGame(listOf(id), chinese, listOf(game)).error)
        }
        // API/admin labels (even for a built-in ID) and custom text are business content.
        val serverGames = listOf(game("genshin-impact", "管理员自定义标题"), game("server-game", "Server label 原文"))
        FriendCardGames.updateOptions(serverGames)
        for (locale in listOf(en, zh)) {
            assertEquals("管理员自定义标题", FriendCardGames.gameName("genshin-impact", locale))
            assertEquals("管理员自定义标题", FriendCardRules.gameName("genshin-impact", serverGames, locale))
            assertEquals("Server label 原文", FriendCardGames.gameName("server-game", locale))
            assertEquals("自填游戏 untouched", FriendCardGames.gameName("自填游戏 untouched", locale))
        }
        assertEquals("genshin-impact", FriendCardGames.gameKey("原神"))
        assertEquals("genshin-impact", FriendCardGames.gameKey("管理员自定义标题"))
    }

    @Test fun fieldLabelsValidationSelectionAndStorageErrorsResolvePerEffectiveContext() {
        val zh = context("zh-CN")
        val en = context("en-US")
        val expectedKeys = listOf("gender", "age", "zodiac", "region", "school", "mbti", "interaction_habits", "interests", "games", "diapers", "looking_for", "boundaries")
        assertEquals(expectedKeys, FriendCardRules.fields.keys.toList())
        assertEquals(expectedKeys, FriendCardInput().publicFields)
        data class Expected(val locale: Context, val title: String, val label: String, val limit: String,
            val duplicate: String, val paused: String, val recommended: String)
        for ((locale, title, label, limit, duplicate, paused, recommended) in listOf(
            Expected(zh, "请填写宣言", "性别", "性别 最多 24 字", "已经添加过了", "已暂停", "推荐"),
            Expected(en, "Enter an introduction", "Gender", "Gender must be at most 24 characters", "Already added", "Paused", "Recommended"),
        )) {
            MastodonApp.context = locale
            assertEquals(title, FriendCardRules.validate(FriendCardInput()))
            assertEquals(label, locale.getString(FriendCardRules.fieldLabel("gender")))
            assertEquals(limit, FriendCardRules.validate(valid().apply { gender = "x".repeat(25) }))
            assertEquals(duplicate, FriendCardRules.addTag(listOf("same"), "SAME", 12, 16).error)
            assertEquals(paused, friendCardHubStateName("paused", locale))
            assertEquals(recommended, locale.getString(FriendCardHubFilter.Recommended.labelRes))
            val selection = FriendCardDiaperSelection("account", "draft", (1..8).toList())
            assertEquals(locale.getString(R.string.fc_diapers_limit), selection.toggle(9))
            val broken = object : FriendCardDraftStorage {
                override fun read(key: String): String? = null
                override fun commit(key: String, value: String?) = false
            }
            val failure = assertThrows(IOException::class.java) { FriendCardDraftStore(broken).save(FriendCardDraft("account", input = valid())) }
            assertEquals(locale.getString(R.string.fc_draft_commit_failed), failure.message)
        }
    }

    @Test fun localeChangesLeaveDraftAndIdempotentRequestSnapshotByteForByteUnchanged() {
        val memory = Memory()
        val store = FriendCardDraftStore(memory)
        MastodonApp.context = context("zh-CN")
        val draft = FriendCardDraft("same-account", draftId = "same-draft", input = valid().apply {
            title = "原始用户宣言"; games = listOf("genshin-impact", "自填桌游")
            school = "private school"; publicFields = publicFields - "school"
            socialAccounts = listOf(FriendCard.SocialAccount().apply {
                id = "same-row"; platform = "wechat"; account = "original_contact"; isPublic = true
            })
        }, currentLoaded = true)
        store.save(draft)
        val ready = store.prepare(draft)
        val operation = ready.pending!!
        val saved = memory.entries.values.single()
        val beforeRequest = body(FriendCardRequest.create(operation.operationId, null, operation.input()))
        val chinese = FriendCardRules.legacyText(FriendCardRules.publicPreview(ready.input, emptyList(), null), emptyList())
        MastodonApp.context = context("en-US")
        val english = FriendCardRules.legacyText(FriendCardRules.publicPreview(ready.input, emptyList(), null), emptyList())
        assertTrue(chinese.contains("我在玩：原神、自填桌游"))
        assertTrue(english.contains("Games I play: Genshin Impact, 自填桌游"))
        assertTrue(english.contains("WeChat: original_contact"))
        assertFalse(english.contains("private school"))
        val retry = FriendCardDraftStore(memory).prepare(ready).pending!!
        assertEquals(saved, memory.entries.values.single())
        assertEquals(operation.operationId, retry.operationId)
        assertEquals(operation.inputJson, retry.inputJson)
        assertEquals(beforeRequest, body(FriendCardRequest.create(retry.operationId, null, retry.input())))
        val input = JsonParser.parseString(retry.inputJson).asJsonObject
        assertEquals(listOf("genshin-impact", "自填桌游"), input.getAsJsonArray("games").map { it.asString })
        assertFalse(retry.inputJson.contains("Genshin Impact"))
        assertFalse(retry.inputJson.contains("WeChat"))
        assertEquals("原始用户宣言", input["title"].asString)
    }

    @Test fun javaDisplayAdapterUsesTheProvidedContextWithoutMutatingWireLists() {
        val card = FriendCard().apply { games = listOf("genshin-impact", "自填桌游"); publicFields = listOf("games") }
        assertEquals(listOf("Genshin Impact", "自填桌游"), FriendCardStatusDisplayItem.interestTags(card, context("en-US")))
        assertEquals(listOf("原神", "自填桌游"), FriendCardStatusDisplayItem.interestTags(card, context("zh-CN")))
        assertEquals(listOf("genshin-impact", "自填桌游"), card.games)
    }

    private fun context(tag: String): Context = RuntimeEnvironment.getApplication().let { app ->
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(tag))) })
    }
    private fun valid() = FriendCardInput().apply { title = "Original introduction"; lookingFor = "Original looking for" }
    private fun game(id: String, name: String) = FriendCardResponses.Game().apply { this.id = id; this.name = name }
    private fun body(request: FriendCardRequest<*>) = org.joinmastodon.android.api.MastodonAPIRequest::class.java
        .getDeclaredField("requestBody").apply { isAccessible = true }.get(request).toString()
    private class Memory : FriendCardDraftStorage {
        val entries = linkedMapOf<String, String>()
        override fun read(key: String) = entries[key]
        override fun commit(key: String, value: String?): Boolean { if (value == null) entries.remove(key) else entries[key] = value; return true }
    }
}
