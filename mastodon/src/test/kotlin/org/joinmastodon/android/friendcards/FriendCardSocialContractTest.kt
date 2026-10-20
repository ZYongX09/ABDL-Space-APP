package org.joinmastodon.android.friendcards

import android.os.Parcel
import android.os.Parcelable
import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.api.MastodonAPIController
import org.joinmastodon.android.api.requests.friendcards.FriendCardInput
import org.joinmastodon.android.api.requests.friendcards.FriendCardRequest
import org.joinmastodon.android.api.requests.friendcards.FriendCardResponses
import org.joinmastodon.android.model.FriendCard
import org.joinmastodon.android.model.FriendCardAttachment
import org.joinmastodon.android.model.Status
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.parceler.Parcels
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class)
class FriendCardSocialContractTest {
    @Before fun initializeApiContext() { MastodonApp.context = RuntimeEnvironment.getApplication() }

    @Test fun ordinaryPlatformsAndExactUnicodeLimitsAreValidAndNewRowsArePrivate() {
        val qq = row("qq-main", "qq", "123456789")
        val wechat = row("wechat-main", "wechat", "friend_2026", public = true)
        val custom = row("home", "custom", "https://github.com/friends", "个人主页")
        assertFalse(FriendCard.SocialAccount().isPublic)
        assertFalse(qq.isPublic)
        assertNull(FriendCardRules.validate(input(listOf(qq, wechat, custom))))
        assertEquals(listOf("QQ", "微信", "个人主页"), listOf(qq, wechat, custom).map(FriendCardSocialRules::platformName))

        val atLimits = List(5) { row("row_$it", "custom", "😀".repeat(120), "😀".repeat(20)) }
        assertNull(FriendCardSocialRules.validate(atLimits))
        assertNull(FriendCardRules.validate(input(atLimits)))
        assertNull(FriendCardSocialRules.validate(null))
        assertNull(FriendCardSocialRules.validate(emptyList()))
    }

    @Test fun validationRejectsExcessRowsDuplicateOrInvalidIdsAndInvalidAccountOrName() {
        val invalid = listOf(
            "six rows" to List(6) { row("row-$it") },
            "duplicate ids" to listOf(row("same"), row("same", "wechat", "another_handle")),
            "missing id" to listOf(row().apply { id = null }),
            "blank id" to listOf(row("")),
            "invalid id" to listOf(row("bad/id")),
            "long id" to listOf(row("x".repeat(65))),
            "unsupported platform" to listOf(row(platform = "unsupported")),
            "missing account" to listOf(row().apply { account = null }),
            "blank account" to listOf(row(account = "   ")),
            "long account" to listOf(row(account = "😀".repeat(121))),
            "account newline" to listOf(row(account = "friend\nhandle")),
            "account control" to listOf(row(account = "friend\u0000handle")),
            "missing custom name" to listOf(row(platform = "custom", name = "   ")),
            "long custom name" to listOf(row(platform = "custom", name = "😀".repeat(21))),
            "name control" to listOf(row(platform = "custom", name = "my\tplatform")),
        )
        for ((description, rows) in invalid) {
            assertNotNull(description, FriendCardSocialRules.validate(rows))
            assertNotNull("editor: $description", FriendCardRules.validate(input(rows)))
        }
    }

    @Test fun socialUrlsRejectUnsafeProtocolsCredentialsAndPrivateHosts() {
        val unsafe = listOf(
            "javascript:alert(1)", "data:text/html,hello", "file:///tmp/profile", "ftp://example.com/u",
            "mailto:friend@example.com", "//example.com/u", "https:\\example.com\\u", "https://",
            "https://user:password@example.com/u", "http://localhost/u", "http://LOCALHOST./u",
            "http://app.localhost/u", "http://service.local/u", "http://service.internal/u", "http://intranet/u",
            "http://0.0.0.0/u", "http://10.0.0.1/u", "http://127.0.0.1/u", "http://169.254.1.2/u",
            "http://172.16.0.1/u", "http://172.31.255.254/u", "http://192.168.1.1/u",
            "http://100.64.0.1/u", "http://198.18.0.1/u", "http://224.0.0.1/u", "http://[::1]/u", "https://8.8.8.8/u",
        )
        unsafe.forEach { assertNotNull(it, FriendCardSocialRules.validate(listOf(row(platform = "custom", name = "主页", account = it)))) }
        listOf("http://github.com/profile", "https://github.com/profile?q=friend#top").forEach {
            assertNull(it, FriendCardSocialRules.validate(listOf(row(platform = "custom", name = "主页", account = it))))
        }
    }

