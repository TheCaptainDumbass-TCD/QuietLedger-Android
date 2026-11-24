package com.quietwire.quietledger.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * A fixed palette taken from the app icon, replacing Material You's dynamic
 * colour.
 *
 * Dynamic colour meant the app's character changed with the wallpaper, which is
 * the wrong behaviour for something that should look like a considered
 * financial tool. These hues come straight out of `assets/icon_1024.png`:
 *
 *   navy   #192D41  hue 210  the brand base, used for primary
 *   gold   #D4AF37  hue 46   secondary, and about as financial as a colour gets
 *   green  #00B464  hue 153  tertiary, growth and progress
 *   red    #DC3C3C  hue 0    error, and money moving the wrong way
 *
 * Tones follow the Material 3 scheme conventions, generated as fixed-hue ramps
 * and then checked for contrast. Gold and green sit lower than the usual tone
 * 40 in the light scheme, at 35 and 31, because they are inherently bright and
 * white text on tone 40 fails WCAG AA. Every on-colour pair was verified:
 * worst case 4.59 in light and 4.72 in dark, both above the 4.5 threshold for
 * body text.
 *
 * Generated values rather than hand-picked, so regenerating is reproducible.
 * Do not nudge these by eye without re-checking contrast.
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF396693),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDAE6F1),
    onPrimaryContainer = Color(0xFF0E1925),
    secondary = Color(0xFF8A7328),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF4EDD7),
    onSecondaryContainer = Color(0xFF28210B),
    tertiary = Color(0xFF188655),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD4F7E7),
    onTertiaryContainer = Color(0xFF082B1B),
    error = Color(0xFFA82424),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF6D5D5),
    onErrorContainer = Color(0xFF2A0909),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF181A1B),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF181A1B),
    surfaceVariant = Color(0xFFE2E6E9),
    onSurfaceVariant = Color(0xFF424C57),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F5F6),
    surfaceContainer = Color(0xFFEFF0F1),
    surfaceContainerHigh = Color(0xFFE9EBEC),
    surfaceContainerHighest = Color(0xFFE4E6E7),
    surfaceDim = Color(0xFFE4E6E7),
    surfaceBright = Color(0xFFFCFCFD),
    outline = Color(0xFF6E7F91),
    outlineVariant = Color(0xFFC5CCD3),
    inverseSurface = Color(0xFF2F3337),
    inverseOnSurface = Color(0xFFF1F2F3),
    inversePrimary = Color(0xFFB6CCE2),
    scrim = Color(0xFF000000),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB6CCE2),
    onPrimary = Color(0xFF1D3349),
    primaryContainer = Color(0xFF2B4C6E),
    onPrimaryContainer = Color(0xFFDAE6F1),
    secondary = Color(0xFFE8DBB0),
    onSecondary = Color(0xFF4F4217),
    secondaryContainer = Color(0xFF776322),
    onSecondaryContainer = Color(0xFFF4EDD7),
    tertiary = Color(0xFFA8F0D0),
    onTertiary = Color(0xFF0F5737),
    tertiaryContainer = Color(0xFF15794C),
    onTertiaryContainer = Color(0xFFD4F7E7),
    error = Color(0xFFEDABAB),
    onError = Color(0xFF541212),
    errorContainer = Color(0xFF7E1B1B),
    onErrorContainer = Color(0xFFF6D5D5),
    background = Color(0xFF0E0F10),
    onBackground = Color(0xFFE4E6E7),
    surface = Color(0xFF0E0F10),
    onSurface = Color(0xFFE4E6E7),
    surfaceVariant = Color(0xFF424C57),
    onSurfaceVariant = Color(0xFFC5CCD3),
    surfaceContainerLowest = Color(0xFF090A0B),
    surfaceContainerLow = Color(0xFF181A1B),
    surfaceContainer = Color(0xFF1C1F21),
    surfaceContainerHigh = Color(0xFF282B2E),
    surfaceContainerHighest = Color(0xFF34383C),
    surfaceDim = Color(0xFF0E0F10),
    surfaceBright = Color(0xFF34383C),
    outline = Color(0xFF8B99A7),
    outlineVariant = Color(0xFF424C57),
    inverseSurface = Color(0xFFE4E6E7),
    inverseOnSurface = Color(0xFF2F3337),
    inversePrimary = Color(0xFF396693),
    scrim = Color(0xFF000000),
)

internal val QuietLedgerLightScheme = LightScheme
internal val QuietLedgerDarkScheme = DarkScheme
