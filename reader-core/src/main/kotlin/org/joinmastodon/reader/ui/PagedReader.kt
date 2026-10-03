package org.joinmastodon.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.reader.domain.ReaderChapter
import org.joinmastodon.reader.domain.ReadingSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PagedReader(
	chapter: ReaderChapter,
	settings: ReadingSettings,
	initialPage: Int,
	onPageChanged: (Int) -> Unit,
	onToggleControls: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val pages by produceState<List<String>?>(initialValue = null, chapter.content, settings.fontSize) {
		value = withContext(Dispatchers.Default) { paginate(chapter.content, settings.fontSize) }
	}
	if (pages == null) {
		Box(Modifier.fillMaxSize().background(settings.palette.background), contentAlignment = Alignment.Center) {
			CircularProgressIndicator(color = settings.palette.secondaryText)
		}
		return
	}
	val readyPages = pages.orEmpty()
	key(pagerStateKey(chapter.id)) {
		val pagerState = rememberPagerState(initialPage = clampPageIndex(initialPage, readyPages.size), pageCount = { readyPages.size })
		LaunchedEffect(readyPages.size) {
			val clampedPage = clampPageIndex(pagerState.currentPage, readyPages.size)
			if (clampedPage != pagerState.currentPage) pagerState.scrollToPage(clampedPage)
		}
		LaunchedEffect(pagerState.currentPage) { onPageChanged(pagerState.currentPage) }
		HorizontalPager(
			state = pagerState,
			modifier = modifier
				.fillMaxSize()
				.background(settings.palette.background)
				.pointerInput(chapter.id) { detectTapGestures(onTap = { onToggleControls() }) },
		) { page ->
			Box(Modifier.fillMaxSize().padding(horizontal = settings.horizontalPadding.dp, vertical = 18.dp)) {
				Text(
				text = readyPages[page],
					color = settings.palette.text,
					style = TextStyle(fontSize = settings.fontSize.sp, lineHeight = (settings.fontSize * settings.lineHeight).sp),
				)
			}
		}
	}
}

internal fun pagerStateKey(chapterId: String): String = chapterId

internal fun clampPageIndex(pageIndex: Int, pageCount: Int): Int = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

internal fun pageIndexAfterChapterChange(currentChapterId: String, nextChapterId: String, currentPageIndex: Int): Int =
	if (currentChapterId == nextChapterId) currentPageIndex else 0

private fun paginate(content: String, fontSize: Float): List<String> {
	val target = (820f * 19f / fontSize).toInt().coerceAtLeast(360)
	val pages = mutableListOf<String>()
	var remaining = content.trim()
	while (remaining.length > target) {
		val split = remaining.lastIndexOf('\n', target).takeIf { it > target / 2 }
			?: remaining.lastIndexOf(' ', target).takeIf { it > target / 2 }
			?: target
		pages += remaining.substring(0, split).trim()
		remaining = remaining.substring(split).trimStart()
	}
	if (remaining.isNotBlank()) pages += remaining
	return pages.ifEmpty { listOf("") }
}
