package org.joinmastodon.android.ui.compose.navigation

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.joinmastodon.android.R
import org.joinmastodon.android.ui.compose.AppState
import org.joinmastodon.android.ui.compose.LocalAppState
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import org.joinmastodon.android.ui.compose.navigation.liquid.iosIndicatorSpecular
import org.joinmastodon.android.ui.compose.navigation.liquid.lens
import org.joinmastodon.android.ui.compose.navigation.liquid.rememberGravityRotatedHighlight
import org.joinmastodon.android.ui.compose.navigation.liquid.vibrancy
import org.joinmastodon.android.ui.compose.ui.isInDarkTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.function.Consumer

class FriendUniverseLiquidToolbarController(
	context: Context,
	private val onSearchChanged: Consumer<String>,
	private val onPublish: Runnable,
) {
	private val backdrop = ViewBitmapBackdrop()
	private var statusBarInsetState by mutableIntStateOf(0)
	private var scrollYState by mutableIntStateOf(0)
	private var searchExpandedState by mutableStateOf(false)
	private var searchTextState by mutableStateOf("")
	private var searchOpenListener: Consumer<Boolean>? = null

	private val composeView = ComposeView(context).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
		setContent {
			CompositionLocalProvider(LocalAppState provides AppState()) {
				MiuixAppTheme { ToolbarContent() }
			}
		}
	}

	val view: View = FrameLayout(context).apply {
		addView(composeView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
	}

	fun setBackdropBitmap(bitmap: Bitmap) = backdrop.update(bitmap)
	fun setStatusBarInset(insetPx: Int) { statusBarInsetState = insetPx.coerceAtLeast(0) }
	fun setScrollY(scrollY: Int) { scrollYState = scrollY.coerceAtLeast(0) }
	fun setSearchOpenListener(listener: Consumer<Boolean>?) { searchOpenListener = listener }
	fun isSearchExpanded(): Boolean = searchExpandedState
	fun setSearchQuery(query: String?) {
		searchTextState = query.orEmpty()
		if(searchTextState.isNotEmpty()) setSearchExpanded(true)
	}

	fun closeSearch(): Boolean {
		if(!searchExpandedState) return false
		searchExpandedState = false
		searchOpenListener?.accept(false)
		if(searchTextState.isNotEmpty()) {
			searchTextState = ""
			onSearchChanged.accept("")
		}
		return true
	}

	fun dispose() {
		searchOpenListener = null
		composeView.disposeComposition()
	}

	private fun setSearchExpanded(expanded: Boolean) {
		if(searchExpandedState==expanded) return
		searchExpandedState = expanded
		searchOpenListener?.accept(expanded)
	}

	@Composable
	private fun ToolbarContent() {
		val density = LocalDensity.current
		val topInset = with(density) { statusBarInsetState.toDp() }
		val progress = friendUniverseCollapseProgress(scrollYState, density.density)
		val titleSize = friendUniverseTitleSizeSp(progress)
		val titleGlassWidth = friendUniverseTitleGlassWidthDp(progress)
		val titleGlassHeight = friendUniverseTitleGlassHeightDp(progress)
		val titleOffset = 54f * (1f-progress)
		val focusRequester = remember { FocusRequester() }

		LaunchedEffect(searchExpandedState) {
			if(searchExpandedState) {
				delay(180)
				focusRequester.requestFocus()
			}
		}
		LaunchedEffect(searchTextState, searchExpandedState) {
			if(!searchExpandedState) return@LaunchedEffect
			delay(350)
			onSearchChanged.accept(searchTextState.trim())
		}

		BoxWithConstraints(
			Modifier
				.fillMaxSize()
				.onGloballyPositioned { backdrop.updateOriginInWindow(it.positionInWindow()) },
		) {
			val availableSearchWidth = (this.maxWidth-80.dp).coerceIn(160.dp, 244.dp)
			GlassSurface(
				modifier = Modifier
					.align(Alignment.TopStart)
					.padding(start = 12.dp, top = topInset + 8.dp)
					.width(titleGlassWidth.dp)
					.height(titleGlassHeight.dp)
					.graphicsLayer {
						translationY = with(density) { titleOffset.dp.toPx() }
						alpha = if(searchExpandedState) 0f else 1f
					},
				onClick = null,
				horizontalPaddingDp = 16,
			) {
				Text(
					text = "交友宇宙",
					fontSize = titleSize.sp,
					fontWeight = FontWeight.Bold,
					color = MiuixTheme.colorScheme.onSurface,
				)
			}

			Row(
				modifier = Modifier.align(Alignment.TopEnd).padding(top = topInset + 8.dp, end = 12.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				SearchGlass(focusRequester, availableSearchWidth)
				Spacer(Modifier.width(8.dp))
				GlassSurface(Modifier.size(48.dp), onPublish) {
					Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
						ResourceIcon(R.drawable.ic_fluent_add_24_regular, 24, MiuixTheme.colorScheme.onSurface)
					}
				}
			}
		}
	}

	@Composable
	private fun SearchGlass(focusRequester: FocusRequester, expandedWidth: androidx.compose.ui.unit.Dp) {
		val width = if(searchExpandedState) expandedWidth else 48.dp
		GlassSurface(Modifier.width(width).height(48.dp), Runnable { setSearchExpanded(true) }, if(searchExpandedState) 12 else 0) {
			if(searchExpandedState) {
				ResourceIcon(R.drawable.ic_fluent_search_24_regular, 22, MiuixTheme.colorScheme.onSurface)
				Spacer(Modifier.width(8.dp))
				BasicTextField(
					value = searchTextState,
					onValueChange = { searchTextState = it },
					modifier = Modifier.width(140.dp).focusRequester(focusRequester),
					textStyle = androidx.compose.ui.text.TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 16.sp),
					cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
					singleLine = true,
					decorationBox = { input ->
						Box(contentAlignment = Alignment.CenterStart) {
							if(searchTextState.isEmpty()) Text("搜索交友请求", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f), fontSize = 16.sp)
							input()
						}
					},
				)
				Box(
					Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).clickable { closeSearch() },
					contentAlignment = Alignment.Center,
				) {
					ResourceIcon(R.drawable.ic_fluent_dismiss_24_regular, 20, MiuixTheme.colorScheme.onSurface)
				}
			} else {
				Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					ResourceIcon(R.drawable.ic_fluent_search_24_regular, 24, MiuixTheme.colorScheme.onSurface)
				}
			}
		}
	}

	@Composable
	private fun GlassSurface(modifier: Modifier, onClick: Runnable?, horizontalPaddingDp: Int = 0, content: @Composable () -> Unit) {
		val interactionSource = remember { MutableInteractionSource() }
		val pressed by interactionSource.collectIsPressedAsState()
		val scale by animateFloatAsState(if(pressed) 0.96f else 1f, spring(0.72f, 520f), label = "friendGlassScale")
		val highlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = -45f)
		val surface = if(isInDarkTheme()) Color.Black.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.22f)
		Row(
			modifier = modifier
				.graphicsLayer { scaleX = scale; scaleY = scale }
				.then(
					if(isRuntimeShaderSupported()) Modifier.drawBackdrop(
						backdrop = backdrop,
						shape = { RoundedCornerShape(24.dp) },
						effects = { vibrancy(); blur(18.dp.toPx(), 18.dp.toPx()); lens(12.dp.toPx(), 16.dp.toPx()) },
						highlight = { highlight.value.copy(alpha = 0.75f) },
						onDrawSurface = { drawRect(surface) },
					) else Modifier.background(surface, RoundedCornerShape(24.dp)),
				)
				.clip(RoundedCornerShape(24.dp))
				.then(if(onClick!=null) Modifier.clickable(interactionSource = interactionSource, indication = null) { onClick.run() } else Modifier)
				.padding(horizontal = horizontalPaddingDp.dp),
			verticalAlignment = Alignment.CenterVertically,
			content = { content() },
		)
	}

	@Composable
	private fun ResourceIcon(@DrawableRes icon: Int, sizeDp: Int, tint: Color) {
		AndroidView(
			modifier = Modifier.size(sizeDp.dp),
			factory = { imageContext -> ImageView(imageContext).apply {
				scaleType = ImageView.ScaleType.CENTER_INSIDE
				isClickable = false
				isFocusable = false
				isEnabled = false
			} },
			update = { imageView ->
				imageView.setImageResource(icon)
				imageView.imageTintList = ColorStateList.valueOf(tint.toArgb())
			},
		)
	}
}
