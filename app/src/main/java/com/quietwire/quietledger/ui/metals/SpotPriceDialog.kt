package com.quietwire.quietledger.ui.metals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.quietwire.quietledger.domain.model.Metal

/**
 * Entering spot prices by hand.
 *
 * Deliberately not fetched. The app declares no `INTERNET` permission, so the
 * operating system itself guarantees it cannot send financial data anywhere.
 * Taking that permission to look up three numbers would downgrade an enforced
 * guarantee to a promise, which is a poor trade for a holding that is not being
 * traded.
 */
@Composable
fun SpotPriceDialog(
    state: SpotPriceEditorState,
    heldMetals: Set<Metal>,
    onChange: (Metal, String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Spot prices") },
        text = {
            Column(
                Modifier.imePadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Per troy ounce, all three. Look them up wherever you prefer " +
                        "and type them in; nothing leaves this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Only metals actually held, so the dialog does not ask for a
                // price that could not be used for anything.
                Metal.entries.filter { it in heldMetals }.forEach { metal ->
                    val error = state.error(metal)
                    OutlinedTextField(
                        value = state.entries[metal] ?: "",
                        onValueChange = { onChange(metal, it) },
                        label = { Text("${metal.label} per troy ounce") },
                        prefix = { Text("$") },
                        suffix = { Text("/oz") },
                        isError = error != null,
                        supportingText = {
                            error?.let { Text(it) }
                                ?: Text("Leave blank to remove it")
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(
                    "A market value is only shown once every metal you hold has " +
                        "a price. Valuing part of it at market and the rest at " +
                        "cost would be a figure you could not check.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = state.isValid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}