    @Test fun historicalPendingOperationSurvivesNewNullableFieldWithoutChangingSnapshotOrId() {
        val memory = Memory()
        val store = FriendCardDraftStore(memory)
        val original = FriendCardDraft("historical-owner", input = input(), currentLoaded = true)
        store.save(original)
        store.prepare(original)
        val key = memory.entries.keys.single()
        val stored = JsonParser.parseString(memory.entries.getValue(key)).asJsonObject
        stored.getAsJsonObject("input").remove("social_accounts")
        val operation = stored.getAsJsonObject("pending")
        val oldPayload = JsonParser.parseString(operation.get("inputJson").asString).asJsonObject.apply { remove("social_accounts") }.toString()
        operation.addProperty("inputJson", oldPayload)
        val oldId = operation.get("operationId").asString
        memory.entries[key] = stored.toString()
        val loaded = FriendCardDraftStore(memory).load(original.account, null)!!
        store.save(loaded)
        val retried = store.prepare(loaded).pending!!
        assertEquals(oldId, retried.operationId)
        assertEquals(oldPayload, retried.inputJson)
        assertFalse(body(FriendCardRequest.create(retried.operationId, null, retried.input())).getAsJsonObject("card").has("social_accounts"))
        assertThrows(java.io.IOException::class.java) { store.save(loaded.copy(input = FriendCardRules.copy(loaded.input).apply { socialAccounts = emptyList() })) }
        assertThrows(java.io.IOException::class.java) { store.save(loaded.copy(input = FriendCardRules.copy(loaded.input).apply { title = "changed after pending" })) }
    }

    @Test fun internationalizedPublicUrlsAcceptedByTheBackendRemainEditableOnAndroid() {
        val account = row("international", "custom", "https://例子.中国/u", "个人主页")
        assertNull(FriendCardSocialRules.validate(listOf(account)))
        val card = FriendCard().apply { title = "国际化账号"; lookingFor = "寻找同好"; socialAccounts = listOf(account) }
        val editable = FriendCardInput.fromCard(card).apply { boundaries = "尊重彼此" }
        assertNull(FriendCardRules.validate(editable))
        assertEquals(account.account, editable.socialAccounts.single().account)
    }

    @Test fun previewsUsePerRowVisibilityWithoutMutatingOrAliasingLocalSocialAccounts() {
        val source = input(listOf(
            row("secret-qq", account = "private-qq-value"),
            row("visible-wechat", "wechat", "public-wechat-value", public = true),
            row("secret-home", "custom", "https://github.com/private", "私人主页"),
        )).apply { publicFields = emptyList() }
        val before = FriendCardRules.gson.toJson(source)
        val preview = FriendCardRules.publicPreview(source, emptyList(), null)
        assertEquals(listOf("visible-wechat"), preview.socialAccounts.map { it.id })
        assertNotSame(source.socialAccounts[1], preview.socialAccounts.single())
        val localCard = FriendCard().apply { title = source.title; socialAccounts = source.socialAccounts; publicFields = emptyList() }
        for (card in listOf(preview, localCard)) {
            val text = FriendCardRules.legacyText(card, emptyList())
            assertTrue(text.contains("微信：public-wechat-value"))
            assertFalse(text.contains("private-qq-value"))
            assertFalse(text.contains("https://github.com/private"))
            assertFalse(text.contains("私人主页"))
        }
        assertFalse(FriendCardRules.gson.toJson(preview).contains("private-qq-value"))
        assertFalse(FriendCardRules.gson.toJson(preview).contains("https://github.com/private"))
        assertEquals(before, FriendCardRules.gson.toJson(source))

        val copy = FriendCardRules.copy(source)
        assertNotSame(source.socialAccounts, copy.socialAccounts)
        source.socialAccounts.indices.forEach { assertNotSame(source.socialAccounts[it], copy.socialAccounts[it]) }
        assertEquals(before, FriendCardRules.gson.toJson(copy))
        copy.socialAccounts[0].apply { account = "changed copy"; isPublic = true }
        preview.socialAccounts.single().apply { account = "changed preview"; isPublic = false }
        assertEquals(before, FriendCardRules.gson.toJson(source))
        assertNull(FriendCardRules.validate(source))
    }

