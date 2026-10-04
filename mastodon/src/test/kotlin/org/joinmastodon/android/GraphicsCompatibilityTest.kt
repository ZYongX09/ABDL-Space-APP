package org.joinmastodon.android

import android.os.Build
import org.joinmastodon.android.ui.compose.utils.supportsRuntimeGraphicsEffects
import org.joinmastodon.android.ui.compose.component.effect.BgEffectPainter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 29, 30, 31, 32, 33, 35], application = CompatibilityTestApplication::class)
class GraphicsCompatibilityTest {
	@Test fun lowerApiCannotEnterRuntimeShaderPath() {
		assertEquals(Build.VERSION.SDK_INT >= 33, supportsRuntimeGraphicsEffects())
		if (Build.VERSION.SDK_INT < 33) assertFalse(isRuntimeShaderSupported())
	}

	@Test fun backgroundPainterNeverInitializesRuntimeShaderOnOldSdk() {
		if (Build.VERSION.SDK_INT < 33) assertFalse(BgEffectPainter(true).prepare())
	}

	@Test fun blurTypesLoadWithoutInitializingRuntimeEffects() {
		Class.forName("top.yukonga.miuix.kmp.blur.LayerBackdrop", false, javaClass.classLoader)
		Class.forName("top.yukonga.miuix.kmp.blur.Backdrop", false, javaClass.classLoader)
		assertFalse(supportsRuntimeGraphicsEffects(26))
	}
}
