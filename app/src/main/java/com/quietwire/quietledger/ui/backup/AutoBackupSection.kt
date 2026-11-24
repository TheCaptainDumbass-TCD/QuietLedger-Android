package com.quietwire.quietledger.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WRITTEN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

/**
 * Choosing where automatic backups go.
 *
 * One folder, picked once. Put it in a Syncthing share and backups reach the
 * server with the app having no network access at all: Syncthing moves the
 * file and only ever sees a blob sealed to a key it does not hold.
 */
@Composable
fun AutoBackupSection(viewModel: AutoBackupViewModel) {
    val state by viewModel.ui.collectAsStateWithLifecycle()

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::setFolder) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Automatic backups", style = MaterialTheme.typography.titleSmall)

            Text(
                if (state.configured) {
                    "Written to ${state.folderLabel} when you leave the app."
                } else {
                    "Off. Choose a folder and a backup is written whenever you " +
                        "leave the app, with nothing to type."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!state.serverKeyAvailable) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "This build has no server key, so it cannot seal a backup. " +
                        "Automatic backups are unavailable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            state.lastWrittenAt?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Last written ${WRITTEN.format(it.atZone(ZoneId.systemDefault()))}" +
                        ", " + ago(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Shown because the phone cannot read its own sealed backups, so a
            // failure here is invisible from this end unless it is stated.
            state.lastError?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Last attempt failed: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { pickFolder.launch(null) },
                    enabled = state.serverKeyAvailable
                ) {
                    Text(if (state.configured) "Change folder" else "Choose folder")
                }

                if (state.configured) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = viewModel::writeNow) { Text("Back up now") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = viewModel::clearFolder) { Text("Turn off") }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "No passphrase. Each backup is sealed to the server's key, so " +
                    "only that server can open it. This phone cannot read them " +
                    "back, which is why the viewer reports their age and contents.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun ago(instant: Instant): String {
    val minutes = Duration.between(instant, Instant.now()).toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes minutes ago"
        minutes < 1440 -> "${minutes / 60} hours ago"
        else -> "${minutes / 1440} days ago"
    }
}
