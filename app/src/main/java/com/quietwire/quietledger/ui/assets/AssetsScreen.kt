package com.quietwire.quietledger.ui.assets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quietwire.quietledger.core.money.Money

/**
 * Which kind of asset is on screen.
 *
 * Savings and retirement share a destination because the bottom bar holds five
 * items at most and Overview, Debts, Expenses and Payoff already take four.
 * They also belong together: both answer "what do I own", and net worth adds
 * them without caring which is which.
 *
 * Savings is personal savings. Investments is whatever the Fidelity positions
 * export contains, which is currently a Traditional IRA and a mostly empty
 * taxable account. Metals is bullion, taken from vendor invoices.
 *
 * This tab was called "Retirement" while the IRA was the only account in the
 * file. It no longer is, so the label would have been wrong for part of its own
 * total. Each account is named with its own subtotal on the screen, which is
 * what actually resolves the ambiguity; the tab just needs a heading that is
 * true of all of them.
 *
 * Three sections rather than one merged list, because the three figures are
 * different kinds of fact: a bank balance is known, a holding is a valuation as
 * of a date, and bullion is what was paid. Net worth adds them, but presenting
 * them identically would imply a confidence two of them do not have.
 *
 * They are separate sections rather than one merged list because the two
 * figures mean different things. A savings balance is simply known; a holding
 * is a valuation as of a date, and conflating them would present a week-old
 * price with the same confidence as a bank balance.
 */
enum class AssetSection(val label: String) {
    SAVINGS("Savings"),
    RETIREMENT("Investments"),
    METALS("Metals")
}

@Composable
fun AssetsScaffold(
    selected: AssetSection,
    onSelect: (AssetSection) -> Unit,
    savingsTotal: Money,
    retirementTotal: Money,
    metalsTotal: Money,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable (AssetSection) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            AssetSection.entries.forEachIndexed { index, section ->
                SegmentedButton(
                    selected = section == selected,
                    onClick = { onSelect(section) },
                    shape = SegmentedButtonDefaults.itemShape(index, AssetSection.entries.size),
                    label = {
                        // The total on the tab, so switching is not needed just
                        // to see which side holds more.
                        Column {
                            Text(section.label, style = MaterialTheme.typography.labelLarge)
                            Text(
                                when (section) {
                                    AssetSection.SAVINGS -> savingsTotal.format()
                                    AssetSection.RETIREMENT -> retirementTotal.format()
                                    AssetSection.METALS -> metalsTotal.format()
                                },
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                )
            }
        }

        content(selected)
    }
}
