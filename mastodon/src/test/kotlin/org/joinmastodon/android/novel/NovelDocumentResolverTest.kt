package org.joinmastodon.android.novel

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovelDocumentResolverTest {
	@Test fun uriWithoutExtensionUsesEpubMimeBeforeDisplayNameFallback() {
		val uri = Uri.parse("content://novels/document/42")
		assertEquals("epub", NovelDocumentResolver({ "application/epub+zip" }, { "novel" }).resolve(uri).format)
	}

	@Test fun unknownMimeAndNameAreRejected() {
		val uri = Uri.parse("content://unknown/document/42")
		assertThrows(IllegalStateException::class.java) { NovelDocumentResolver({ "application/octet-stream" }, { "novel" }).resolve(uri) }
	}

	@Test fun selectedDocumentResolutionFailureCallsErrorInsteadOfResolved() {
		val uri = Uri.parse("content://unknown/document/42")
		var resolved: NovelDocument? = null
		var error: String? = null

		handleSelectedDocument(uri, { throw IllegalStateException("不支持的文件") }, { resolved = it.second }, { error = it })

		assertNull(resolved)
		assertEquals("不支持的文件", error)
	}
}
