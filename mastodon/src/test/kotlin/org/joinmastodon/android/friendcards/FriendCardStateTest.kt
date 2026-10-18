package org.joinmastodon.android.friendcards

import org.joinmastodon.android.api.requests.friendcards.FriendCardInput
import org.joinmastodon.android.api.requests.friendcards.FriendCardResponses
import org.joinmastodon.android.model.FriendCard
import org.junit.Assert.*
import org.junit.Test

class FriendCardStateTest {
    private fun card(id: String) = FriendCard().apply { this.id = id; schemaVersion = 1; title = "宣言" }
    @Test fun freeInteractionHabitsStartsEmptyAndIsCopiedVerbatim() {
        val input = FriendCardInput()
        assertEquals("", input.interactionHabits)
        input.interactionHabits = "慢回复但会认真回；\n周末一起玩游戏"
        assertEquals(input.interactionHabits, FriendCardRules.copy(input).interactionHabits)
    }
    @Test fun customTagsTrimClearAndDoNotSplitCommas() {
        assertNotNull(FriendCardRules.addTag(emptyList(), "  \n", 12, 16).error)
        val added = FriendCardRules.addTag(emptyList(), "  A,B  ", 12, 16)
        assertNull(added.error); assertEquals(listOf("A,B"), added.values); assertEquals("", added.remainingInput)
        val duplicate = FriendCardRules.addTag(added.values, " a,b ", 12, 16)
        assertNotNull(duplicate.error); assertEquals(" a,b ", duplicate.remainingInput)
        assertNotNull(FriendCardRules.addTag(List(12) { "$it" }, "新", 12, 16).error)
        assertNotNull(FriendCardRules.addTag(emptyList(), "长".repeat(17), 12, 16).error)
    }
    @Test fun gameNamesNormalizeToStableKeysAndUnknownNamesSurvive() {
        val preset = FriendCardResponses.Game().apply { id = "genshin-impact"; name = "原神" }
        assertEquals(listOf("genshin-impact"), FriendCardRules.addGame(emptyList(), " 原神 ", listOf(preset)).values)
        assertNotNull(FriendCardRules.addGame(listOf("genshin-impact"), "原神", listOf(preset)).error)
        assertEquals("原神", FriendCardRules.gameName("genshin-impact", emptyList()))
        assertEquals("自定义旧游戏", FriendCardRules.gameName("自定义旧游戏", emptyList()))
        assertEquals("三角洲行动", FriendCardGames.gameName("delta-force"))
    }
    @Test fun privateFieldsNeverEnterEitherPreview() {
        val input = FriendCardInput().apply { title = "宣言"; lookingFor = "朋友"; school = "不能公开的学校"; interactionHabits = "私密习惯"; publicFields = publicFields - "interaction_habits" }
        val preview = FriendCardRules.publicPreview(input, emptyList(), null)
        assertEquals("", preview.school); assertEquals("", preview.interactionHabits)
        assertFalse(FriendCardRules.legacyText(preview, emptyList()).contains("不能公开"))
        assertFalse(FriendCardRules.legacyText(preview, emptyList()).contains("私密习惯"))
        assertNull(FriendCardRules.validate(input))
    }
    @Test fun validationUsesContractLimitsAndAgeIsOptionalDisplayOnly() {
        val input = FriendCardInput().apply { title = "宣言"; lookingFor = "朋友"; age = "不想写年龄" }
        assertNull(FriendCardRules.validate(input))
        input.title = "x".repeat(41); assertNotNull(FriendCardRules.validate(input))
        input.title = "😀".repeat(40); assertNull(FriendCardRules.validate(input))
        input.coverFocusX = Float.NaN; assertNotNull(FriendCardRules.validate(input))
        input.coverFocusX = .5f; input.diaperIds = listOf(1, 1); assertNotNull(FriendCardRules.validate(input))
    }
    @Test fun cursorPagesDeduplicateAndRejectOutOfOrderResponses() {
        val pages = FriendCardPages()
        val old = pages.begin()!!; assertNull(pages.begin())
        pages.reset(); assertFalse(pages.accept(old, listOf(card("old")), "old-cursor"))
        val first = pages.begin()!!
        assertTrue(pages.accept(first, listOf(card("a"), card("a")), "next"))
        val next = pages.begin()!!
        assertTrue(pages.accept(next, listOf(card("a"), card("b")), "next"))
        assertEquals(listOf("a", "b"), pages.cards.map { it.id }); assertNull(pages.nextCursor); assertNull(pages.begin())
    }
    @Test fun emptyPagesKeepAdvancingUntilExhaustionAndCursorCyclesTerminate() {
        val pages = FriendCardPages()
        assertTrue(pages.accept(pages.begin()!!, emptyList(), "first"))
        assertTrue(pages.cards.isEmpty()); assertEquals("first", pages.nextCursor)
        assertTrue(pages.accept(pages.begin()!!, listOf(card("a")), "middle"))
        assertTrue(pages.accept(pages.begin()!!, emptyList(), "duplicates"))
        assertEquals("duplicates", pages.nextCursor)
        assertTrue(pages.accept(pages.begin()!!, listOf(card("a")), "last"))
        assertTrue(pages.accept(pages.begin()!!, listOf(card("b")), null))
        assertEquals(listOf("a", "b"), pages.cards.map { it.id }); assertNull(pages.begin())
        pages.reset()
        assertTrue(pages.accept(pages.begin()!!, emptyList(), "a"))
        assertTrue(pages.accept(pages.begin()!!, emptyList(), "b"))
        assertTrue(pages.accept(pages.begin()!!, emptyList(), "a"))
        assertTrue(pages.cards.isEmpty()); assertNull(pages.nextCursor); assertNull(pages.begin())
    }
    @Test fun cursorFailureRetainsCursorAndPauseInvalidatesLateResponse() {
        val pages = FriendCardPages()
        val first = pages.begin()!!; pages.fail(first, "404暂不可用")
        assertFalse(pages.loaded); assertEquals("404暂不可用", pages.error)
        val retry = pages.begin()!!; pages.accept(retry, listOf(card("a")), "cursor")
        val next = pages.begin()!!; pages.interrupt()
        assertFalse(pages.accept(next, listOf(card("stale")), null))
        assertEquals("cursor", pages.begin()!!.cursor)
    }
    @Test fun diaperSearchGenerationAndOrderedSelectionAreIndependent() {
        val selection = FriendCardDiaperSelection("account-a", "draft-a", listOf(9, 3, 9))
        val stale = selection.begin()!!; selection.search("新关键词")
        assertFalse(selection.accept(stale, listOf(product(1)), 30))
        val pageOne = selection.begin()!!; selection.accept(pageOne, listOf(product(4)), 2)
        selection.toggle(4); val pageTwo = selection.begin()!!; selection.accept(pageTwo, listOf(product(5)), 2); selection.toggle(5)
        assertEquals(listOf(9, 3, 4, 5), selection.ids)
        selection.search("另一个搜索"); assertEquals(listOf(9, 3, 4, 5), selection.ids)
        selection.toggle(3); selection.toggle(3); assertEquals(listOf(9, 4, 5, 3), selection.ids)
        assertFalse(selection.resultMatches("account-b", "draft-a")); assertFalse(selection.resultMatches("account-a", "draft-b")); assertTrue(selection.resultMatches("account-a", "draft-a"))
    }
    @Test fun diaperSelectionZeroMeansClearAndLimitIsEight() {
        val selected = FriendCardDiaperSelection("a", "d", (1..8).toList())
        assertNotNull(selected.toggle(9)); assertEquals(8, selected.ids.size)
        (1..8).forEach { selected.toggle(it) }; assertTrue(selected.ids.isEmpty())
    }
    private fun product(id: Int) = FriendCard.Diaper().apply { this.id = id; name = "商品$id"; available = true }
}
