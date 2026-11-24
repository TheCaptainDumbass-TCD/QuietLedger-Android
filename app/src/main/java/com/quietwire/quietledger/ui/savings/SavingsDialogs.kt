package com.quietwire.quietledger.ui.savings

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.quietwire.quietledger.data.model.SavingsPurpose

@Composable
fun SavingsEditorDialog(
    state: SavingsEditorState,
    onChange: ((SavingsEditorState) -> SavingsEditorState) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onClose: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (state.isEditing) "Edit account" else "Add savings account") },
        text = {
            Column(
                Modifier.imePadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { v -> onChange { it.copy(name = v) } },
                    label = { Text("Name") },
                    placeholder = { Text("Emergency fund") },
                    isError = state.name.isNotEmpty() && state.nameError != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.institution,
                    onValueChange = { v -> onChange { it.copy(institution = v) } },
                    label = { Text("Bank") },
                    // Stated explicitly, because a field called "bank" next to a
                    // balance invites typing an account number into it.
                    supportingText = { Text("Optional. Never put an account number here.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                PurposePicker(
                    selected = state.purpose,
                    onSelect = { v -> onChange { s -> s.copy(purpose = v) } }
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
                    value = state.apy,
                    onValueChange = { v -> onChange { it.copy(apy = v) } },
                    label = { Text("Interest rate") },
                    suffix = { Text("% APY") },
                    isError = state.apyError != null,
                    supportingText = {
                        state.apyError?.let { Text(it) }
                            ?: state.projectedInterest?.let {
                                Text("About ${it.format()} a year at this balance")
                            }
                            ?: Text("Optional")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.goal,
                    onValueChange = { v -> onChange { it.copy(goal = v) } },
                    label = { Text("Goal") },
                    prefix = { Text("$") },
                    isError = state.goalError != null,
                    supportingText = {
                        state.goalError?.let { Text(it) } ?: Text("Optional, shows progress")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.notes,
                    onValueChange = { v -> onChange { it.copy(notes = v) } },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = state.isValid) { Text("Save") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (onClose != null) {
                    // Softer than Delete: leaves net worth while keeping the
                    // record of what was saved.
                    TextButton(onClick = onClose) { Text("Close") }
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    )
}

/**
 * A deposit or withdrawal, recorded as an event.
 *
 * Asking for the amount moved rather than the new balance means the journal
 * records what happened, not just where things ended up. A balance that fell
 * then has an answer attached.
 */
@Composable
fun MovementDialog(
    state: MovementState,
    onChange: ((MovementState) -> MovementState) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (state.isDeposit) "Deposit" else "Withdraw") },
        text = {
            Column(
                Modifier.imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    state.accountName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    value = state.amount,
                    onValueChange = { v -> onChange { it.copy(amount = v) } },
                    label = { Text("Amount") },
                    prefix = { Text("$") },
                    isError = state.amount.isNotEmpty() && !state.isValid,
                    supportingText = {
                        if (state.amount.isNotEmpty() && !state.isValid) {
                            Text("Enter an amount greater than zero")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.note,
                    onValueChange = { v -> onChange { it.copy(note = v) } },
                    label = { Text("What for") },
                    supportingText = { Text("Optional, but it is what makes the history useful") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = state.isValid) {
                Text(if (state.isDeposit) "Deposit" else "Withdraw")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PurposePicker(selected: SavingsPurpose, onSelect: (SavingsPurpose) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("What it is for") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SavingsPurpose.entries.forEach { purpose ->
                DropdownMenuItem(
                    text = { Text(purpose.label) },
                    onClick = {
                        onSelect(purpose)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}
