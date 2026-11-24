package com.quietwire.quietledger.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shown when the database could not be read.
 *
 * The important thing is that this is **not** the empty state. Rendering "you
 * have no debts" when the truth is "I could not open your database" tells
 * someone their financial records have vanished, which is the single most
 * alarming thing a finance app can say, and it was wrong every time it said it.
 *
 * The distinction exists because a transient read failure used to be caught and
 * turned into an empty list, so a momentary problem around locking looked
 * identical to a fresh install.
 */
@Composable
fun UnavailableNotice(
    what: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Could not read your $what",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Your data is still there. This usually means the app locked while " +
                "the screen was open. Lock and unlock it again, and if it keeps " +
                "happening it is worth reporting.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 420.dp)
        )
    }
}
