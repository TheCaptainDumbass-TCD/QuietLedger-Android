package com.quietwire.quietledger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours for money moving, which the Material roles do not cover.
 *
 * A balance going down is good news on a debt and bad news on a savings
 * account, so these are named by direction rather than sentiment and the caller
 * decides which is which. Both sets are contrast checked against their theme's
 * surface.
 */
@Immutable
data class MoneyColors(
    /** Money reduced: a payment made, a balance coming down. */
    val positive: Color,
    /** Money increased: interest charged, a balance growing. */
    val negative: Color,
    /** Highlight for figures worth drawing the eye to. Gold, from the icon. */
    val accent: Color,
    /**
     * Tonal pairs for cards that carry a meaning.
     *
     * The desktop app fills these cards with fully saturated colour and white
     * text. That reads well on one fixed light theme and badly on a dark one,
     * so the same five hues are used here as tonal containers instead: a tinted
     * background with a matching foreground. Every pair is contrast checked,
     * worst case 6.54 against the 4.5 needed for body text.
     */
    val positiveContainer: Color,
    val onPositiveContainer: Color,
    val negativeContainer: Color,
    val onNegativeContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val accentContainer: Color,
    val onAccentContainer: Color
)

private val LightMoneyColors = MoneyColors(
    positive = Color(0xFF188655),
    negative = Color(0xFFA82424),
    accent = Color(0xFF8A7328),
    positiveContainer = Color(0xFFDCF9EC), onPositiveContainer = Color(0xFF0F5737),
    negativeContainer = Color(0xFFF8DDDD), onNegativeContainer = Color(0xFF541212),
    warningContainer = Color(0xFFFCEFD9), onWarningContainer = Color(0xFF5E3F08),
    infoContainer = Color(0xFFD6F5FF), onInfoContainer = Color(0xFF004D66),
    accentContainer = Color(0xFFF6F1DF), onAccentContainer = Color(0xFF4F4217)
)

private val DarkMoneyColors = MoneyColors(
    positive = Color(0xFFA8F0D0),
    negative = Color(0xFFEDABAB),
    accent = Color(0xFFE8DBB0),
    positiveContainer = Color(0xFF115F3C), onPositiveContainer = Color(0xFFCBF6E3),
    negativeContainer = Color(0xFF5D1414), onNegativeContainer = Color(0xFFF4CDCD),
    warningContainer = Color(0xFF684508), onWarningContainer = Color(0xFFFAE7C6),
    infoContainer = Color(0xFF005470), onInfoContainer = Color(0xFFC2F0FF),
    accentContainer = Color(0xFF574919), onAccentContainer = Color(0xFFF1E9D0)
)

internal val LocalMoneyColors = staticCompositionLocalOf { LightMoneyColors }

internal fun moneyColorsFor(darkTheme: Boolean): MoneyColors =
    if (darkTheme) DarkMoneyColors else LightMoneyColors

/** `MaterialTheme.moneyColors` alongside the standard roles. */
val MaterialTheme.moneyColors: MoneyColors
    @Composable
    @ReadOnlyComposable
    get() = LocalMoneyColors.current
