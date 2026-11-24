package com.quietwire.quietledger.ui.investments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val AS_OF = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

/**
 * Reports what an import actually did.
 *
 * Specifically including what it threw away. The parser skips rows on purpose,
 * such as Fidelity's "Pending activity" line, and a silent skip is the kind of
 * thing that leaves someone wondering why a total is short.
 */
@Composable
fun ImportResultDialog(state: ImportState, onDismiss: () -> Unit) {
    when (state) {
        ImportState.Idle -> Unit

        ImportState.Working -> AlertDialog(
            onDismissRequest = { },
            title = { Text("Reading the file") },
            text = { CircularProgressIndicator() },
            confirmButton = { }
        )

        is ImportState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("That did not import") },
            text = {
                Column {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Nothing was changed. Your existing positions are untouched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )

        is ImportState.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Positions updated") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(state.result.accountName, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${state.result.positions} positions and " +
                            "${state.result.cashPositions} cash, " +
                            "worth ${state.result.total.format()}.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Valued as of " +
                            state.result.asOf.atZone(ZoneId.systemDefault()).format(AS_OF) +
                            ", taken from the file rather than from this phone's clock.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (state.result.removed > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Replaced ${state.result.removed} previously imported rows. A " +
                                "positions file is a snapshot, so anything no longer in it " +
                                "has been sold.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (state.result.skipped.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Rows deliberately skipped", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        state.result.skipped.forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
        )
    }
}
