package org.joinmastodon.android.ui.compose.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import org.joinmastodon.android.ui.utils.LiquidGlassCompatibility

/** Java/Kotlin graphics failures only; native driver/RenderThread crashes cannot be caught here. */
internal object GraphicsSafety {
    fun isSupported(): Boolean = LiquidGlassCompatibility.isSupported()

    /** Never enclose a composable invocation or application content in this operation. */
    fun <T> guarded(
        operation: String,
        fallback: () -> T,
        onFailure: () -> Unit = {},
        block: () -> T,
    ): T {
        if (!isSupported()) return fallback()
        return try {
            block()
        } catch (error: RuntimeException) {
            failed(operation, error, onFailure)
            fallback()
        } catch (error: LinkageError) {
            failed(operation, error, onFailure)
            fallback()
        } catch (error: OutOfMemoryError) {
            failed(operation, error, onFailure)
            fallback()
        }
    }

    private fun failed(operation: String, error: Throwable, onFailure: () -> Unit) {
        // Disable first, before cleanup/fallback. Notifications are deferred by the Java helper.
        LiquidGlassCompatibility.reportFailure(operation, error)
        cleanup("$operation cleanup", onFailure)
    }

    /** Resource release must still run after the session has been disabled. */
    fun cleanup(operation: String, block: () -> Unit) {
        try {
            block()
        } catch (error: RuntimeException) {
            LiquidGlassCompatibility.reportFailure(operation, error)
        } catch (error: LinkageError) {
            LiquidGlassCompatibility.reportFailure(operation, error)
        } catch (error: OutOfMemoryError) {
            LiquidGlassCompatibility.reportFailure(operation, error)
        }
    }

    fun drawEffect(
        scope: DrawScope,
        operation: String,
        fallback: DrawScope.() -> Unit = {},
        onFailure: () -> Unit = {},
        effect: DrawScope.() -> Unit,
    ) {
        val drawn = guarded(operation, fallback = { false }, onFailure = onFailure) {
            val canvas = scope.drawContext.canvas.nativeCanvas
            val saveCount = canvas.save()
            val oldSize = scope.drawContext.size
            try {
                scope.effect()
                // A nested uniform/effect guard may have failed without throwing further.
                isSupported()
            } finally {
                scope.drawContext.size = oldSize
                canvas.restoreToCount(saveCount)
            }
        }
        // Outside the catch and after restoration: failures in plain fallback drawing propagate.
        if (!drawn) scope.fallback()
    }
}

/** Observe deferred failure notifications; draw-time guards always consult the live session flag. */
@Composable
internal fun rememberGraphicsEffectsSupported(): Boolean {
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val listener = Runnable { failed = true }
        LiquidGlassCompatibility.addFailureListener(listener)
        onDispose { LiquidGlassCompatibility.removeFailureListener(listener) }
    }
    return !failed && GraphicsSafety.isSupported()
}

/**
 * Only for an EMPTY graphics-only child (never a subtree containing application content).
 * Bypassing its drawContent skips the library's complete effect chain on a disabled session.
 */
internal fun Modifier.safeGraphicsEffect(
    operation: String,
    fallback: DrawScope.() -> Unit,
    factory: () -> Modifier,
): Modifier = then(GraphicsSafety.guarded(operation, fallback = { Modifier.drawBehind(fallback) }) {
    Modifier.drawWithContent {
        val effectScope = this
        GraphicsSafety.drawEffect(this, operation, fallback) { effectScope.drawContent() }
    }.then(factory())
})

/**
 * Only for drawBackdrop/textureBlur's single node: disabled attachment performs no graphics
 * allocation. Enable via an update AFTER Compose finishes the child's attach lifecycle.
 * Other modifier nodes must stay outside this factory; it is not a generic lifecycle catch.
 */
internal fun Modifier.safeBackdropEffect(
    operation: String,
    fallback: DrawScope.() -> Unit,
    factory: (enabled: Boolean) -> Modifier,
): Modifier = safeGraphicsEffect(operation, fallback) {
    val disabled = factory(false).foldIn(null as ModifierNodeElement<*>?) { found, element ->
        check(found == null && element is ModifierNodeElement<*>) { "Expected one backdrop node" }
        element as ModifierNodeElement<*>
    }
    val enabled = factory(true).foldIn(null as ModifierNodeElement<*>?) { found, element ->
        check(found == null && element is ModifierNodeElement<*>) { "Expected one backdrop node" }
        element as ModifierNodeElement<*>
    }
    checkNotNull(disabled)
    checkNotNull(enabled)
    check(disabled.javaClass == enabled.javaClass)
    SafeBackdropElement(operation, disabled, enabled)
}