    @Test fun draftRestartAndOperationSnapshotPreserveStableIdsAndEveryRowVisibility() {
        val memory = Memory()
        val store = FriendCardDraftStore(memory)
        val draft = FriendCardDraft("account-a", input = input(listOf(
            row("qq-private"), row("wechat-public", "wechat", "friend_handle", public = true),
            row("home-private", "custom", "https://github.com/profile", "个人主页"),
        )), currentLoaded = true)
        val expected = FriendCardRules.gson.toJson(draft.input)
        store.save(draft)
        val serialized = memory.entries.values.single()
        val rows = JsonParser.parseString(serialized).asJsonObject.getAsJsonObject("input").getAsJsonArray("social_accounts")
        assertEquals(listOf(false, true, false), rows.map { it.asJsonObject["is_public"].asBoolean })
        val restarted = FriendCardDraftStore(memory)
        val restored = restarted.load(draft.account, null)!!
        assertEquals(expected, FriendCardRules.gson.toJson(restored.input))
        assertNotSame(draft.input.socialAccounts[0], restored.input.socialAccounts[0])
        restarted.save(restored)
        assertEquals(serialized, memory.entries.values.single())
        val operation = restarted.prepare(restored).pending!!
        restored.input.socialAccounts[0].apply { account = "changed later"; isPublic = true }
        draft.input.socialAccounts[1].isPublic = false
        assertEquals(expected, FriendCardRules.gson.toJson(operation.input()))
        val retry = FriendCardDraftStore(memory).prepare(restored).pending!!
        assertEquals(operation.operationId, retry.operationId)
        assertEquals(operation.inputJson, retry.inputJson)
        val wireRows = body(FriendCardRequest.create(retry.operationId, null, retry.input()))
            .getAsJsonObject("card").getAsJsonArray("social_accounts")
        assertEquals(listOf("qq-private", "wechat-public", "home-private"), wireRows.map { it.asJsonObject["id"].asString })
        assertEquals(listOf(false, true, false), wireRows.map { it.asJsonObject["is_public"].asBoolean })
    }

    @Test fun oldDraftMissingOrNullSocialAccountsStayOmittedOnCreateAndEditWhileEmptyArrayClears() {
        for (shape in listOf("missing", "null", "empty")) {
            val memory = Memory()
            val store = FriendCardDraftStore(memory)
            val draft = FriendCardDraft("account-a", input = input(), currentLoaded = true)
            store.save(draft)
            val key = memory.entries.keys.single()
            val old = JsonParser.parseString(memory.entries.getValue(key)).asJsonObject
            val oldInput = old.getAsJsonObject("input")
            when (shape) {
                "missing" -> oldInput.remove("social_accounts")
                "null" -> oldInput.add("social_accounts", JsonNull.INSTANCE)
                else -> oldInput.add("social_accounts", JsonParser.parseString("[]"))
            }
            memory.entries[key] = old.toString()
            val restored = FriendCardDraftStore(memory).load(draft.account, null)!!
            if (shape == "empty") assertTrue(restored.input.socialAccounts.isEmpty()) else assertNull(restored.input.socialAccounts)
            val operation = FriendCardDraftStore(memory).prepare(restored).pending!!
            val snapshot = operation.input()
            if (shape == "empty") assertTrue(snapshot.socialAccounts.isEmpty()) else assertNull(snapshot.socialAccounts)
            val requests = listOf(
                FriendCardRequest.create(operation.operationId, null, FriendCardRules.copy(snapshot)),
                FriendCardRequest.edit("card-a", 7, snapshot),
            )
            for (request in requests) {
                val card = body(request).getAsJsonObject("card")
                if (shape == "empty") {
                    assertTrue(card["social_accounts"].isJsonArray)
                    assertEquals(0, card.getAsJsonArray("social_accounts").size())
                } else assertFalse("$shape must not clear server accounts", card.has("social_accounts"))
                assertTrue("other explicit nulls remain on the actual request", card["cover_upload_id"].isJsonNull)
            }
        }
    }

