@file:OptIn(ExperimentalScrollBarApi::class)

package org.joinmastodon.android.ui.compose

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.BuildConfig
import org.joinmastodon.android.R
import org.joinmastodon.android.ui.compose.component.BackNavigationIcon
import org.joinmastodon.android.ui.compose.component.blend.ColorBlendToken
import org.joinmastodon.android.ui.compose.component.effect.BgEffectBackground
import org.joinmastodon.android.ui.compose.ui.isInDarkTheme
import org.joinmastodon.android.ui.compose.utils.BlurredBar
import org.joinmastodon.android.ui.compose.utils.pageContentPadding
import org.joinmastodon.android.ui.compose.utils.pageScrollModifiers
import org.joinmastodon.android.ui.compose.utils.rememberBlurBackdrop
import org.joinmastodon.android.ui.compose.utils.shouldShowSplitPane
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode

@Composable
fun AboutPage(
	padding: PaddingValues = PaddingValues(0.dp),
	onOpenSourceLicenses: () -> Unit = {},
) {
	val activity = LocalContext.current as? Activity
	val topAppBarScrollBehavior = MiuixScrollBehavior()
	val lazyListState = rememberLazyListState()
	val scrollProgress by remember {
		derivedStateOf {
			if (lazyListState.firstVisibleItemIndex > 0) {
				1f
			} else {
				val spacer = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "logoSpacer" }
				if (spacer != null && spacer.size > 0) {
					(lazyListState.firstVisibleItemScrollOffset.toFloat() / spacer.size).coerceIn(0f, 1f)
				} else {
					0f
				}
			}
		}
	}
	val backdrop = rememberBlurBackdrop()
	val collapsed by remember { derivedStateOf { scrollProgress == 1f } }
	val blurActive by remember(backdrop) { derivedStateOf { backdrop != null && collapsed } }

	Scaffold(
		topBar = {
			val barColor = if (blurActive) Color.Transparent else if (collapsed) MiuixTheme.colorScheme.surface else Color.Transparent
			val titleColor = MiuixTheme.colorScheme.onSurface.copy(
				alpha = ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f),
			)
			BlurredBar(backdrop, blurActive, topAppBarScrollBehavior) {
				SmallTopAppBar(
					title = "关于",
					scrollBehavior = topAppBarScrollBehavior,
					color = barColor,
					titleColor = titleColor,
					navigationIcon = { BackNavigationIcon(onClick = { activity?.onBackPressed() }) },
				)
			}
		},
	) { innerPadding ->
		Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
			AboutContent(
				padding = PaddingValues(
					top = innerPadding.calculateTopPadding(),
					bottom = padding.calculateBottomPadding(),
				),
				topAppBarScrollBehavior = topAppBarScrollBehavior,
				lazyListState = lazyListState,
				scrollProgressProvider = { scrollProgress },
				onOpenSourceLicenses = onOpenSourceLicenses,
			)
		}
	}
}

