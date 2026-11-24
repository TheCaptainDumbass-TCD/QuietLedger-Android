package com.quietwire.quietledger.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.unit.dp
import com.quietwire.quietledger.domain.model.BalanceChange
import com.quietwire.quietledger.ui.theme.moneyColors
import com.quietwire.quietledger.domain.payoff.DebtProjection
import com.quietwire.quietledger.ui.common.debts
import com.quietwire.quietledger.ui.common.months
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.quietwire.quietledger.ui.common.UnavailableNotice

private val DATE = DateTimeFormatter.ofPattern("MMM yyyy")
private val ACTIVITY_DATE = DateTimeFormatter.ofPattern("d MMM")

/**
 * Shared floor for the stat cards, so a row reads as a row.
 *
 * Enough for a label, a titleLarge value and two lines of caption at the default
 * font scale, with a little headroom. The hero card is deliberately exempt: it
 * spans the full width and carries the headline figure.
 */
private val STAT_CARD_MIN_HEIGHT = 124.dp

/**
 * Cards reflow with the available width, so the cover screen gets one column
 * and the unfolded inner screen gets several, without a separate layout.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 200.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize().padding(contentPadding)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            HeroCard(state)
        }

        item {
            StatCard("Monthly outlay", state.monthlyOutlay.format(), "across ${debts(state.debtCount)}")
        }

        if (state.savingsTotal.isPositive) {
            item {
                StatCard(
                    label = "Savings",
                    value = state.savingsTotal.format(),
                    caption = if (state.savingsCount == 1) {
                        "1 account"
                    } else {
                        "${state.savingsCount} accounts"
                    },
                    // Money held, so green. The counterpart to the red on
                    // interest and on what is owed.
                    container = MaterialTheme.moneyColors.positiveContainer,
                    onContainer = MaterialTheme.moneyColors.onPositiveContainer
                )
            }
        }

        if (state.metalsTotal.isPositive) {
            item {
                // Coloured by gain, matching the per-metal cards. Gold tone
                // only when there is no spot price and therefore no gain to
                // report; green or red would both imply one.
                val gain = state.metalsGain
                StatCard(
                    label = "Metals",
                    value = state.metalsTotal.format(),
                    // Says which figure this is, because a cost and a market
                    // value are different claims and the card is small.
                    caption = when {
                        gain == null -> "at cost"
                        gain.isNegative -> "down ${gain.absoluteValue.format()}"
                        gain.isPositive -> "up ${gain.format()}"
                        else -> "at your spot prices"
                    },
                    container = when {
                        gain == null -> MaterialTheme.moneyColors.accentContainer
                        gain.isNegative -> MaterialTheme.moneyColors.negativeContainer
                        gain.isPositive -> MaterialTheme.moneyColors.positiveContainer
                        else -> MaterialTheme.moneyColors.accentContainer
                    },
                    onContainer = when {
                        gain == null -> MaterialTheme.moneyColors.onAccentContainer
                        gain.isNegative -> MaterialTheme.moneyColors.onNegativeContainer
                        gain.isPositive -> MaterialTheme.moneyColors.onPositiveContainer
                        else -> MaterialTheme.moneyColors.onAccentContainer
                    }
                )
            }
        }

        if (state.investmentsTotal.isPositive) {
            item {
                StatCard(
                    label = "Investments",
                    value = state.investmentsTotal.format(),
                    caption = "as last imported",
                    container = MaterialTheme.moneyColors.positiveContainer,
                    onContainer = MaterialTheme.moneyColors.onPositiveContainer
                )
            }
        }

        item {
            // Green when there is an end in sight, red when something never
            // clears. That distinction is the whole point of the card.
            val onTrack = state.plan.clearsCompletely
            StatCard(
                label = "Debt free",
                // Three distinct states, and the first used to be reported as
                // the last: nothing owed, a date to work towards, and something
                // that never clears.
                value = when {
                    state.plan.isDebtFree -> "Now"
                    else -> state.plan.debtFreeDate?.format(DATE) ?: "Not on track"
                },
                caption = when {
                    state.plan.isDebtFree -> "nothing owed"
                    else -> state.plan.monthsToDebtFree?.let { months(it) }
                        ?: "Something never clears"
                },
                container = if (onTrack) {
                    MaterialTheme.moneyColors.positiveContainer
                } else {
                    MaterialTheme.moneyColors.negativeContainer
                },
                onContainer = if (onTrack) {
                    MaterialTheme.moneyColors.onPositiveContainer
                } else {
                    MaterialTheme.moneyColors.onNegativeContainer
                }
            )
        }

        state.nextToClear?.let { next ->
            item {
                StatCard(
                    label = "Next to clear",
                    value = next.name,
                    caption = next.currentBalance.format(),
                    // Progress coming, so green.
                    container = MaterialTheme.moneyColors.positiveContainer,
                    onContainer = MaterialTheme.moneyColors.onPositiveContainer
                )
            }
        }

        // Anything with a promotional rate is worth flagging, because its
        // projection is built on a number that is going to change.
        state.promotionalCount.takeIf { it > 0 }?.let { count ->
            item {
                StatCard(
                    label = "Promotional rates",
                    value = if (count == 1) "1 debt" else "$count debts",
                    caption = "the rate will change, so these dates will move",
                    container = MaterialTheme.moneyColors.warningContainer,
                    onContainer = MaterialTheme.moneyColors.onWarningContainer
                )
            }
        }

        if (state.plan.totalInterest.isPositive) {
            item {
                StatCard(
                    label = "Interest ahead",
                    value = state.plan.totalInterest.format(),
                    caption = "if nothing changes",
                    // Money leaving, so red.
                    container = MaterialTheme.moneyColors.negativeContainer,
                    onContainer = MaterialTheme.moneyColors.onNegativeContainer
                )
            }
        }

        val problems = state.plan.unpayable
        if (problems.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { ProblemCard(problems) }
        }

        // Real content for space that was otherwise empty, and it is the point
        // of recording the journal in the first place.
        if (state.recentActivity.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ActivityCard(state.recentActivity)
            }
        }
    }
}

@Composable
private fun HeroCard(state: DashboardUiState) {
    Card(Modifier.fillMaxWidth()) {
        if (state.unavailable) {
            UnavailableNotice("finances", Modifier.fillMaxWidth())
            return@Card
        }

        // Side by side when there is room, stacked when there is not. A
        // currency figure at this size needs roughly 200dp, and half of the
        // folded cover screen does not give it that.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sideBySide = maxWidth >= 460.dp

            if (sideBySide) {
                Row(Modifier.fillMaxWidth().padding(24.dp)) {
                    OwedHalf(state, Modifier.weight(1f))
                    VerticalDivider(Modifier.height(72.dp))
                    NetWorthHalf(state, Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    OwedHalf(state, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))
                    NetWorthHalf(state, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * What is owed, shown as a negative number.
 *
 * Debt is money you do not have, so a bare positive figure next to a net worth
 * reads as though it were an asset. The sign makes the two halves add up on
 * sight, which is the point of putting them together.
 */