    @Test fun socialDtoParcelAndCapabilityJsonHonorWireNamesNullableLegacyDefaultsAndPrivateDefault() {
        assertNull(FriendCardInput().socialAccounts)
        assertNull(FriendCardResponses.Options().capabilities)
        for (gson in listOf(Gson(), MastodonAPIController.gson)) {
            for (json in listOf("{}", "{\"social_accounts\":null}")) {
                assertNull(gson.fromJson(json, FriendCardInput::class.java).socialAccounts)
                assertNull(FriendCardInput.fromCard(gson.fromJson(json, FriendCard::class.java)).socialAccounts)
            }
            for (json in listOf("{}", "{\"capabilities\":null}")) {
                assertNull(gson.fromJson(json, FriendCardResponses.Options::class.java).capabilities)
            }
            val none = gson.fromJson("{\"capabilities\":{}}", FriendCardResponses.Options::class.java).capabilities
            assertFalse(none.socialAccounts); assertFalse(none.linkPreview)
            for ((social, link) in listOf(true to false, false to true)) {
                val options = gson.fromJson("""{"capabilities":{"social_accounts":$social,"link_preview":$link}}""", FriendCardResponses.Options::class.java)
                assertEquals(social, options.capabilities.socialAccounts); assertEquals(link, options.capabilities.linkPreview)
                val flags = gson.toJsonTree(options).asJsonObject.getAsJsonObject("capabilities")
                assertEquals(social, flags["social_accounts"].asBoolean); assertEquals(link, flags["link_preview"].asBoolean)
            }
            for (visible in listOf("", ",\"is_public\":null")) {
                assertFalse(gson.fromJson("""{"id":"old","platform":"qq","account":"123456789"$visible}""", FriendCard.SocialAccount::class.java).isPublic)
            }
            val card = gson.fromJson("""{"schema_version":1,"id":"card-a","title":"宣言","social_accounts":[{"id":"private","platform":"qq","account":"123456789"},{"id":"public","platform":"custom","account":"https://github.com/profile","name":"主页","is_public":true}]}""", FriendCard::class.java)
            assertEquals(listOf(false, true), card.socialAccounts.map { it.isPublic })
            val encoded = gson.toJsonTree(card)
            val first = encoded.asJsonObject.getAsJsonArray("social_accounts")[0].asJsonObject
            assertTrue(first.has("is_public")); assertFalse(first.has("isPublic"))
            val restored: FriendCard = parcelRoundTrip(card)
            assertEquals(encoded, gson.toJsonTree(restored))
            assertNotSame(card.socialAccounts[0], restored.socialAccounts[0])
        }
    }

