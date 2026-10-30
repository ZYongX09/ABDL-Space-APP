package org.joinmastodon.android.novel.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.ui.compose.navigation.ViewBitmapBackdrop
import org.joinmastodon.android.ui.utils.LiquidGlassCompatibility
import org.joinmastodon.android.ui.views.BackdropCaptureFrameLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Only hardware/capability eligibility is simulated. Native ImageView traversal, bitmap capture,
 * real View attachment/layout and production binding callbacks remain real; not glass-render proof.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class, shadows = [NovelBackdropCaptureBindingTest.CompatibilityShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelBackdropCaptureBindingTest {
    @Implements(LiquidGlassCompatibility::class)
    class CompatibilityShadow {
        companion object {
            @JvmStatic @Implementation fun isSupported() = true
        }
    }
    private val controller = Robolectric.buildActivity(Activity::class.java)
    private lateinit var parent: FrameLayout
    private lateinit var capture: HardwareEligibleCapture
    private lateinit var binding: NovelBackdropCaptureBinding
    private val backdrop = ViewBitmapBackdrop()
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    @Before fun setUp() {
        controller.setup().visible()
        parent = FrameLayout(controller.get())
        controller.get().setContentView(parent)
        capture = HardwareEligibleCapture(controller.get())
        capture.addView(ImageView(controller.get()).apply { setImageDrawable(ColorDrawable(Color.BLUE)); scaleType = ImageView.ScaleType.FIT_XY }, FrameLayout.LayoutParams(-1, -1))
        parent.addView(capture, FrameLayout.LayoutParams(80, 200).apply { leftMargin = 7; topMargin = 11 })
        // A visually overlapping sibling must never enter the page capture.
        parent.addView(View(controller.get()).apply { setBackgroundColor(Color.MAGENTA) }, FrameLayout.LayoutParams(80, 40).apply { leftMargin = 7; topMargin = 171 })
        layout(80, 200)
        assertTrue(capture.isAttachedToWindow)
        owner.registry.currentState = Lifecycle.State.RESUMED
        binding = NovelBackdropCaptureBinding(capture, backdrop, owner.lifecycle)
    }
    @After fun tearDown() {
        if (::binding.isInitialized) binding.dispose()
        controller.pause().stop().destroy()
    }
    private fun layout(width: Int, height: Int) {
        capture.layoutParams = (capture.layoutParams as FrameLayout.LayoutParams).apply { this.width = width; this.height = height }
        parent.measure(exact(240), exact(360)); parent.layout(0, 0, 240, 360)
        capture.measure(exact(width), exact(height)); capture.layout(7, 11, 7 + width, 11 + height)
    }
    private fun exact(n: Int) = View.MeasureSpec.makeMeasureSpec(n, View.MeasureSpec.EXACTLY)
    private fun render() {
        // A Canvas subclass reporting hardware=true is not a recording Canvas: attached API28
        // children legitimately cast it to DisplayListCanvas. Record the real first draw into a
        // platform RenderNode; the production capture then rasterizes its second pass with the
        // native software Canvas, just as BackdropCaptureFrameLayoutTest verifies. Assert pixels
        // only from that delivered software strip, never pretend this proves GPU glass rendering.
        val renderNodeClass = Class.forName("android.view.RenderNode")
        val renderNode = renderNodeClass.getMethod("create", String::class.java, View::class.java)
            .invoke(null, "novel-binding-test", capture)
        val recordingCanvasClass = Class.forName("android.view.DisplayListCanvas")
        val canvas = renderNodeClass.getMethod("start", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(renderNode, capture.width, capture.height) as Canvas
        assertTrue(canvas.isHardwareAccelerated)
        assertTrue(recordingCanvasClass.isInstance(canvas))
        try { capture.render(canvas) } finally {
            renderNodeClass.getMethod("end", recordingCanvasClass).invoke(renderNode, canvas)
            renderNodeClass.getMethod("discardDisplayList").invoke(renderNode)
        }
    }
    private fun listener() = ReflectionHelpers.getField<BackdropCaptureFrameLayout.CaptureListener?>(capture, "captureListener")
    private fun image() = ReflectionHelpers.getField<State<ImageBitmap?>>(backdrop, "image\$delegate").value
    private fun origin() = ReflectionHelpers.getField<State<Offset>>(backdrop, "originInWindow\$delegate").value
    private fun assertOrigin(stripHeight: Int) {
        val location = IntArray(2); capture.getLocationInWindow(location)
        assertEquals(Offset(location[0].toFloat(), (location[1] + capture.height - stripHeight).toFloat()), origin())
    }

    @Test fun pauseClearsSampleAndResumeRestoresRealNativePixelsWithoutNavigationSibling() {
        binding.update(true, 40)
        assertNotNull(listener()); render()
        assertNotNull(image()); assertOrigin(40)
        val delivered = ReflectionHelpers.getField<Bitmap>(capture, "bottomCaptureBitmap")
        assertEquals(40, delivered.height)
        assertEquals("Real Android ImageView pixels, not magenta sibling", Color.BLUE, delivered.getPixel(10, 10))
        owner.registry.currentState = Lifecycle.State.STARTED
        assertNull(listener()); assertNull(image())
        assertEquals(0, ReflectionHelpers.getField<Int>(capture, "bottomCaptureHeight"))
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertNotNull(listener()); render()
        assertNotNull(image()); assertOrigin(40)
    }

    @Test fun resizeAndHeightChangesClampToPageAndKeepWindowRelativeStripOrigin() {
        binding.update(true, 40); render(); assertOrigin(40)
        layout(100, 160); render(); assertOrigin(40)
        assertEquals(100, image()!!.width)
        binding.update(true, 300); render()
        assertEquals(160, image()!!.height); assertOrigin(160)
        layout(120, 220); render()
        assertEquals(220, image()!!.height); assertOrigin(220)
    }

    @Test fun detachReattachAndDisposeNeverRestoreStaleSamplesOrCallbacks() {
        binding.update(true, 40); render()
        assertNotNull(image())
        parent.removeView(capture)
        assertNull(listener()); assertNull(image())
        parent.addView(capture, FrameLayout.LayoutParams(80, 200)); layout(80, 200)
        render(); assertNotNull(listener()); assertNotNull(image())
        binding.dispose()
        assertNull(listener()); assertNull(image())
        layout(90, 180)
        owner.registry.currentState = Lifecycle.State.STARTED
        owner.registry.currentState = Lifecycle.State.RESUMED
        render(); assertNull(listener()); assertNull(image())
    }

    @Test fun plainModeAndZeroHeightDoNotAllocateCaptureBuffers() {
        binding.update(false, 100); render()
        assertNull(listener()); assertNull(image())
        binding.update(true, 0); render()
        assertNull(listener()); assertNull(image())
        assertNull(ReflectionHelpers.getField<Bitmap?>(capture, "captureBitmap"))
    }

    private class HardwareEligibleCapture(context: Context) : BackdropCaptureFrameLayout(context) {
        override fun isHardwareAccelerated() = true
        fun render(canvas: Canvas) = super.dispatchDraw(canvas)
    }
}
