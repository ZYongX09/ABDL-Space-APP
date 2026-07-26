package org.joinmastodon.android.ui.compose.navigation

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.R
import org.joinmastodon.android.ui.compose.AppState
import org.joinmastodon.android.ui.compose.LocalAppState
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import org.joinmastodon.android.ui.compose.navigation.liquid.lens
import org.joinmastodon.android.ui.compose.navigation.liquid.vibrancy
import org.joinmastodon.android.ui.compose.ui.isInDarkTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.function.IntConsumer

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

	val view = ComposeView(context).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
		setContent {
			CompositionLocalProvider(LocalAppState provides AppState()) {
				MiuixAppTheme { ToolbarContent() }
			}
		}
	}

	fun setBackdropBitmap(bitmap: Bitmap) = backdrop.update(bitmap)
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
	fun closeMenu() { menuPageState = HomeToolbarMenuPage.NONE }
	fun dispose() = view.disposeComposition()

	@Composable
	private fun ToolbarContent() {
		val visualSpec = homeLiquidToolbarVisualSpec()
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
			Row(
				modifier = Modifier.fillMaxWidth().padding(top = topInset + 8.dp, start = 12.dp, end = 12.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.Top,
			) {
				GlassSurface(
					modifier = Modifier.height(48.dp).weight(1f, fill = false),
					onClick = {
						if(showNewPostsState) onNewPosts.run() else menuPageState = HomeToolbarMenuPage.TIMELINES
					},
				) {
					Icon(
						modifier = Modifier.size(22.dp),
						painter = painterResource(if(showNewPostsState) R.drawable.ic_fluent_arrow_up_16_filled else currentTimeline?.iconRes ?: R.drawable.ic_fluent_home_24_regular),
						contentDescription = null,
						tint = contentColor,
					)
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
						Icon(painter = painterResource(R.drawable.ic_fluent_chevron_down_16_filled), contentDescription = null, modifier = Modifier.size(18.dp), tint = contentColor)
					}
				}
				Spacer(Modifier.width(10.dp))
				GlassSurface(modifier = Modifier.height(48.dp), onClick = {}) {
					ToolbarIcon(R.drawable.ic_fluent_edit_24_regular, view.context.getString(R.string.new_post), onCompose::run)
					Box {
						ToolbarIcon(R.drawable.ic_fluent_more_vertical_24_regular, view.context.getString(R.string.more_options)) {
							menuPageState = if(menuPageState==HomeToolbarMenuPage.ROOT) HomeToolbarMenuPage.NONE else HomeToolbarMenuPage.ROOT
						}
						if(remindersState.overflowBadged) Box(Modifier.align(Alignment.TopEnd).size(7.dp).background(Color.Red, CircleShape))
					}
				}
			}
			if(menuPageState!=HomeToolbarMenuPage.NONE) {
				GlassMenu(
					modifier = Modifier
						.align(if(menuPageState==HomeToolbarMenuPage.TIMELINES) Alignment.TopStart else Alignment.TopEnd)
						.padding(top = topInset + 64.dp, start = 12.dp, end = 12.dp),
					page = menuPageState,
					items = menuItems,
				)
			}
		}
	}

	@Composable
	private fun GlassMenu(modifier: Modifier, page: HomeToolbarMenuPage, items: List<HomeToolbarMenuItem>) {
		GlassSurface(modifier = modifier.width(248.dp), onClick = {}) {
			Column(
				Modifier
					.heightIn(max = 420.dp)
					.verticalScroll(rememberScrollState())
					.padding(vertical = 6.dp),
			) {
				if(page==HomeToolbarMenuPage.LISTS || page==HomeToolbarMenuPage.HASHTAGS) {
					MenuRow(R.drawable.ic_fluent_chevron_left_24_regular, view.context.getString(R.string.back), false) { menuPageState = HomeToolbarMenuPage.ROOT }
				}
				items.forEach { item ->
					val badged = (item.id==R.id.announcements && remindersState.announcementsBadged) || (item.id==R.id.settings && remindersState.settingsBadged)
					MenuRow(item.iconRes, item.title, badged) {
						when(item.id) {
							R.id.lists -> menuPageState = HomeToolbarMenuPage.LISTS
							R.id.hashtags -> menuPageState = HomeToolbarMenuPage.HASHTAGS
							else -> {
								closeMenu()
								if(page==HomeToolbarMenuPage.TIMELINES) onTimelineSelected.accept(item.id) else onMenuItem.accept(item.id)
							}
						}
					}
				}
			}
		}
	}

	@Composable
	private fun MenuRow(@DrawableRes icon: Int, title: String, badged: Boolean, onClick: () -> Unit) {
		Row(
			modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = MiuixTheme.colorScheme.onSurface)
			Spacer(Modifier.width(12.dp))
			Text(title, modifier = Modifier.weight(1f), fontSize = homeLiquidToolbarVisualSpec().menuTextSp.sp)
			if(badged) Box(Modifier.size(7.dp).background(Color.Red, CircleShape))
		}
	}

	@Composable
	private fun GlassSurface(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
		val isDark = isInDarkTheme()
		val surfaceAlpha = homeLiquidToolbarVisualSpec().surfaceAlpha
		val surface = if(isDark) Color.Black.copy(alpha = surfaceAlpha) else Color.White.copy(alpha = surfaceAlpha)
		Row(
			modifier = modifier
				.then(
					if(isRuntimeShaderSupported()) Modifier.drawBackdrop(
						backdrop = backdrop,
						shape = { RoundedCornerShape(24.dp) },
						effects = { vibrancy(); lens(12.dp.toPx(), 16.dp.toPx()) },
						onDrawSurface = { drawRect(surface) },
					) else Modifier.background(surface, RoundedCornerShape(24.dp)),
				)
				.clip(RoundedCornerShape(24.dp))
				.clickable(onClick = onClick)
				.padding(horizontal = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
			content = { content() },
		)
	}

	@Composable
	private fun ToolbarIcon(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
		Icon(
			modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick).padding(8.dp),
			painter = painterResource(icon),
			contentDescription = description,
			tint = MiuixTheme.colorScheme.onSurface,
		)
	}
}
