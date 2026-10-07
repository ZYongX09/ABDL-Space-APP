package org.joinmastodon.android.ui.compose.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import org.joinmastodon.android.ui.utils.LiquidGlassCompatibility

/** Only the system API boundary remains; supported-device graphics failures propagate. */
@Suppress("UNUSED_PARAMETER")
internal open class GraphicsEffectSafety {
    fun isSupported(): Boolean = LiquidGlassCompatibility.isSystemSupported()

    fun <T> guarded(
        operation: String,
        fallback: () -> T,
        onFailure: () -> Unit = {},
        block: () -> T,
    ): T = if (isSupported()) block() else fallback()

    fun cleanup(operation: String, block: () -> Unit) = block()

    fun drawEffect(
        scope: DrawScope,
        operation: String,
        fallback: DrawScope.() -> Unit = {},
        onFailure: () -> Unit = {},
        effect: DrawScope.() -> Unit,
    ) {
        if (!isSupported()) {
            scope.fallback()
            return
        }
        val canvas = scope.drawContext.canvas.nativeCanvas
        val saveCount = canvas.save()
        val oldSize = scope.drawContext.size
        try {
            scope.effect()
        } finally {
            scope.drawContext.size = oldSize
            canvas.restoreToCount(saveCount)
        }
    }
}

internal object GraphicsSafety : GraphicsEffectSafety()
internal object BackgroundGraphicsSafety : GraphicsEffectSafety()
internal object PageBlurGraphicsSafety : GraphicsEffectSafety()

@Composable
internal fun rememberGraphicsEffectsSupported(safety: GraphicsEffectSafety = GraphicsSafety): Boolean =
    safety.isSupported()

internal fun Modifier.safeGraphicsEffect(
    operation: String,
    fallback: DrawScope.() -> Unit,
    safety: GraphicsEffectSafety = GraphicsSafety,
    factory: () -> Modifier,
): Modifier = then(safety.guarded(operation, fallback = { Modifier.drawBehind(fallback) }, block = factory))

internal fun Modifier.safeBackdropEffect(
    operation: String,
    fallback: DrawScope.() -> Unit,
    safety: GraphicsEffectSafety = GraphicsSafety,
    factory: (enabled: Boolean) -> Modifier,
): Modifier = then(safety.guarded(operation, fallback = { Modifier.drawBehind(fallback) }) { factory(true) })

internal fun Modifier.safeBackdropRecording(
    safety: GraphicsEffectSafety = GraphicsSafety,
    factory: () -> Modifier,
): Modifier = then(if (safety.isSupported()) factory() else Modifier)
