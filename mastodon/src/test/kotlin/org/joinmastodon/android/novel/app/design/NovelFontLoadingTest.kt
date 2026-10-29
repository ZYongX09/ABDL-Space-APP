package org.joinmastodon.android.novel.app.design

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.core.content.res.ResourcesCompat
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.R
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** API 26 is the app floor. Resolve and rasterize the actual packaged font through both consumers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28], application = CompatibilityTestApplication::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NovelFontLoadingTest {
    @Test fun xmlAliasesAndComposeVariableWeightsLoadAndDrawChineseOnTheMinSdk() {
        val context = RuntimeEnvironment.getApplication()
        val resolver = createFontFamilyResolver(context)
        val weights = listOf(FontWeight.Normal, FontWeight.SemiBold, FontWeight.Bold)
        val aliases = listOf(R.font.noto_serif_sc_regular, R.font.noto_serif_sc_semibold, R.font.noto_serif_sc_bold)
        val canvasImage = Bitmap.createBitmap(393, 440, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(canvasImage)
            canvas.drawColor(Color.WHITE)
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 26f }
            val xmlInk = mutableListOf<Int>()
            val composeInk = mutableListOf<Int>()
            weights.forEachIndexed { index, weight ->
                val native = ResourcesCompat.getFont(context, aliases[index])
                assertNotNull("Bundled XML family must load at weight ${weight.weight}", native)
                assertNotEquals("XML family must not fall back to DEFAULT", Typeface.DEFAULT, native)
                val variable = resolver.resolve(NovelTypography.serif, weight, FontStyle.Normal, FontSynthesis.None).value as Typeface
                assertNotEquals("Compose FontVariation must load the bundled font", Typeface.DEFAULT, variable)
                for ((column, face) in listOf(native, variable).withIndex()) {
                    paint.typeface = face
                    for (glyph in listOf("柔", "软", "章", "龘")) assertTrue("The full bundled font must provide $glyph", paint.hasGlyph(glyph))
                    val baseline = 44f + (index * 2 + column) * 62f
                    canvas.drawText("柔软日常 · 小说章节 龘", 14f, baseline, paint)
                    val ink = ink(canvasImage, baseline.toInt() - 34, baseline.toInt() + 8)
                    assertTrue("Native font rasterization must contain Chinese ink, not an empty view", ink > 100)
                    if (column == 0) xmlInk += ink else composeInk += ink
                }
            }
            val output = File(System.getProperty("novelUiRenderDir", "/tmp/abdl-novel-ui-renders"), "fonts/api-${android.os.Build.VERSION.SDK_INT}-xml-and-compose-variable.png")
            assertTrue(output.parentFile.isDirectory || output.parentFile.mkdirs())
            output.outputStream().use { assertTrue(canvasImage.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(output.length() > 0)
            // This checks real font axes, not FontWeight objects with three identical default faces.
            assertTrue("XML aliases must rasterize genuinely different 400/600/700 weights", xmlInk.distinct().size > 1)
            assertTrue("Compose FontVariation must rasterize genuinely different weights", composeInk.distinct().size > 1)
        } finally {
            canvasImage.recycle()
        }
    }

    private fun ink(bitmap: Bitmap, top: Int, bottom: Int): Int {
        var count = 0
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(bitmap.height)) for (x in 0 until bitmap.width) {
            val color = bitmap.getPixel(x, y)
            if (Color.red(color) < 180 && Color.green(color) < 180 && Color.blue(color) < 180) count++
        }
        return count
    }
}
