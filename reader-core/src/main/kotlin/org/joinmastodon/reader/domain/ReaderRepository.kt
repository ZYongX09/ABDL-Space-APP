package org.joinmastodon.reader.domain

interface ReaderRepository {
	suspend fun getBook(bookId: String): ReaderBook?
	suspend fun getChapters(bookId: String): List<ReaderChapter>
	suspend fun getPosition(bookId: String): ReaderPosition
	suspend fun savePosition(bookId: String, position: ReaderPosition)
}
