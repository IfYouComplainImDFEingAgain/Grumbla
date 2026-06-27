package app.notmumla.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalMumbleColors: ProvidableCompositionLocal<MumbleColors> =
    staticCompositionLocalOf { LightMumbleColors }

/** Convenience accessor: `MumbleTheme.colors`. */
object MumbleTheme {
    val colors: MumbleColors
        @Composable @ReadOnlyComposable get() = LocalMumbleColors.current
}

private fun MumbleColors.toMaterialScheme() = if (isDark) {
    darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        background = surface,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfHigh,
        onSurfaceVariant = onSurfaceVar,
        outline = outline,
        outlineVariant = outlineVariant,
        errorContainer = errContainer,
        onErrorContainer = onErrContainer,
    )
} else {
    lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        background = surface,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfHigh,
        onSurfaceVariant = onSurfaceVar,
        outline = outline,
        outlineVariant = outlineVariant,
        errorContainer = errContainer,
        onErrorContainer = onErrContainer,
    )
}

@Composable
fun NotMumlaTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkMumbleColors else LightMumbleColors
    CompositionLocalProvider(LocalMumbleColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(),
            typography = MumbleTypography,
            content = content,
        )
    }
}
