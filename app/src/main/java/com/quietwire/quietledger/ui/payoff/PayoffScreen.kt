package com.quietwire.quietledger.ui.payoff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quietwire.quietledger.domain.payoff.DebtProjection
import com.quietwire.quietledger.ui.theme.moneyColors
import com.quietwire.quietledger.ui.common.months
import com.quietwire.quietledger.domain.payoff.PayoffComparison
import java.time.format.DateTimeFormatter
import com.quietwire.quietledger.ui.common.UnavailableNotice

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
fun PayoffScreen(
    state: PayoffUiState,
    onExtraPaymentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRolloverChange: (Boolean) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    if (state.unavailable) {
        UnavailableNotice("payoff plan", modifier.fillMaxSize())
        return
    }

    if (!state.hasDebts && !state.loading) {
        Column(
            modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Nothing to pay off", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Add a debt and the projection appears here.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val comparison = state.comparison ?: return

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize().padding(contentPadding)
    ) {
        item { Headline(comparison, state.rolloverEnabled) }
        item {
            OutlinedTextField(
                value = state.extraPayment,
                onValueChange = onExtraPaymentChange,
                label = { Text("Extra each month") },
                prefix = { Text("$") },
                supportingText = { Text("Anything spare goes to the smallest debt first") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            RolloverToggle(
                enabled = state.rolloverEnabled,
                comparison = comparison,
                onChange = onRolloverChange
            )
        }
        item {
            Text(
                "Payoff order",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        val plan = if (state.rolloverEnabled) comparison.snowball else comparison.withoutRollover
        items(plan.projections, key = { it.debt.id }) { ProjectionRow(it) }
    }
}

@Composable
private fun Headline(comparison: PayoffComparison, rolloverEnabled: Boolean) {
    val plan = if (rolloverEnabled) comparison.snowball else comparison.withoutRollover
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (plan.isDebtFree) {
                // Nothing owed. Distinct from "everything clears eventually",
                // and there is no date to show because there is nothing to
                // wait for. Also guards the force-unwrap below: an empty plan
                // now counts as clearing completely and has no date.
                Text("Debt free", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Nothing owed",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.moneyColors.positive
                )
                Text(
                    "No debt with a balance, so there is nothing to project.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (plan.clearsCompletely) {
                Text("Debt free", style = MaterialTheme.typography.labelMedium)
                Text(
                    plan.debtFreeDate!!.format(DATE),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${months(plan.monthsToDebtFree!!)} from now",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    if (rolloverEnabled) {
                        "Assumes each cleared payment goes straight to the next debt."
                    } else {
                        "Based on the payments you make now, plus interest."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("Not on track", style = MaterialTheme.typography.labelMedium)
                Text(
                    "These debts never clear",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(4.dp))
                plan.unpayable.forEach {
                    Text(
                        "${it.debt.name}: ${it.unpayableReason?.explanation.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Labelled("Per month", plan.startingMonthlyOutlay.format())
                if (plan.totalInterest.isPositive) {
                    Labelled("Interest", plan.totalInterest.format())
                }
                Labelled("Total", plan.totalPaid.format())
            }
        }
    }
}

@Composable
private fun Labelled(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProjectionRow(projection: DebtProjection) {
    Card(
        Modifier.fillMaxWidth(),
        colors = if (projection.willClear) {
            CardDefaults.cardColors()
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(32.dp)
            ) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        projection.payoffOrder?.toString() ?: "none",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(projection.debt.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    projection.debt.currentBalance.format(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (projection.willClear) {
                    Text(
                        projection.payoffDate!!.format(DATE),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        months(projection.monthsToPayoff!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Never",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * Rollover is a choice, not an assumption.
 *
 * Projecting a debt-free date that quietly depends on redirecting freed up
 * money produces figures that look wrong and cannot be checked: a balance of
 * 9,985 at 541 a month reporting nine months instead of nineteen. The money
 * might go to savings, or investing, or nowhere in particular. So the default
 * is what is actually happening, and this offers the alternative with its
 * benefit stated.
 */
@Composable
private fun RolloverToggle(
    enabled: Boolean,
    comparison: PayoffComparison,
    onChange: (Boolean) -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Roll payments forward", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append("When a debt clears, put its payment towards the next one. ")
                        val saved = comparison.monthsSaved
                        if (saved != null && saved > 0) {
                            append("Would clear everything ")
                            append(months(saved))
                            append(" sooner")
                            if (comparison.interestSaved.isPositive) {
                                append(" and save ${comparison.interestSaved.format()} of interest")
                            }
                            append(".")
                        } else {
                            append("Makes no difference to these debts.")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = onChange)
        }
    }
}
