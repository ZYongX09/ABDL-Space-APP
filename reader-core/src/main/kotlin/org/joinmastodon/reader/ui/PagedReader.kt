package org.joinmastodon.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.resolveDefaults
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.joinmastodon.reader.domain.ReaderChapter
import org.joinmastodon.reader.domain.ReadingSettings

@Composable
fun PagedReader(
	chapter: ReaderChapter,
	settings: ReadingSettings,
	initialPage: Int,
	onPageChanged: (Int) -> Unit,
	onToggleControls: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val layoutDirection = LocalLayoutDirection.current
	val density = LocalDensity.current
	val style = resolveDefaults(readerBodyTextStyle(settings), layoutDirection)
	val measurer = rememberTextMeasurer(cacheSize = 0)
	// Async font completion must invalidate pagination too, not just redraw the existing pages.
	val typeface by LocalFontFamilyResolver.current.resolve(style.fontFamily, style.fontWeight ?: FontWeight.Normal,
		style.fontStyle ?: FontStyle.Normal, style.fontSynthesis ?: FontSynthesis.All)
	val pageChanged by rememberUpdatedState(onPageChanged)
	val toggleControls by rememberUpdatedState(onToggleControls)
	// A page number is not a text position after a font/viewport change. Save the current range's
	// start across recomposition AND Activity recreation; the host's persisted page unit is unchanged.
	var anchor by rememberSaveable(chapter.id, chapter.content) { mutableIntStateOf(-1) }
	BoxWithConstraints(
		modifier.fillMaxSize().background(settings.palette.background)
			.pointerInput(chapter.id) { detectTapGestures(onTap = { toggleControls() }) }
			.padding(horizontal = settings.horizontalPadding.dp, vertical = 18.dp),
	) {
		val viewport = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
		if (viewport.maxWidth <= 0 || viewport.maxHeight <= 0) return@BoxWithConstraints
		// Recreate only the measured layout/pager, NEVER the saved source anchor. LocalDensity
		// (including fontScale) is part of rememberTextMeasurer's identity.
		key(chapter.id, chapter.content, viewport, style, density.density, density.fontScale, layoutDirection, measurer, typeface) {
			val pages by produceState<List<ReaderPage>?>(initialValue = null) {
				value = withContext(Dispatchers.Default) {
					val context = coroutineContext
					paginateReaderText(chapter.content, style, viewport, measurer) { context.ensureActive() }
				}
			}
			val readyPages = pages
			if (readyPages == null) {
				Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					CircularProgressIndicator(color = settings.palette.secondaryText)
				}
			} else {
				val restoredPage = if (anchor >= 0) pageIndexForAnchor(readyPages, anchor) else clampPageIndex(initialPage, readyPages.size)
				val pagerState = rememberPagerState(initialPage = restoredPage, pageCount = { readyPages.size })
				LaunchedEffect(pagerState) {
					var firstEmission = true
					snapshotFlow { pagerState.settledPage }.collect { page ->
						val index = clampPageIndex(page, readyPages.size)
						// Keep the exact source anchor when merely reflowing. Otherwise repeated
						// toolbar/font/rotation changes would gradually walk back through the book.
						if (!firstEmission || anchor < 0) anchor = readyPages[index].start
						firstEmission = false
						pageChanged(index)
					}
				}
				HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
					val range = readyPages[page]
					Box(Modifier.fillMaxSize()) {
						Text(
							text = chapter.content.substring(range.start, range.end),
							color = settings.palette.text,
							style = style,
							softWrap = true,
							overflow = TextOverflow.Clip,
							modifier = if (range.needsScroll) Modifier.fillMaxWidth()
								.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
							else Modifier.fillMaxWidth(),
						)
					}
				}
			}
		}
	}
}

@Composable
internal fun readerBodyTextStyle(settings: ReadingSettings): TextStyle = LocalTextStyle.current.merge(
	TextStyle(fontSize = settings.fontSize.sp, lineHeight = (settings.fontSize * settings.lineHeight).sp),
)

internal fun pagerStateKey(chapterId: String): String = chapterId

internal fun clampPageIndex(pageIndex: Int, pageCount: Int): Int = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

internal fun pageIndexAfterChapterChange(currentChapterId: String, nextChapterId: String, currentPageIndex: Int): Int =
	if (currentChapterId == nextChapterId) currentPageIndex else 0
