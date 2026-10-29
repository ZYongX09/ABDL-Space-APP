package org.joinmastodon.android.novel.app.design

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import org.joinmastodon.android.GlobalUserPreferences
import org.joinmastodon.android.R
import org.joinmastodon.android.api.session.AccountLocalPreferences.ColorPreference
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.ui.utils.ColorPalette
import org.joinmastodon.android.ui.utils.UiUtils

/** Stable, semantic tokens shared by the store and editor. Timeline cards deliberately do not use this theme. */
@Immutable
data class NovelColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val text: Color,
    val textSecondary: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val outline: Color,
    val error: Color,
    val success: Color,
)

private val BlueLight = NovelColors(
    background = Color(0xFFFAFBFD), surface = Color.White, surfaceVariant = Color(0xFFE2EBF7),
    text = Color(0xFF263548), textSecondary = Color(0xFF758091), primary = Color(0xFF38669B),
    onPrimary = Color.White, primaryContainer = Color(0xFFE2EBF7), onPrimaryContainer = Color(0xFF263548),
    outline = Color(0xFFDEE4ED), error = Color(0xFFB3261E), success = Color(0xFF397347),
)

val LocalNovelColors = staticCompositionLocalOf { BlueLight }

object NovelTheme {
    val colors: NovelColors
        @Composable get() = LocalNovelColors.current
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object NovelTypography {
    /** Bundled OFL Noto Serif SC, not a device serif alias or network font provider. */
    val serif = FontFamily(
        Font(R.font.noto_serif_sc_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.noto_serif_sc_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
        Font(R.font.noto_serif_sc_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )
}

@Composable
fun NovelTheme(accountId: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val dark = when (GlobalUserPreferences.theme) {
        GlobalUserPreferences.ThemePreference.LIGHT -> false
        GlobalUserPreferences.ThemePreference.DARK -> true
        else -> configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    val account = AccountSessionManager.getInstance().tryGetAccount(accountId)
    val palette = NovelPalettePolicy.resolve(
        account?.localPreferences?.color, GlobalUserPreferences.color,
        GlobalUserPreferences.colorExplicitlyChosen, dark,
    )
    // Apply the account palette to an isolated context; never force BLUE on the host timeline or mutate prefs.
    val themed = ContextThemeWrapper(context, if (dark) R.style.Theme_Mastodon_Dark else R.style.Theme_Mastodon_Light)
    ColorPalette.palettes[palette]?.apply(themed, if (dark) GlobalUserPreferences.ThemePreference.DARK else GlobalUserPreferences.ThemePreference.LIGHT)
    fun token(attr: Int) = Color(UiUtils.getThemeColor(themed, attr))
    val colors = if (!dark && palette == ColorPreference.BLUE) BlueLight else NovelColors(
        background = token(R.attr.colorM3Background), surface = token(R.attr.colorM3Surface),
        surfaceVariant = token(R.attr.colorM3SurfaceVariant), text = token(R.attr.colorM3OnSurface),
        textSecondary = token(R.attr.colorM3OnSurfaceVariant), primary = token(R.attr.colorM3Primary),
        onPrimary = token(R.attr.colorM3OnPrimary), primaryContainer = token(R.attr.colorM3PrimaryContainer),
        onPrimaryContainer = token(R.attr.colorM3OnPrimaryContainer), outline = token(R.attr.colorM3OutlineVariant),
        error = token(R.attr.colorM3Error), success = token(R.attr.colorM3Success),
    )
    val scheme = if (dark) darkColorScheme(
        primary = colors.primary, onPrimary = colors.onPrimary,
        primaryContainer = colors.primaryContainer, onPrimaryContainer = colors.onPrimaryContainer,
        secondary = colors.primary, onSecondary = colors.onPrimary,
        secondaryContainer = colors.primaryContainer, onSecondaryContainer = colors.onPrimaryContainer,
        tertiary = colors.primary, onTertiary = colors.onPrimary,
        tertiaryContainer = colors.primaryContainer, onTertiaryContainer = colors.onPrimaryContainer,
        surfaceContainerLowest = colors.background, surfaceContainerLow = colors.surface,
        surfaceContainer = colors.surface, surfaceContainerHigh = colors.surfaceVariant,
        surfaceContainerHighest = colors.surfaceVariant, surfaceTint = colors.primary,
        background = colors.background, onBackground = colors.text, surface = colors.surface, onSurface = colors.text,
        surfaceVariant = colors.surfaceVariant, onSurfaceVariant = colors.textSecondary, outline = colors.outline, error = colors.error,
    ) else lightColorScheme(
        primary = colors.primary, onPrimary = colors.onPrimary,
        primaryContainer = colors.primaryContainer, onPrimaryContainer = colors.onPrimaryContainer,
        secondary = colors.primary, onSecondary = colors.onPrimary,
        secondaryContainer = colors.primaryContainer, onSecondaryContainer = colors.onPrimaryContainer,
        tertiary = colors.primary, onTertiary = colors.onPrimary,
        tertiaryContainer = colors.primaryContainer, onTertiaryContainer = colors.onPrimaryContainer,
        surfaceContainerLowest = colors.background, surfaceContainerLow = colors.surface,
        surfaceContainer = colors.surface, surfaceContainerHigh = colors.surfaceVariant,
        surfaceContainerHighest = colors.surfaceVariant, surfaceTint = colors.primary,
        background = colors.background, onBackground = colors.text, surface = colors.surface, onSurface = colors.text,
        surfaceVariant = colors.surfaceVariant, onSurfaceVariant = colors.textSecondary, outline = colors.outline, error = colors.error,
    )
    CompositionLocalProvider(LocalNovelColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
