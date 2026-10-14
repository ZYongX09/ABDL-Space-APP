package org.joinmastodon.android.ui.compose.navigation

import org.junit.Assert.assertTrue
import org.junit.Test

class GlassShaderIsolationTest {
    @Test fun mapSurfaceUsesIsolatedShaderCache() {
        val source = java.io.File("src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/MapGlassSurface.kt").readText()
        assertTrue(source.contains("WithIsolatedGlassShaderCache"))
        assertTrue(source.contains("rememberIsolatedGlassHighlight"))
    }

    @Test fun sharedHomeGlassUsesIsolatedHighlights() {
        val files = listOf(
            "src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassContainer.kt",
            "src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarView.kt",
            "src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/liquid/IosLiquidGlassNavigationBar.kt",
        )
        files.forEach { path ->
            val source = java.io.File(path).readText()
            assertTrue("$path must isolate mutable highlight shader parameters", source.contains("rememberIsolatedGlassHighlight"))
        }
    }
}
