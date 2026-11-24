package com.quietwire.quietledger.ui.debts

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.domain.model.Debt
import com.quietwire.quietledger.ui.theme.moneyColors
import com.quietwire.quietledger.ui.common.UnavailableNotice

@Composable
fun DebtsScreen(
    state: DebtsUiState,
    onAdd: () -> Unit,
    onEdit: (Debt) -> Unit,
    modifier: Modifier = Modifier,
    /** Highlighted in the list when a detail pane is showing it. */
    selectedId: Long? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    Box(modifier.fillMaxSize()) {
        if (state.unavailable) {
            UnavailableNotice("debts", Modifier.fillMaxSize())
        } else if (state.debts.isEmpty() && !state.loading) {
            EmptyDebts(Modifier.align(Alignment.Center).padding(32.dp))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(contentPadding)
            ) {
                item { DebtTotals(state) }
                items(state.debts, key = { it.id }) { debt ->
                    DebtCard(
                        debt = debt,
                        selected = debt.id == selectedId,
                        onClick = { onEdit(debt) }
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("Add debt") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .padding(contentPadding)
        )
    }
}

@Composable
private fun DebtTotals(state: DebtsUiState) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Outstanding", style = MaterialTheme.typography.labelMedium)
                Text(
                    state.totalOutstanding.format(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Per month", style = MaterialTheme.typography.labelMedium)
                Text(
                    state.totalMonthly.format(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun DebtCard(debt: Debt, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (selected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(debt.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        debt.category.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    debt.currentBalance.format(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            debt.fractionPaid?.takeIf { it > 0f }?.let { progress ->
                Spacer(Modifier.height(12.dp))
                // Green, because this bar is progress towards clearing it.
                LinearProgressIndicator(
                    progress = { progress },
                    color = MaterialTheme.moneyColors.positive,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val payment = debt.effectivePayment
                // "No payment set" is a warning, and a warning only makes sense
                // when there is something to pay. A cleared card needs no
                // payment, so flagging it in red reports success as a problem
                // and buries the real warnings among false ones.
                val needsPayment = !payment.isPositive && debt.currentBalance.isPositive
                Text(
                    when {
                        payment.isPositive ->
                            "${payment.format()} ${debt.paymentFrequency.label.lowercase()}"
                        debt.currentBalance.isPositive -> "No payment set"
                        else -> "Cleared"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!needsPayment) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                debt.aprBasisPoints?.let {
                    Text(
                        "%.2f%% APR".format(it / 100.0),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (debt.paymentFrequency != com.quietwire.quietledger.core.money.Frequency.MONTHLY &&
                debt.effectivePayment.isPositive
            ) {
                // Shows the working, not just the result.
                //
                // $375 weekly reads as $1,500 a month if you assume four weeks,
                // and the figure here is $1,625. Both are defensible readings,
                // but a year has 52 weeks rather than 48, so four-week months
                // quietly drop four payments. Stating the arithmetic makes the
                // number checkable instead of merely surprising.
                Text(
                    "${debt.monthlyPayment.format()} a month " +
                        "(${debt.paymentFrequency.perYear} a year over 12)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyDebts(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No debts yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add what you owe and QuietLedger will work out when it clears.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
