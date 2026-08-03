package org.joinmastodon.android.reader

import org.junit.Test

class ReaderModuleContractTest {
	@Test
	fun exposesNovelDatabase() {
		Class.forName("org.joinmastodon.reader.data.NovelDatabase")
	}
}
