package com.quietwire.quietledger.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.quietwire.quietledger.ui.common.PassphraseKeyboardOptions
import com.quietwire.quietledger.data.backup.ImportSummary
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val EXPORT_STAMP = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

@Composable
fun ExportPassphraseDialog(
    busy: Boolean,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val tooShort = passphrase.isNotEmpty() && passphrase.length < BackupViewModel.MIN_PASSPHRASE
    val mismatch = confirm.isNotEmpty() && passphrase != confirm
    val valid = passphrase.length >= BackupViewModel.MIN_PASSPHRASE && passphrase == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Back up your ledger") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "The file is encrypted with a passphrase of your choosing. It can be " +
                        "anything, and it does not have to match the one you unlock the app " +
                        "with.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "There is no recovery. Lose the passphrase and the file is unreadable, " +
                        "including by you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                if (busy) {
                    CircularProgressIndicator()
                    return@Column
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    supportingText = {
                        Text(
                            if (tooShort) "At least ${BackupViewModel.MIN_PASSPHRASE} characters"
                            else "${BackupViewModel.MIN_PASSPHRASE} or more characters"
                        )
                    },
                    isError = tooShort,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = PassphraseKeyboardOptions,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Confirm") },
                    supportingText = { if (mismatch) Text("Does not match") },
                    isError = mismatch,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = PassphraseKeyboardOptions,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(passphrase) }, enabled = valid && !busy) {
                Text("Choose location")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ImportPassphraseDialog(
    busy: Boolean,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var passphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore from a backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter the passphrase the backup was created with.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (busy) {
                    CircularProgressIndicator()
                    return@Column
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = PassphraseKeyboardOptions,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase) },
                enabled = passphrase.isNotEmpty() && !busy
            ) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Shown before anything is overwritten. Importing replaces everything, so the
 * user gets to see what they are swapping in first.
 */
@Composable
fun ConfirmImportDialog(
    summary: ImportSummary,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replace everything?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                summary.exportedAt?.let {
                    Text(
                        "Taken on ${it.atZone(ZoneId.systemDefault()).format(EXPORT_STAMP)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    "Contains ${summary.debts} debts, " +
                        "${summary.recurringExpenses} recurring expenses and " +
                        "${summary.balanceChanges} history entries.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Everything currently in the app will be replaced. This cannot be undone, " +
                        "so back up first if the current data matters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) { Text("Replace") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun BackupResultDialog(message: String, isError: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isError) "That did not work" else "Done") },
        text = {
            Text(
                message,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}
