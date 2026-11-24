package com.quietwire.quietledger.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import android.content.Intent
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.quietwire.quietledger.ui.common.copySensitive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quietwire.quietledger.core.totp.Totp
import com.quietwire.quietledger.ui.common.QrCode
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.time.LocalDate

@Composable
fun TotpEnrolmentDialog(
    step: EnrolmentStep.ShowSecret,
    sampleCode: String,
    secondsRemaining: Int,
    onCodeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add an authenticator") },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val context = LocalContext.current
                var copied by remember { mutableStateOf(false) }

                // Same-phone setup comes first, because that is the common case
                // and a phone cannot photograph its own screen. Handing the
                // otpauth URI to the authenticator directly is also the only
                // path where the secret is never transcribed or displayed.
                val handoff = remember(step.provisioningUri) {
                    Intent(Intent.ACTION_VIEW, step.provisioningUri.toUri())
                }
                val canHandOff = remember(handoff) {
                    context.packageManager.queryIntentActivities(handoff, 0).isNotEmpty()
                }

                // Copy leads, because it is the only option that is certain to
                // work. Handing over the otpauth URI is nicer when the receiving
                // app supports it, and some authenticators register for the
                // scheme without actually importing from it, which looks like
                // nothing happening. Not worth making that the headline action.
                Text(
                    "Copy this key and paste it into your authenticator app.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    step.manualKey,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        // Grouping is for reading, not for pasting.
                        context.copySensitive("QuietLedger key", step.manualKey.replace(" ", ""))
                        copied = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (copied) "Key copied" else "Copy key") }

                if (canHandOff) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { context.startActivity(handoff) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Try opening in an authenticator") }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Some apps fill the key in automatically. If yours opens but " +
                            "stays empty, come back and copy the key instead.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    "Or scan this from another device:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))

                // White plate behind the code regardless of theme. A QR rendered
                // in dark theme colours is unreliable to scan.
                Surface(color = Color.White, shape = MaterialTheme.shapes.medium) {
                    QrCode(content = step.provisioningUri, modifier = Modifier.padding(12.dp))
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    "Then type the code it shows, to confirm it worked.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = step.entered,
                    onValueChange = onCodeChange,
                    label = { Text("Six digit code") },
                    isError = step.error != null,
                    supportingText = { step.error?.let { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )

                // The expected code, shown alongside a countdown. If the
                // authenticator disagrees the cause is almost always a clock
                // that is not set automatically, and seeing both makes that
                // diagnosable instead of a bare "wrong code".
                Spacer(Modifier.height(16.dp))
                Text(
                    "This phone currently expects $sampleCode",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { secondsRemaining / Totp.PERIOD_SECONDS.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    "changes in ${secondsRemaining}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = step.entered.length == Totp.DIGITS
            ) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

/**
 * Shown once, immediately after enrolment succeeds, and never retrievable
 * afterwards because only salted hashes are kept.
 *
 * Copying and saving are both offered deliberately. Telling someone to write
 * down eight sixteen-character codes from a phone screen, with no way to export
 * them, is the kind of instruction people skip. A skipped backup means a lost
 * authenticator becomes a passphrase-only recovery, which is exactly the
 * situation the codes exist to avoid.
 */
@Composable
fun RecoveryCodesDialog(
    codes: List<String>,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    val asText = remember(codes) {
        buildString {
            appendLine("QuietLedger recovery codes")
            appendLine()
            appendLine("Each code works once. Use one in place of your authenticator")
            appendLine("code if you lose access to your authenticator app.")
            appendLine()
            codes.forEach { appendLine(it) }
            appendLine()
            appendLine("Generated ${LocalDate.now()}")
        }
    }

    // Storage Access Framework, so these can land in Proton Drive, Google Drive
    // or local storage without this app integrating with any of them.
    val saveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(asText.toByteArray())
                }
            }.onSuccess { saved = true }
        }
    }

    AlertDialog(
        onDismissRequest = { /* must be acknowledged, not dismissed by accident */ },
        title = { Text("Save your recovery codes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "If you lose your authenticator, these are the only way back in " +
                        "other than your passphrase. Each one works once.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "They are not stored anywhere you can read them again, so save " +
                        "them now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(16.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    codes.forEach {
                        Text(it, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }

                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    onClick = {
                        context.copySensitive("QuietLedger recovery codes", asText)
                        copied = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (copied) "Copied to clipboard" else "Copy all codes") }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { saveFile.launch("quietledger-recovery-codes.txt") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (saved) "Saved to file" else "Save to a file...") }

                Spacer(Modifier.height(8.dp))
                Text(
                    "A saved file is plain text and is not encrypted, so put it " +
                        "somewhere you would keep a password, not in your downloads " +
                        "folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDone) {
                Text(if (copied || saved) "Done" else "I have saved them")
            }
        }
    )
}
