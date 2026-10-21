package org.joinmastodon.android.ui.compose.navigation

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.joinmastodon.android.GlobalUserPreferences
import org.joinmastodon.android.R
import org.joinmastodon.android.ui.compose.AppState
import org.joinmastodon.android.ui.compose.LocalAppState
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ContactsBook
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.function.Consumer
import java.util.function.IntConsumer

/** One destination of the home compose fan. [id] matches the ids used by the liquid compose menu. */
data class ComposeFanMenuItem @JvmOverloads constructor(
	val id: Int,
	val title: String,
	val icon: ImageVector,
	@DrawableRes val iconRes: Int? = null,
)

/** FAB geometry shared with [R.layout.compose_fab]: 56dp square, 16dp end/bottom margin. */
private const val FAB_SIZE_DP = 56
private const val FAB_MARGIN_END_DP = 16
private const val FAB_MARGIN_BOTTOM_DP = 16
private const val CIRCLE_SIZE_DP = 48
private const val ROW_GAP_DP = 12
/** Distance between the FAB centre and the nearest option centre. */
private const val FIRST_CENTER_OFFSET_DP = 64
/** Distance between two option centres. */
private const val CENTER_STEP_DP = 60
/** Delay between two neighbouring options opening (and closing, in reverse). */
private const val STAGGER_MS = 60L
/** How much later than its circle a label starts to fade in. */
private const val LABEL_DELAY_MS = 90L
/** How far a label travels from the right (under its circle) into place. */
private const val LABEL_SLIDE_DP = 24
private const val SCRIM_ALPHA = 0.45f

/**
 * Fan-out compose menu for the non-liquid home FAB. Tapping the FAB unfolds its circular
 * options upwards from the FAB position (60ms apart, springy), with their labels fading in from
 * the right a moment later over a dimmed page; tapping the cross or the dimmed area folds them
 * back in reverse order.
 */
class ComposeFanMenuController(
	context: Context,
	private val onAction: IntConsumer,
	private val onExpandedChanged: Consumer<Boolean> = Consumer {},
) {
	private var expandedState by mutableStateOf(false)
	private var bottomInsetPxState by mutableIntStateOf(0)
	private val items: List<ComposeFanMenuItem> = listOf(
		ComposeFanMenuItem(R.id.compose_post, context.getString(R.string.compose_menu_post), MiuixIcons.Notes),
		ComposeFanMenuItem(R.id.compose_friend_request, context.getString(R.string.compose_menu_friend_request), MiuixIcons.ContactsBook),
		ComposeFanMenuItem(R.id.compose_friend_card, context.getString(R.string.compose_menu_friend_card), MiuixIcons.ContactsBook, iconRes = R.drawable.ic_friend_card),
		ComposeFanMenuItem(R.id.compose_album, context.getString(R.string.baby_albums_compose_upload), MiuixIcons.Image),
	)

	private val composeView = ComposeView(context).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
		setContent {
			CompositionLocalProvider(LocalAppState provides AppState()) {
				MiuixAppTheme {
					FanMenuContent(
						expanded = expandedState,
						bottomInsetPx = bottomInsetPxState,
						items = items,
						onDismiss = { collapse() },
						onSelect = { id ->
							// Ignore taps on an already folding option, then fold away first so the
							// destination never animates underneath the menu.
							if (expandedState) {
								collapse()
								onAction.accept(id)
							}
						},
						onCollapsed = { view.visibility = View.GONE },
					)
				}
			}
		}
	}

	/** Full-screen overlay; sits above the content but below the FAB (see HomeTabFragment). */
	val view: FrameLayout = FrameLayout(context).apply {
		visibility = View.GONE
		addView(composeView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
	}

	val isExpanded: Boolean get() = expandedState

	fun setBottomInset(px: Int) {
		bottomInsetPxState = px.coerceAtLeast(0)
	}

	fun expand() {
		if (expandedState) return
		view.visibility = View.VISIBLE
		expandedState = true
		onExpandedChanged.accept(true)
	}

	fun collapse() {
		if (!expandedState) return
		expandedState = false
		onExpandedChanged.accept(false)
	}

	/** @return true when the menu was open and consumed the back gesture. */
	fun collapseIfExpanded(): Boolean {
		if (!expandedState) return false
		collapse()
		return true
	}
}

