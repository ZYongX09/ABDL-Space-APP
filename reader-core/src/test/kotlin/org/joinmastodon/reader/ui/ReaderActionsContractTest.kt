package org.joinmastodon.reader.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderActionsContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))
	private val screen = File(projectDir, "src/main/kotlin/org/joinmastodon/reader/ui/ReaderScreen.kt").readText()
	private val controls = File(projectDir, "src/main/kotlin/org/joinmastodon/reader/ui/ReaderControls.kt").readText()

	@Test fun readerExposesCurrentPositionBookmarkAndNoteActions() {
		assertTrue(screen.contains("onBookmark: (ReaderPosition) -> Unit"))
		assertTrue(screen.contains("onNote: (ReaderPosition) -> Unit"))
		assertTrue(screen.contains("onBookmark(ReaderPosition(chapterIndex, pageIndex))"))
		assertTrue(screen.contains("onNote(ReaderPosition(chapterIndex, pageIndex))"))
	}

	@Test fun controlsRenderProductionActionButtons() {
		assertTrue(controls.contains("TextButton(onClick = onBookmark)"))
		assertTrue(controls.contains("Text(\"添加书签\""))
		assertTrue(controls.contains("TextButton(onClick = onNote)"))
		assertTrue(controls.contains("Text(\"添加笔记\""))
	}
}
