package org.joinmastodon.reader.parser

import android.content.Context
import androidx.annotation.StringRes
import org.joinmastodon.reader.R

/** Stable diagnostic codes; keeps the existing IllegalArgumentException handling semantics. */
enum class BookParseError(@get:StringRes val messageRes: Int) {
    UNSUPPORTED_FORMAT(R.string.novel_reader_unsupported_format),
    EPUB_CONTAINER_MISSING(R.string.novel_reader_epub_container_missing),
    EPUB_OPF_INVALID(R.string.novel_reader_epub_opf_invalid),
    EPUB_OPF_MISSING(R.string.novel_reader_epub_opf_missing),
    EPUB_NO_CHAPTERS(R.string.novel_reader_epub_no_chapters),
    DOCX_DOCUMENT_MISSING(R.string.novel_reader_docx_document_missing),
    DOCX_NO_BODY(R.string.novel_reader_docx_no_body),
}

class BookParseException(val code: BookParseError) : IllegalArgumentException(code.name) {
    fun userMessage(context: Context): String = context.getString(code.messageRes)
}