@Composable
private fun FanMenuContent(
	expanded: Boolean,
	bottomInsetPx: Int,
	items: List<ComposeFanMenuItem>,
	onDismiss: () -> Unit,
	onSelect: (Int) -> Unit,
	onCollapsed: () -> Unit,
) {
	val reduceMotion = GlobalUserPreferences.reduceMotion
	val scrim = remember { Animatable(0f) }
	LaunchedEffect(expanded) {
		if (expanded) {
			scrim.animateTo(1f, tween(if (reduceMotion) 0 else 200))
		} else {
			// Keep the overlay alive until the last staggered option has finished folding.
			val collapseDurationMs = if (reduceMotion) 0 else (items.lastIndex.coerceAtLeast(0) * STAGGER_MS + 160L).toInt()
			scrim.animateTo(0f, tween(collapseDurationMs))
			onCollapsed()
		}
	}

	Box(Modifier.fillMaxSize()) {
		if (scrim.value > 0.001f) {
			Box(
				Modifier
					.fillMaxSize()
					.background(Color.Black.copy(alpha = SCRIM_ALPHA * scrim.value))
					.pointerInput(Unit) { detectTapGestures { onDismiss() } }
			)
		}
		Column(
			modifier = Modifier
				.align(Alignment.BottomEnd)
				.padding(
					end = (FAB_MARGIN_END_DP + (FAB_SIZE_DP - CIRCLE_SIZE_DP) / 2).dp,
					bottom = (FAB_MARGIN_BOTTOM_DP + FAB_SIZE_DP / 2 + FIRST_CENTER_OFFSET_DP - CIRCLE_SIZE_DP / 2).dp + with(LocalDensity.current) { bottomInsetPx.toDp() },
				),
			horizontalAlignment = Alignment.End,
			verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
		) {
			// Rendered bottom-up: index 0 is the option nearest the FAB.
			for (index in items.indices.reversed()) {
				FanRow(
					index = index,
					count = items.size,
					item = items[index],
					expanded = expanded,
					reduceMotion = reduceMotion,
					onSelect = onSelect,
				)
			}
		}
	}
}

@Composable
private fun FanRow(
	index: Int,
	count: Int,
	item: ComposeFanMenuItem,
	expanded: Boolean,
	reduceMotion: Boolean,
	onSelect: (Int) -> Unit,
) {
	val circle = remember { Animatable(0f) }
	val label = remember { Animatable(0f) }
	LaunchedEffect(expanded) {
		if (expanded) {
			launch {
				delay(if (reduceMotion) 0 else index * STAGGER_MS)
				circle.animateTo(
					1f,
					if (reduceMotion) tween(0) else spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow),
				)
			}
			launch {
				delay(if (reduceMotion) 0 else index * STAGGER_MS + LABEL_DELAY_MS)
				label.animateTo(1f, tween(if (reduceMotion) 0 else 200))
			}
		} else {
			// Reverse order: the option furthest from the FAB leaves first.
			val delayMs = if (reduceMotion) 0L else (count - 1 - index) * STAGGER_MS
			launch {
				delay(delayMs)
				circle.animateTo(0f, tween(if (reduceMotion) 0 else 160))
			}
			launch {
				delay(delayMs)
				label.animateTo(0f, tween(if (reduceMotion) 0 else 120))
			}
		}
	}

	val (containerColor, contentColor) = when (index) {
		0 -> MiuixTheme.colorScheme.primaryContainer to MiuixTheme.colorScheme.onPrimaryContainer
		1 -> MiuixTheme.colorScheme.secondaryContainer to MiuixTheme.colorScheme.onSecondaryContainer
		else -> MiuixTheme.colorScheme.tertiaryContainer to MiuixTheme.colorScheme.onTertiaryContainer
	}

	Row(
		modifier = Modifier.graphicsLayer {
			translationY = (FIRST_CENTER_OFFSET_DP + index * CENTER_STEP_DP) * (1f - circle.value) * density
		},
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = item.title,
			color = contentColor,
			fontSize = 13.sp,
			fontWeight = FontWeight.Medium,
			maxLines = 1,
			modifier = Modifier
				.graphicsLayer {
					translationX = LABEL_SLIDE_DP * (1f - label.value) * density
					alpha = label.value
				}
				.clip(RoundedCornerShape(14.dp))
				.background(MiuixTheme.colorScheme.surfaceContainerHighest)
				.clickable { onSelect(item.id) }
				.padding(horizontal = 12.dp, vertical = 7.dp),
		)
		Spacer(Modifier.width(12.dp))
		Box(
			modifier = Modifier
				.size(CIRCLE_SIZE_DP.dp)
				.graphicsLayer {
					val scale = 0.35f + 0.65f * circle.value
					scaleX = scale
					scaleY = scale
					alpha = circle.value
				}
				.clip(CircleShape)
				.background(containerColor)
				.clickable(
					interactionSource = remember { MutableInteractionSource() },
					indication = null,
					role = Role.Button,
					onClick = { onSelect(item.id) },
				)
				.semantics { contentDescription = item.title },
			contentAlignment = Alignment.Center,
		) {
			if (item.iconRes != null) {
				Icon(painterResource(item.iconRes), contentDescription = null, modifier = Modifier.size(24.dp), tint = contentColor)
			} else {
				Icon(item.icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = contentColor)
			}
		}
	}
}
