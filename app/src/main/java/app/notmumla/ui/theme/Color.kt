package app.notmumla.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Design tokens lifted verbatim from the original HTML design mockup (LIGHT/DARK maps).
 *
 * The design uses tokens beyond Material 3's standard scheme (speaking, afk, muted,
 * several surface tiers), so we carry them in a dedicated [MumbleColors] holder exposed
 * through [LocalMumbleColors] and also map the overlapping ones onto a Material 3 scheme.
 */
@Immutable
data class MumbleColors(
    val surface: Color,
    val surfContainer: Color,
    val surfHigh: Color,
    val surfHighest: Color,
    val onSurface: Color,
    val onSurfaceVar: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val outline: Color,
    val outlineVariant: Color,
    val bezel: Color,
    val speaking: Color,
    val muted: Color,
    val afk: Color,
    val errContainer: Color,
    val onErrContainer: Color,
    val isDark: Boolean,
)

val LightMumbleColors = MumbleColors(
    surface = Color(0xFFFBFCFF),
    surfContainer = Color(0xFFECEEF4),
    surfHigh = Color(0xFFE6E8EE),
    surfHighest = Color(0xFFDFE2E8),
    onSurface = Color(0xFF1A1C1E),
    onSurfaceVar = Color(0xFF44474E),
    primary = Color(0xFF2A6FDB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC5C6D0),
    bezel = Color(0xFFCFD2D8),
    speaking = Color(0xFF1F8A5B),
    muted = Color(0xFFBA1A1A),
    afk = Color(0xFF8A6D00),
    errContainer = Color(0xFFFFDAD6),
    onErrContainer = Color(0xFF410002),
    isDark = false,
)

val DarkMumbleColors = MumbleColors(
    surface = Color(0xFF111318),
    surfContainer = Color(0xFF1D2024),
    surfHigh = Color(0xFF282B30),
    surfHighest = Color(0xFF33363B),
    onSurface = Color(0xFFE3E2E6),
    onSurfaceVar = Color(0xFFC4C6CF),
    primary = Color(0xFFABC7FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF284777),
    onPrimaryContainer = Color(0xFFD8E2FF),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
    bezel = Color(0xFF2A2D31),
    speaking = Color(0xFF6FDC9E),
    muted = Color(0xFFFFB4AB),
    afk = Color(0xFFF0C000),
    errContainer = Color(0xFF93000A),
    onErrContainer = Color(0xFFFFDAD6),
    isDark = true,
)
