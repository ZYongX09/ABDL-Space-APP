package org.joinmastodon.android.friendcards

import com.google.gson.Gson
import org.joinmastodon.android.api.requests.friendcards.FriendCardInput
import org.joinmastodon.android.api.requests.friendcards.FriendCardRequest
import org.joinmastodon.android.api.requests.friendcards.FriendCardResponses
import org.joinmastodon.android.model.FriendCard
import org.joinmastodon.android.model.FriendCardAttachment
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28], application = org.joinmastodon.android.CompatibilityTestApplication::class)
class FriendCardWireTest {
    @Test fun plainGsonReadsSnakeCaseIntoCamelCasePublicDtos() {
        val value = Gson().fromJson("""{"id":"card-a","schema_version":1,"version":4,"owner_id":"22","owner":{"id":"22","display_name":"用户"},"title":"宣言","interaction_habits":"自由原文","public_fields":["interaction_habits"],"cover_upload_id":"generic-id","cover_focus_x":0.2,"cover_focus_y":0.7,"post_id":"123","diapers":[{"id":7,"name":"商品","image_url":"https://example.com/x.jpg","available":true}]}""", FriendCard::class.java)
        assertTrue(value.isSupported); assertEquals("22", value.ownerId); assertEquals("自由原文", value.interactionHabits)
        assertEquals("generic-id", value.coverUploadId); assertEquals("用户", value.owner.displayName)
        assertEquals("https://example.com/x.jpg", value.diapers[0].imageUrl); assertEquals("123", value.postId)
        assertTrue(value.isPublic("interaction_habits")); assertFalse(value.isPublic("school"))
        for (type in listOf(FriendCard::class.java, FriendCard.Owner::class.java, FriendCard.Diaper::class.java, FriendCardAttachment::class.java, FriendCardInput::class.java, FriendCardResponses.Options::class.java)) {
            type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.forEach { field -> assertTrue("${type.simpleName}.${field.name} public", Modifier.isPublic(field.modifiers)); assertFalse("${field.name} camelCase", field.name.contains('_')) }
        }
    }
    @Test fun inputSerializesOnlyContractFieldsAndNullCoverExplicitly() {
        val input = FriendCardInput().apply { interactionHabits = "原文"; coverUploadId = null; diaperIds = listOf(1, 2) }
        val json = FriendCardRules.gson.toJsonTree(input).asJsonObject
        assertEquals("原文", json["interaction_habits"].asString); assertTrue(json["cover_upload_id"].isJsonNull)
        assertFalse(json.has("interactionHabits")); assertFalse(json.has("verified")); assertFalse(json.has("owner_id"))
        assertEquals(2, json["diaper_ids"].asJsonArray.size()); assertFalse(input.publicFields.contains("school"))
    }
    @Test fun unknownOrMismatchedAttachmentFallsBackToPostContent() {
        val extension = Gson().fromJson("""{"schema_version":1,"mode":"publication","id":"a","available":true,"card":{"schema_version":1,"id":"b","title":"标题"}}""", FriendCardAttachment::class.java)
        assertFalse(extension.hasPublication())
        extension.card.id = "a"; assertTrue(extension.hasPublication())
        extension.schemaVersion = 2; assertFalse(extension.isSupported)
    }
    @Test fun requestRootIsApiAndEndpointsAreContractPaths() {
        val prefix = FriendCardRequest::class.java.getDeclaredMethod("getPathPrefix").apply { isAccessible = true }
        assertEquals("/api", prefix.invoke(FriendCardRequest.options()))
        val base = FriendCardRequest::class.java.superclass
        val path = base.getDeclaredField("path").apply { isAccessible = true }
        assertEquals("/friend-cards/history", path.get(FriendCardRequest.history("cursor")))
        assertEquals("/friend-cards/abc", path.get(FriendCardRequest.detail("abc")))
        val request = FriendCardRequest.create("operation", null, FriendCardInput())
        val body = base.getDeclaredField("requestBody").apply { isAccessible = true }.get(request) as com.google.gson.JsonObject
        assertEquals("operation", body["operation_id"].asString); assertTrue(body["expected_current_id"].isJsonNull)
    }
}
