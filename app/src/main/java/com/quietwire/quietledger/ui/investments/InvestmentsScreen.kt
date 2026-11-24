package com.quietwire.quietledger.ui.investments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.domain.model.Holding
import com.quietwire.quietledger.ui.common.UnavailableNotice
import com.quietwire.quietledger.ui.theme.moneyColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val AS_OF = DateTimeFormatter.ofPattern("d MMM yyyy")

/**
 * Holdings from a brokerage positions export.
 *
 * Read only by design. The export is the broker's own statement of what is held
 * and what it is worth, and editing it by hand would mean the total stopped
 * corresponding to anything. Refreshing means importing a newer file.
 */
@Composable
fun InvestmentsScreen(
    state: InvestmentsUiState,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    Box(modifier.fillMaxSize()) {
        if (state.unavailable) {
            UnavailableNotice("investments", Modifier.fillMaxSize())
        } else if (state.holdings.isEmpty() && !state.loading) {
            EmptyState(Modifier.fillMaxSize().padding(32.dp))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(contentPadding)
            ) {
                item { TotalCard(state) }

                if (state.hasMultipleAccounts) {
                    // Named and subtotalled, because an IRA and a taxable
                    // account are not interchangeable money.
                    state.byAccount.forEach { account ->
                        item(key = "head-${account.accountName}") {
                            AccountHeader(account.accountName, account.total)
                        }
                        items(account.positions, key = { it.id }) { HoldingRow(it) }
                        items(account.cash, key = { it.id }) { HoldingRow(it) }
                    }
                } else {
                    if (state.positions.isNotEmpty()) {
                        item { SectionHeader("Positions") }
                        items(state.positions, key = { it.id }) { HoldingRow(it) }
                    }
                    if (state.cash.isNotEmpty()) {
                        item { SectionHeader("Cash") }
                        items(state.cash, key = { it.id }) { HoldingRow(it) }
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onImport,
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text(if (state.holdings.isEmpty()) "Import positions" else "Update") },
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }
}

@Composable
private fun TotalCard(state: InvestmentsUiState) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.moneyColors.positiveContainer,
            contentColor = MaterialTheme.moneyColors.onPositiveContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Value", style = MaterialTheme.typography.labelMedium)
                    Text(
                        state.total.format(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                state.totalGainLoss?.let { change ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Gain or loss", style = MaterialTheme.typography.labelMedium)
                        Text(
                            (if (change.isNegative) "" else "+") + change.format(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            state.asOf?.let { asOf ->
                Spacer(Modifier.height(8.dp))
                val days = state.ageInDays ?: 0
                Text(
                    buildString {
                        append("As the broker reported on ")
                        append(asOf.atZone(ZoneId.systemDefault()).format(AS_OF))
                        // Being explicit about age matters: these are not live
                        // prices, and a week-old figure is a different claim.
                        when {
                            days == 0L -> append(", today")
                            days == 1L -> append(", yesterday")
                            days < 14L -> append(", $days days ago")
                            else -> append(", $days days ago. Worth importing a newer file.")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun AccountHeader(accountName: String, total: Money) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            accountName,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(total.format(), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun HoldingRow(holding: Holding) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    holding.symbol,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    holding.description.ifBlank { holding.type },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                holding.quantityLabel?.let { shares ->
                    Text(
                        buildString {
                            append(shares)
                            append(" shares")
                            holding.lastPrice?.let { append(" at ${it.format()}") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    holding.currentValue.format(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                when {
                    holding.gainLoss != null -> Text(
                        (if (holding.isDown) "" else "+") + holding.gainLoss!!.format(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (holding.isDown) {
                            MaterialTheme.moneyColors.negative
                        } else {
                            MaterialTheme.moneyColors.positive
                        }
                    )
                    // Said plainly rather than shown as zero, which would read
                    // as having broken even.
                    !holding.isCash -> Text(
                        "no cost basis",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No positions imported", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Export your positions from Fidelity as a CSV and import them here. " +
                "The file already carries the values the broker calculated, which " +
                "is why this app needs no internet access to show them.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
