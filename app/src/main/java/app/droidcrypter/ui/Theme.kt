package app.droidcrypter.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

object VaultColors {
    val Background = Color(0xFF0F141A)
    val Surface = Color(0xFF171F28)
    val SurfaceElevated = Color(0xFF202B38)
    val Outline = Color(0xFF303E4F)
    val Primary = Color(0xFF00D1B2)
    val Secondary = Color(0xFF3B82F6)
    val Danger = Color(0xFFF43F5E)
    val StarAmber = Color(0xFFF59E0B)
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
}

private val DarkColorScheme = darkColorScheme(
    primary = VaultColors.Primary,
    onPrimary = Color(0xFF0F141A),
    primaryContainer = Color(0xFF064E43),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = VaultColors.Secondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1E3A5F),
    onSecondaryContainer = Color(0xFFBFDBFE),
    tertiary = VaultColors.StarAmber,
    onTertiary = Color(0xFF0F141A),
    background = VaultColors.Background,
    onBackground = VaultColors.TextPrimary,
    surface = VaultColors.Surface,
    onSurface = VaultColors.TextPrimary,
    surfaceVariant = VaultColors.SurfaceElevated,
    onSurfaceVariant = VaultColors.TextSecondary,
    surfaceContainer = VaultColors.Surface,
    surfaceContainerLow = VaultColors.Background,
    surfaceContainerHigh = VaultColors.SurfaceElevated,
    surfaceContainerHighest = VaultColors.SurfaceElevated,
    surfaceDim = VaultColors.Background,
    surfaceBright = VaultColors.SurfaceElevated,
    outline = VaultColors.Outline,
    outlineVariant = VaultColors.Outline,
    error = VaultColors.Danger,
    onError = Color.White,
)

private val DefaultTypography = Typography()
private fun TextStyle.withDefaultColor(): TextStyle =
    if (color == Color.Unspecified) copy(color = VaultColors.TextPrimary) else this

private val DarkTypography = Typography(
    displayLarge = DefaultTypography.displayLarge.withDefaultColor(),
    displayMedium = DefaultTypography.displayMedium.withDefaultColor(),
    displaySmall = DefaultTypography.displaySmall.withDefaultColor(),
    headlineLarge = DefaultTypography.headlineLarge.withDefaultColor(),
    headlineMedium = DefaultTypography.headlineMedium.withDefaultColor(),
    headlineSmall = DefaultTypography.headlineSmall.withDefaultColor(),
    titleLarge = DefaultTypography.titleLarge.withDefaultColor(),
    titleMedium = DefaultTypography.titleMedium.withDefaultColor(),
    titleSmall = DefaultTypography.titleSmall.withDefaultColor(),
    bodyLarge = DefaultTypography.bodyLarge.withDefaultColor(),
    bodyMedium = DefaultTypography.bodyMedium.withDefaultColor(),
    bodySmall = DefaultTypography.bodySmall.withDefaultColor(),
    labelLarge = DefaultTypography.labelLarge.withDefaultColor(),
    labelMedium = DefaultTypography.labelMedium.withDefaultColor(),
    labelSmall = DefaultTypography.labelSmall.withDefaultColor(),
)

@Composable
fun DroidCrypterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = DarkTypography,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides VaultColors.TextPrimary,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = VaultColors.Background,
                contentColor = VaultColors.TextPrimary,
            ) {
                content()
            }
        }
    }
}

