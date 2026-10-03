package org.joinmastodon.reader.domain

import androidx.compose.ui.graphics.Color

enum class BookFormat {
	TXT,
	EPUB,
	DOCX,
}

data class ReaderBook(
	val id: String,
	val title: String,
	val author: String? = null,
	val format: BookFormat,
)

data class ReaderChapter(
	val id: String,
	val bookId: String,
	val index: Int,
	val title: String,
	val content: String,
	val anchorId: String,
)

data class ParsedBook(
	val book: ReaderBook,
	val chapters: List<ReaderChapter>,
)

data class ReadingSettings(
	val fontSize: Float = 19f,
	val lineHeight: Float = 1.65f,
	val horizontalPadding: Float = 28f,
	val paged: Boolean = true,
	val palette: ReaderPalette = ReaderPalette.PAPER,
)

enum class ReaderPalette(
	val background: Color,
	val text: Color,
	val secondaryText: Color,
) {
	PAPER(Color(0xFFF5F0E5), Color(0xFF292620), Color(0xFF716B60)),
	WHITE(Color(0xFFFDFDFD), Color(0xFF202124), Color(0xFF6C7075)),
	NIGHT(Color(0xFF151719), Color(0xFFD5D1C8), Color(0xFF918D86)),
}

data class ReaderPosition(
	val chapterIndex: Int = 0,
	val pageIndex: Int = 0,
)
