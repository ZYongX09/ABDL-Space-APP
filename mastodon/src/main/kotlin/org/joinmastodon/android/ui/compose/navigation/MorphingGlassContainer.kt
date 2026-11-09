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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
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
	/** The trigger pill keeps its own band above the panel, with a small liquid gap. */
	const val TRIGGER_HEIGHT_DP = 48
	const val PANEL_GAP_DP = 2

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

/**
 * Trigger lobe + menu panel + a curved neck, filled as one path. While the neck is present the
 * lens shader is skipped (it only understands a single rounded rect) which matches the reference:
 * its own lens is released ~84ms in, long before the neck detaches at ~210ms.
 */
private class TwoLobeGlassShape(
	private val pill: Rect,
	private val pillRadius: Float,
	private val panel: Rect,
	private val panelRadius: Float,
	private val neckTopHalf: Float,
	private val neckBottomHalf: Float,
) : Shape {
	override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
		val path = Path()
		val pillVisible = pill.height > .5f
		val panelVisible = panel.height > .5f
		if(pillVisible) path.addRoundRect(RoundRect(pill.left, pill.top, pill.right, pill.bottom, CornerRadius(pillRadius)))
		if(panelVisible) path.addRoundRect(RoundRect(panel.left, panel.top, panel.right, panel.bottom, CornerRadius(panelRadius)))
		if(pillVisible && panelVisible && neckTopHalf > .5f && pill.bottom <= panel.top){
			val topX = pill.center.x
			val bottomX = panel.center.x
			val topHalf = neckTopHalf
			val bottomHalf = neckBottomHalf
			val midY = (pill.bottom + panel.top) / 2f
			// Concave waist: leave the pill at its own edge, widen toward the panel.
			path.moveTo(topX - topHalf, pill.bottom)
			path.cubicTo(topX - topHalf * .72f, midY, bottomX - bottomHalf * .88f, midY, bottomX - bottomHalf, panel.top)
			path.lineTo(bottomX + bottomHalf, panel.top)
			path.cubicTo(bottomX + bottomHalf * .88f, midY, topX + topHalf * .72f, midY, topX + topHalf, pill.bottom)
			path.close()
		}
		path.fillType = PathFillType.NonZero
		return Outline.Generic(path)
	}
}

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
	// 0..1 panel presence: drives the panel height and the trigger's shrink/offset.
	val panelProgress = remember { Animatable(if(expanded) 1f else 0f) }
	// 0..1 neck strength: rises with the panel, detaches ~210ms after opening.
	val neckProgress = remember { Animatable(if(expanded) 1f else 0f) }
	val kickOffset = remember { Animatable(0f) }
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
	val densityObj = LocalDensity.current
	val press = pressInput ?: remember { GlassPressInput() }
	val reduceMotion = GlobalUserPreferences.reduceMotion
	val deformRect = remember { GlassRect() }
	val kickPrimed = remember { booleanArrayOf(false) }

	val closedWidthPx = with(densityObj) { closedWidth.toPx() }
	val closedHeightPx = with(densityObj) { closedHeight.toPx() }
	val expandedWidthPx = with(densityObj) { expandedWidth.toPx() }
	val expandedHeightPx = with(densityObj) { expandedHeight.toPx() }
	val gapPx = with(densityObj) { ToolbarMenuGeometry.PANEL_GAP_DP.dp.toPx() }
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
		val morphSpec = folmeSpring<Float>(
			damping = if(expanded) .75f else .8f,
			response = if(expanded) .32f else .35f,
		)
		val fractionSpring = folmeSpring<Float>(
			damping = 1f,
			response = .3f,
			visibilityThreshold = menuSpec.fractionVisibilityThreshold,
		)
		val kickSpec = folmeSpring<Float>(damping = 1f, response = .14f)
		val blurOutSpec = folmeSpring<Float>(damping = .8f, response = .315f)
		val lensOutSpec = folmeSpring<Float>(damping = 1f, response = if(expanded) .42f else .28f)
		val smoothingRelease = folmeSpring<Float>(damping = 1f, response = .42f)
		val alphaSpec = tween<Float>(
			durationMillis = if(expanded) menuSpec.alphaEnterDurationMs else menuSpec.alphaExitDurationMs,
			easing = SinOutEasing,
		)
		launch { panelProgress.animateTo(target, morphSpec) }
		launch {
			if(reduceMotion) {
				neckProgress.snapTo(0f)
				return@launch
			}
			neckProgress.snapTo(1f)
			// The reference releases the metaball neck 210ms after opening (70ms when closing).
			delay(if(expanded) 210 else 70)
			neckProgress.animateTo(0f, smoothingRelease)
		}
		launch {
			if(reduceMotion) {
				kickOffset.snapTo(0f)
				return@launch
			}
			if(kickPrimed[0]) {
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
		if(!expanded) {
			containerMorph.snapTo(0f)
			panelProgress.snapTo(0f)
			neckProgress.snapTo(0f)
			enterFraction.snapTo(0f)
			enterAlpha.snapTo(0f)
			kickOffset.snapTo(0f)
		}
		onExpansionFinished(expanded)
	}

	val cp = containerMorph.value.coerceIn(0f, 1f)
	val pp = panelProgress.value.coerceIn(0f, 1f)
	val neck = neckProgress.value.coerceIn(0f, 1f)
	val width = lerp(closedWidth.value, expandedWidth.value, cp).dp
	val height = closedHeight + (ToolbarMenuGeometry.PANEL_GAP_DP.dp + animatedExpandedHeight) * pp
	val boxWidthPx = with(densityObj) { width.toPx() }
	val boxHeightPx = with(densityObj) { height.toPx() }
	val kick = kickOffset.value

	// Trigger lobe: shrinks toward the press point as the panel grows.
	val pillScale = 1f - .1f * pp
	val pillW = closedWidthPx * pillScale
	val pillH = closedHeightPx * pillScale
	val basePillCenterX = when {
		anchorFractionX >= 1f -> boxWidthPx - closedWidthPx / 2f
		anchorFractionX > .01f -> boxWidthPx / 2f
		else -> closedWidthPx / 2f
	}
	val pressX = press.jelly.lightPositionX.coerceIn(0f, closedWidthPx)
	val pillShift = (pressX - closedWidthPx / 2f) * .15f * pp
	val pillCenterX = basePillCenterX + pillShift
	val pillRect = Rect(pillCenterX - pillW / 2f, (closedHeightPx - pillH) / 2f, pillCenterX + pillW / 2f, (closedHeightPx + pillH) / 2f)
	val panelTopPx = closedHeightPx + gapPx * pp + kick
	val panelRect = Rect(0f, panelTopPx, boxWidthPx, boxHeightPx + kick)
	val neckTopHalf = min(pillW, boxWidthPx) * .5f * neck
	val neckBottomHalf = min(boxWidthPx, pillW * 1.9f) * .5f * neck

	val twoLobe = pp > .01f || neck > .01f
	val radius = lerp(closedHeight.value / 2f, 28f, cp).dp
	val panelTopState = rememberUpdatedState(panelTopPx)
	val glassShape: Shape = if(twoLobe)
		TwoLobeGlassShape(pillRect, pillH / 2f, panelRect, 28.dp.value * density, neckTopHalf, neckBottomHalf)
	else
		RoundedCornerShape(radius)
	// Clip/hit-test with a plain rounded box: a compound Outline.Generic clips out the rows on
	// some API levels, and the panel content never paints outside the box anyway.
	val shape: Shape = RoundedCornerShape(radius)
	val squircleEnabled = isSquircleEnabled()
	val graphicsSupported = rememberGraphicsEffectsSupported()
	Box(
		modifier = modifier
			.width(width)
			.height(height)
			.onGloballyPositioned { onBoundsChanged(it.positionInRoot(), it.size) }
			.graphicsLayer {
				@Suppress("UNUSED_VARIABLE") val pressTick = press.tick
				val boxW = size.width.toFloat()
				val boxH = size.height.toFloat()
				if(boxW <= 0f || boxH <= 0f) return@graphicsLayer
				deformRect.set(0f, 0f, boxW, boxH)
				if(pp > .01f) {
					press.jelly.deformMenu(deformRect, if(anchorFractionX >= .5f) -1f else 1f, 1f, density, 1f)
				}else{
					press.jelly.deformControl(deformRect, density, 1f)
				}
				scaleX = deformRect.width / boxW
				scaleY = deformRect.height / boxH
				translationX = deformRect.left
				translationY = deformRect.top
				transformOrigin = TransformOrigin(0f, 0f)
			}
			.onGloballyPositioned { onLayoutCoordinates(it) }
			.then(
				if(graphicsSupported) Modifier.drawBackdrop(
					backdrop = backdrop,
					shape = { glassShape },
					effects = {
						vibrancy()
						val blurRadius = homeLiquidToolbarVisualSpec().blurRadiusDp.dp.toPx()
						blur(blurRadius, blurRadius)
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
							val menuTop = panelTopState.value
							val index = if(canSelect) ToolbarMenuGeometry.rowAt(
								x = change.position.x, y = change.position.y - menuTop,
								width = size.width.toFloat(), height = size.height.toFloat() - menuTop,
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
					val lightRadius = (if(pp > .01f) 80f else 38f) * density
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
				alpha = 1f
				translationY = -6.dp.toPx() * cp
			},
			content = closedContent,
		)
		Box(
			modifier = Modifier
				.graphicsLayer {
					translationY = panelTopPx
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
