package org.joinmastodon.android.ui.compose.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.runtime.withFrameNanos
import org.joinmastodon.android.ui.compose.navigation.liquid.iosIndicatorSpecular
import org.joinmastodon.android.ui.compose.navigation.liquid.lens
import org.joinmastodon.android.ui.compose.navigation.liquid.rememberGravityRotatedHighlight
import org.joinmastodon.android.ui.compose.navigation.liquid.vibrancy
import org.joinmastodon.android.ui.compose.ui.isInDarkTheme
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import kotlin.math.hypot

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
	onBoundsChanged: (Offset, IntSize) -> Unit = { _, _ -> },
	closedContent: @Composable BoxScope.() -> Unit,
	expandedContent: @Composable BoxScope.(progress: Float) -> Unit,
) {
	val progress = remember { Animatable(if(expanded) 1f else 0f) }
	val animatedExpandedHeight by animateDpAsState(
		targetValue = expandedHeight,
		animationSpec = spring(dampingRatio = 0.88f, stiffness = 560f),
		label = "expandedGlassHeight",
	)
	val motion = homeLiquidToolbarMotionSpec()
	val interactionSource = remember { MutableInteractionSource() }
	val pressed by interactionSource.collectIsPressedAsState()
	val pressScale = remember { Animatable(1f) }
	val outlineHighlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = -45f)
	val isDark = isInDarkTheme()
	val surfaceAlpha = homeLiquidToolbarVisualSpec().surfaceAlpha
	val surface = if(isDark) Color.Black.copy(alpha = surfaceAlpha) else Color.White.copy(alpha = surfaceAlpha)
	val haptics = LocalHapticFeedback.current
	val viewConfiguration = LocalViewConfiguration.current
	val expandedState = rememberUpdatedState(expanded)
	val itemCountState = rememberUpdatedState(selectionItemCount)

	LaunchedEffect(pressed) {
		pressScale.animateTo(
			targetValue = if(pressed) 0.965f else 1f,
			animationSpec = spring(dampingRatio = 0.72f, stiffness = 650f),
		)
	}
	LaunchedEffect(expanded) {
		onExpansionStarted()
		if(expanded) withFrameNanos { }
		progress.animateTo(
			targetValue = if(expanded) 1f else 0f,
			animationSpec = spring(
				dampingRatio = if(expanded) motion.dampingRatio else 0.9f,
				stiffness = if(expanded) motion.stiffness else 620f,
			),
		)
		onExpansionFinished(expanded)
	}

	val p = progress.value.coerceIn(0f, 1f)
	val width = lerp(closedWidth.value, expandedWidth.value, p).dp
	val height = lerp(closedHeight.value, animatedExpandedHeight.value, p).dp
	val radius = lerp(closedHeight.value / 2f, 28f, p).dp
	val shape = RoundedCornerShape(radius)
	Box(
		modifier = modifier
			.width(width)
			.height(height)
			.onGloballyPositioned { onBoundsChanged(it.positionInRoot(), it.size) }
			.graphicsLayer {
				scaleX = pressScale.value
				scaleY = pressScale.value
				transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
					pivotFractionX = anchorFractionX,
					pivotFractionY = 0f,
				)
			}
			.then(
				if(isRuntimeShaderSupported()) Modifier.drawBackdrop(
					backdrop = backdrop,
					shape = { shape },
					effects = {
						vibrancy()
						val blurRadius = homeLiquidToolbarVisualSpec().blurRadiusDp.dp.toPx()
						blur(blurRadius, blurRadius)
						lens(
							refractionHeight = lerp(18.dp.toPx(), 10.dp.toPx(), p),
							refractionAmount = lerp(20.dp.toPx(), 12.dp.toPx(), p),
						)
					},
					highlight = { outlineHighlight.value.copy(alpha = lerp(0.82f, 0.68f, p)) },
					onDrawSurface = { drawRect(surface) },
				) else Modifier.background(surface, shape),
			)
			.clip(shape)
			.then(if(enableDragSelection) Modifier.pointerInput(Unit) {
				awaitEachGesture {
					val down = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
					val startedExpanded = expandedState.value
					val requestedExpansion = !startedExpanded && shouldExpandFromClosed(down.position, size)
					if(requestedExpansion) onExpansionRequested()
					var highlighted: Int? = null
					var dragged = false
					var totalDx = 0f
					var totalDy = 0f
					try {
						while(true) {
							val event = awaitPointerEvent(PointerEventPass.Initial)
							val change = event.changes.firstOrNull { it.id==down.id } ?: break
							val delta = change.positionChange()
							totalDx += delta.x
							totalDy += delta.y
							if(!dragged && hypot(totalDx, totalDy)>viewConfiguration.touchSlop) dragged = true
							val canSelect = dragged && expandedState.value && itemCountState.value>0
							val index = if(canSelect) ((change.position.y - 6.dp.toPx()) / 48.dp.toPx()).toInt()
								.takeIf { change.position.x in 0f..size.width.toFloat() && it in 0 until itemCountState.value } else null
							if(index!=highlighted) {
								highlighted = index
								onSelectionChanged(index)
								if(index!=null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
							}
							if(dragged) change.consume()
							if(change.changedToUpIgnoreConsumed()) {
								if(dragged) index?.let(onSelectionConfirmed)
								else if(!requestedExpansion && !startedExpanded) onClosedTap(change.position, size)
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
		Box(
			modifier = Modifier.graphicsLayer {
				alpha = (1f - p * 1.8f).coerceIn(0f, 1f)
				translationY = -6.dp.toPx() * p
			},
			content = closedContent,
		)
		Box(
			modifier = Modifier.graphicsLayer {
				alpha = ((p - 0.18f) / 0.82f).coerceIn(0f, 1f)
				translationY = 8.dp.toPx() * (1f - p)
			},
			content = { expandedContent(p) },
		)
	}
}
