package com.quietwire.quietledger.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quietwire.quietledger.ui.theme.moneyColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val BUILT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

/**
 * What this build is, and what it can currently see.
 *
 * Every figure here answers a question that previously needed a cable: which
 * APK is actually installed, is the data still there, and why was a screen
 * blank. An app that can explain itself is worth more than one that needs
 * diagnosing from outside.
 */
@Composable
fun AboutSection(state: AboutUiState, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("This build", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))

                Detail("Version", "${state.versionName} (${state.versionCode})")
                Detail("Commit", state.commit, monospace = true)
                Detail("Built", state.buildTime.atZone(ZoneId.systemDefault()).format(BUILT))
                Detail(
                    "Data schema",
                    if (!state.databaseOpen) {
                        "unknown while locked"
                    } else if (state.schemaMismatch) {
                        "version ${state.schemaVersion}, expected ${state.expectedSchemaVersion}"
                    } else {
                        "version ${state.schemaVersion}"
                    }
                )
                // Read from the key itself rather than from what was asked
                // for. Some devices accept a StrongBox request and quietly
                // return a trusted environment key, so only the key is
                // evidence of where it lives.
                Detail(
                    "Database key",
                    state.keySecurityLevel + if (
                        state.securityChipAvailable &&
                        !state.keySecurityLevel.contains("StrongBox")
                    ) " (chip present, not in use)" else ""
                )
                Detail("Type", if (state.debug) "debug" else "release")

                if (state.builtFromModifiedTree) {
                    Spacer(Modifier.height(8.dp))
                    // Matters when comparing behaviour against a commit: the
                    // hash does not fully describe what is running.
                    Text(
                        "Built from a modified working tree, so the commit above " +
                            "does not describe it exactly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.moneyColors.negative
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "What is stored",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRefresh) { Text("Refresh") }
                }

                Text(
                    if (state.databaseOpen) "Database open" else "Database closed",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.databaseOpen) {
                        MaterialTheme.moneyColors.positive
                    } else {
                        MaterialTheme.moneyColors.negative
                    }
                )

                Spacer(Modifier.height(8.dp))

                when {
                    state.countError != null -> Text(
                        // Not zeros. "Cannot count" and "nothing to count" are
                        // the distinction this screen exists to make visible.
                        "Could not count rows.\n${state.countError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.moneyColors.negative
                    )

                    state.counts == null -> Text(
                        "Reading...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    else -> {
                        state.counts.forEach { Detail(it.name, it.rows.toString()) }
                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(4.dp))
                        Detail("Total rows", state.totalRows.toString(), emphasise = true)
                    }
                }
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = if (state.recentFailures.isEmpty()) {
                CardDefaults.cardColors()
            } else {
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.moneyColors.negativeContainer,
                    contentColor = MaterialTheme.moneyColors.onNegativeContainer
                )
            }
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Recent read problems", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))

                if (state.recentFailures.isEmpty()) {
                    Text(
                        "None since the app started.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "A screen that showed nothing will have left a line here. " +
                            "These are kept in memory only and clear on restart.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    state.recentFailures.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Detail(
    label: String,
    value: String,
    monospace: Boolean = false,
    emphasise: Boolean = false
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasise) FontWeight.Medium else FontWeight.Normal,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
        )
    }
}
