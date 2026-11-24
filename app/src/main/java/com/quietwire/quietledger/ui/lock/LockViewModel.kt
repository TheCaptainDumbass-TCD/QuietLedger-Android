package com.quietwire.quietledger.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietwire.quietledger.core.biometric.BiometricAvailability
import com.quietwire.quietledger.core.totp.Totp
import com.quietwire.quietledger.core.totp.TotpStore
import com.quietwire.quietledger.session.SessionManager
import com.quietwire.quietledger.session.SessionState
import com.quietwire.quietledger.session.UnlockPolicy
import com.quietwire.quietledger.session.UnlockPolicyStore
import com.quietwire.quietledger.session.UnlockProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LockUiState(
    val busy: Boolean = false,
    val error: String? = null,
    val passphraseRequired: Boolean = false,
    /** Which factors are still outstanding for this unlock attempt. */
    val progress: UnlockProgress = UnlockProgress(UnlockPolicy.NONE),
    val totpCode: String = "",
    val totpError: String? = null,
    /** Swaps the six digit field for a recovery code field. */
    val usingRecoveryCode: Boolean = false,
    val recoveryCode: String = ""
) {
    val awaitingTotp: Boolean get() = progress.totpOutstanding
}

@HiltViewModel
class LockViewModel @Inject constructor(
    private val session: SessionManager,
    private val policies: UnlockPolicyStore,
    private val totp: TotpStore
) : ViewModel() {

    val sessionState: StateFlow<SessionState> = session.state

    private val _ui = MutableStateFlow(LockUiState())
    val ui: StateFlow<LockUiState> = _ui.asStateFlow()

    val hasPassphrase: Boolean get() = session.hasPassphrase

    init {
        session.refresh()
    }

    /**
     * Called once the lock screen knows what the hardware can do, since the
     * effective policy depends on it. A biometric requirement with nothing
     * enrolled has to be dropped rather than enforced, or the user is locked out
     * by a stale setting.
     */
    fun startAttempt(availability: BiometricAvailability) {
        val policy = policies.effectivePolicy(availability)
        _ui.value = LockUiState(progress = UnlockProgress(policy))
    }

    fun setUp(passphrase: CharArray?) {
        _ui.value = LockUiState(busy = true)
        viewModelScope.launch {
            session.setUp(passphrase)
                .onFailure { _ui.value = LockUiState(error = it.message ?: "Setup failed.") }
                .onSuccess { _ui.value = LockUiState() }
        }
    }

    /**
     * The biometric prompt succeeded. That satisfies one gate; it does not open
     * anything on its own. The database is opened only once every required
     * factor has been met.
     */
    fun biometricSucceeded() {
        _ui.value = _ui.value.copy(progress = _ui.value.progress.withBiometric(), error = null)
        openIfAllFactorsMet()
    }

    fun updateTotpCode(code: String) {
        _ui.value = _ui.value.copy(
            totpCode = code.filter { it.isDigit() }.take(Totp.DIGITS),
            totpError = null
        )
    }

    fun updateRecoveryCode(code: String) {
        _ui.value = _ui.value.copy(recoveryCode = code, totpError = null)
    }

    fun useRecoveryCode(using: Boolean) {
        _ui.value = _ui.value.copy(usingRecoveryCode = using, totpError = null, totpCode = "")
    }

    fun submitTotpCode() {
        val state = _ui.value
        val accepted = if (state.usingRecoveryCode) {
            totp.consumeRecoveryCode(state.recoveryCode)
        } else {
            totp.verifyCode(state.totpCode)
        }

        if (!accepted) {
            _ui.value = state.copy(
                totpCode = "",
                recoveryCode = "",
                totpError = if (state.usingRecoveryCode) {
                    "That recovery code is not valid, or has already been used."
                } else {
                    "That code is not correct. If it keeps failing, check this " +
                        "phone's clock is set automatically."
                }
            )
            return
        }

        _ui.value = state.copy(
            progress = state.progress.withTotp(),
            totpCode = "",
            recoveryCode = "",
            totpError = null
        )
        openIfAllFactorsMet()
    }

    /**
     * Opens the database, but only when nothing is outstanding. Keeping this in
     * one place means a new factor cannot be added later and accidentally
     * bypassed by one of several unlock paths.
     */
    private fun openIfAllFactorsMet() {
        if (!_ui.value.progress.satisfied) return

        _ui.value = _ui.value.copy(busy = true)
        viewModelScope.launch {
            session.unlockWithDeviceKey().onFailure {
                _ui.value = _ui.value.copy(
                    busy = false,
                    error = it.message ?: "Could not unlock.",
                    passphraseRequired = session.hasPassphrase
                )
            }
            if (session.state.value is SessionState.Unlocked) _ui.value = LockUiState()
        }
    }

    /**
     * The passphrase unwraps the key directly, so it is the one path that does
     * not sit behind the gates. It has to stay that way: it is the recovery
     * route when the secure element has invalidated its key, and gating it
     * behind a factor the user may have lost would make the data unrecoverable.
     */
    fun unlockWithPassphrase(passphrase: CharArray) {
        _ui.value = _ui.value.copy(busy = true, error = null)
        viewModelScope.launch {
            session.unlockWithPassphrase(passphrase).onFailure {
                _ui.value = _ui.value.copy(
                    busy = false,
                    error = it.message ?: "That passphrase is not correct.",
                    passphraseRequired = true
                )
            }
            if (session.state.value is SessionState.Unlocked) _ui.value = LockUiState()
        }
    }

    /** Closes the database and returns to the lock screen. */
    fun lock() {
        session.lock()
        _ui.value = LockUiState()
    }

    fun reportBiometricFailure(message: String) {
        _ui.value = _ui.value.copy(
            busy = false,
            error = message,
            passphraseRequired = session.hasPassphrase
        )
    }

    fun showPassphraseEntry() {
        _ui.value = _ui.value.copy(passphraseRequired = true, error = null)
    }
}
