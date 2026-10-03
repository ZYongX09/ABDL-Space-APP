package org.joinmastodon.android.ui.displayitems

import org.joinmastodon.android.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaGridImageUrlTest {
	@Test
	fun imagePrefersPreviewUrl() {
		assertEquals(
			"https://api.abdl-space.top/preview.jpg",
			MediaGridImageUrl.select(Attachment.Type.IMAGE, "https://img.abdl-space.top/original.jpg", "https://api.abdl-space.top/preview.jpg"),
		)
	}

	@Test
	fun imageFallsBackToOriginalForMissingPreview() {
		assertEquals("original", MediaGridImageUrl.select(Attachment.Type.IMAGE, "original", null))
		assertEquals("original", MediaGridImageUrl.select(Attachment.Type.IMAGE, "original", ""))
	}

	@Test
	fun videoAndGifvKeepUsingPreviewUrl() {
		assertEquals("video-preview", MediaGridImageUrl.select(Attachment.Type.VIDEO, "video", "video-preview"))
		assertEquals("gif-preview", MediaGridImageUrl.select(Attachment.Type.GIFV, "gif", "gif-preview"))
	}
}