@Composable
private fun AboutContent(
	padding: PaddingValues,
	topAppBarScrollBehavior: ScrollBehavior,
	lazyListState: LazyListState,
	scrollProgressProvider: () -> Float,
	onOpenSourceLicenses: () -> Unit,
) {
	val appState = LocalAppState.current
	val isWideScreen = shouldShowSplitPane()
	val uriHandler = LocalUriHandler.current
	val backdrop = rememberBlurBackdrop()
	var blurRadius by remember { mutableFloatStateOf(60f) }
	var noiseCoefficient by remember { mutableFloatStateOf(BlurDefaults.NoiseCoefficient) }
	var brightness by remember { mutableFloatStateOf(0f) }
	var contrast by remember { mutableFloatStateOf(1f) }
	var saturation by remember { mutableFloatStateOf(1f) }
	val scrollPadding = pageContentPadding(
		padding,
		padding,
		isWideScreen,
		extraStart = WindowInsets.displayCutout.asPaddingValues().calculateLeftPadding(LayoutDirection.Ltr),
		extraEnd = WindowInsets.displayCutout.asPaddingValues().calculateRightPadding(LayoutDirection.Ltr),
	)
	val logoPadding = pageContentPadding(
		padding,
		padding,
		isWideScreen,
		extraTop = 40.dp,
		extraStart = WindowInsets.displayCutout.asPaddingValues().calculateLeftPadding(LayoutDirection.Ltr),
		extraEnd = WindowInsets.displayCutout.asPaddingValues().calculateRightPadding(LayoutDirection.Ltr),
	)

	val isInDark = isInDarkTheme()
	val dynamicBackground = remember { mutableStateOf(isRuntimeShaderSupported()) }
	val backgroundVisible by remember { derivedStateOf { scrollProgressProvider() < 1f } }
	val cardBlend = if (isInDark) ColorBlendToken.Overlay_Thin_Light else ColorBlendToken.Pured_Regular_Light
	val logoBlend = remember(isInDark) {
		if (isInDark) {
			listOf(
				BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
				BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
				BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
			)
		} else {
			listOf(
				BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
				BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
				BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
			)
		}
	}
	val density = LocalDensity.current
	var logoHeightDp by remember { mutableStateOf(300.dp) }

	BgEffectBackground(
		dynamicBackground = dynamicBackground.value,
		isOs3Effect = true,
		isFullSize = false,
		modifier = Modifier.fillMaxSize(),
		bgModifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier,
		alpha = { 1f - scrollProgressProvider() },
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(
					top = logoPadding.calculateTopPadding() + 52.dp,
					start = logoPadding.calculateLeftPadding(LayoutDirection.Ltr),
					end = logoPadding.calculateRightPadding(LayoutDirection.Ltr),
				)
				.onSizeChanged { size -> with(density) { logoHeightDp = size.height.toDp() } },
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Box(
				contentAlignment = Alignment.Center,
				modifier = Modifier
					.size(88.dp)
					.graphicsLayer {
						val progress = ((scrollProgressProvider() - 0.35f) / 0.15f).coerceIn(0f, 1f)
						clip = true
						shape = RoundedCornerShape(24.dp)
						alpha = 1 - progress
						scaleX = 1 - progress * 0.05f
						scaleY = 1 - progress * 0.05f
					}
					.background(Color.White),
			) {
				Image(
					modifier = Modifier.size(74.dp),
					painter = painterResource(R.drawable.ic_ntf_logo),
					contentDescription = null,
				)
			}
			Text(
				modifier = Modifier
					.padding(top = 12.dp, bottom = 5.dp)
					.graphicsLayer {
						val progress = ((scrollProgressProvider() - 0.20f) / 0.15f).coerceIn(0f, 1f)
						alpha = 1 - progress
						scaleX = 1 - progress * 0.05f
						scaleY = 1 - progress * 0.05f
					}
					.then(
						if (backdrop != null) {
							Modifier.textureBlur(
								backdrop = backdrop,
								shape = RoundedCornerShape(16.dp),
								blurRadius = 150f,
								noiseCoefficient = noiseCoefficient,
								colors = BlurDefaults.blurColors(blendColors = logoBlend),
								contentBlendMode = ComposeBlendMode.DstIn,
							)
						} else {
							Modifier
						},
					),
				text = "ABDL Space",
				color = MiuixTheme.colorScheme.onBackground,
				fontWeight = FontWeight.Bold,
				fontSize = 35.sp,
			)
			Text(
				modifier = Modifier
					.fillMaxWidth()
					.graphicsLayer {
						val progress = ((scrollProgressProvider() - 0.05f) / 0.15f).coerceIn(0f, 1f)
						alpha = 1 - progress
						scaleX = 1 - progress * 0.05f
						scaleY = 1 - progress * 0.05f
					},
				color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
				text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
				fontSize = 14.sp,
				textAlign = TextAlign.Center,
			)
		}

		LazyColumn(
			state = lazyListState,
			modifier = Modifier
				.fillMaxSize()
				.pageScrollModifiers(
					appState.enableScrollEndHaptic,
					appState.showTopAppBar,
					topAppBarScrollBehavior,
				),
			contentPadding = PaddingValues(
				top = scrollPadding.calculateTopPadding(),
				start = scrollPadding.calculateLeftPadding(LayoutDirection.Ltr),
				end = scrollPadding.calculateRightPadding(LayoutDirection.Ltr),
			),
		) {
			item(key = "logoSpacer") {
				Box(
					Modifier
						.fillMaxWidth()
						.height(
							logoHeightDp + 52.dp + logoPadding.calculateTopPadding() -
								scrollPadding.calculateTopPadding() + 126.dp,
						),
					contentAlignment = Alignment.TopCenter,
					content = {},
				)
			}
			item(key = "about") {
				Box {
					Spacer(Modifier.fillParentMaxHeight())
					Column(modifier = Modifier.padding(bottom = scrollPadding.calculateBottomPadding())) {
						AboutCard(
							backdropAvailable = backdrop != null,
							modifier = Modifier.padding(horizontal = 12.dp),
							blurModifier = if (backdrop != null) {
								Modifier.textureBlur(
									backdrop = backdrop,
									shape = RoundedCornerShape(16.dp),
									blurRadius = blurRadius,
									noiseCoefficient = noiseCoefficient,
									colors = BlurDefaults.blurColors(
										blendColors = cardBlend,
										brightness = brightness,
										contrast = contrast,
										saturation = saturation,
									),
								)
							} else Modifier,
						) {
							ArrowPreference(title = "官方网站", endActions = { ValueText("abdl-space.top") }, onClick = { uriHandler.openUri("https://abdl-space.top") })
							ArrowPreference(title = "GitHub", endActions = { ValueText("Source") }, onClick = { uriHandler.openUri("https://github.com/ZYongX09/ABDL-Space-APP") })
							ArrowPreference(title = "博客", onClick = { uriHandler.openUri("https://zhx-blog.top") })
						}
						AboutCard(
							backdropAvailable = backdrop != null,
							modifier = Modifier.padding(horizontal = 12.dp).padding(top = 12.dp),
							blurModifier = if (backdrop != null) Modifier.textureBlur(
								backdrop = backdrop,
								shape = RoundedCornerShape(16.dp),
								blurRadius = blurRadius,
								noiseCoefficient = noiseCoefficient,
								colors = BlurDefaults.blurColors(
									blendColors = cardBlend,
									brightness = brightness,
									contrast = contrast,
									saturation = saturation,
								),
							) else Modifier,
						) {
							ArrowPreference(title = "用户协议", onClick = { uriHandler.openUri("https://abdl-space.top/terms") })
							ArrowPreference(title = "隐私政策", onClick = { uriHandler.openUri("https://abdl-space.top/privacy") })
							ArrowPreference(title = "Cookie 政策", onClick = { uriHandler.openUri("https://abdl-space.top/cookies") })
						}
						AboutCard(
							backdropAvailable = backdrop != null,
							modifier = Modifier.padding(horizontal = 12.dp).padding(top = 12.dp),
							blurModifier = if (backdrop != null) Modifier.textureBlur(
								backdrop = backdrop,
								shape = RoundedCornerShape(16.dp),
								blurRadius = blurRadius,
								noiseCoefficient = noiseCoefficient,
								colors = BlurDefaults.blurColors(
									blendColors = cardBlend,
									brightness = brightness,
									contrast = contrast,
									saturation = saturation,
								),
							) else Modifier,
						) {
							ArrowPreference(title = "支持我们", onClick = { uriHandler.openUri("https://ifdian.net/a/ZYongX") })
							ArrowPreference(title = "开源许可", endActions = { ValueText("GPL-3.0") }, onClick = { uriHandler.openUri("https://www.gnu.org/licenses/gpl-3.0.html") })
							ArrowPreference(
								title = "开放源代码许可",
								onClick = onOpenSourceLicenses,
							)
						}
						Spacer(modifier = Modifier.height(12.dp))
					}
				}
			}
		}
		VerticalScrollBar(
			adapter = rememberScrollBarAdapter(lazyListState),
			modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
			trackPadding = scrollPadding,
		)
	}
}

@Composable
private fun AboutCard(
	backdropAvailable: Boolean,
	modifier: Modifier,
	blurModifier: Modifier,
	content: @Composable ColumnScope.() -> Unit,
) {
	Card(
		modifier = modifier.then(blurModifier),
		colors = CardDefaults.defaultColors(
			if (backdropAvailable) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
			Color.Transparent,
		),
		content = content,
	)
}

@Composable
private fun ValueText(text: String) {
	Text(
		text = text,
		fontSize = MiuixTheme.textStyles.body2.fontSize,
		color = MiuixTheme.colorScheme.onSurfaceVariantActions,
	)
}
