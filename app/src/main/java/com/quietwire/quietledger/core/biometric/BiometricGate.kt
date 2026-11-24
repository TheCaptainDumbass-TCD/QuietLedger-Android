package com.quietwire.quietledger.core.biometric

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity

/** What the device can actually offer, so the UI does not promise what is absent. */
enum class BiometricAvailability {
    /** A fingerprint, face or device PIN can be used. */
    AVAILABLE,

    /** Hardware exists but nothing is enrolled. */
    NOT_ENROLLED,

    /** No usable hardware or secure lock screen. */
    UNAVAILABLE
}

/**
 * Wraps the biometric prompt.
 *
 * An important limitation, stated plainly because it is easy to overestimate
 * what this buys: the Keystore key that wraps the database key is **not** bound
 * to user authentication. So this is an application level gate, not a
 * cryptographic one. It stops someone picking up an unlocked phone and reading
 * your balances. It does not stop someone who can run code as this app.
 *
 * Binding the key to authentication, via `setUserAuthenticationRequired`, is
 * strictly stronger and is the obvious next step. It needs the unlock to go
 * through a `CryptoObject` so the Keystore itself enforces the check, and it
 * needs testing on hardware, because getting it wrong locks the user out. The
 * recovery path already exists: a passphrase unlock re-wraps the key. Noted in
 * docs/release-readiness.md rather than half done here.
 */
object BiometricGate {

    private const val ALLOWED = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    fun availability(context: Context): BiometricAvailability =
        when (BiometricManager.from(context).canAuthenticate(ALLOWED)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NOT_ENROLLED
            else -> BiometricAvailability.UNAVAILABLE
        }

    /**
     * @param onFailed called for a genuine problem, with a message worth showing.
     * @param onCancelled called when the user backed out, which is not an error
     *        and should not produce a scary message.
     */
    fun prompt(
        activity: FragmentActivity,
        onSucceeded: () -> Unit,
        onFailed: (String) -> Unit,
        onCancelled: () -> Unit
    ) {
        val prompt = BiometricPrompt(
            activity,
            androidx.core.content.ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSucceeded()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    when (code) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED -> onCancelled()

                        else -> onFailed(message.toString())
                    }
                }
                // onAuthenticationFailed, a single rejected fingerprint, is
                // deliberately not forwarded. The prompt shows its own feedback
                // and lets the user try again.
            }
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock QuietLedger")
                .setSubtitle("Your ledger is encrypted on this device")
                .setAllowedAuthenticators(ALLOWED)
                .build()
        )
    }
}