    @Test fun statusAdaptersStripOnlyBadOrUnavailableLinkPreviewAndKeepOldBareLinksReadable() {
        val bare = JsonParser.parseString("""{"schema_version":1,"mode":"link","id":"card-link","available":true,"title":"Old link","url":"https://example.com/cards/card-link"}""").asJsonObject
        val valid = bare.deepCopy().apply {
            add("link_preview", JsonParser.parseString("""{"cover_url":"https://example.com/cover.jpg","cover_focus_x":0.2,"cover_focus_y":0.7,"owner_id":"owner-a","owner_name":"用户"}"""))
        }
        val malformed = listOf("null", "[]", "true", "42", "\"bad\"", "{\"cover_url\":{}}", "{\"owner_id\":[]}",
            "{\"owner_name\":false}", "{\"cover_focus_x\":\"0.5\"}", "{\"cover_focus_x\":-0.1}", "{\"cover_focus_y\":1.1}", "{\"cover_focus_y\":1e309}")
        for (gson in listOf(Gson(), MastodonAPIController.gson)) {
            val old = status(gson, bare)
            val oldCard = requireNotNull(old.friendCard)
            assertEquals("Original text", old.content)
            assertTrue(oldCard.isSupported); assertTrue(oldCard.available)
            assertEquals("Old link", oldCard.title); assertNull(oldCard.linkPreview)
            val supported = requireNotNull(status(gson, valid).friendCard)
            assertEquals("用户", supported.linkPreview.ownerName)
            val restored: FriendCardAttachment = parcelRoundTrip(supported)
            assertEquals(gson.toJsonTree(supported), gson.toJsonTree(restored))
            for (bad in malformed) {
                val extension = bare.deepCopy().apply { add("link_preview", JsonParser.parseString(bad)) }
                val decoded = status(gson, extension)
                for (post in listOf(decoded, decoded.reblog)) {
                    assertEquals("post-a", post.id); assertEquals("Original text", post.content)
                    val extensionCard = requireNotNull(post.friendCard) { bad }
                    assertTrue(extensionCard.isSupported); assertTrue(extensionCard.available)
                    assertEquals("Old link", extensionCard.title)
                    assertEquals("https://example.com/cards/card-link", extensionCard.url)
                    assertNull(bad, extensionCard.linkPreview)
                }
            }
            val unavailable = valid.deepCopy().apply {
                addProperty("available", false)
                add("card", JsonParser.parseString("""{"title":"Stale private title","social_accounts":[{"account":"must not leak"}]}"""))
            }
            val decoded = status(gson, unavailable)
            for (post in listOf(decoded, decoded.reblog)) {
                assertEquals("Original text", post.content)
                val unavailableCard = requireNotNull(post.friendCard)
                assertTrue(unavailableCard.isSupported); assertFalse(unavailableCard.available)
                assertNull(unavailableCard.title); assertNull(unavailableCard.url)
                assertNull(unavailableCard.card); assertNull(unavailableCard.linkPreview)
            }
            assertTrue(unavailable.has("link_preview")); assertTrue(unavailable.has("card"))
        }
    }

    private fun row(id: String = "row-a", platform: String = "qq", account: String = "123456789", name: String = "", public: Boolean = false) =
        FriendCard.SocialAccount().apply { this.id = id; this.platform = platform; this.account = account; this.name = name; isPublic = public }

    private fun input(rows: List<FriendCard.SocialAccount>? = null) = FriendCardInput().apply {
        title = "宣言"; lookingFor = "朋友"; socialAccounts = rows
    }

    // Read the real request envelope, including cardBody's null-removal behavior, not a stand-in Gson serialization.
    private fun body(request: FriendCardRequest<*>): JsonObject = FriendCardRequest::class.java.superclass
        .getDeclaredField("requestBody").apply { isAccessible = true }.get(request) as JsonObject

    private fun status(gson: Gson, extension: JsonObject): Status {
        val post = """{"id":"post-a","content":"Original text","friend_card":$extension}"""
        return gson.fromJson("""{"id":"post-a","content":"Original text","friend_card":$extension,"reblog":$post}""", Status::class.java)
    }

    private fun <T> parcelRoundTrip(value: T): T {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(Parcels.wrap(value), 0)
            parcel.setDataPosition(0)
            Parcels.unwrap<T>(parcel.readParcelable<Parcelable>(javaClass.classLoader))
        } finally { parcel.recycle() }
    }

    private class Memory : FriendCardDraftStorage {
        val entries = linkedMapOf<String, String>()
        override fun read(key: String) = entries[key]
        override fun commit(key: String, value: String?): Boolean {
            if (value == null) entries.remove(key) else entries[key] = value
            return true
        }
    }
}
