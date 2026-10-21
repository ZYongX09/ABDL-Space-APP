package org.joinmastodon.android.friendcards

import android.os.Parcel
import android.os.Parcelable
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.api.MastodonAPIController
import org.joinmastodon.android.model.Account
import org.joinmastodon.android.model.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.parceler.Parcels
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Public profile payloads carry a versioned reference, not a card snapshot or authorization. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class)
class FriendCardPublicReferenceWireTest {
    @Before fun initializeApiContext() {
        MastodonApp.context = RuntimeEnvironment.getApplication()
    }

    @Test fun validReferencePinsExplicitWireNamesWithPlainAndBothProductionGsons() {
        for (gson in listOf(Gson(), MastodonAPIController.gsonWithoutDeserializer, MastodonAPIController.gson)) {
            for (id in listOf(cardId, cardId.uppercase())) {
                val wire = """{"schema_version":1,"id":"$id","future_hint":{"ignored":true}}"""
                val account = gson.fromJson(accountJson(wire), Account::class.java)
                assertAccount(account)
                val reference = requireNotNull(account.publicFriendCard)
                assertEquals(1, reference.schemaVersion)
                assertEquals(id, reference.id)
                assertTrue(reference.isSupported)
                val encoded = gson.toJsonTree(account).asJsonObject
                assertFalse(encoded.has("publicFriendCard"))
                val encodedReference = encoded.getAsJsonObject("public_friend_card")
                assertEquals(setOf("schema_version", "id"), encodedReference.keySet())
                assertEquals(1, encodedReference["schema_version"].asInt)
                assertEquals(id, encodedReference["id"].asString)
                assertTrue(gson.fromJson(encodedReference, Account.PublicFriendCard::class.java).isSupported)
            }
        }
    }

    @Test fun absentNullUnknownAndMalformedReferencesNeverDiscardTheAccount() {
        // The tolerant adapter is registered on these actual instances, not on plain Gson.
        for (gson in listOf(MastodonAPIController.gsonWithoutDeserializer, MastodonAPIController.gson)) {
            for ((label, wire) in invalidReferences()) {
                val account = gson.fromJson(accountJson(wire), Account::class.java)
                account.postprocess()
                assertAccount(account)
                assertNull(label, account.publicFriendCard)
                assertEquals("https://profile-fixture.example.test/@friend", account.query)
                assertTrue(account.fields.isEmpty())
                assertTrue(account.emojis.isEmpty())
            }
        }
    }

    @Test fun ordinaryStatusAndNestedReblogSurviveEveryOptionalAccountReferenceShape() {
        val cases = listOf("supported" to referenceJson) + invalidReferences()
        for (gson in listOf(MastodonAPIController.gsonWithoutDeserializer, MastodonAPIController.gson)) {
            for ((label, wire) in cases) {
                val nested = postJson("nested-post", "<p>Nested ordinary text</p>", accountJson(wire))
                val outer = postJson("outer-post", "<p>Original ordinary text</p>", accountJson(wire), nested)
                val status = gson.fromJson(outer, Status::class.java)
                status.postprocess()
                for ((post, expected) in listOf(
                    status to ("outer-post" to "<p>Original ordinary text</p>"),
                    requireNotNull(status.reblog) to ("nested-post" to "<p>Nested ordinary text</p>"),
                )) {
                    assertEquals(label, expected.first, post.id)
                    assertEquals(label, expected.second, post.content)
                    assertAccount(post.account)
                    assertNull("A profile reference must not manufacture a post attachment: $label", post.friendCard)
                    assertNull(post.friendRequest)
                    assertTrue(post.mediaAttachments.isEmpty())
                    if (label == "supported") {
                        assertEquals(cardId, requireNotNull(post.account.publicFriendCard).id)
                        assertTrue(post.account.publicFriendCard.isSupported)
                    } else assertNull(label, post.account.publicFriendCard)
                }
                assertNull(status.reblog.reblog)
            }
        }
    }

