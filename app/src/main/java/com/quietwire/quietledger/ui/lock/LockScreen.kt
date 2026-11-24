package com.quietwire.quietledger.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import com.quietwire.quietledger.core.totp.Totp
import androidx.compose.ui.unit.dp
import com.quietwire.quietledger.ui.common.PassphraseKeyboardOptions
import com.quietwire.quietledger.core.biometric.BiometricAvailability

@Composable
fun LockScreen(
    state: LockUiState,
    onUnlockWithDeviceKey: () -> Unit,
    onUnlockWithPassphrase: (CharArray) -> Unit,
    onUsePassphrase: () -> Unit,
    canUsePassphrase: Boolean,
    modifier: Modifier = Modifier,
    biometricAvailability: BiometricAvailability = BiometricAvailability.UNAVAILABLE,
    onBiometricPrompt: () -> Unit = {},
    onTotpCodeChange: (String) -> Unit = {},
    onRecoveryCodeChange: (String) -> Unit = {},
    onUseRecoveryCode: (Boolean) -> Unit = {},
    onSubmitTotp: () -> Unit = {}
) {
    val biometricsUsable = biometricAvailability == BiometricAvailability.AVAILABLE
    CentredPanel(modifier) {
        Text("QuietLedger", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your ledger is locked.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
        }

        if (state.busy) {
            CircularProgressIndicator()
            return@CentredPanel
        }

        // The code step comes after any biometric gate and before the database
        // opens. Shown only when the authenticator factor is still outstanding.
        if (state.awaitingTotp && !state.passphraseRequired) {
            TotpStep(
                state = state,
                onCodeChange = onTotpCodeChange,
                onRecoveryChange = onRecoveryCodeChange,
                onUseRecovery = onUseRecoveryCode,
                onSubmit = onSubmitTotp
            )
            if (canUsePassphrase) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onUsePassphrase) { Text("Use passphrase instead") }
            }
            return@CentredPanel
        }

        if (state.passphraseRequired) {
            var passphrase by remember { mutableStateOf("") }
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Passphrase") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = PassphraseKeyboardOptions.copy(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = { onUnlockWithPassphrase(passphrase.toCharArray()) }
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onUnlockWithPassphrase(passphrase.toCharArray()) },
                enabled = passphrase.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Unlock") }
        } else {
            if (biometricsUsable) {
                // Offered automatically on arrival, so the common case is one
                // touch rather than a tap then a touch.
                LaunchedEffect(Unit) { onBiometricPrompt() }

                Button(onClick = onBiometricPrompt, modifier = Modifier.fillMaxWidth()) {
                    Text("Unlock")
                }
            } else {
                Button(onClick = onUnlockWithDeviceKey, modifier = Modifier.fillMaxWidth()) {
                    Text("Unlock")
                }
                if (biometricAvailability == BiometricAvailability.NOT_ENROLLED) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Set up a screen lock or fingerprint in system settings for a " +
                            "stronger lock on this app.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (canUsePassphrase) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onUsePassphrase) { Text("Use passphrase instead") }
            }
        }
    }
}

@Composable
fun SetupScreen(
    state: LockUiState,
    onFinish: (CharArray?) -> Unit,
    modifier: Modifier = Modifier
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val tooShort = passphrase.isNotEmpty() && passphrase.length < MIN_PASSPHRASE
    val mismatch = confirm.isNotEmpty() && passphrase != confirm
    val canContinue = passphrase.length >= MIN_PASSPHRASE && passphrase == confirm

    CentredPanel(modifier) {
        Text("Welcome to QuietLedger", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "Your data is encrypted on this device and never leaves it.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Set a passphrase. It is the only way back into your data if this " +
                "phone is lost, reset, or its secure hardware is wiped. Without " +
                "one, that data cannot be recovered by anyone, including you.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
        }

        if (state.busy) {
            CircularProgressIndicator()
            return@CentredPanel
        }

        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Passphrase") },
            supportingText = {
                Text(if (tooShort) "At least $MIN_PASSPHRASE characters" else "$MIN_PASSPHRASE or more characters")
            },
            isError = tooShort,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text("Confirm passphrase") },
            supportingText = { if (mismatch) Text("Passphrases do not match") },
            isError = mismatch,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onFinish(passphrase.toCharArray()) },
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Create my ledger") }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { onFinish(null) }) {
            Text("Skip, and accept there is no way to recover")
        }
    }
}

@Composable
private fun CentredPanel(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content
            )
        }
    }
}

private const val MIN_PASSPHRASE = 10

@Composable
private fun TotpStep(
    state: LockUiState,
    onCodeChange: (String) -> Unit,
    onRecoveryChange: (String) -> Unit,
    onUseRecovery: (Boolean) -> Unit,
    onSubmit: () -> Unit
) {
    Text(
        if (state.usingRecoveryCode) "Enter a recovery code" else "Enter your code",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(Modifier.height(8.dp))
    Text(
        if (state.usingRecoveryCode) {
            "One of the codes you saved when you set up the authenticator. Each works once."
        } else {
            "The six digit code from your authenticator app."
        },
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(16.dp))

    if (state.usingRecoveryCode) {
        OutlinedTextField(
            value = state.recoveryCode,
            onValueChange = onRecoveryChange,
            label = { Text("Recovery code") },
            isError = state.totpError != null,
            supportingText = { state.totpError?.let { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        OutlinedTextField(
            value = state.totpCode,
            onValueChange = onCodeChange,
            label = { Text("Six digit code") },
            isError = state.totpError != null,
            supportingText = { state.totpError?.let { Text(it) } },
            singleLine = true,
            // NumberPassword rather than Number: it keeps the code out of the
            // keyboard's suggestion strip and personalised learning.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Go
            ),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            modifier = Modifier.fillMaxWidth()
        )
    }

    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onSubmit,
        enabled = if (state.usingRecoveryCode) {
            state.recoveryCode.isNotBlank()
        } else {
            state.totpCode.length == Totp.DIGITS
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Continue") }

    Spacer(Modifier.height(4.dp))
    TextButton(onClick = { onUseRecovery(!state.usingRecoveryCode) }) {
        Text(
            if (state.usingRecoveryCode) "Use my authenticator" else "Lost your authenticator?"
        )
    }
}
