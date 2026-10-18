package org.joinmastodon.android.ui.compose.friendcards

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import org.joinmastodon.android.CompatibilityTestApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class)
class FocusImagePositionTest {
    private fun checkPosition(width: Int, height: Int, imageWidth: Int, imageHeight: Int) {
        val view = FocusImage(RuntimeEnvironment.getApplication())
        view.setImageDrawable(object : ColorDrawable(Color.BLACK) {
            override fun getIntrinsicWidth() = imageWidth
            override fun getIntrinsicHeight() = imageHeight
        })
        view.layout(0, 0, width, height)
        val scale = maxOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val lowerX = (width - imageWidth * scale).coerceAtMost(0f)
        val lowerY = (height - imageHeight * scale).coerceAtMost(0f)
        for (x in listOf(0f, .5f, 1f)) for (y in listOf(0f, .4f, 1f)) {
            view.focusX = x; view.focusY = y
            view.position()
            val matrix = FloatArray(9).also { view.imageMatrix.getValues(it) }
            assertTrue("All matrix entries must remain finite", matrix.all { it.isFinite() })
            assertEquals(scale, matrix[Matrix.MSCALE_X], .000001f)
            assertEquals(scale, matrix[Matrix.MSCALE_Y], .000001f)
            assertTrue("X translation stays in its non-inverted interval", matrix[Matrix.MTRANS_X] in lowerX..0f)
            assertTrue("Y translation stays in its non-inverted interval", matrix[Matrix.MTRANS_Y] in lowerY..0f)
        }
    }

    @Test fun landscapeFloatUndershootDoesNotInvertXClamp() {
        val scale = maxOf(888f / 541, 404f / 512)
        assertTrue("Regression dimensions exercise a positive rounding remainder", 888 - 541 * scale > 0f)
        checkPosition(888, 404, 541, 512)
    }

    @Test fun portraitFloatUndershootDoesNotInvertYClamp() {
        val scale = maxOf(404f / 512, 888f / 541)
        assertTrue("Transposed dimensions exercise the Y rounding remainder", 888 - 541 * scale > 0f)
        checkPosition(404, 888, 512, 541)
    }

    @Test fun exactAndExtremeAspectRatiosKeepValidFocusTranslations() {
        checkPosition(400, 400, 400, 400)
        checkPosition(1, 1024, 1024, 1)
        checkPosition(1024, 1, 1, 1024)
        checkPosition(1080, 720, 2048, 1536)
    }
}
