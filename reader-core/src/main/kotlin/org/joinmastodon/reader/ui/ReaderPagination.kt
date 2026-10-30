package org.joinmastodon.reader.ui

import android.icu.text.BreakIterator
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import java.util.Locale

/** Half-open offsets into the UNMODIFIED chapter, in Compose's UTF-16 offset unit. */
internal data class ReaderPage(val start: Int, val end: Int, val needsScroll: Boolean = false)

internal fun readerTextLayout(
	text: String,
	style: TextStyle,
	viewport: Constraints,
	measurer: TextMeasurer,
): TextLayoutResult = measurer.measure(
	text = text,
	style = style,
	overflow = TextOverflow.Clip,
	softWrap = true,
	constraints = viewport,
	skipCache = true,
)

/** Checking size.height alone is wrong: Text clamps that size even when later lines are clipped. */
internal fun readerLayoutFits(layout: TextLayoutResult, viewport: Constraints): Boolean =
	!layout.hasVisualOverflow && layout.multiParagraph.height <= viewport.maxHeight &&
		(0 until layout.lineCount).all { line ->
			layout.getLineTop(line) >= 0f && layout.getLineBottom(line) <= viewport.maxHeight &&
				layout.getLineLeft(line) >= 0f && layout.getLineRight(line) <= viewport.maxWidth
		}

/** Android ICU understands extended graphemes; java.text's iterator can split emoji sequences.
 * Also protect newer emoji joins on devices whose bundled ICU predates those emoji versions.
 * Do not merge adjacent emoji: a family/flag/keycap is atomic, a run of separate emoji is not.
 */
internal class ReaderTextBoundaries(private val text: String) {
	private val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply {
		// BreakIterator also has a getText(): CharacterIterator; qualify the source, otherwise
		// this receiver's initially EMPTY text silently shadows ReaderTextBoundaries.text.
		setText(this@ReaderTextBoundaries.text)
	}

	fun beforeOrAt(offset: Int): Int {
		if (offset >= text.length) return text.length
		var boundary = if (iterator.isBoundary(offset)) offset else iterator.preceding(offset)
		while (boundary > 0 && joinsAt(boundary)) boundary = iterator.preceding(boundary)
		return boundary
	}

	fun preceding(offset: Int): Int = beforeOrAt(iterator.preceding(offset))

	fun following(offset: Int): Int {
		var boundary = iterator.following(offset)
		while (boundary in 1 until text.length && joinsAt(boundary)) boundary = iterator.following(boundary)
		return boundary
	}

	private fun joinsAt(offset: Int): Boolean {
		val left = text.codePointBefore(offset)
		val right = text.codePointAt(offset)
		val type = Character.getType(right)
		return left == 0x200D || right == 0x200D || (left == 13 && right == 10) ||
			type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() ||
			type == Character.ENCLOSING_MARK.toInt() || right in 0x1F3FB..0x1F3FF ||
			right in 0xE0020..0xE007F || right in 0xE0100..0xE01EF
	}
}

/**
 * Page the same text/style/pixel constraints as the rendered Text. The bounded probe is ONLY an
 * allocation optimisation; it is never a page length. Every chosen substring is measured again,
 * including terminal newlines (which themselves create a line) and fallback/emoji font metrics.
 */
internal fun paginateReaderText(
	content: String,
	style: TextStyle,
	viewport: Constraints,
	measurer: TextMeasurer,
	checkCancelled: () -> Unit = {},
): List<ReaderPage> {
	require(viewport.hasBoundedWidth && viewport.hasBoundedHeight)
	require(viewport.maxWidth > 0 && viewport.maxHeight > 0)
	if (content.isEmpty()) return listOf(ReaderPage(0, 0))
	val characters = ReaderTextBoundaries(content)
	val pages = ArrayList<ReaderPage>()
	var start = 0
	while (start < content.length) {
		checkCancelled()
		var probeEnd = characters.beforeOrAt((start.toLong() + 2048).coerceAtMost(content.length.toLong()).toInt())
		if (probeEnd <= start) probeEnd = characters.following(start)
		var probe = readerTextLayout(content.substring(start, probeEnd), style, viewport, measurer)
		while (readerLayoutFits(probe, viewport) && probeEnd < content.length) {
			checkCancelled()
			val next = characters.beforeOrAt((start.toLong() + (probeEnd - start).toLong() * 2).coerceAtMost(content.length.toLong()).toInt())
			probeEnd = if (next > probeEnd) next else characters.following(probeEnd)
			probe = readerTextLayout(content.substring(start, probeEnd), style, viewport, measurer)
		}
		if (readerLayoutFits(probe, viewport)) {
			pages += ReaderPage(start, probeEnd)
			start = probeEnd
			continue
		}
		// Use real line boundaries, then verify the standalone page: paragraph shaping and a
		// trailing newline can change the standalone result compared with the longer probe.
		val firstClippedLine = (0 until probe.lineCount).firstOrNull {
			probe.getLineTop(it) < 0f || probe.getLineBottom(it) > viewport.maxHeight ||
				probe.getLineLeft(it) < 0f || probe.getLineRight(it) > viewport.maxWidth
		}
		var end = characters.beforeOrAt(start + when {
			firstClippedLine != null -> probe.getLineStart(firstClippedLine)
			else -> probeEnd - start
		})
		var pageLayout = if (end > start) readerTextLayout(content.substring(start, end), style, viewport, measurer) else null
		while (end > start && pageLayout != null && !readerLayoutFits(pageLayout, viewport)) {
			checkCancelled()
			end = characters.preceding(end)
			pageLayout = if (end > start) readerTextLayout(content.substring(start, end), style, viewport, measurer) else null
		}
		if (end <= start) {
			// A landscape/multi-window viewport may be shorter than even ONE font/emoji line.
			// Never skip it or loop forever: render this grapheme in a scrollable page instead.
			end = characters.following(start)
			pages += ReaderPage(start, end, needsScroll = true)
		} else pages += ReaderPage(start, end)
		start = end
	}
	return pages
}

internal fun pageIndexForAnchor(pages: List<ReaderPage>, offset: Int): Int {
	if (pages.isEmpty()) return 0
	val anchor = offset.coerceIn(0, pages.last().end.coerceAtLeast(0))
	var low = 0
	var high = pages.lastIndex
	while (low < high) {
		val middle = (low + high + 1) ushr 1
		if (pages[middle].start <= anchor) low = middle else high = middle - 1
	}
	return low
}
