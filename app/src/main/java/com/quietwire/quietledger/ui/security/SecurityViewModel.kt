package com.quietwire.quietledger.ui.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietwire.quietledger.core.biometric.BiometricAvailability
import com.quietwire.quietledger.core.totp.Base32
import com.quietwire.quietledger.core.totp.Totp
import com.quietwire.quietledger.core.totp.TotpStore
import com.quietwire.quietledger.session.UnlockPolicy
import com.quietwire.quietledger.session.UnlockPolicyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Enrolment is a sequence, not a toggle.
 *
 * The secret is generated but deliberately **not stored** until the user has
 * typed back a working code. Saving on "show me the QR" would let someone who
 * mis-scanned, or who closed the screen halfway, end up with a factor switched
 * on that they cannot satisfy.
 */
sealed interface EnrolmentStep {
    data object NotEnrolling : EnrolmentStep

    /** Secret generated and on screen, nothing persisted yet. */
    data class ShowSecret(
        val secret: ByteArray,
        val provisioningUri: String,
        val manualKey: String,
        val entered: String = "",
        val error: String? = null
    ) : EnrolmentStep {
        // ByteArray in a data class needs these, otherwise equality is identity
        // and Compose will not recompose correctly on a change.
        override fun equals(other: Any?): Boolean =
            this === other || (other is ShowSecret &&
                secret.contentEquals(other.secret) && entered == other.entered &&
                error == other.error)

        override fun hashCode(): Int =
            secret.contentHashCode() * 31 + entered.hashCode() + (error?.hashCode() ?: 0)
    }

    /** Verified and saved. Recovery codes are shown exactly once. */
    data class ShowRecoveryCodes(val codes: List<String>) : EnrolmentStep
}

data class SecurityUiState(
    val policy: UnlockPolicy = UnlockPolicy.NONE,
    val biometricAvailability: BiometricAvailability = BiometricAvailability.UNAVAILABLE,
    val totpEnrolled: Boolean = false,
    val recoveryCodesRemaining: Int = 0,
    val enrolment: EnrolmentStep = EnrolmentStep.NotEnrolling,
    /** Drives the countdown ring next to the sample code during enrolment. */
    val secondsRemaining: Int = Totp.PERIOD_SECONDS.toInt(),
    val currentSampleCode: String = ""
)

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val totp: TotpStore,
    private val policies: UnlockPolicyStore
) : ViewModel() {

    private val _ui = MutableStateFlow(SecurityUiState())
    val ui: StateFlow<SecurityUiState> = _ui.asStateFlow()

    fun refresh(availability: BiometricAvailability) {
        _ui.value = _ui.value.copy(
            policy = policies.policy.value,
            biometricAvailability = availability,
            totpEnrolled = totp.isEnabled,
            recoveryCodesRemaining = totp.unusedRecoveryCodeCount
        )
    }

    fun setBiometricRequired(required: Boolean) {
        policies.setBiometricRequired(required)
        _ui.value = _ui.value.copy(policy = policies.policy.value)
    }

    fun beginEnrolment() {
        val secret = totp.beginEnrolment()
        _ui.value = _ui.value.copy(
            enrolment = EnrolmentStep.ShowSecret(
                secret = secret,
                provisioningUri = Totp.provisioningUri(secret, accountName = ACCOUNT_LABEL),
                manualKey = Base32.formatForDisplay(Base32.encode(secret))
            )
        )
        startSampleTicker()
    }

    /**
     * Shows the code the app currently expects, next to a countdown. Purely a
     * setup aid: if the authenticator disagrees, the secret was mis-entered or
     * the phone's clock is adrift, and seeing both makes that obvious instead of
     * producing a bare "wrong code".
     */
    private fun startSampleTicker() {
        viewModelScope.launch {
            while (_ui.value.enrolment is EnrolmentStep.ShowSecret) {
                val step = _ui.value.enrolment as? EnrolmentStep.ShowSecret ?: break
                val now = System.currentTimeMillis()
                _ui.value = _ui.value.copy(
                    currentSampleCode = Totp.code(step.secret, now),
                    secondsRemaining = Totp.secondsRemaining(now)
                )
                delay(1000)
            }
        }
    }

    fun updateEnteredCode(code: String) {
        val step = _ui.value.enrolment as? EnrolmentStep.ShowSecret ?: return
        _ui.value = _ui.value.copy(
            enrolment = step.copy(entered = code.filter { it.isDigit() }.take(Totp.DIGITS), error = null)
        )
    }

    fun confirmEnrolment() {
        val step = _ui.value.enrolment as? EnrolmentStep.ShowSecret ?: return

        if (!Totp.verify(step.secret, step.entered, System.currentTimeMillis())) {
            _ui.value = _ui.value.copy(
                enrolment = step.copy(
                    entered = "",
                    error = "That code did not match. Check the app scanned the right " +
                        "code, and that this phone's clock is set automatically."
                )
            )
            return
        }

        val codes = totp.completeEnrolment(step.secret)
        policies.setTotpRequired(true)
        step.secret.fill(0)

        _ui.value = _ui.value.copy(
            enrolment = EnrolmentStep.ShowRecoveryCodes(codes),
            totpEnrolled = true,
            policy = policies.policy.value,
            recoveryCodesRemaining = codes.size
        )
    }

    fun cancelEnrolment() {
        (_ui.value.enrolment as? EnrolmentStep.ShowSecret)?.secret?.fill(0)
        _ui.value = _ui.value.copy(enrolment = EnrolmentStep.NotEnrolling)
    }

    fun finishRecoveryCodes() {
        _ui.value = _ui.value.copy(enrolment = EnrolmentStep.NotEnrolling)
    }

    fun disableTotp() {
        policies.clearTotp()
        _ui.value = _ui.value.copy(
            totpEnrolled = false,
            policy = policies.policy.value,
            recoveryCodesRemaining = 0
        )
    }

    private companion object {
        const val ACCOUNT_LABEL = "My ledger"
    }
}
