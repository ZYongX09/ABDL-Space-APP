// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package org.joinmastodon.android.ui.compose.utils

import org.joinmastodon.android.ui.compose.LocalAppState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.textureBlurEffect
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

fun Modifier.pageScrollModifiers(
    enableScrollEndHaptic: Boolean,
    showTopAppBar: Boolean,
    topAppBarScrollBehavior: ScrollBehavior,
): Modifier = this
    .then(if (enableScrollEndHaptic) Modifier.scrollEndHaptic() else Modifier)
    .overScrollVertical()
    .then(if (showTopAppBar) Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection) else Modifier)
    .fillMaxHeight()

@Composable
fun pageContentPadding(
    innerPadding: PaddingValues,
    outerPadding: PaddingValues,
    isWideScreen: Boolean,
    extraTop: Dp = 0.dp,
    extraStart: Dp = 0.dp,
    extraEnd: Dp = 0.dp,
    extraBottom: Dp = 0.dp,
): PaddingValues {
    val topPadding = innerPadding.calculateTopPadding() + extraTop
    val bottomPadding = outerPadding.calculateBottomPadding() + extraBottom + if (isWideScreen) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + WindowInsets.captionBar.asPaddingValues()
            .calculateBottomPadding()
    } else {
        0.dp
    }

    return remember(topPadding, bottomPadding, extraStart, extraEnd, extraBottom) {
        PaddingValues(
            top = topPadding,
            start = extraStart,
            end = extraEnd,
            bottom = bottomPadding,
        )
    }
}

@Composable
fun AdaptiveTopAppBar(
    title: String,
    showTopAppBar: Boolean,
    isWideScreen: Boolean,
    scrollBehavior: ScrollBehavior,
    subtitle: String = "",
    color: Color = MiuixTheme.colorScheme.surface,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
) {
    if (showTopAppBar) {
        if (isWideScreen) {
            SmallTopAppBar(
                title = title,
                subtitle = subtitle,
                color = color,
                scrollBehavior = scrollBehavior,
                defaultWindowInsetsPadding = false,
                navigationIcon = navigationIcon,
                actions = actions,
                bottomContent = bottomContent,
            )
        } else {
            TopAppBar(
                title = title,
                subtitle = subtitle,
                color = color,
                scrollBehavior = scrollBehavior,
                navigationIcon = navigationIcon,
                actions = actions,
                bottomContent = bottomContent,
            )
        }
    }
}

@Composable
fun rememberBlurBackdrop(): LayerBackdrop? {
    val appState = LocalAppState.current
    val supported = rememberGraphicsEffectsSupported(PageBlurGraphicsSafety)
    if (!appState.enableBlur || !supported) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

/** Theme values and application content stay outside the guarded, empty texture child. */
@Composable
internal fun PageTextureBlur(
    backdrop: LayerBackdrop?,
    shape: Shape,
    colors: BlurColors,
    modifier: Modifier = Modifier,
    blurRadius: Float,
    noiseCoefficient: Float = BlurDefaults.NoiseCoefficient,
    contentBlendMode: BlendMode = BlendMode.SrcOver,
    fallbackColor: Color = MiuixTheme.colorScheme.surfaceContainer,
    content: @Composable () -> Unit,
) {
    val supported = rememberGraphicsEffectsSupported(PageBlurGraphicsSafety)
    val blurActive = supported && backdrop != null
    if (!blurActive || backdrop == null) {
        Box(modifier.background(fallbackColor, shape)) { content() }
        return
    }
    val maskPaint = remember(contentBlendMode) { Paint().apply { blendMode = contentBlendMode } }
    Box(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        Spacer(Modifier.matchParentSize().safeBackdropEffect(
            operation = "page texture blur",
            fallback = { drawOutline(shape.createOutline(size, layoutDirection, this), fallbackColor) },
            safety = PageBlurGraphicsSafety,
        ) { enabled ->
            // miuix 0.9.3 textureBlur exposes enabled and produces one drawBackdrop node.
            Modifier.textureBlur(
                enabled = enabled,
                backdrop = backdrop,
                shape = shape,
                blurRadius = blurRadius,
                noiseCoefficient = noiseCoefficient,
                colors = colors,
                // Application content is drawn separately, never within safeBackdropEffect.
                contentBlendMode = BlendMode.SrcOver,
            )
        })
        Box(Modifier.drawWithContent {
            if (contentBlendMode == BlendMode.SrcOver || !PageBlurGraphicsSafety.isSupported()) {
                drawContent()
            } else {
                // Preserve DstIn (and other content blending) outside the graphics catch.
                val canvas = drawContext.canvas
                canvas.saveLayer(Rect(0f, 0f, size.width, size.height), maskPaint)
                try {
                    drawContent()
                } finally {
                    canvas.restore()
                }
            }
        }) { content() }
    }
}

@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurEnabled: Boolean,
    scrollBehavior: ScrollBehavior? = null,
    content: @Composable () -> Unit,
) {
    val supported = rememberGraphicsEffectsSupported(PageBlurGraphicsSafety)
    val blurActive = supported && blurEnabled && backdrop != null
    val surface = MiuixTheme.colorScheme.surface
    // Material/theme reads are composable; calculate them before the guarded factory.
    val colors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(color = surface.copy(0.8f))),
    )
    Box {
        Box(Modifier.matchParentSize().then(
            if (blurActive && backdrop != null) Modifier.safeBackdropEffect(
                operation = "page bar texture blur",
                fallback = { drawRect(surface) },
                safety = PageBlurGraphicsSafety,
            ) { enabled ->
                Modifier.drawBackdrop(
                    enabled = enabled,
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = {
                        PageBlurGraphicsSafety.guarded("page texture effects callback", fallback = { renderEffect = null }) {
                            textureBlurEffect(25f, 25f, BlurDefaults.NoiseCoefficient, colors)
                        }
                    },
                )
            } else Modifier,
        ))
        content()
    }
}
