package org.joinmastodon.android.ui.compose.navigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import java.util.function.IntConsumer
import java.util.function.IntPredicate
import me.grishka.appkit.imageloader.ViewImageLoader
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest
import me.grishka.appkit.utils.V
import org.joinmastodon.android.R
import org.joinmastodon.android.ui.OutlineProviders
import org.joinmastodon.android.ui.views.ItshoverNavigationIconView
import org.joinmastodon.android.ui.compose.AppState
import org.joinmastodon.android.ui.compose.LocalAppState
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import org.joinmastodon.android.ui.compose.navigation.liquid.IosLiquidGlassNavigationBar
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

object HomeNavigationTabs {
	@JvmField
	val ids = intArrayOf(
		R.id.tab_home,
		R.id.tab_search,
		R.id.tab_diaper,
		R.id.tab_friend_request,
		R.id.tab_profile,
	)

	@JvmStatic
	fun indexOf(tabId: Int): Int = ids.indexOf(tabId).takeIf { it >= 0 } ?: 0
}

internal fun snapNavigationDragTarget(value: Float, tabsCount: Int): Int =
	value.roundToInt().coerceIn(0, tabsCount - 1)

class HomeLiquidNavigationController(
	context: Context,
	initialSelectedTab: Int,
	private val avatarUrl: String?,
	private val onTabClick: IntConsumer,
	private val onTabLongClick: IntPredicate,
) {
	private var selectedTabState by mutableIntStateOf(initialSelectedTab)
	private var unreadBadgeState by mutableStateOf<String?>(null)
	private var diaperBadgeVisibleState by mutableStateOf(false)
	private var bottomInsetState by mutableIntStateOf(0)
	private val backdrop = ViewBitmapBackdrop()

	val view = ComposeView(context).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
		setContent {
			CompositionLocalProvider(LocalAppState provides AppState()) {
				MiuixAppTheme {
					HomeLiquidNavigationContent()
				}
			}
		}
	}

	fun setSelectedTab(tabId: Int) {
		selectedTabState = tabId
	}

	fun setUnreadBadge(text: String?) {
		unreadBadgeState = text
	}

	fun setDiaperBadgeVisible(visible: Boolean) {
		diaperBadgeVisibleState = visible
	}

	fun setBottomInset(insetPx: Int) {
		bottomInsetState = insetPx.coerceAtLeast(0)
	}

	fun setBackdropBitmap(bitmap: Bitmap) {
		backdrop.update(bitmap)
	}

	fun dispose() {
		view.disposeComposition()
	}

	@Composable
	private fun HomeLiquidNavigationContent() {
		val selectedIndex = HomeNavigationTabs.indexOf(selectedTabState)
		val items = listOf(
			NavigationItem("首页", androidx.compose.ui.graphics.vector.ImageVector.vectorResource(R.drawable.ic_tab_rating)),
			NavigationItem(view.context.getString(R.string.search_hint), androidx.compose.ui.graphics.vector.ImageVector.vectorResource(R.drawable.ic_tab_rating)),
			NavigationItem(view.context.getString(R.string.diaper), androidx.compose.ui.graphics.vector.ImageVector.vectorResource(R.drawable.ic_tab_rating)),
			NavigationItem(view.context.getString(R.string.friend_request), androidx.compose.ui.graphics.vector.ImageVector.vectorResource(R.drawable.ic_tab_rating)),
			NavigationItem(view.context.getString(R.string.my_profile), androidx.compose.ui.graphics.vector.ImageVector.vectorResource(R.drawable.ic_tab_rating)),
		)
		val iconTypes = intArrayOf(
			ItshoverNavigationIconView.ICON_HOME,
			ItshoverNavigationIconView.ICON_MAGNIFIER,
			ItshoverNavigationIconView.ICON_STAR,
			ItshoverNavigationIconView.ICON_GLOBE,
		)
		val bottomPadding = with(LocalDensity.current) {
			if (bottomInsetState > 0) bottomInsetState.toDp() + 8.dp else 36.dp
		}

		Box(
			modifier = Modifier
				.fillMaxWidth()
				.onGloballyPositioned { backdrop.updateOriginInWindow(it.positionInWindow()) },
		) {
			IosLiquidGlassNavigationBar(
				items = items,
				selectedIndex = selectedIndex,
				onItemClick = { index -> onTabClick.accept(HomeNavigationTabs.ids[index]) },
				onItemLongClick = { index -> onTabLongClick.test(HomeNavigationTabs.ids[index]) },
				backdrop = backdrop,
				isBlurActive = isRuntimeShaderSupported(),
				bottomPaddingOverride = bottomPadding,
				badge = { index ->
					when {
						index == 2 && diaperBadgeVisibleState -> ({ Badge { Text("新功能") } })
						index == 4 && !unreadBadgeState.isNullOrEmpty() -> ({ Badge { Text(unreadBadgeState.orEmpty()) } })
						else -> null
					}
				},
				iconContent = { index, selected, animationToken ->
					if (index == 4) {
						AvatarIcon(avatarUrl, animationToken)
					} else {
						ItshoverIcon(iconTypes[index], animationToken)
					}
				},
			)
		}
	}
}

@Composable
private fun ItshoverIcon(iconType: Int, animationToken: Int) {
	val tint = LocalContentColor.current.toArgb()
	var iconView by remember { mutableStateOf<ItshoverNavigationIconView?>(null) }
	var consumedToken by remember { mutableIntStateOf(animationToken) }
	AndroidView(
		modifier = Modifier.size(22.dp),
		factory = { context ->
			ItshoverNavigationIconView(context).apply {
				this.iconType = iconType
				iconView = this
			}
		},
		update = { iconView ->
			iconView.setIconColor(tint)
		},
	)
	LaunchedEffect(animationToken, iconView) {
		if (animationToken > consumedToken) {
			consumedToken = animationToken
			iconView?.playAnimation()
		}
	}
}

@Composable
private fun AvatarIcon(avatarUrl: String?, animationToken: Int) {
	var imageView by remember { mutableStateOf<ImageView?>(null) }
	var consumedToken by remember { mutableIntStateOf(animationToken) }
	AndroidView(
		modifier = Modifier.size(22.dp),
		factory = { context ->
			ImageView(context).apply {
				imageView = this
				layoutParams = ViewGroup.LayoutParams(V.dp(22f), V.dp(22f))
				scaleType = ImageView.ScaleType.CENTER_CROP
				outlineProvider = OutlineProviders.OVAL
				clipToOutline = true
				setImageResource(R.drawable.image_placeholder)
				if (!avatarUrl.isNullOrEmpty()) {
					ViewImageLoader.loadWithoutAnimation(this, drawable, UrlImageLoaderRequest(avatarUrl, V.dp(24f), V.dp(24f)))
				}
			}
		},
	)
	LaunchedEffect(animationToken, imageView) {
		if (animationToken > consumedToken) {
			consumedToken = animationToken
			imageView?.let { avatar ->
				avatar.animate().cancel()
				avatar.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).withEndAction {
					avatar.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
				}.start()
			}
		}
	}
}
