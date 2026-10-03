package org.joinmastodon.android.novel

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelLibraryViewModelContractTest {
	private val source = File(requireNotNull(System.getProperty("user.dir")), "src/main/kotlin/org/joinmastodon/android/novel/NovelLibraryViewModel.kt").readText()

	@Test fun workerIsMetadataAuthorityAndOperationsAreSessionGuarded() {
		assertFalse(source.contains("newBooksCall"))
		assertTrue(source.contains("NovelSyncWorker.enqueue"))
		assertTrue(source.contains("private fun guard()"))
		assertTrue(source.contains("NovelAccountDataCleaner.isSessionValid"))
	}

	@Test fun uploadAndPasteRequireNonNullAuthor() {
		assertTrue(source.contains("fun paste(title: String, author: String, text: String)"))
		assertTrue(source.contains("fun upload(uri: Uri, title: String, author: String"))
	}
}