    @Test fun parcelRoundTripsPinTheNestedDtoAccountFieldAndNullableLegacyDefault() {
        for (gson in listOf(Gson(), MastodonAPIController.gsonWithoutDeserializer, MastodonAPIController.gson)) {
            val account = gson.fromJson(accountJson(referenceJson), Account::class.java)
            val restored: Account = parcelRoundTrip(account)
            assertAccount(restored)
            assertNotSame(account, restored)
            assertNotSame(account.publicFriendCard, restored.publicFriendCard)
            assertEquals(cardId, requireNotNull(restored.publicFriendCard).id)
            assertEquals(1, restored.publicFriendCard.schemaVersion)
            assertTrue(restored.publicFriendCard.isSupported)
            val reference: Account.PublicFriendCard = parcelRoundTrip(account.publicFriendCard)
            assertNotSame(account.publicFriendCard, reference)
            assertEquals(JsonParser.parseString(referenceJson), gson.toJsonTree(reference))
            assertTrue(reference.isSupported)
            for (wire in listOf(null, "null")) {
                val legacy = gson.fromJson(accountJson(wire), Account::class.java)
                assertNull(legacy.publicFriendCard)
                val legacyRestored: Account = parcelRoundTrip(legacy)
                assertAccount(legacyRestored)
                assertNull(legacyRestored.publicFriendCard)
            }
        }
        assertNull(Account().publicFriendCard)
        assertFalse(Account.PublicFriendCard().isSupported)
        val unsupported = Account.PublicFriendCard().apply { schemaVersion = 2; id = cardId }
        assertFalse(unsupported.isSupported)
        unsupported.schemaVersion = 1
        unsupported.id = "not-a-uuid"
        assertFalse(unsupported.isSupported)
    }

    private fun invalidReferences(): List<Pair<String, String?>> = listOf(
        "missing" to null,
        "null" to "null",
        "array" to "[]",
        "nonempty array" to "[$referenceJson]",
        "boolean" to "true",
        "number" to "42",
        "string" to "\"not an object\"",
        "empty object" to "{}",
        "missing schema" to """{"id":"$cardId"}""",
        "missing id" to """{"schema_version":1}""",
    ) + listOf("0", "2", "-1", "1.5", "1e309", "\"1\"", "true", "null", "[]", "{}")
        .map { "unsupported or malformed schema $it" to """{"schema_version":$it,"id":"$cardId"}""" } +
        listOf("null", "42", "true", "[]", "{}", "\"\"", "\"card-a\"", "\"1-1-4-8-1\"",
            "\" $cardId\"", "\"$cardId \"", "\"https://example.test/$cardId\"",
            "\"00000000-0000-0000-0000-000000000000\"",
            "\"123e4567-e89b-92d3-a456-426614174000\"",
            "\"123e4567-e89b-42d3-7456-426614174000\"",
            "\"123e4567-e89b-42d3-a456-42661417400g\"")
            .map { "malformed UUID/id $it" to """{"schema_version":1,"id":$it}""" }

    private fun accountJson(reference: String?): String {
        val extension = reference?.let { ",\"public_friend_card\":$it" }.orEmpty()
        return """{"id":"42","username":"friend","acct":"friend@profile-fixture.example.test","url":"https://profile-fixture.example.test/@friend","note":"Original profile text","locked":true$extension}"""
    }

    private fun postJson(id: String, content: String, account: String, reblog: String? = null): String =
        """{"id":"$id","uri":"https://profile-fixture.example.test/posts/$id","created_at":"2026-10-20T00:00:00Z","visibility":"public","content":"$content","spoiler_text":"","account":$account,"mentions":[],"tags":[],"emojis":[],"media_attachments":[],"reblog":${reblog ?: "null"}}"""

    private fun assertAccount(account: Account) {
        assertEquals("42", account.id)
        assertEquals("friend", account.username)
        assertEquals("friend@profile-fixture.example.test", account.acct)
        assertEquals("https://profile-fixture.example.test/@friend", account.url)
        assertEquals("Original profile text", account.note)
        assertTrue(account.locked)
    }

    private fun <T> parcelRoundTrip(value: T): T {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(Parcels.wrap(value), 0)
            parcel.setDataPosition(0)
            Parcels.unwrap<T>(parcel.readParcelable<Parcelable>(javaClass.classLoader))
        } finally { parcel.recycle() }
    }

    companion object {
        private const val cardId = "123e4567-e89b-42d3-a456-426614174000"
        private const val referenceJson = """{"schema_version":1,"id":"$cardId"}"""
    }
}
