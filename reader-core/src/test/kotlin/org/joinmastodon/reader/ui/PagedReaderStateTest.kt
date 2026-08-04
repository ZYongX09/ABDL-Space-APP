package org.joinmastodon.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PagedReaderStateTest {
	@Test
	fun chapterChangeCreatesNewPagerStateKey() {
		assertNotEquals(pagerStateKey("chapter-1"), pagerStateKey("chapter-2"))
		assertEquals(0, pageIndexAfterChapterChange("chapter-1", "chapter-2", 4))
	}

	@Test
	fun pageIndexIsClampedWhenPageCountShrinks() {
		assertEquals(2, clampPageIndex(pageIndex = 8, pageCount = 3))
		assertEquals(0, clampPageIndex(pageIndex = 8, pageCount = 0))
	}
}