private data class SafeBackdropElement(
    val operation: String,
    val disabled: ModifierNodeElement<*>,
    val enabled: ModifierNodeElement<*>,
) : ModifierNodeElement<SafeBackdropNode>() {
    override fun create() = SafeBackdropNode(operation, disabled, enabled)
    override fun update(node: SafeBackdropNode) = node.update(disabled, enabled)
    override fun InspectorInfo.inspectableProperties() { name = "safeBackdropNode" }
}

private class SafeBackdropNode(
    private val operation: String,
    private var disabled: ModifierNodeElement<*>,
    private var enabled: ModifierNodeElement<*>,
) : DelegatingNode() {
    private var effectNode: Modifier.Node? = null

    override fun onAttach() {
        // Do not swallow disabled lifecycle failures: Compose has not set its detach flag yet.
        val node = disabled.create()
        delegate(node)
        effectNode = node
        enable()
    }

    @Suppress("UNCHECKED_CAST")
    private fun enable() {
        val node = effectNode ?: return
        GraphicsSafety.guarded("$operation enable", fallback = {}) {
            (enabled as ModifierNodeElement<Modifier.Node>).update(node)
        }
        // The child completed attachment, so this removal has valid lifecycle flags.
        if (!GraphicsSafety.isSupported()) release()
    }

    fun update(off: ModifierNodeElement<*>, on: ModifierNodeElement<*>) {
        disabled = off
        enabled = on
        enable()
    }

    private fun release() {
        val node = effectNode ?: return
        try {
            undelegate(node)
            effectNode = null
        } catch (error: RuntimeException) {
            LiquidGlassCompatibility.reportFailure("$operation detach", error)
            throw error
        } catch (error: LinkageError) {
            LiquidGlassCompatibility.reportFailure("$operation detach", error)
            throw error
        } catch (error: OutOfMemoryError) {
            LiquidGlassCompatibility.reportFailure("$operation detach", error)
            throw error
        }
        // Opaque child detach exceptions cannot safely be recovered using Compose public APIs.
    }

    override fun onDetach() = release()
}

/** A non-recoverable sentinel only around application content, unwrapped outside the catch. */
private class ContentDrawFailure(val original: Throwable) : Error(null, null, false, false)

/** For layerBackdrop recording: disabled sessions bypass recording but still draw content. */
internal fun Modifier.safeBackdropRecording(factory: () -> Modifier): Modifier = then(
    GraphicsSafety.guarded("backdrop recording construction", fallback = { Modifier }) {
        val element = factory().foldIn(null as ModifierNodeElement<*>?) { found, value ->
            check(found == null && value is ModifierNodeElement<*>) { "Expected one recording node" }
            value as ModifierNodeElement<*>
        }
        SafeRecordingElement(checkNotNull(element))
    },
)

private data class SafeRecordingElement(val element: ModifierNodeElement<*>) : ModifierNodeElement<SafeRecordingNode>() {
    override fun create() = SafeRecordingNode(element)
    override fun update(node: SafeRecordingNode) = node.update(element)
    override fun InspectorInfo.inspectableProperties() { name = "safeBackdropRecording" }
}

private class SafeRecordingNode(private var element: ModifierNodeElement<*>) : DelegatingNode(), DrawModifierNode {
    private var recorder: Modifier.Node? = null

    override fun onAttach() {
        // layerBackdrop has no allocating onAttach; let structural failures propagate.
        val node = element.create()
        delegate(node)
        recorder = node
    }

    @Suppress("UNCHECKED_CAST")
    fun update(value: ModifierNodeElement<*>) {
        element = value
        recorder?.let { (value as ModifierNodeElement<Modifier.Node>).update(it) }
    }

    override fun ContentDrawScope.draw() {
        val node = recorder as? DrawModifierNode
        if (!GraphicsSafety.isSupported() || node == null) {
            drawContent()
            return
        }
        val originalScope = this
        var contentDrawn = false
        val contentScope = object : ContentDrawScope by originalScope {
            override fun drawContent() {
                try {
                    originalScope.drawContent()
                    contentDrawn = true
                } catch (error: RuntimeException) {
                    throw ContentDrawFailure(error)
                } catch (error: LinkageError) {
                    throw ContentDrawFailure(error)
                } catch (error: OutOfMemoryError) {
                    throw ContentDrawFailure(error)
                }
            }
        }
        try {
            GraphicsSafety.drawEffect(this, "backdrop recording", fallback = {
                if (!contentDrawn) originalScope.drawContent()
            }) {
                with(node) { contentScope.draw() }
            }
        } catch (error: ContentDrawFailure) {
            throw error.original
        }
    }
}
