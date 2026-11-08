package org.joinmastodon.android.ui.compose.navigation

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.SinOutEasing
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import org.joinmastodon.android.GlobalUserPreferences
import org.joinmastodon.android.ui.compose.navigation.liquid.iosIndicatorSpecular
import org.joinmastodon.android.ui.compose.navigation.liquid.lens
import org.joinmastodon.android.ui.compose.navigation.liquid.rememberGravityRotatedHighlight
import org.joinmastodon.android.ui.compose.navigation.liquid.vibrancy
import org.joinmastodon.android.ui.compose.navigation.motion.GlassRect
import org.joinmastodon.android.ui.compose.ui.isInDarkTheme
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import org.joinmastodon.android.ui.compose.utils.rememberGraphicsEffectsSupported
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Shared by the rendered toolbar menus, outside-touch gate and drag selection. */
internal object ToolbarMenuGeometry {
	const val TOP_OFFSET_DP = 8
	const val CONTENT_TOP_PADDING_DP = 14
	const val CONTENT_BOTTOM_PADDING_DP = 6
	const val ROW_HEIGHT_DP = 48
	const val MAX_HEIGHT_DP = 420

	fun heightDp(rowCount: Int): Int =
		(CONTENT_TOP_PADDING_DP + rowCount.coerceAtLeast(1) * ROW_HEIGHT_DP + CONTENT_BOTTOM_PADDING_DP)
			.coerceAtMost(MAX_HEIGHT_DP)

	fun rowAt(x: Float, y: Float, width: Float, height: Float, density: Float, rowCount: Int): Int? {
		val rowY = y - CONTENT_TOP_PADDING_DP * density
		if(x < 0f || x >= width || y >= height || rowY < 0f) return null
		return (rowY / (ROW_HEIGHT_DP * density)).toInt().takeIf { it in 0 until rowCount }
	}
}

/** Content blur while the rows reveal; RenderEffect is API 31+, the glass path is 33+. */
private fun rowsBlurEffect(radiusPx: Float): androidx.compose.ui.graphics.RenderEffect? =
	if(radiusPx > .5f && Build.VERSION.SDK_INT >= 31)
		androidx.compose.ui.graphics.BlurEffect(radiusPx, radiusPx, androidx.compose.ui.graphics.TileMode.Clamp)
	else null

