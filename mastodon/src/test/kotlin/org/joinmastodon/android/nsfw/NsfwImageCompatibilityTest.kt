package org.joinmastodon.android.nsfw

import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import org.joinmastodon.android.CompatibilityTestApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27], application = CompatibilityTestApplication::class)
class NsfwImageCompatibilityTest {
	@Test fun imageDecodesOnAndroid8WithoutImageDecoderApi() {
		val context = RuntimeEnvironment.getApplication()
		val file = File(context.cacheDir, "compat-image.png")
		val bitmap = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
		file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
		bitmap.recycle()
		val decoded = NsfwDetector.decodeImage(context, Uri.fromFile(file))
		assertEquals(100, decoded.width)
		assertEquals(80, decoded.height)
		decoded.recycle()
		assertTrue(file.delete())
	}
}
