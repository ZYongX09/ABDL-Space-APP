package org.joinmastodon.android.ui.compose.friendcards

import org.joinmastodon.android.api.requests.friendcards.FriendCardInput
import org.joinmastodon.android.friendcards.FriendCardRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The completion percentage is informational, not a new required-field or privacy policy. */
class FriendCardCompletionTest {
    @Test
    fun emptyAndWhitespaceOnlyFieldsDoNotContributeAndAnOptionalCoverIsOneSlot() {
        val input = FriendCardInput()
        assertEquals(0, FriendCardCompletion(input, hasCover = false))
        assertEquals(7, FriendCardCompletion(input, hasCover = true))

        input.apply {
            title = " \n\t"
            gender = " "
            age = "\t"
            zodiac = "\n"
            region = " "
            school = " "
            mbti = " "
            interactionHabits = "\n"
            lookingFor = " "
            boundaries = "\t"
            // Upload metadata and focus are not evidence that a cover is available to render.
            coverUploadId = "synthetic-upload-without-renderable-cover"
            coverFocusX = .25f
            coverFocusY = .75f
        }
        assertEquals(0, FriendCardCompletion(input, hasCover = false))
        assertEquals(7, FriendCardCompletion(input, hasCover = true))
    }

    @Test
    fun everyFilledSlotContributesOnceWithIntegerRoundingAndNoFakeStatistics() {
        val input = FriendCardInput()
        populatedSlots().forEachIndexed { index, fill ->
            fill(input)
            assertEquals("Filled text/list slots: ${index + 1}", (index + 1) * 100 / 14,
                FriendCardCompletion(input, hasCover = false))
            assertEquals("The cover is an independent fourteenth slot", (index + 2) * 100 / 14,
                FriendCardCompletion(input, hasCover = true))
        }
        assertEquals(92, FriendCardCompletion(input, hasCover = false))
        assertEquals(100, FriendCardCompletion(input, hasCover = true))

        // More tags or products must not turn a completion score into an invented activity score.
        input.interests = input.interests + "散步"
        input.games = input.games + "minecraft"
        input.diaperIds = input.diaperIds + 902
        assertEquals(100, FriendCardCompletion(input, hasCover = true))
    }

    @Test
    fun optionalFieldsCanStayEmptyAndPrivateAnswersCountWithoutBeingMutatedOrMadePublic() {
        val minimal = FriendCardInput().apply {
            title = "虚构成人样例：周末一起散步"
            lookingFor = "认识尊重边界的成年朋友。"
            publicFields = emptyList()
        }
        assertNull("Optional age, school, habits, tags and cover must stay optional", FriendCardRules.validate(minimal))
        assertEquals(14, FriendCardCompletion(minimal, hasCover = false))

        val complete = FriendCardInput().apply {
            populatedSlots().forEach { it(this) }
            publicFields = emptyList()
        }
        val before = FriendCardRules.gson.toJson(complete)
        assertEquals(100, FriendCardCompletion(complete, hasCover = true))
        assertEquals("Scoring must not publish or otherwise rewrite private answers", before, FriendCardRules.gson.toJson(complete))
        complete.publicFields = FriendCardRules.fields.keys.toList()
        assertEquals("Visibility is independent of completeness", 100, FriendCardCompletion(complete, hasCover = true))
    }

    private fun populatedSlots(): List<(FriendCardInput) -> Unit> = listOf(
        { it.title = "虚构成人样例：周末一起散步" },
        { it.gender = "自愿填写的性别" },
        { it.age = "28岁（虚构成人）" },
        { it.zodiac = "天秤座" },
        { it.region = "虚构城市" },
        { it.school = "虚构成人夜校" },
        { it.mbti = "INFP" },
        { it.interactionHabits = "下班后慢回复，先文字沟通。" },
        { it.interests = listOf("摄影") },
        { it.games = listOf("stardew-valley") },
        { it.diaperIds = listOf(901) },
        { it.lookingFor = "认识尊重边界的成年朋友。" },
        { it.boundaries = "不接受未经同意的联系。" },
    )
}
