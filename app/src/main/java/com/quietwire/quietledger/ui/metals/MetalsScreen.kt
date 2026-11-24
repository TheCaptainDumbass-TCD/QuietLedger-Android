package com.quietwire.quietledger.ui.metals

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.domain.model.Metal
import com.quietwire.quietledger.domain.model.MetalLot
import com.quietwire.quietledger.domain.model.MetalSummary
import com.quietwire.quietledger.ui.common.UnavailableNotice
import com.quietwire.quietledger.ui.theme.moneyColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PURCHASED = DateTimeFormatter.ofPattern("MMM yyyy")

/**
 * Bullion, as purchase lots.
 *
 * Valued at what was paid, and labelled as such. There is no spot price feed
 * and no network permission, so a market value would be a number this app
 * cannot know. Saying "at cost" is a smaller claim and a true one.
 */
@Composable
fun MetalsScreen(
    state: MetalsUiState,
    onEditSpotPrices: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    Box(modifier.fillMaxSize()) {
        if (state.unavailable) {
            UnavailableNotice("precious metals", Modifier.fillMaxSize())
        } else if (state.lots.isEmpty() && !state.loading) {
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
                items(state.summaries, key = { it.metal.name }) { MetalCard(it) }
                item {
                    Text(
                        "Purchases",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                    )
                }
                items(state.lots, key = { it.id }) { LotRow(it, state.currentSpot(it.metal)) }
            }

            ExtendedFloatingActionButton(
                onClick = onEditSpotPrices,
                icon = { Icon(Icons.Default.Create, contentDescription = null) },
                text = {
                    Text(if (state.marketValue == null) "Add spot prices" else "Spot prices")
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            )
        }
    }
}

@Composable
private fun TotalCard(state: MetalsUiState) {
    val market = state.marketValue
    val gain = state.gain

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.moneyColors.accentContainer,
            contentColor = MaterialTheme.moneyColors.onAccentContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            // The headline is the market value once there is a complete one,
            // because that is what the metal is worth. Until then it is cost,
            // stated as cost. Neither is ever dressed up as the other.
            Text(
                if (market == null) "Held, at cost" else "Worth at spot",
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                (market ?: state.totalCost).format(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium
            )

            if (market == null) {
                Text(
                    "What was paid, including ${state.shippingTotal.format()} of " +
                        "shipping. Not a market value.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (state.awaitingPrice.isNotEmpty()) {
                    Text(
                        "Add a spot price for " +
                            state.awaitingPrice.joinToString(", ") { it.metal.label.lowercase() } +
                            " to see the total value and gain.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Text(
                    buildString {
                        append(state.totalCost.format())
                        append(" paid, so ")
                        append(if (gain!!.isNegative) "down " else "up ")
                        append(gain.absoluteValue.format())
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                state.oldestPriceAgeDays?.let { days ->
                    Text(
                        when {
                            days == 0L -> "Your spot prices, entered today."
                            days == 1L -> "Your spot prices, entered yesterday."
                            days < 30L -> "Your spot prices, entered $days days ago."
                            else -> "Your spot prices are $days days old. Worth updating."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "Spot is not a sale price. A dealer buying from you would pay less.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * One metal, coloured by whether it is up or down on what was paid.
 *
 * Colour does the work here: the question being asked of this screen is "am I
 * ahead or behind", and that should be answerable without reading a number.
 *
 * Neutral rather than green when no spot price has been given, because without
 * one there is no gain to report and a green card would imply a profit nobody
 * has evidence for.
 */
@Composable
private fun MetalCard(summary: MetalSummary) {
    val gain = summary.gain
    val container = when {
        gain == null -> MaterialTheme.colorScheme.surfaceContainerLow
        gain.isNegative -> MaterialTheme.moneyColors.negativeContainer
        gain.isPositive -> MaterialTheme.moneyColors.positiveContainer
        // Exactly break even is neither, and saying so is more honest than
        // rounding it into one of them.
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val onContainer = when {
        gain == null -> MaterialTheme.colorScheme.onSurface
        gain.isNegative -> MaterialTheme.moneyColors.onNegativeContainer
        gain.isPositive -> MaterialTheme.moneyColors.onPositiveContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = onContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        summary.metal.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "${summary.ouncesLabel} troy oz over ${summary.lots} purchases",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        (summary.marketValue ?: summary.cost).format(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    summary.spot?.let {
                        Text(
                            "${it.quotedPrice.format()} /oz",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (gain == null) {
                Text(
                    "${summary.cost.format()} paid. Add a spot price to see if it is up or down.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    buildString {
                        append(summary.cost.format())
                        append(" paid, ")
                        append(if (gain.isNegative) "down " else "up ")
                        append(gain.absoluteValue.format())
                        summary.gainFraction?.let { append(" (%+.1f%%)".format(it * 100)) }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                summary.averageCostPerOunce?.let {
                    Text(
                        "${it.format()} an ounce paid",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun LotRow(lot: MetalLot, currentSpotPerOunce: Money?) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    lot.description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(lot.quantityLabel)
                        append(" x ")
                        append(lot.weightLabel.ifBlank { "${lot.ouncesLabel} oz" })
                        append(" | ")
                        append(lot.vendor)
                        append(" | ")
                        append(lot.purchasedAt.atZone(ZoneId.systemDefault()).format(PURCHASED))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    lot.totalCost.format(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                // Only knowable because the invoice printed spot on the day,
                // and only worth showing when that figure is credible.
                // Three cases, and which reference was used has to be stated.
                val fallback = currentSpotPerOunce?.let { lot.premiumOver(it) }
                when {
                    lot.premiumOverSpot != null -> Text(
                        "%+.0f%% over spot".format(lot.premiumOverSpot!! * 100),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // The invoice figure is unusable, so today's is used
                    // instead and said so. A different question from the
                    // premium on the day, and more useful than nothing.
                    lot.spotLooksUnreliable && fallback != null -> Text(
                        "%+.0f%% over spot today".format(fallback * 100),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    lot.spotLooksUnreliable -> Text(
                        "vendor spot looks wrong",
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
        Text("No metals recorded", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Gold, silver and copper purchases, taken from vendor invoices and " +
                "valued at what was paid.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
