package org.joinmastodon.android.novel

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toolbar
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/** Inflates and lays out actual Android Toolbar/PopupMenu resources, rather than XML-only claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w320dp-h640dp-notnight-mdpi", application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelHomeMenuLayoutTest {
    @Test fun narrowNativeToolbarKeepsOneCustomOverflowAndNovelInItsPopup() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_Mastodon_Light)
        controller.setup().visible()
        try {
            val toolbar = Toolbar(activity)
            activity.setContentView(toolbar)
            toolbar.inflateMenu(R.menu.home_custom)
            assertNull("No second native never-action novel item", toolbar.menu.findItem(R.id.novel))
            val overflow = toolbar.menu.findItem(R.id.overflow)
            assertNotNull("Keep production custom overflow item", overflow)
            val actionView = View(activity).apply { contentDescription = activity.getString(R.string.more_options); layoutParams = FrameLayout.LayoutParams(48, 48) }
            overflow.actionView = actionView
            overflow.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            toolbar.measure(exact(320), exact(64)); toolbar.layout(0, 0, 320, 64)
            assertEquals(1, (0 until toolbar.menu.size()).count { toolbar.menu.getItem(it).itemId == R.id.overflow && toolbar.menu.getItem(it).isVisible })
            assertTrue(actionView.width > 0)
            val popup = PopupMenu(activity, actionView).apply { inflate(R.menu.home_overflow) }
            val novel = popup.menu.findItem(R.id.novel)
            assertNotNull(novel); assertTrue(novel.isVisible)
            assertEquals(1, (0 until popup.menu.size()).count { popup.menu.getItem(it).itemId == R.id.novel })
            val output = File(System.getProperty("novelUiRenderDir").orEmpty().ifBlank { "/home/ZYongX/.local/share/abdl-novel-polish-20261029/android-ui-renders" }, "app/home-native-menu-light-320x64.png")
            assertTrue(output.parentFile.isDirectory || output.parentFile.mkdirs())
            val bitmap = Bitmap.createBitmap(320, 64, Bitmap.Config.ARGB_8888)
            try { toolbar.draw(Canvas(bitmap)); output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
        } finally { controller.pause().stop().destroy() }
    }
    private fun exact(n: Int) = View.MeasureSpec.makeMeasureSpec(n, View.MeasureSpec.EXACTLY)
}
