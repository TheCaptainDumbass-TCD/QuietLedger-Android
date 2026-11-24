package com.quietwire.quietledger.ui.debts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Used on narrow screens only. When there is room for two columns the form
 * lives in a detail pane instead, which avoids a dialog fighting the keyboard.
 */
@Composable
fun DebtEditorDialog(
    state: DebtEditorState,
    onChange: ((DebtEditorState) -> DebtEditorState) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (state.isEditing) "Edit debt" else "Add debt") },
        text = {
            DebtForm(
                state = state,
                onChange = onChange,
                modifier = Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState())
            )
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
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    )
}
