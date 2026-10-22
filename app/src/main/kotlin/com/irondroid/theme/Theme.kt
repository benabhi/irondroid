package com.irondroid.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.irondroid.R

/** Which theme the player picked (RF-UI-04): light paper and ink unless they choose otherwise. */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

/** Whether [mode] shows the dark theme right now. */
@Composable
fun ThemeMode.isDark() = when (this) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/**
 * The app theme (RF-UI-01, RF-UI-03, RF-UI-14): Material 3 with every color role replaced by white, greys
 * and black, Cormorant Garamond for titles and Inter for the narration and the controls.
 */
@Composable
fun IrondroidTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) InkDark else PaperLight,
        typography = IrondroidTypography,
        content = content,
    )
}

/** Paper and ink: the default theme. Errors use ink too, since no color marks them (RF-UI-01). */
private val PaperLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF1A1A1A),
    onPrimary = Color(0xFFFAFAFA),
    primaryContainer = Color(0xFFE0E0E0),
    onPrimaryContainer = Color(0xFF1A1A1A),
    inversePrimary = Color(0xFFD6D6D6),
    secondary = Color(0xFF4A4A4A),
    onSecondary = Color(0xFFFAFAFA),
    secondaryContainer = Color(0xFFEBEBEB),
    onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = Color(0xFF4A4A4A),
    onTertiary = Color(0xFFFAFAFA),
    tertiaryContainer = Color(0xFFEBEBEB),
    onTertiaryContainer = Color(0xFF1A1A1A),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEEEEEE),
    onSurfaceVariant = Color(0xFF5C5C5C),
    surfaceTint = Color(0xFF1A1A1A),
    inverseSurface = Color(0xFF2E2E2E),
    inverseOnSurface = Color(0xFFF2F2F2),
    error = Color(0xFF1A1A1A),
    onError = Color(0xFFFAFAFA),
    errorContainer = Color(0xFFE0E0E0),
    onErrorContainer = Color(0xFF1A1A1A),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFFCFCFCF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFAFAFA),
    surfaceDim = Color(0xFFDEDEDE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFEBEBEB),
    surfaceContainerHighest = Color(0xFFE5E5E5),
)

/** Inverted ink: the optional dark theme, just as colorless. */
private val InkDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFE6E6E6),
    onPrimary = Color(0xFF121212),
    primaryContainer = Color(0xFF3A3A3A),
    onPrimaryContainer = Color(0xFFE6E6E6),
    inversePrimary = Color(0xFF2E2E2E),
    secondary = Color(0xFFBDBDBD),
    onSecondary = Color(0xFF121212),
    secondaryContainer = Color(0xFF2C2C2C),
    onSecondaryContainer = Color(0xFFE6E6E6),
    tertiary = Color(0xFFBDBDBD),
    onTertiary = Color(0xFF121212),
    tertiaryContainer = Color(0xFF2C2C2C),
    onTertiaryContainer = Color(0xFFE6E6E6),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFA8A8A8),
    surfaceTint = Color(0xFFE6E6E6),
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF1E1E1E),
    error = Color(0xFFE6E6E6),
    onError = Color(0xFF121212),
    errorContainer = Color(0xFF3A3A3A),
    onErrorContainer = Color(0xFFE6E6E6),
    outline = Color(0xFF7A7A7A),
    outlineVariant = Color(0xFF3D3D3D),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF383838),
    surfaceDim = Color(0xFF121212),
    surfaceContainerLowest = Color(0xFF0D0D0D),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF282828),
    surfaceContainerHighest = Color(0xFF333333),
)

/** One weight of a bundled variable font. */
private fun variable(resource: Int, weight: FontWeight) =
    Font(resource, weight, FontStyle.Normal, variationSettings = FontVariation.Settings(weight, FontStyle.Normal))

/** Cormorant Garamond (SIL OFL), for titles and move names. */
val CormorantGaramond = FontFamily(
    variable(R.font.cormorant_garamond, FontWeight.Normal),
    variable(R.font.cormorant_garamond, FontWeight.Medium),
    variable(R.font.cormorant_garamond, FontWeight.SemiBold),
    variable(R.font.cormorant_garamond, FontWeight.Bold),
)

/** Inter (SIL OFL), for the narration and the controls. */
val Inter = FontFamily(
    variable(R.font.inter, FontWeight.Normal),
    variable(R.font.inter, FontWeight.Medium),
    variable(R.font.inter, FontWeight.SemiBold),
    variable(R.font.inter, FontWeight.Bold),
)

/** Titles are a touch larger and heavier than Material's: Cormorant is small and thin beside Inter. */
private fun TextStyle.serif() = copy(fontFamily = CormorantGaramond, fontWeight = FontWeight.SemiBold, fontSize = fontSize * 1.15f)

private fun TextStyle.sans() = copy(fontFamily = Inter)

/** Few sizes, Material's (RF-UI-03): serif for display, headline and title; sans for body and label. */
private val IrondroidTypography = Typography().run {
    copy(
        displayLarge = displayLarge.serif(),
        displayMedium = displayMedium.serif(),
        displaySmall = displaySmall.serif(),
        headlineLarge = headlineLarge.serif(),
        headlineMedium = headlineMedium.serif(),
        headlineSmall = headlineSmall.serif(),
        titleLarge = titleLarge.serif(),
        titleMedium = titleMedium.serif(),
        titleSmall = titleSmall.serif(),
        bodyLarge = bodyLarge.sans(),
        bodyMedium = bodyMedium.sans(),
        bodySmall = bodySmall.sans(),
        labelLarge = labelLarge.sans(),
        labelMedium = labelMedium.sans(),
        labelSmall = labelSmall.sans(),
    )
}

/** How large the text is (IRON-69, RF-CFG-01), on top of the phone's own font size. */
enum class TextSize(val scale: Float) { SMALL(0.9f), NORMAL(1f), LARGE(1.15f), HUGE(1.3f) }
