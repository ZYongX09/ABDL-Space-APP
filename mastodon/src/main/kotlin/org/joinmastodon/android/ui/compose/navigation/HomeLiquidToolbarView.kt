package org.joinmastodon.android.ui.compose.navigation

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.R
import org.joinmastodon.android.ui.compose.AppState
import org.joinmastodon.android.ui.compose.LocalAppState
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import org.joinmastodon.android.ui.compose.navigation.liquid.lens
import org.joinmastodon.android.ui.compose.navigation.liquid.iosIndicatorSpecular
import org.joinmastodon.android.ui.compose.navigation.liquid.rememberGravityRotatedHighlight
import org.joinmastodon.android.ui.compose.navigation.liquid.vibrancy
import org.joinmastodon.android.ui.compose.ui.isInDarkTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.function.IntConsumer
import java.util.function.Consumer

data class HomeToolbarTimeline(
	val id: Int,
	val title: String,
	@DrawableRes val iconRes: Int,
)

data class HomeToolbarMenuItem(
	val id: Int,
	val title: String,
	@DrawableRes val iconRes: Int,
)

class HomeLiquidToolbarController(
	context: Context,
	private val onTimelineSelected: IntConsumer,
	private val onNewPosts: Runnable,
	private val onCompose: Runnable,
	private val onMenuItem: IntConsumer,
) {
	private val backdrop = ViewBitmapBackdrop()
	private var statusBarInsetState by mutableIntStateOf(0)
	private var timelinesState by mutableStateOf(emptyList<HomeToolbarTimeline>())
	private var selectedTimelineState by mutableIntStateOf(0)
	private var showNewPostsState by mutableStateOf(false)
	private var remindersState by mutableStateOf(homeToolbarReminderState(false, false))
	private var rootMenuState by mutableStateOf(emptyList<HomeToolbarMenuItem>())
	private var listsState by mutableStateOf(emptyList<HomeToolbarMenuItem>())
	private var hashtagsState by mutableStateOf(emptyList<HomeToolbarMenuItem>())
	private var menuPageState by mutableStateOf(HomeToolbarMenuPage.NONE)
	private var pendingMenuPageState by mutableStateOf(HomeToolbarMenuPage.NONE)
	private var highlightedMenuIndexState by mutableStateOf<Int?>(null)
	private var menuOpenListener: Consumer<Boolean>? = null

	val view = ComposeView(context).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
		setContent {
			CompositionLocalProvider(LocalAppState provides AppState()) {
				MiuixAppTheme { ToolbarContent() }
			}
		}
	}

	fun setBackdropBitmap(bitmap: Bitmap) {
		backdrop.update(bitmap)
		if(pendingMenuPageState!=HomeToolbarMenuPage.NONE) {
			menuPageState = pendingMenuPageState
			pendingMenuPageState = HomeToolbarMenuPage.NONE
		}
	}
	fun setStatusBarInset(insetPx: Int) { statusBarInsetState = insetPx.coerceAtLeast(0) }
	fun setTimelines(items: List<HomeToolbarTimeline>, selectedIndex: Int) {
		timelinesState = items
		selectedTimelineState = selectedIndex.coerceIn(0, (items.size-1).coerceAtLeast(0))
	}
	fun setShowNewPosts(show: Boolean) { showNewPostsState = show }
	fun setReminderState(hasUnreadAnnouncements: Boolean, hasUpdate: Boolean) {
		remindersState = homeToolbarReminderState(hasUnreadAnnouncements, hasUpdate)
	}
	fun setMenus(root: List<HomeToolbarMenuItem>, lists: List<HomeToolbarMenuItem>, hashtags: List<HomeToolbarMenuItem>) {
		rootMenuState = root
		listsState = lists
		hashtagsState = hashtags
	}
	fun closeMenu() {
		val wasPending = pendingMenuPageState!=HomeToolbarMenuPage.NONE
		pendingMenuPageState = HomeToolbarMenuPage.NONE
		menuPageState = HomeToolbarMenuPage.NONE
		if(wasPending) menuOpenListener?.accept(false)
	}
	private fun requestMenu(page: HomeToolbarMenuPage) {
		if(menuPageState!=HomeToolbarMenuPage.NONE) {
			menuPageState = page
			return
		}
		pendingMenuPageState = page
		menuOpenListener?.accept(true)
	}
	private fun activateMenuItem(page: HomeToolbarMenuPage, item: HomeToolbarMenuItem) {
		when(item.id) {
			R.id.lists -> menuPageState = HomeToolbarMenuPage.LISTS
			R.id.hashtags -> menuPageState = HomeToolbarMenuPage.HASHTAGS
			else -> {
				closeMenu()
				if(page==HomeToolbarMenuPage.TIMELINES) onTimelineSelected.accept(item.id) else onMenuItem.accept(item.id)
			}
		}
	}
	fun setMenuOpenListener(listener: Consumer<Boolean>?) { menuOpenListener = listener }
	fun dispose() = view.disposeComposition()

	@Composable
	private fun ToolbarContent() {
		val visualSpec = homeLiquidToolbarVisualSpec()
		val motionSpec = homeLiquidToolbarMotionSpec()
		val contentColor = MiuixTheme.colorScheme.onSurface
		val density = LocalDensity.current
		val topInset = with(density) { statusBarInsetState.toDp() }
		val currentTimeline = timelinesState.getOrNull(selectedTimelineState)
		val menuItems = when(menuPageState) {
			HomeToolbarMenuPage.TIMELINES -> timelinesState.map { HomeToolbarMenuItem(it.id, it.title, it.iconRes) }
			HomeToolbarMenuPage.LISTS -> listsState
			HomeToolbarMenuPage.HASHTAGS -> hashtagsState
			HomeToolbarMenuPage.ROOT -> rootMenuState
			HomeToolbarMenuPage.NONE -> emptyList()
		}
		Box(
			modifier = Modifier
				.fillMaxSize()
				.onGloballyPositioned { backdrop.updateOriginInWindow(it.positionInWindow()) },
		) {
			if(menuPageState!=HomeToolbarMenuPage.NONE) {
				Box(Modifier.fillMaxSize().clickable { closeMenu() })
			}
			val leadingExpanded = menuPageState==HomeToolbarMenuPage.TIMELINES
			MorphingGlassContainer(
				expanded = leadingExpanded,
				closedWidth = 220.dp,
				closedHeight = 48.dp,
				expandedWidth = 248.dp,
				expandedHeight = (timelinesState.size * 48 + 12).coerceAtLeast(60).dp,
				backdrop = backdrop,
				anchorFractionX = 0f,
				selectionItemCount = menuItems.size,
				shouldExpandFromClosed = { _, _ -> !showNewPostsState },
				onExpansionRequested = { requestMenu(HomeToolbarMenuPage.TIMELINES) },
				onClosedTap = { _, _ -> if(showNewPostsState) onNewPosts.run() },
				modifier = Modifier.align(Alignment.TopStart).padding(top = topInset + 8.dp, start = 12.dp),
				onExpansionStarted = {},
				onExpansionFinished = { open -> if(!open && menuPageState==HomeToolbarMenuPage.NONE) menuOpenListener?.accept(false) },
				onClick = {},
				onSelectionChanged = { highlightedMenuIndexState = it },
				onSelectionConfirmed = { index -> menuItems.getOrNull(index)?.let { activateMenuItem(HomeToolbarMenuPage.TIMELINES, it) } },
				closedContent = {
					Row(Modifier.height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
					ResourceIcon(if(showNewPostsState) R.drawable.ic_fluent_arrow_up_16_filled else currentTimeline?.iconRes ?: R.drawable.ic_fluent_home_24_regular, 24, contentColor)
					Spacer(Modifier.width(8.dp))
					Text(
						text = if(showNewPostsState) view.context.getString(R.string.see_new_posts) else currentTimeline?.title.orEmpty(),
						fontSize = visualSpec.titleTextSp.sp,
						fontWeight = FontWeight.Medium,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					if(!showNewPostsState) {
						Spacer(Modifier.width(6.dp))
						ResourceIcon(R.drawable.ic_fluent_chevron_down_16_filled, 18, contentColor)
					}
					}
				},
				expandedContent = { progress ->
					MenuContent(page = HomeToolbarMenuPage.TIMELINES, progress = progress, highlightedIndex = highlightedMenuIndexState)
				},
			)

			val trailingExpanded = menuPageState==HomeToolbarMenuPage.ROOT || menuPageState==HomeToolbarMenuPage.LISTS || menuPageState==HomeToolbarMenuPage.HASHTAGS
			val trailingRows = menuItems.size + if(menuPageState==HomeToolbarMenuPage.LISTS || menuPageState==HomeToolbarMenuPage.HASHTAGS) 1 else 0
			MorphingGlassContainer(
				expanded = trailingExpanded,
				closedWidth = 108.dp,
				closedHeight = 48.dp,
				expandedWidth = 248.dp,
				expandedHeight = (trailingRows * 48 + 12).coerceIn(60, 420).dp,
				backdrop = backdrop,
				anchorFractionX = 1f,
				selectionItemCount = menuItems.size + if(menuPageState==HomeToolbarMenuPage.LISTS || menuPageState==HomeToolbarMenuPage.HASHTAGS) 1 else 0,
				shouldExpandFromClosed = { position, size -> position.x >= size.width - 54f },
				onExpansionRequested = { requestMenu(HomeToolbarMenuPage.ROOT) },
				onClosedTap = { position, size -> if(position.x < size.width / 2f) onCompose.run() },
				modifier = Modifier.align(Alignment.TopEnd).padding(top = topInset + 8.dp, end = 12.dp),
				onExpansionStarted = {},
				onExpansionFinished = { open -> if(!open && menuPageState==HomeToolbarMenuPage.NONE) menuOpenListener?.accept(false) },
				onClick = {},
				onSelectionChanged = { highlightedMenuIndexState = it },
				onSelectionConfirmed = { rawIndex ->
					val hasBack = menuPageState==HomeToolbarMenuPage.LISTS || menuPageState==HomeToolbarMenuPage.HASHTAGS
					if(hasBack && rawIndex==0) menuPageState = HomeToolbarMenuPage.ROOT
					else menuItems.getOrNull(rawIndex - if(hasBack) 1 else 0)?.let { activateMenuItem(menuPageState, it) }
				},
				closedContent = {
					Row(Modifier.height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
					ToolbarIcon(R.drawable.ic_fluent_edit_24_regular)
					Box {
						ToolbarIcon(R.drawable.ic_fluent_more_vertical_24_regular)
						if(remindersState.overflowBadged) Box(Modifier.align(Alignment.TopEnd).size(7.dp).background(Color.Red, CircleShape))
					}
					}
				},
				expandedContent = { progress -> MenuContent(menuPageState, progress, highlightedMenuIndexState) },
			)
		}
	}

	@Composable
	private fun MenuContent(page: HomeToolbarMenuPage, progress: Float, highlightedIndex: Int?) {
		AnimatedContent(
			targetState = page,
			transitionSpec = {
				val forward = targetState==HomeToolbarMenuPage.LISTS || targetState==HomeToolbarMenuPage.HASHTAGS
				(slideInHorizontally(
					animationSpec = spring(dampingRatio = 0.86f, stiffness = 560f),
					initialOffsetX = { if(forward) 18 else -18 },
				) + fadeIn()).togetherWith(
					slideOutHorizontally(
						animationSpec = spring(dampingRatio = 0.9f, stiffness = 620f),
						targetOffsetX = { if(forward) -18 else 18 },
					) + fadeOut(),
				).using(SizeTransform(clip = false))
			},
			label = "toolbarMenuPage",
		) { animatedPage ->
			val animatedItems = when(animatedPage) {
				HomeToolbarMenuPage.TIMELINES -> timelinesState.map { HomeToolbarMenuItem(it.id, it.title, it.iconRes) }
				HomeToolbarMenuPage.LISTS -> listsState
				HomeToolbarMenuPage.HASHTAGS -> hashtagsState
				HomeToolbarMenuPage.ROOT -> rootMenuState
				HomeToolbarMenuPage.NONE -> emptyList()
			}
			Column(
				Modifier
					.heightIn(max = 420.dp)
					.verticalScroll(rememberScrollState())
					.padding(vertical = 6.dp)
					.graphicsLayer { alpha = progress },
			) {
				if(animatedPage==HomeToolbarMenuPage.LISTS || animatedPage==HomeToolbarMenuPage.HASHTAGS) {
					MenuRow(R.drawable.ic_fluent_chevron_left_24_regular, view.context.getString(R.string.back), false, highlightedIndex==0) { menuPageState = HomeToolbarMenuPage.ROOT }
				}
				animatedItems.forEachIndexed { index, item ->
					val badged = (item.id==R.id.announcements && remindersState.announcementsBadged) || (item.id==R.id.settings && remindersState.settingsBadged)
					val offset = if(animatedPage==HomeToolbarMenuPage.LISTS || animatedPage==HomeToolbarMenuPage.HASHTAGS) 1 else 0
					MenuRow(item.iconRes, item.title, badged, highlightedIndex==index+offset) { activateMenuItem(animatedPage, item) }
				}
			}
		}
	}

	@Composable
	private fun MenuRow(@DrawableRes icon: Int, title: String, badged: Boolean, highlighted: Boolean, onClick: () -> Unit) {
		Row(
			modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if(highlighted) MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f) else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			ResourceIcon(icon, 24, MiuixTheme.colorScheme.onSurface)
			Spacer(Modifier.width(12.dp))
			Text(title, modifier = Modifier.weight(1f), fontSize = homeLiquidToolbarVisualSpec().menuTextSp.sp)
			if(badged) Box(Modifier.size(7.dp).background(Color.Red, CircleShape))
		}
	}

	@Composable
	private fun GlassSurface(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
		val isDark = isInDarkTheme()
		val interactionSource = remember { MutableInteractionSource() }
		val pressed by interactionSource.collectIsPressedAsState()
		val motionSpec = homeLiquidToolbarMotionSpec()
		val outlineHighlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = -45f)
		val scale by animateFloatAsState(
			targetValue = if(pressed) 0.96f else 1f,
			animationSpec = spring(motionSpec.dampingRatio, motionSpec.stiffness),
			label = "glassPressScale",
		)
		val surfaceAlpha = homeLiquidToolbarVisualSpec().surfaceAlpha
		val surface = if(isDark) Color.Black.copy(alpha = surfaceAlpha) else Color.White.copy(alpha = surfaceAlpha)
		Row(
			modifier = modifier
				.graphicsLayer { scaleX = scale; scaleY = scale }
				.then(
					if(isRuntimeShaderSupported()) Modifier.drawBackdrop(
						backdrop = backdrop,
						shape = { RoundedCornerShape(24.dp) },
						effects = { vibrancy(); blur(homeLiquidToolbarVisualSpec().blurRadiusDp.dp.toPx(), homeLiquidToolbarVisualSpec().blurRadiusDp.dp.toPx()); lens(12.dp.toPx(), 16.dp.toPx()) },
						highlight = { outlineHighlight.value.copy(alpha = 0.75f) },
						onDrawSurface = { drawRect(surface) },
					) else Modifier.background(surface, RoundedCornerShape(24.dp)),
				)
				.clip(RoundedCornerShape(24.dp))
				.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
				.padding(horizontal = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
			content = { content() },
		)
	}

	@Composable
	private fun ToolbarIcon(@DrawableRes icon: Int) {
		Box(Modifier.size(40.dp).padding(8.dp)) {
			ResourceIcon(icon, 24, MiuixTheme.colorScheme.onSurface)
		}
	}

	@Composable
	private fun ResourceIcon(@DrawableRes icon: Int, sizeDp: Int, tint: Color) {
		AndroidView(
			modifier = Modifier.size(sizeDp.dp),
			factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE } },
			update = { imageView ->
				imageView.setImageResource(icon)
				imageView.imageTintList = ColorStateList.valueOf(tint.toArgb())
			},
		)
	}
}
