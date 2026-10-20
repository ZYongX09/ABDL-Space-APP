package org.joinmastodon.android.ui.compose.friendcards

import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

internal const val FriendCardContrastPatchSize = 16
internal val LocalFriendCardContentInvalidated = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/** Content and controls are siblings. Only the content's display list is ever replayed/read back. */
internal class FriendCardContrastSampler internal constructor() {
    internal var source: LayerBackdrop? = null
    internal var failed by mutableStateOf(false)
        private set
    internal var closeInk by mutableStateOf(FriendCardContrastInk.Unknown)
        private set
    internal var manageInk by mutableStateOf(FriendCardContrastInk.Unknown)
        private set
    private var contentBounds: Rect? = null
    private val controlBounds = arrayOfNulls<Rect>(FriendCardContrastControl.entries.size)
    private val controller = FriendCardContrastController()
    private var pump: FriendCardContrastPump? = null

    internal val backdrop: LayerBackdrop? get() = if (failed) null else source

    internal fun connect(value: FriendCardContrastPump) { pump = value }
    internal fun contentDrawn() { pump?.invalidate() }
    fun invalidateContent() { pump?.invalidate() }

    internal fun setContentBounds(bounds: Rect) {
        if (contentBounds != bounds) { contentBounds = bounds; invalidateContent() }
    }
    internal fun setControlBounds(control: FriendCardContrastControl, bounds: Rect) {
        if (controlBounds[control.ordinal] != bounds) {
            controlBounds[control.ordinal] = bounds
            invalidateContent()
        }
    }
    internal fun patchBounds(control: FriendCardContrastControl): Rect? {
        val content = contentBounds ?: return null
        val controlRect = controlBounds[control.ordinal] ?: return null
        val local = controlRect.translate(Offset(-content.left, -content.top))
        val clipped = local.intersect(Rect(0f, 0f, content.width, content.height))
        return clipped.takeIf { it.width > 1f && it.height > 1f }
    }
    internal fun accept(control: FriendCardContrastControl, pixels: IntArray) {
        val luminance = friendCardPatchLuminance(pixels) ?: error("Missing opaque content in contrast patch")
        val ink = controller.update(control, luminance)
        when (control) {
            FriendCardContrastControl.Close -> closeInk = ink
            FriendCardContrastControl.Manage -> manageInk = ink
        }
    }
    internal fun fail() {
        failed = true
        closeInk = FriendCardContrastInk.Unknown
        manageInk = FriendCardContrastInk.Unknown
    }
    internal fun dispose() { pump?.dispose(); pump = null }
}

@Composable
internal fun rememberFriendCardContrastSampler(): FriendCardContrastSampler {
    val view = LocalView.current
    val sampler = remember(view) { FriendCardContrastSampler() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    // Software Canvas cannot replay Android RenderNodes. Do not pretend it sampled or enable glass.
    if (view.isHardwareAccelerated && !sampler.failed) {
        val source = rememberLayerBackdrop()
        val closePatch = rememberGraphicsLayer()
        val managePatch = rememberGraphicsLayer()
        sampler.source = source
        val pump = remember(sampler, source, closePatch, managePatch, density, direction) {
            FriendCardContrastPump(
                nowMillis = SystemClock::uptimeMillis,
                capture = {
                    // Resume after the draw that dirtied the recorded content, not recursively in it.
                    withFrameNanos { }
                    if (view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE &&
                        source.graphicsLayer.size.width > 0 && source.graphicsLayer.size.height > 0) {
                        val patches = arrayOf(closePatch, managePatch)
                        for (control in FriendCardContrastControl.entries) {
                            val bounds = sampler.patchBounds(control) ?: continue
                            val layer = patches[control.ordinal]
                            recordFriendCardContrastPatch(source.graphicsLayer, layer, bounds, density, direction)
                            val bitmap = layer.toImageBitmap()
                            try {
                                currentCoroutineContext().ensureActive()
                                val pixels = IntArray(FriendCardContrastPatchSize * FriendCardContrastPatchSize)
                                bitmap.readPixels(pixels)
                                sampler.accept(control, pixels)
                            } finally {
                                // This is our tiny snapshot, not the shared source or an image loader bitmap.
                                bitmap.asAndroidBitmap().recycle()
                            }
                        }
                    }
                },
                onFailure = sampler::fail,
            )
        }
        DisposableEffect(sampler, pump, lifecycle, view) {
            sampler.connect(pump)
            // Cover AndroidViews change their layout when their async drawable becomes available.
            // This catches that event even when only a cached child RenderNode was invalidated.
            var tree: ViewTreeObserver? = null
            val layout = ViewTreeObserver.OnGlobalLayoutListener { sampler.invalidateContent() }
            fun observeLayout(active: Boolean) {
                tree?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layout)
                tree = if (active) view.viewTreeObserver.takeIf { it.isAlive }?.also {
                    it.addOnGlobalLayoutListener(layout)
                } else null
            }
            val observer = LifecycleEventObserver { _, _ ->
                observeLayout(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            }
            lifecycle.addObserver(observer)
            observeLayout(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            onDispose {
                lifecycle.removeObserver(observer)
                observeLayout(false)
                sampler.dispose()
            }
        }
        LaunchedEffect(pump, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { pump.run() }
        }
    } else {
        sampler.source = null
    }
    return sampler
}

/**
 * Clip in destination space BEFORE scaling, then translate in source space. Only 16×16 pixels are
 * allocated/read back per control, regardless of viewport, density, scroll distance or pill width.
 * Replaying a RenderNode/display list is not drawing the View hierarchy or recording ourselves.
 */
internal fun recordFriendCardContrastPatch(
    source: GraphicsLayer,
    patch: GraphicsLayer,
    bounds: Rect,
    density: androidx.compose.ui.unit.Density,
    direction: androidx.compose.ui.unit.LayoutDirection,
) {
    val edge = FriendCardContrastPatchSize.toFloat()
    patch.record(density, direction, IntSize(FriendCardContrastPatchSize, FriendCardContrastPatchSize)) {
        clipRect(0f, 0f, edge, edge) {
            withTransform({
                scale(edge / bounds.width, edge / bounds.height, pivot = Offset.Zero)
                translate(-bounds.left, -bounds.top)
            }) { drawLayer(source) }
        }
    }
}

/** Attach only to the content Column, with the opaque card base INSIDE the recording modifier. */
internal fun Modifier.friendCardContrastContent(sampler: FriendCardContrastSampler): Modifier {
    val backdrop = sampler.backdrop ?: return this
    return onGloballyPositioned { sampler.setContentBounds(it.boundsInRoot()) }
        .graphicsLayer() // Isolate source dirtiness from the sibling buttons' tint/press animations.
        .drawWithContent {
            drawContent() // layerBackdrop has completed its recording when this returns.
            if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) sampler.contentDrawn()
            else sampler.fail()
        }
        .layerBackdrop(backdrop)
}

/** Measured before the press scale so the .96 animation never changes or dirties sample bounds. */
internal fun Modifier.friendCardContrastBounds(
    sampler: FriendCardContrastSampler?,
    control: FriendCardContrastControl,
): Modifier = if (sampler == null) this else onGloballyPositioned {
    sampler.setControlBounds(control, it.boundsInRoot())
}
