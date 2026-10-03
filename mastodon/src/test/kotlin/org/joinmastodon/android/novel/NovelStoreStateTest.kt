package org.joinmastodon.android.novel

import org.joinmastodon.android.api.novels.PublicNovelStoreApi
import org.joinmastodon.reader.domain.BookFormat
import org.joinmastodon.reader.domain.ReaderBook
import org.joinmastodon.reader.domain.ReaderChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovelStoreStateTest {
	@Test fun chaptersAreSortedAcrossVolumesAndWithinEachVolume() {
		val work = PublicNovelStoreApi.WorkDto().apply {
			id = "work"
			volumes = listOf(
				volume("v2", 2, chapter("c3", 0)),
				volume("v1", 1, chapter("c2", 2), chapter("c1", 1)),
			)
		}
		assertEquals(listOf("c1", "c2", "c3"), sortedPublicChapters(work).map { it.id })
	}

	@Test fun publicationChangeExitsReaderAndKeepsRefreshedDirectoryVisible() {
		val oldWork = PublicNovelStoreApi.WorkDto().apply { id = "work"; title = "旧目录" }
		val refreshed = PublicNovelStoreApi.WorkDto().apply { id = "work"; title = "新目录" }
		val reader = NovelReaderState(
			ReaderBook("public:work:revision", "作品", null, BookFormat.TXT),
			listOf(ReaderChapter("chapter", "work", 0, "章节", "正文", "revision")),
		)
		val state = NovelStoreState(selectedWork = oldWork, reader = reader, publicReader = PublicReaderState(oldWork, emptyList(), 0, reader))
		val changed = publicationChangedState(state, refreshed)
		assertEquals(refreshed, changed.selectedWork)
		assertNull(changed.reader)
		assertNull(changed.publicReader)
		assertEquals("作品内容已更新，请从目录重新打开章节", changed.error)
	}

	private fun volume(id: String, order: Int, vararg chapters: PublicNovelStoreApi.ChapterDto) = PublicNovelStoreApi.VolumeDto().apply {
		this.id = id
		sortOrder = order
		this.chapters = chapters.toList()
	}

	private fun chapter(id: String, order: Int) = PublicNovelStoreApi.ChapterDto().apply {
		this.id = id
		sortOrder = order
		publishedRevisionId = "revision-$id"
	}
}
