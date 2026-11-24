package com.quietwire.quietledger.ui.savings

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quietwire.quietledger.domain.model.SavingsAccount
import com.quietwire.quietledger.ui.common.UnavailableNotice
import com.quietwire.quietledger.ui.theme.moneyColors

/**
 * Savings accounts, the asset side of net worth.
 *
 * Deposits and withdrawals are first-class actions rather than making someone
 * edit the balance, because an edited number loses the reason it changed and the
 * journal then reads as a list of balances instead of a history of events.
 */
@Composable
fun SavingsScreen(
    state: SavingsUiState,
    onAdd: () -> Unit,
    onEdit: (SavingsAccount) -> Unit,
    onDeposit: (SavingsAccount) -> Unit,
    onWithdraw: (SavingsAccount) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    Box(modifier.fillMaxSize()) {
        if (state.unavailable) {
            UnavailableNotice("savings", Modifier.fillMaxSize())
        } else if (state.accounts.isEmpty() && !state.loading) {
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
                if (state.emergencyFund.isNotEmpty()) {
                    item { EmergencyFundCard(state) }
                }
                items(state.accounts, key = { it.id }) { account ->
                    AccountCard(
                        account = account,
                        onClick = { onEdit(account) },
                        onDeposit = { onDeposit(account) },
                        onWithdraw = { onWithdraw(account) }
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("Add account") },
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }
}

@Composable
private fun TotalCard(state: SavingsUiState) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.moneyColors.positiveContainer,
            contentColor = MaterialTheme.moneyColors.onPositiveContainer
        )
    ) {
        Row(Modifier.padding(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Total saved", style = MaterialTheme.typography.labelMedium)
                Text(
                    state.total.format(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium
                )
            }
            if (state.projectedAnnualInterest.isPositive) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("Interest a year", style = MaterialTheme.typography.labelMedium)
                    Text(
                        state.projectedAnnualInterest.format(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountCard(
    account: SavingsAccount,
    onClick: () -> Unit,
    onDeposit: () -> Unit,
    onWithdraw: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        account.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        buildString {
                            append(account.purpose.label)
                            if (account.institution.isNotBlank()) {
                                append(" | ")
                                append(account.institution)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        account.currentBalance.format(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    account.apyBasisPoints?.takeIf { it > 0 }?.let {
                        Text(
                            "%.2f%% APY".format(it / 100.0),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            account.goalProgress?.let { progress ->
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    color = MaterialTheme.moneyColors.positive,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (account.goalReached) {
                        "Goal of ${account.goal!!.format()} reached"
                    } else {
                        "${account.remainingToGoal!!.format()} to go of " +
                            account.goal!!.format()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDeposit) { Text("Deposit") }
                TextButton(onClick = onWithdraw) { Text("Withdraw") }
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
        Text("No savings yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Emergency fund, cash, anything set aside. These count towards net " +
                "worth, so adding them is what turns that figure from minus your " +
                "debt into something meaningful.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * How long the savings would last, and what each rung costs.
 *
 * Sized against every recurring expense plus every debt payment, because that
 * is what actually leaves the account each month. Expenses alone would measure
 * surviving rather than carrying on paying what is owed, and missing a tax
 * payment costs more than a late fee.
 */
@Composable
private fun EmergencyFundCard(state: SavingsUiState) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Emergency fund", style = MaterialTheme.typography.titleSmall)
            Text(
                "${state.monthlyNeed.format()} a month covers everything: bills and " +
                    "debt payments.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(4.dp))
            Text(
                "%.1f months covered".format(state.monthsCovered),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = when {
                    state.monthsCovered >= 3f -> MaterialTheme.moneyColors.positive
                    state.monthsCovered >= 1f -> Color.Unspecified
                    else -> MaterialTheme.moneyColors.negative
                }
            )

            Spacer(Modifier.height(10.dp))

            state.emergencyFund.forEach { rung ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        if (rung.months == 1) "1 month" else "${rung.months} months",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (rung.reached) {
                            "covered"
                        } else {
                            // The shortfall rather than the target: what is
                            // still needed is the actionable number.
                            "${rung.shortfall.format()} short of ${rung.target.format()}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (rung.reached) {
                            MaterialTheme.moneyColors.positive
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                LinearProgressIndicator(
                    progress = { rung.progress },
                    color = if (rung.reached) {
                        MaterialTheme.moneyColors.positive
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
