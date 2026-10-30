package org.joinmastodon.reader.ui

import android.content.Context
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual Android/Skia font layout, not a character-count estimate or mocked line metrics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPaginationNativeTest {
	private fun measurer(fontScale: Float = 1f, density: Float = 1f) = TextMeasurer(
		defaultFontFamilyResolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext<Context>()),
		defaultDensity = Density(density, fontScale),
		defaultLayoutDirection = LayoutDirection.Ltr,
		cacheSize = 0,
	)

	private fun style(fontSize: Float = 19f, lineHeight: Float = 1.65f) = TextStyle(
		fontFamily = FontFamily.SansSerif, fontSize = fontSize.sp, lineHeight = (fontSize * lineHeight).sp,
	)

	/** Re-measure EVERY standalone substring: summing ranges alone does not detect clipped pages. */
	private fun assertExactAndFits(content: String, viewport: Constraints, measurer: TextMeasurer,
		style: TextStyle = style(), allowScrolling: Boolean = false): List<ReaderPage> {
		val pages = paginateReaderText(content, style, viewport, measurer)
		assertTrue(pages.isNotEmpty())
		assertEquals(0, pages.first().start)
		assertEquals(content.length, pages.last().end)
		assertEquals("No trimming, normalization, missing or repeated characters", content,
			pages.joinToString("") { content.substring(it.start, it.end) })
		pages.zipWithNext().forEach { (left, right) -> assertEquals(left.end, right.start) }
		val boundaries = ReaderTextBoundaries(content)
		pages.forEach { page ->
			assertEquals(page.start, boundaries.beforeOrAt(page.start))
			assertEquals(page.end, boundaries.beforeOrAt(page.end))
			if (content.isNotEmpty()) assertTrue(page.end > page.start)
			val measured = readerTextLayout(content.substring(page.start, page.end), style, viewport, measurer)
			if (page.needsScroll) {
				assertTrue("Normal viewports must not use the oversized-grapheme escape hatch", allowScrolling)
				assertFalse(readerLayoutFits(measured, viewport))
				assertEquals("A scroll page contains exactly one indivisible cluster", boundaries.following(page.start), page.end)
			} else {
				assertTrue("Page ${page.start}..${page.end} must fit $viewport; paragraph=${measured.multiParagraph.height}",
					readerLayoutFits(measured, viewport))
				assertFalse(measured.hasVisualOverflow)
				assertTrue(measured.multiParagraph.height <= viewport.maxHeight)
				for (line in 0 until measured.lineCount) {
					assertTrue(measured.getLineTop(line) >= 0f)
					assertTrue(measured.getLineBottom(line) <= viewport.maxHeight)
					assertTrue(measured.getLineLeft(line) >= 0f)
					assertTrue(measured.getLineRight(line) <= viewport.maxWidth)
				}
			}
		}
		return pages
	}

	@Test fun androidIcuBoundariesUseTheSuppliedSourceRatherThanTheirInitiallyEmptyReceiverText() {
		val text = "Ae\u0301\uD83D\uDC69\u200D\uD83D\uDE80\r\nB"
		val boundaries = ReaderTextBoundaries(text)
		val clusters = buildList {
			var offset = 0
			while (offset < text.length) {
				val end = boundaries.following(offset)
				assertTrue("ICU must advance within the actual source", end in offset + 1..text.length)
				add(text.substring(offset, end))
				offset = end
			}
		}
		assertEquals(listOf("A", "e\u0301", "\uD83D\uDC69\u200D\uD83D\uDE80", "\r\n", "B"), clusters)
		assertEquals(1, boundaries.beforeOrAt(2))
	}

	@Test fun pixelMeasuredPagesFitAndConcatenateExactSourceAcrossViewports() {
		val content = "  原样保留开头空格。\r\n" + (1..32).joinToString("") {
			"第${it}段：这是实际字体度量的中文与标点，不能丢字。 Latin words and punctuation.\n" +
				"组合字符 e\u0301 / \uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDE80。\n\n"
		} + "  尾部空格不可 trim  \r\n\n"
		for ((width, height, scale) in listOf(Triple(337, 720, 1f), Triple(264, 384, 1.4f), Triple(640, 220, 1.8f))) {
			val pages = assertExactAndFits(content, Constraints(maxWidth = width, maxHeight = height), measurer(scale))
			assertTrue("Fixture must exercise real page breaks", pages.size > 1)
		}
		assertExactAndFits(content, Constraints(maxWidth = 560, maxHeight = 900), measurer(density = 2f))
	}

	@Test fun terminalNewlinesWhitespaceAndLongWordsAreNeverTrimmedOrDuplicated() {
		val viewport = Constraints(maxWidth = 164, maxHeight = 116)
		for (text in listOf("", " \t  \r\n\n  ", "\r\n".repeat(30), "\n".repeat(33),
			"wordWithoutABreak".repeat(180) + "\n", "首行\n\n尾行\n\n\n".repeat(20))) {
			assertExactAndFits(text, viewport, measurer())
		}
	}

	@Test fun consecutiveExtendedEmojiAndCombiningClustersStayWholeEvenInSubLineViewports() {
		val clusters = listOf(
			"\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66",
			"\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDE80",
			"\uD83C\uDDE8\uD83C\uDDF3", "\uD83C\uDDFA\uD83C\uDDF3",
			"1\uFE0F\u20E3", "e\u0301",
			"\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F",
			"\r\n",
		)
		val expected = List(3) { clusters }.flatten()
		val content = expected.joinToString("")
		val pages = assertExactAndFits(content, Constraints(maxWidth = 1, maxHeight = 1), measurer(), allowScrolling = true)
		assertEquals("No split surrogate, mark, skin tone, ZWJ, regional pair, keycap, tag or CRLF", expected,
			pages.map { content.substring(it.start, it.end) })
		assertTrue(pages.all { it.needsScroll })
	}

	@Test fun fontScaleLineHeightAndPixelResizeRepaginateAroundTheSameSourceAnchor() {
		val content = (1..120).joinToString("") { "段落$it 的原文 anchor 不能因工具栏或字体重排反复往前走。\n" }
		val normalViewport = Constraints(maxWidth = 320, maxHeight = 480)
		val normal = assertExactAndFits(content, normalViewport, measurer())
		val anchor = normal[3].start + 7 // Inside a page, not a convenient page boundary.
		val configurations = listOf(
			Triple(normalViewport, measurer(1.6f), style()),
			Triple(normalViewport, measurer(), style(lineHeight = 2.1f)),
			Triple(Constraints(maxWidth = 240, maxHeight = 260), measurer(), style()),
		)
		configurations.forEach { (viewport, measurer, style) ->
			val pages = assertExactAndFits(content, viewport, measurer, style)
			assertNotEquals(normal.map { it.end }, pages.map { it.end })
			val range = pages[pageIndexForAnchor(pages, anchor)]
			assertTrue(anchor >= range.start && anchor < range.end)
		}
		assertEquals(3, pageIndexForAnchor(normal, anchor))
		assertEquals(normal.lastIndex, pageIndexForAnchor(normal, content.length))
	}

	@Test fun aNarrowButTallViewportUsesMeasuredHorizontalOverflowFallbackWithoutLosingClusters() {
		val content = "W\uD83D\uDC69\u200D\uD83D\uDE80e\u0301".repeat(5)
		assertExactAndFits(content, Constraints(maxWidth = 1, maxHeight = 200), measurer(), allowScrolling = true)
	}
}