@Composable
private fun OwedHalf(state: DashboardUiState, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Owed", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            if (state.totalDebt.isZero) state.totalDebt.format() else (-state.totalDebt).format(),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (state.totalDebt.isPositive) {
                MaterialTheme.moneyColors.negative
            } else {
                Color.Unspecified
            }
        )
        Text(
            if (state.debtCount == 0 && !state.loading) {
                "nothing tracked yet"
            } else {
                "across ${debts(state.debtCount)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Assets minus what is owed.
 *
 * Shown always, including while negative, rather than appearing once it turns
 * positive. A headline figure that changes identity at a threshold flips back
 * and forth for anyone sitting near zero, and cannot be compared against last
 * month.
 */
@Composable
private fun NetWorthHalf(state: DashboardUiState, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Net worth", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            state.netWorth.format(),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = when {
                state.netWorth.isPositive -> MaterialTheme.moneyColors.positive
                state.netWorth.isNegative -> MaterialTheme.moneyColors.negative
                else -> Color.Unspecified
            }
        )
        Text(
            if (!state.hasAssets) {
                // Explains why the two halves currently match, instead of
                // leaving it looking like a duplicated number.
                "no assets recorded yet"
            } else {
                "${state.assetsTotal.format()} in assets"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Colour here carries meaning rather than decoration, which is the only reason
 * to use it on a screen full of numbers. Red is money leaving, green is
 * progress, amber is something needing attention. A card with nothing to say is
 * left neutral, because colouring everything communicates nothing.
 */
@Composable
private fun StatCard(
    label: String,
    value: String,
    caption: String,
    container: Color = Color.Unspecified,
    onContainer: Color = Color.Unspecified
) {
    Card(
        // A minimum height rather than a fixed one, so every card in a row
        // matches while still growing if the system font scale is turned up.
        // A fixed height would look tidy at the default scale and clip text for
        // anyone who needs larger type, which is the wrong thing to optimise
        // for in a grid of five cards.
        Modifier.fillMaxWidth().heightIn(min = STAT_CARD_MIN_HEIGHT),
        colors = if (container == Color.Unspecified) {
            CardDefaults.cardColors()
        } else {
            CardDefaults.cardColors(containerColor = container, contentColor = onContainer)
        }
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                // A debt name can be long. One line with an ellipsis keeps the
                // row aligned; the full name is on the Debts screen.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (container == Color.Unspecified) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    // Already contrast checked against the container, so it is
                    // only softened rather than recoloured.
                    onContainer.copy(alpha = 0.78f)
                }
            )
        }
    }
}

@Composable
private fun ActivityCard(activity: List<BalanceChange>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Recent activity", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            activity.forEach { change ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            change.subjectName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            change.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (!change.delta.isZero) {
                            Text(
                                (if (change.isDecrease) "-" else "+") +
                                    change.delta.absoluteValue.format(),
                                style = MaterialTheme.typography.bodyMedium,
                                // Good news depends on what moved: a debt going
                                // down is progress, savings going down is not.
                                color = if (change.isProgress) {
                                    MaterialTheme.moneyColors.positive
                                } else {
                                    MaterialTheme.moneyColors.negative
                                }
                            )
                        }
                        Text(
                            change.occurredAt.atZone(ZoneId.systemDefault()).format(ACTIVITY_DATE),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProblemCard(problems: List<DebtProjection>) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Needs attention",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(8.dp))
            problems.forEach {
                Text(
                    "${it.debt.name}: ${it.unpayableReason?.explanation.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}
