package com.quietwire.quietledger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Light and dark are treated as equals. Both schemes are generated from the
 * same icon hues and both are contrast checked, so neither is an afterthought.
 *
 * Dynamic colour is deliberately not offered. See Color.kt for why.
 */
@Composable
fun QuietLedgerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) QuietLedgerDarkScheme else QuietLedgerLightScheme

    CompositionLocalProvider(LocalMoneyColors provides moneyColorsFor(darkTheme)) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = QuietLedgerTypography,
            content = content
        )
    }
}
