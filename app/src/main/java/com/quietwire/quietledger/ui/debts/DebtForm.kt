package com.quietwire.quietledger.ui.debts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.quietwire.quietledger.core.money.Frequency
import com.quietwire.quietledger.data.model.DebtCategory

/**
 * The debt fields, shared by the dialog used on narrow screens and the detail
 * pane used when there is room for two columns. One definition so the two
 * cannot drift apart.
 */
@Composable
fun DebtForm(
    state: DebtEditorState,
    onChange: ((DebtEditorState) -> DebtEditorState) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.name,
            onValueChange = { v -> onChange { it.copy(name = v) } },
            label = { Text("Name") },
            isError = state.name.isNotEmpty() && state.nameError != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        EnumDropdown(
            label = "Category",
            options = DebtCategory.entries,
            selected = state.category,
            optionLabel = { it.label },
            onSelect = { v -> onChange { s -> s.copy(category = v) } }
        )

        OutlinedTextField(
            value = state.balance,
            onValueChange = { v -> onChange { it.copy(balance = v) } },
            label = { Text("Current balance") },
            prefix = { Text("$") },
            isError = state.balance.isNotEmpty() && state.balanceError != null,
            supportingText = {
                state.balanceError?.takeIf { state.balance.isNotEmpty() }?.let { Text(it) }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.payment,
            onValueChange = { v -> onChange { it.copy(payment = v) } },
            label = { Text("Payment") },
            prefix = { Text("$") },
            isError = state.paymentError != null,
            supportingText = {
                state.paymentError?.let { Text(it) }
                    ?: Text("Leave blank if you are not paying it yet")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        EnumDropdown(
            label = "How often",
            options = Frequency.entries,
            selected = state.frequency,
            optionLabel = { it.label },
            onSelect = { v -> onChange { s -> s.copy(frequency = v) } }
        )

        // The monthly equivalent is not obvious for weekly or fortnightly, and
        // it is the figure the projection actually uses.
        val monthly = state.parsedPayment
            ?.takeIf { it.isPositive && state.frequency != Frequency.MONTHLY }
            ?.let { state.frequency.monthlyEquivalent(it) }
        if (monthly != null) {
            Text(
                "That is ${monthly.format()} a month",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OutlinedTextField(
            value = state.apr,
            onValueChange = { v -> onChange { it.copy(apr = v) } },
            label = { Text("Interest rate") },
            suffix = { Text("% APR") },
            isError = state.aprError != null,
            supportingText = { state.aprError?.let { Text(it) } ?: Text("Optional") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Only offered once there is a rate for it to qualify. A promotional
        // flag on a debt with no APR says nothing.
        if (state.parsedApr != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChange { it.copy(aprIsPromotional = !it.aprIsPromotional) } }
            ) {
                Checkbox(
                    checked = state.aprIsPromotional,
                    onCheckedChange = { v -> onChange { it.copy(aprIsPromotional = v) } }
                )
                Column(Modifier.weight(1f)) {
                    Text("This is a promotional rate", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "An introductory or balance transfer offer that will end. " +
                            "Long projections will be marked as unreliable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        OutlinedTextField(
            value = state.notes,
            onValueChange = { v -> onChange { it.copy(notes = v) } },
            label = { Text("Notes") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}
