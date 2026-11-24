package com.quietwire.quietledger.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quietwire.quietledger.ui.backup.AutoBackupViewModel
import com.quietwire.quietledger.ui.backup.AutoBackupSection
import com.quietwire.quietledger.core.biometric.BiometricAvailability
import com.quietwire.quietledger.ui.theme.moneyColors

/**
 * Where the unlock factors are turned on and off.
 *
 * The copy here is deliberately unglamorous about what these do. Both factors
 * gate the app rather than the encryption, and telling a user they have
 * "two-factor authentication" on their finances implies a guarantee that is not
 * being made.
 */
@Composable
fun SecurityScreen(
    state: SecurityUiState,
    onBiometricChange: (Boolean) -> Unit,
    onSetUpAuthenticator: () -> Unit,
    onDisableAuthenticator: () -> Unit,
    autoBackup: AutoBackupViewModel,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Placed on Security rather than behind the backup menu because it is a
        // standing setting, not an action. Everything else under Back up is
        // something you do once.
        AutoBackupSection(autoBackup)

        Text(
            "Your ledger is always encrypted on this device. These settings control " +
                "what is asked for before it opens.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 560.dp)
        )

        SettingCard(
            title = "Fingerprint, face or device PIN",
            body = when (state.biometricAvailability) {
                BiometricAvailability.AVAILABLE ->
                    "Ask for your fingerprint when opening the app."
                BiometricAvailability.NOT_ENROLLED ->
                    "Set up a screen lock or fingerprint in system settings to use this."
                BiometricAvailability.UNAVAILABLE ->
                    "This device has no usable biometric hardware or screen lock."
            },
            trailing = {
                Switch(
                    checked = state.policy.biometricRequired,
                    onCheckedChange = onBiometricChange,
                    enabled = state.biometricAvailability == BiometricAvailability.AVAILABLE
                )
            }
        )

        SettingCard(
            title = "Authenticator app",
            body = if (state.totpEnrolled) {
                "A six digit code is required. " +
                    "${state.recoveryCodesRemaining} recovery codes remaining."
            } else {
                "Ask for a code from Proton Pass, Aegis, or any authenticator app. " +
                    "Useful if you would rather not use biometrics."
            },
            trailing = {
                if (state.totpEnrolled) {
                    TextButton(onClick = onDisableAuthenticator) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Button(onClick = onSetUpAuthenticator) { Text("Set up") }
                }
            }
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("What these actually protect", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your data is encrypted with a key held in this phone's secure " +
                        "hardware, and with your passphrase. That is what keeps it " +
                        "unreadable if the phone is lost or the storage is removed.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "The two settings above are a lock on the door rather than a second " +
                        "lock on the safe. They stop someone picking up your unlocked " +
                        "phone and reading your finances. Your passphrase is always " +
                        "available, because without it a reset phone would mean the data " +
                        "is gone for good.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    body: String,
    trailing: @Composable () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.widthIn(min = 12.dp))
            trailing()
        }
    }
}
