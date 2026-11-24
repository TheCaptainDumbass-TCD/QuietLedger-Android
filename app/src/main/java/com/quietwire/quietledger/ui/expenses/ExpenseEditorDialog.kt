package com.quietwire.quietledger.ui.expenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.quietwire.quietledger.core.money.Frequency
import com.quietwire.quietledger.data.model.ExpenseGroup
import com.quietwire.quietledger.data.model.RecurringExpenseCategory

@Composable
fun ExpenseEditorDialog(
    state: ExpenseEditorState,
    onChange: ((ExpenseEditorState) -> ExpenseEditorState) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onEnd: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (state.isEditing) "Edit expense" else "Add expense") },
        text = {
            Column(
                Modifier.imePadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { v -> onChange { it.copy(name = v) } },
                    label = { Text("Name") },
                    placeholder = { Text("Electric, rent, Netflix") },
                    isError = state.name.isNotEmpty() && state.nameError != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                CategoryPicker(
                    selected = state.category,
                    onSelect = { v -> onChange { it.copy(category = v) } }
                )

                OutlinedTextField(
                    value = state.amount,
                    onValueChange = { v -> onChange { it.copy(amount = v) } },
                    label = { Text("Amount") },
                    prefix = { Text("$") },
                    isError = state.amount.isNotEmpty() && state.amountError != null,
                    supportingText = {
                        state.amountError?.takeIf { state.amount.isNotEmpty() }?.let { Text(it) }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                FrequencyPicker(
                    selected = state.frequency,
                    onSelect = { v -> onChange { it.copy(frequency = v) } }
                )

                state.monthlyEquivalent?.let {
                    Text(
                        "That is ${it.format()} a month",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = state.dueDay,
                    onValueChange = { v -> onChange { it.copy(dueDay = v.filter { c -> c.isDigit() }) } },
                    label = { Text("Day of the month it is due") },
                    isError = state.dueDayError != null,
                    supportingText = { state.dueDayError?.let { Text(it) } ?: Text("Optional") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.notes,
                    onValueChange = { v -> onChange { it.copy(notes = v) } },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Escrow is the most common way to double count housing costs.
                if (state.category == RecurringExpenseCategory.PROPERTY_TAX ||
                    state.category == RecurringExpenseCategory.HOMEOWNERS_INSURANCE
                ) {
                    HorizontalDivider()
                    Text(
                        "If this is escrowed into your mortgage payment, it is already " +
                            "counted there. Adding it here as well would count it twice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = state.isValid) { Text("Save") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onDelete != null) {
                    // Removes the entry outright, for something added in error
                    // or no longer wanted on the list at all.
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (onEnd != null) {
                    // Softer than Delete: stops it counting towards the monthly
                    // total while keeping the history of what was paid.
                    TextButton(onClick = onEnd) { Text("End") }
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    )
}

/**
 * Grouped picker. Thirty-odd flat entries would be a scroll of indistinguishable
 * labels, so the group headings do the narrowing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPicker(
    selected: RecurringExpenseCategory,
    onSelect: (RecurringExpenseCategory) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Category") },
            supportingText = { Text(selected.group.label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RecurringExpenseCategory.grouped().forEach { (group, categories) ->
                GroupLabel(group)
                categories.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category.label) },
                        onClick = {
                            onSelect(category)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(group: ExpenseGroup) {
    Text(
        group.label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrequencyPicker(selected: Frequency, onSelect: (Frequency) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("How often") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Frequency.entries.forEach { frequency ->
                DropdownMenuItem(
                    text = { Text(frequency.label) },
                    onClick = {
                        onSelect(frequency)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}
