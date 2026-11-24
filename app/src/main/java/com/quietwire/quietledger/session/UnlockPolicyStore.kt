package com.quietwire.quietledger.session

import android.content.Context
import androidx.core.content.edit
import com.quietwire.quietledger.core.biometric.BiometricAvailability
import com.quietwire.quietledger.core.totp.TotpStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which unlock factors the user has turned on.
 *
 * Stored separately from the TOTP secret, because the two have different
 * lifetimes: a secret can exist while the factor is switched off, and the
 * biometric preference has nothing to do with TOTP at all.
 *
 * The stored preference is only ever a request. [effectivePolicy] reconciles it
 * with what the device can currently do, so a user who turns on biometrics and
 * later removes every fingerprint is not locked out of their own ledger by a
 * stale setting.
 */
@Singleton
class UnlockPolicyStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val totp: TotpStore
) {

    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _policy = MutableStateFlow(readStored())
    val policy: StateFlow<UnlockPolicy> = _policy.asStateFlow()

    private fun readStored() = UnlockPolicy(
        biometricRequired = prefs.getBoolean(KEY_BIOMETRIC, false),
        totpRequired = prefs.getBoolean(KEY_TOTP, false)
    )

    /**
     * What should actually be demanded right now.
     *
     * A factor is dropped if it cannot be satisfied: biometrics with nothing
     * enrolled, or TOTP with no secret stored. Silently demanding an impossible
     * factor would be a lockout, and the passphrase is always available anyway,
     * so nothing is weakened by being honest about it.
     */
    fun effectivePolicy(biometricAvailability: BiometricAvailability): UnlockPolicy {
        val stored = _policy.value
        return UnlockPolicy(
            biometricRequired = stored.biometricRequired &&
                biometricAvailability == BiometricAvailability.AVAILABLE,
            totpRequired = stored.totpRequired && totp.isEnabled
        )
    }

    fun setBiometricRequired(required: Boolean) {
        prefs.edit(commit = true) { putBoolean(KEY_BIOMETRIC, required) }
        _policy.value = readStored()
    }

    fun setTotpRequired(required: Boolean) {
        prefs.edit(commit = true) { putBoolean(KEY_TOTP, required) }
        _policy.value = readStored()
    }

    /** Called when the authenticator is removed, so the factor cannot linger. */
    fun clearTotp() {
        totp.disable()
        setTotpRequired(false)
    }

    companion object {
        private const val PREFS_NAME = "quietledger_unlock_policy"
        private const val KEY_BIOMETRIC = "biometric_required"
        private const val KEY_TOTP = "totp_required"
    }
}
