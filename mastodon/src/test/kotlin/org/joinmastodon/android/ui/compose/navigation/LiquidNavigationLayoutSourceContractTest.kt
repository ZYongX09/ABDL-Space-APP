package org.joinmastodon.android.ui.compose.navigation

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Production wiring supplement to LiquidNavigationLayoutParityTest, not a visual oracle. */
class LiquidNavigationLayoutSourceContractTest {
    private fun source(name: String): String = File(
        requireNotNull(System.getProperty("user.dir")),
        "src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/$name",
    ).readText()

    private fun assertOrder(text: String, vararg tokens: String) {
        var cursor = 0
        for (token in tokens) {
            val next = text.indexOf(token, cursor)
            assertTrue("Missing/out-of-order $token", next >= cursor)
            cursor = next + token.length
        }
    }

    @Test fun visibleRowOwnsPillBackdropAndPressLayerBeforeContentPadding() {
        val navigation = source("liquid/IosLiquidGlassNavigationBar.kt")
        val visible = navigation.substringAfter("CompositionLocalProvider(LocalContentColor provides tabContentColor) {")
            .substringBefore("if (blurActive && backdrop != null && tabsBackdrop != null) {\n                CompositionLocalProvider")
        assertTrue(visible.trimStart().startsWith("Row("))
        assertOrder(visible, ".selectableGroup()", ".onSizeChanged", ".graphicsLayer", ".dropShadow(",
            ".clickable(", "if (blurActive && backdrop != null && tabsBackdrop != null)", "Modifier.drawBackdrop(",
            "layerBlock = {", "16.dp.toPx() / width", "dampedDrag.pressProgress", "scaleX = s", "scaleY = s",
            ".height(64.dp)", ".padding(4.dp)", "content = tabsContent")
        assertFalse(visible.contains("matchParentSize"))
        assertFalse(visible.contains("Box("))
        assertTrue(visible.contains("padding = maxOf(padding, 40.dp.toPx())"))
    }

    @Test fun hiddenCaptureRowRecordsFullWidthSurfaceBeforeItsEightDpContentInset() {
        val navigation = source("liquid/IosLiquidGlassNavigationBar.kt")
        val capture = navigation.substringAfter("LocalIosTabScale provides { lerp(1f, 1.2f, dampedDrag.pressProgress) }")
            .substringBefore("if (tabWidthPx > 0f)")
        assertTrue(capture.substringAfter("LocalContentColor provides accentColor,").substringAfter(") {").trimStart().startsWith("Row("))
        assertOrder(capture, ".clearAndSetSemantics", ".alpha(0f)", ".layerBackdrop(tabsBackdrop)",
            ".graphicsLayer", ".drawBackdrop(", ".then(interactiveHighlight.modifier)", ".height(56.dp)",
            ".padding(horizontal = 4.dp)", "content = tabsContent")
        assertFalse(capture.contains("Box("))
        assertFalse(capture.contains("matchParentSize"))
        assertTrue(navigation.contains("val contentWidthPx = totalWidthPx - with(density) { 8.dp.toPx() }"))
    }

    @Test fun morphingBackdropIsOutsideContentClipAndGestureWiringRemainsIntact() {
        val morphing = source("MorphingGlassContainer.kt")
        val container = morphing.substringAfter("val graphicsSupported = rememberGraphicsEffectsSupported()")
        assertOrder(container, "Box(", ".width(width)", ".height(height)", ".onGloballyPositioned",
            ".graphicsLayer", "if(graphicsSupported) Modifier.drawBackdrop(", "highlight =",
            "else Modifier.background(surface.copy(alpha = 1f), shape)", ".clip(shape)",
            "Modifier.pointerInput(Unit)", ".clickable(", "content = closedContent", ".menuClipRevealFromTop(", "expandedContent(")
        assertEquals(1, Regex("Modifier\\.drawBackdrop\\(").findAll(container).count())
        assertFalse(container.contains("matchParentSize"))
        assertTrue(container.contains("ToolbarMenuGeometry.rowAt("))
        assertTrue(container.contains("if(requestedExpansion) onExpansionRequested()"))
        assertTrue(container.contains("else if(dragged) index?.let(onSelectionConfirmed)"))
        assertTrue(container.contains("onClosedTap(change.position, size)"))
        assertTrue(container.contains("onSelectionChanged(null)"))
    }

    @Test fun sdkOnlyGateAndCurrentTabInteractionHooksAreRetained() {
        val navigation = source("liquid/IosLiquidGlassNavigationBar.kt")
        assertTrue(navigation.contains("val blurActive = isBlurActive && graphicsSupported"))
        assertTrue(navigation.contains("val tabsBackdrop = if (blurActive) rememberLayerBackdrop() else null"))
        assertTrue(navigation.contains("if (blurActive && combinedBackdrop != null)"))
        assertTrue(navigation.contains(".background(accentColor.copy(alpha = 0.15f), pillShape)"))
        assertTrue(navigation.contains(".combinedClickable("))
        assertTrue(navigation.contains("onLongClick = { onItemLongClick(index) }"))
        assertTrue(navigation.contains("onLongClick = { onItemLongClick(currentIndex) }"))
        assertTrue(navigation.contains("iconContent(index, index == currentIndex, iconAnimationTokens[index])"))
        assertTrue(navigation.contains("BadgedBox(badge = { badge(index)?.invoke() })"))
        for (text in listOf(navigation, source("MorphingGlassContainer.kt"))) {
            assertFalse(text.contains("safeBackdropEffect"))
            assertFalse(text.contains("GraphicsSafety.guarded"))
            assertFalse(text.contains("catch("))
            assertFalse(text.contains("reportFailure"))
        }
    }
}
