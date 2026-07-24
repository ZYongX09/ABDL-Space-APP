package org.joinmastodon.android.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import org.joinmastodon.android.R
import org.joinmastodon.android.ui.compose.ui.LocalColorMode
import org.joinmastodon.android.ui.utils.UiUtils
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
fun MiuixAppTheme(content: @Composable () -> Unit) {
	val context = LocalContext.current
	val darkTheme = UiUtils.isDarkTheme()
	val colorMode = if (darkTheme) 2 else 1
	val primary = Color(UiUtils.getThemeColor(context, R.attr.colorM3Primary))
	val onPrimary = Color(UiUtils.getThemeColor(context, R.attr.colorM3OnPrimary))
	val primaryContainer = Color(UiUtils.getThemeColor(context, R.attr.colorM3PrimaryContainer))
	val onPrimaryContainer = Color(UiUtils.getThemeColor(context, R.attr.colorM3OnPrimaryContainer))
	val secondary = Color(UiUtils.getThemeColor(context, R.attr.colorM3Secondary))
	val onSecondary = Color(UiUtils.getThemeColor(context, R.attr.colorM3OnSecondary))
	val secondaryContainer = Color(UiUtils.getThemeColor(context, R.attr.colorM3SecondaryContainer))
	val onSecondaryContainer = Color(UiUtils.getThemeColor(context, R.attr.colorM3OnSecondaryContainer))
	val tertiaryContainer = Color(UiUtils.getThemeColor(context, R.attr.colorM3TertiaryContainer))
	val onTertiaryContainer = Color(UiUtils.getThemeColor(context, R.attr.colorM3OnTertiaryContainer))
	val background = Color(UiUtils.getThemeColor(context, R.attr.colorM3Background))
	val onBackground = Color(UiUtils.getThemeColor(context, R.attr.colorM3OnBackground))
	val surface = Color(UiUtils.getThemeColor(context, R.attr.colorM3Surface))
	val onSurface = Color(UiUtils.getThemeColor(context, R.attr.colorM3OnSurface))
	val surfaceVariant = Color(UiUtils.getThemeColor(context, R.attr.colorM3SurfaceVariant))
	val onSurfaceVariant = Color(UiUtils.getThemeColor(context, R.attr.colorM3OnSurfaceVariant))
	val outline = Color(UiUtils.getThemeColor(context, R.attr.colorM3Outline))
	val outlineVariant = Color(UiUtils.getThemeColor(context, R.attr.colorM3OutlineVariant))
	val error = Color(UiUtils.getThemeColor(context, R.attr.colorM3Error))
	val onError = Color(UiUtils.getThemeColor(context, R.attr.colorM3OnError))
	val errorContainer = Color(UiUtils.getThemeColor(context, R.attr.colorM3ErrorContainer))
	val onErrorContainer = Color(UiUtils.getThemeColor(context, R.attr.colorM3OnErrorContainer))
	val colors = if (darkTheme) {
		darkColorScheme(
			primary = primary,
			onPrimary = onPrimary,
			primaryVariant = primaryContainer,
			onPrimaryVariant = onPrimaryContainer,
			error = error,
			onError = onError,
			errorContainer = errorContainer,
			onErrorContainer = onErrorContainer,
			primaryContainer = primaryContainer,
			onPrimaryContainer = onPrimaryContainer,
			secondary = secondary,
			onSecondary = onSecondary,
			secondaryVariant = secondaryContainer,
			onSecondaryVariant = onSecondaryContainer,
			secondaryContainer = secondaryContainer,
			onSecondaryContainer = onSecondaryContainer,
			tertiaryContainer = tertiaryContainer,
			onTertiaryContainer = onTertiaryContainer,
			background = background,
			onBackground = onBackground,
			onBackgroundVariant = onSurfaceVariant,
			surface = surface,
			onSurface = onSurface,
			surfaceVariant = surfaceVariant,
			onSurfaceSecondary = onSurfaceVariant,
			onSurfaceVariantSummary = onSurfaceVariant,
			onSurfaceVariantActions = onSurfaceVariant,
			surfaceContainer = surface,
			onSurfaceContainer = onSurface,
			onSurfaceContainerVariant = onSurfaceVariant,
			surfaceContainerHigh = surfaceVariant,
			onSurfaceContainerHigh = onSurfaceVariant,
			surfaceContainerHighest = surfaceVariant,
			onSurfaceContainerHighest = onSurface,
			outline = outline,
			dividerLine = outlineVariant,
		)
	} else {
		lightColorScheme(
			primary = primary,
			onPrimary = onPrimary,
			primaryVariant = primaryContainer,
			onPrimaryVariant = onPrimaryContainer,
			error = error,
			onError = onError,
			errorContainer = errorContainer,
			onErrorContainer = onErrorContainer,
			primaryContainer = primaryContainer,
			onPrimaryContainer = onPrimaryContainer,
			secondary = secondary,
			onSecondary = onSecondary,
			secondaryVariant = secondaryContainer,
			onSecondaryVariant = onSecondaryContainer,
			secondaryContainer = secondaryContainer,
			onSecondaryContainer = onSecondaryContainer,
			tertiaryContainer = tertiaryContainer,
			onTertiaryContainer = onTertiaryContainer,
			background = background,
			onBackground = onBackground,
			onBackgroundVariant = onSurfaceVariant,
			surface = surface,
			onSurface = onSurface,
			surfaceVariant = surfaceVariant,
			onSurfaceSecondary = onSurfaceVariant,
			onSurfaceVariantSummary = onSurfaceVariant,
			onSurfaceVariantActions = onSurfaceVariant,
			surfaceContainer = surface,
			onSurfaceContainer = onSurface,
			onSurfaceContainerVariant = onSurfaceVariant,
			surfaceContainerHigh = surfaceVariant,
			onSurfaceContainerHigh = onSurfaceVariant,
			surfaceContainerHighest = surfaceVariant,
			onSurfaceContainerHighest = onSurface,
			outline = outline,
			dividerLine = outlineVariant,
		)
	}
	val controller = remember(darkTheme, colors) {
		ThemeController(
			colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light,
			lightColors = colors,
			darkColors = colors,
		)
	}
	CompositionLocalProvider(LocalColorMode provides colorMode) {
		MiuixTheme(controller = controller, content = content)
	}
}
