package org.joinmastodon.reader.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderInsetsContractTest {
	@Test fun readerUsesSafeDrawingInsetsAndConfigurablePalette() {
		val source = File("src/main/kotlin/org/joinmastodon/reader/ui/ReaderScreen.kt").readText()
		assertTrue(source.contains("WindowInsets.safeDrawing"))
		assertTrue(source.contains("initialPalette"))
	}
}
