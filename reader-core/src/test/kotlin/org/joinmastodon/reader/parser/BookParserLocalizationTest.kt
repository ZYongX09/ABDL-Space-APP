package org.joinmastodon.reader.parser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS")
class BookParserLocalizationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun stableParserFailuresRemainIllegalArgumentExceptionsAndUseLibraryResources() {
        val failure = BookParseException(BookParseError.EPUB_NO_CHAPTERS)
        assertTrue(failure is IllegalArgumentException)
        assertEquals("EPUB_NO_CHAPTERS", failure.message)
        assertEquals("EPUB contains no readable chapters.", failure.userMessage(context))
        assertNotNull(BookParser()) // Existing Java/Kotlin no-argument API stays available.
    }

    @Test @Config(qualifiers = "zh-rCN")
    fun chineseParserErrorIsResolvedWithoutChangingTheStableCode() {
        val failure = BookParseException(BookParseError.EPUB_NO_CHAPTERS)
        assertEquals("EPUB_NO_CHAPTERS", failure.message)
        assertEquals("EPUB 没有可读取的章节。", failure.userMessage(context))
    }
}