@Composable
internal fun MorphingGlassContainer(
	expanded: Boolean,
	closedWidth: Dp,
	closedHeight: Dp,
	expandedWidth: Dp,
	expandedHeight: Dp,
	backdrop: Backdrop,
	anchorFractionX: Float,
	selectionItemCount: Int,
	enableDragSelection: Boolean,
	modifier: Modifier = Modifier,
	shouldExpandFromClosed: (Offset, IntSize) -> Boolean,
	onExpansionRequested: () -> Unit,
	onClosedTap: (Offset, IntSize) -> Unit = { _, _ -> },
	onExpansionStarted: () -> Unit = {},
	onExpansionFinished: (Boolean) -> Unit = {},
	onClick: () -> Unit,
	onSelectionChanged: (Int?) -> Unit = {},
	onSelectionConfirmed: (Int) -> Unit = {},
	onUpwardFling: () -> Unit = {},
	onBoundsChanged: (Offset, IntSize) -> Unit = { _, _ -> },
	onLayoutCoordinates: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit = {},
	pressInput: GlassPressInput? = null,
	closedContent: @Composable BoxScope.() -> Unit,
	expandedContent: @Composable BoxScope.() -> Unit,
) {
	val containerMorph = remember { Animatable(if(expanded) 1f else 0f) }
	// 0..1 birth scale: the panel is born as a small disc at the finger and springs to full size.
	val birthScale = remember { Animatable(1f) }
	// Vertical kick while the panel hands over from the trigger, matching the reference timeline.
	val kickOffset = remember { Animatable(0f) }
	// Optical channels: the lens and the portal zoom are released ~70-84ms into the morph.
	val lensProgress = remember { Animatable(1f) }
	val portalProgress = remember { Animatable(0f) }
	val enterFraction = remember { Animatable(if(expanded) 1f else 0f) }
	val enterAlpha = remember { Animatable(if(expanded) 1f else 0f) }
	val animatedExpandedHeight by animateDpAsState(
		targetValue = expandedHeight,
		animationSpec = spring(dampingRatio = 0.88f, stiffness = 560f),
		label = "expandedGlassHeight",
	)
	val menuSpec = homeLiquidToolbarMenuAnimationSpec()
	val interactionSource = remember { MutableInteractionSource() }
	val outlineHighlight = rememberIsolatedGlassHighlight(iosIndicatorSpecular, extraDegrees = -45f)
	val isDark = isInDarkTheme()
	val surfaceAlpha = homeLiquidToolbarVisualSpec().surfaceAlpha
	val surface = if(isDark) Color.Black.copy(alpha = surfaceAlpha) else Color.White.copy(alpha = surfaceAlpha)
	val haptics = LocalHapticFeedback.current
	val viewConfiguration = LocalViewConfiguration.current
	val expandedState = rememberUpdatedState(expanded)
	val itemCountState = rememberUpdatedState(selectionItemCount)
	val density = LocalDensity.current.density
	val press = pressInput ?: remember { GlassPressInput() }
	val reduceMotion = GlobalUserPreferences.reduceMotion
	val deformRect = remember { GlassRect() }
	// The first composition must not play the hand-off kick; only later state changes should.
	val kickPrimed = remember { booleanArrayOf(false) }

	val closedWidthPx = with(LocalDensity.current) { closedWidth.toPx() }
	val closedHeightPx = with(LocalDensity.current) { closedHeight.toPx() }
	val expandedWidthPx = with(LocalDensity.current) { expandedWidth.toPx() }
	val expandedHeightPx = with(LocalDensity.current) { expandedHeight.toPx() }
	// Short menus zoom harder through the portal (1.65x), tall ones settle at 1.2x.
	val portalZoom = 1.65f + (1.2f - 1.65f) * ((expandedHeightPx / density - 215f) / 305f).coerceIn(0f, 1f)

	// Single frame loop: advances the press springs and wakes on the next pointer event.
	LaunchedEffect(press) {
		var last = 0L
		while(isActive) {
			if(!press.needsFrame) {
				val seen = press.request
				snapshotFlow { press.request }.first { it != seen }
				last = 0L
			}
			val now = withFrameNanos { it }
			val dt = if(last == 0L) 1f / 60f else ((now - last) / 1_000_000_000f).coerceIn(0f, .05f)
			last = now
			press.jelly.advance(if(reduceMotion) 1f else dt)
			press.tick++
		}
	}

	LaunchedEffect(expanded) {
		onExpansionStarted()
		if(expanded) withFrameNanos { }
		val target = if(expanded) 1f else 0f
		// Reference timing: opening uses the eject spring (.75, .32s), closing the absorb
		// spring (.8, .35s). Both are damping-ratio + response, which folmeSpring matches.
		val morphSpec = folmeSpring<Float>(
			damping = if(expanded) .75f else .8f,
			response = if(expanded) .32f else .35f,
		)
		val fractionSpring = folmeSpring<Float>(
			damping = 1f,
			response = .3f,
			visibilityThreshold = menuSpec.fractionVisibilityThreshold,
		)
		val ejectSpec = folmeSpring<Float>(damping = .75f, response = .32f)
		val kickSpec = folmeSpring<Float>(damping = 1f, response = .14f)
		val blurOutSpec = folmeSpring<Float>(damping = .8f, response = .315f)
		val lensOutSpec = folmeSpring<Float>(
			damping = 1f,
			response = if(expanded) .42f else .28f,
		)
		val alphaSpec = tween<Float>(
			durationMillis = if(expanded) menuSpec.alphaEnterDurationMs else menuSpec.alphaExitDurationMs,
			easing = SinOutEasing,
		)
		launch {
			if(expanded && !reduceMotion) {
				val diameter = max(expandedWidthPx * .5f * 44.dp.value * density / max(expandedWidthPx, expandedHeightPx), 1f)
				// Clamp the birth scale: an extreme inverse scale makes the backdrop effect sample a
				// huge area on low-end GPUs even though the drawn dot is only a few pixels.
				birthScale.snapTo((diameter / closedWidthPx).coerceIn(.35f, 1f))
				birthScale.animateTo(1f, ejectSpec)
			}else{
				birthScale.snapTo(1f)
			}
		}
		launch {
			if(reduceMotion) {
				kickOffset.snapTo(0f)
				return@launch
			}
			if(kickPrimed[0]) {
				// The reference applies -80dp at retarget and clears it 70ms later.
				val heightFactor = ((expandedHeightPx / density - 60f) / 140f).coerceIn(.5f, 1f)
				val anchorY = (press.jelly.lightPositionY.coerceIn(0f, closedHeightPx) / max(expandedHeightPx, 1f)).coerceIn(0f, 1f)
				kickOffset.snapTo(-80f * density * heightFactor * (2f * anchorY - 1f))
				if(expanded) delay(70)
				kickOffset.animateTo(0f, kickSpec)
			}else{
				kickPrimed[0] = true
			}
		}
		launch {
			// Lens/portal: on while the glass hands over, released 84ms after opening (70ms on close).
			if(reduceMotion) {
				lensProgress.snapTo(0f)
				portalProgress.snapTo(0f)
				return@launch
			}
			if(expanded) {
				lensProgress.snapTo(1f)
				portalProgress.snapTo(1f)
				delay(84)
			}else{
				delay(70)
			}
			launch { lensProgress.animateTo(0f, lensOutSpec) }
			portalProgress.animateTo(0f, blurOutSpec)
		}
		launch { containerMorph.animateTo(target, morphSpec) }
		launch { enterFraction.animateTo(target, fractionSpring) }
		enterAlpha.animateTo(target, alphaSpec)
		// Matches miuix ListPopupLayout: snap residual tracks to a clean baseline after exit so
		// the next enter starts from zero without drifting on the spring's residual velocity.
		if(!expanded) {
			containerMorph.snapTo(0f)
			enterFraction.snapTo(0f)
			enterAlpha.snapTo(0f)
			kickOffset.snapTo(0f)
			birthScale.snapTo(1f)
		}
		onExpansionFinished(expanded)
	}

	val cp = containerMorph.value.coerceIn(0f, 1f)
	val width = lerp(closedWidth.value, expandedWidth.value, cp).dp
	val height = lerp(closedHeight.value, animatedExpandedHeight.value, cp).dp
	val radius = lerp(closedHeight.value / 2f, 28f, cp).dp
	val shape = RoundedCornerShape(radius)
	val squircleEnabled = isSquircleEnabled()
	val graphicsSupported = rememberGraphicsEffectsSupported()
	Box(
		modifier = modifier
			.width(width)
			.height(height)
			.onGloballyPositioned { onBoundsChanged(it.positionInRoot(), it.size) }
			.graphicsLayer {
				// Draw-time read of the press frame; keeps press/jelly out of recomposition.
				@Suppress("UNUSED_VARIABLE") val pressTick = press.tick
				val boxWidth = size.width.toFloat()
				val boxHeight = size.height.toFloat()
				if(boxWidth <= 0f || boxHeight <= 0f) return@graphicsLayer
				deformRect.set(0f, 0f, boxWidth, boxHeight)
				if(cp > .01f) {
					press.jelly.deformMenu(deformRect, if(anchorFractionX >= .5f) -1f else 1f, 1f, density, 1f)
				}else{
					press.jelly.deformControl(deformRect, density, 1f)
				}
				// Birth origin in the closed box, remapped per anchor so the world point stays put
				// while the layout box grows from its anchored edge.
				val pressX = press.jelly.lightPositionX.coerceIn(0f, closedWidthPx)
				val pressY = press.jelly.lightPositionY.coerceIn(0f, closedHeightPx)
				val originX = when {
					anchorFractionX >= 1f -> boxWidth - (closedWidthPx - pressX)
					anchorFractionX > .01f -> boxWidth / 2f + (pressX - closedWidthPx / 2f)
					else -> pressX
				}
				val scaleIn = birthScale.value
				val left = originX + (deformRect.left - originX) * scaleIn
				val top = pressY + (deformRect.top - pressY) * scaleIn
				scaleX = deformRect.width * scaleIn / boxWidth
				scaleY = deformRect.height * scaleIn / boxHeight
				translationX = left
				translationY = top + kickOffset.value
				transformOrigin = TransformOrigin(0f, 0f)
			}
			// Observe inside the real graphics layer so window bounds include its live transform,
			// not the padding or the full-screen capture/touch host outside the glass.
			.onGloballyPositioned { onLayoutCoordinates(it) }
			.then(
				if(graphicsSupported) Modifier.drawBackdrop(
					backdrop = backdrop,
					shape = { shape },
					effects = {
						vibrancy()
						val blurRadius = homeLiquidToolbarVisualSpec().blurRadiusDp.dp.toPx()
						blur(blurRadius, blurRadius)
						// Lens strength follows the optical channel (on at hand-over, released at
						// ~84ms) instead of the whole geometry progress; at 0 the effect is skipped.
						val lensFactor = lensProgress.value.coerceIn(0f, 1f)
						lens(
							refractionHeight = 18.dp.toPx() * lensFactor,
							refractionAmount = 20.dp.toPx() * lensFactor,
						)
					},
					highlight = {
						val glow = press.jelly.glowValue
						outlineHighlight.value.copy(alpha = (lerp(0.82f, 0.68f, cp) + glow * 0.12f).coerceAtMost(1f))
					},
					onDrawSurface = { drawRect(surface) },
				) else Modifier.background(surface.copy(alpha = 1f), shape),
			)
			// Clip inner content only; the outer glass highlight must remain outside this clip.
			.clip(shape)
			.then(if(enableDragSelection) Modifier.pointerInput(Unit) {
				awaitEachGesture {
					val down = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
					if(!reduceMotion) press.down(down.position.x, down.position.y)
					val startedExpanded = expandedState.value
					val requestedExpansion = !startedExpanded && shouldExpandFromClosed(down.position, size)
					if(requestedExpansion) onExpansionRequested()
					var highlighted: Int? = null
					var dragged = false
					var totalDx = 0f
					var totalDy = 0f
					val velocityTracker = VelocityTracker()
					velocityTracker.addPosition(down.uptimeMillis, down.position)
					try {
						while(true) {
							val event = awaitPointerEvent(PointerEventPass.Initial)
							val change = event.changes.firstOrNull { it.id==down.id } ?: break
							val delta = change.positionChange()
							velocityTracker.addPosition(change.uptimeMillis, change.position)
							totalDx += delta.x
							totalDy += delta.y
							if(!dragged && hypot(totalDx, totalDy)>viewConfiguration.touchSlop) dragged = true
							if(!reduceMotion) press.move(change.position.x, change.position.y)
							val canSelect = dragged && expandedState.value && itemCountState.value>0
							val index = if(canSelect) ToolbarMenuGeometry.rowAt(
								x = change.position.x, y = change.position.y,
								width = size.width.toFloat(), height = size.height.toFloat(),
								density = density, rowCount = itemCountState.value,
							) else null
							if(index!=highlighted) {
								highlighted = index
								onSelectionChanged(index)
								if(index!=null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
							}
							if(dragged) change.consume()
							if(change.changedToUpIgnoreConsumed()) {
								val velocityY = velocityTracker.calculateVelocity().y
								if(shouldCollapseTrailingMenu(velocityY, viewConfiguration.minimumFlingVelocity)) onUpwardFling()
								else if(dragged) index?.let(onSelectionConfirmed)
								else if(!requestedExpansion && !startedExpanded) onClosedTap(change.position, size)
								if(!reduceMotion) press.release()
								break
							}
						}
					} finally {
						onSelectionChanged(null)
					}
				}
			} else Modifier)
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
		contentAlignment = Alignment.TopStart,
	) {
		// Press light lives outside the backdrop node: drawing it inside onDrawSurface would
		// re-record the glass display list and re-run blur+lens on every press frame.
		Box(Modifier.fillMaxSize().clip(shape)) {
			Canvas(Modifier.fillMaxSize()) {
				@Suppress("UNUSED_VARIABLE") val pressTick = press.tick
				val glow = press.jelly.glowValue
				if(glow > .01f) {
					val lightRadius = (if(containerMorph.value > .01f) 80f else 38f) * density
					drawRect(
						brush = Brush.radialGradient(
							colors = listOf(Color.White.copy(alpha = .12f * glow), Color.Transparent),
							center = Offset(press.jelly.lightPositionX, press.jelly.lightPositionY),
							radius = lightRadius,
						),
						size = size,
					)
				}
			}
		}
		Box(
			modifier = Modifier.graphicsLayer {
				alpha = (1f - cp * 1.8f).coerceIn(0f, 1f)
				translationY = -6.dp.toPx() * cp
			},
			content = closedContent,
		)
		Box(
			modifier = Modifier
				.graphicsLayer {
					val reveal = enterFraction.value.coerceIn(0f, 1f)
					val portal = 1f + (portalZoom - 1f) * portalProgress.value
					alpha = enterAlpha.value.coerceIn(0f, 1f)
					val scale = (menuSpec.enterScaleFrom + (1f - menuSpec.enterScaleFrom) * reveal) * portal
					scaleX = scale
					scaleY = scale
					transformOrigin = TransformOrigin(
						pivotFractionX = anchorFractionX,
						pivotFractionY = 0f,
					)
					// Rows blur while they reveal (18dp -> 0); off once settled to avoid a
					// permanent full-surface blur on low-end GPUs.
					renderEffect = rowsBlurEffect(18.dp.toPx() * (1f - reveal))
				}
				.menuClipRevealFromTop(
					fractionProgress = { enterFraction.value },
					cornerRadius = menuSpec.cornerRadiusDp.dp,
					squircleEnabled = squircleEnabled,
				),
			content = { expandedContent() },
		)
	}
}
