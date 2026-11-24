package com.quietwire.quietledger.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle

/**
 * Material's default type, with one change: tabular figures everywhere.
 *
 * By default most typefaces use proportional digits, so a 1 is narrower than a
 * 0 and a column of amounts will not line up. The `tnum` OpenType feature gives
 * every digit the same advance width, so $1,254.63 and $2,000.00 align on the
 * decimal point down a list.
 *
 * This is not the same thing as making the layout dense. Spacing and card sizes
 * are untouched. It only stops the numbers from wandering, which matters a lot
 * in an app whose whole content is numbers.
 */
private fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")

private val Default = Typography()

val QuietLedgerTypography = Typography(
    displayLarge = Default.displayLarge.tabularFigures(),
    displayMedium = Default.displayMedium.tabularFigures(),
    displaySmall = Default.displaySmall.tabularFigures(),
    headlineLarge = Default.headlineLarge.tabularFigures(),
    headlineMedium = Default.headlineMedium.tabularFigures(),
    headlineSmall = Default.headlineSmall.tabularFigures(),
    titleLarge = Default.titleLarge.tabularFigures(),
    titleMedium = Default.titleMedium.tabularFigures(),
    titleSmall = Default.titleSmall.tabularFigures(),
    bodyLarge = Default.bodyLarge.tabularFigures(),
    bodyMedium = Default.bodyMedium.tabularFigures(),
    bodySmall = Default.bodySmall.tabularFigures(),
    labelLarge = Default.labelLarge.tabularFigures(),
    labelMedium = Default.labelMedium.tabularFigures(),
    labelSmall = Default.labelSmall.tabularFigures()
)
