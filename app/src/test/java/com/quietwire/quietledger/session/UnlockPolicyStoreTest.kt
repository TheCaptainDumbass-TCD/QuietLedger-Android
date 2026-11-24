package com.quietwire.quietledger.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.quietwire.quietledger.core.biometric.BiometricAvailability
import com.quietwire.quietledger.core.totp.TotpStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reconciliation between what the user asked for and what the device can
 * currently do.
 *
 * This is lockout prevention, which is why it is worth testing properly. A
 * stored preference that demands a factor the device can no longer satisfy would
 * leave the user staring at a prompt they cannot answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnlockPolicyStoreTest {

    private lateinit var store: UnlockPolicyStore
    private lateinit var totp: TotpStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Clear both prefs files so tests cannot leak into one another.
        context.getSharedPreferences("quietledger_unlock_policy", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("quietledger_totp", Context.MODE_PRIVATE)
            .edit().clear().commit()

        totp = TotpStore(context)
        store = UnlockPolicyStore(context, totp)
    }

    @Test
    fun `nothing is required by default`() {
        val policy = store.effectivePolicy(BiometricAvailability.AVAILABLE)
        assertFalse(policy.biometricRequired)
        assertFalse(policy.totpRequired)
        assertTrue(UnlockProgress(policy).satisfied)
    }

    @Test
    fun `a biometric preference survives a restart`() {
        store.setBiometricRequired(true)
        val reloaded = UnlockPolicyStore(
            ApplicationProvider.getApplicationContext(), totp
        )
        assertTrue(reloaded.policy.value.biometricRequired)
    }

    @Test
    fun `biometrics are not demanded when nothing is enrolled`() {
        store.setBiometricRequired(true)

        // The preference is still recorded...
        assertTrue(store.policy.value.biometricRequired)
        // ...but is not enforced, because the user would have no way to answer.
        assertFalse(store.effectivePolicy(BiometricAvailability.NOT_ENROLLED).biometricRequired)
        assertFalse(store.effectivePolicy(BiometricAvailability.UNAVAILABLE).biometricRequired)
        assertTrue(store.effectivePolicy(BiometricAvailability.AVAILABLE).biometricRequired)
    }

    @Test
    fun `the preference returns when the hardware does`() {
        // Removing every fingerprint must not silently erase the setting, or it
        // would have to be found and turned back on later.
        store.setBiometricRequired(true)
        assertFalse(store.effectivePolicy(BiometricAvailability.NOT_ENROLLED).biometricRequired)
        assertTrue(store.effectivePolicy(BiometricAvailability.AVAILABLE).biometricRequired)
    }

    @Test
    fun `a code is not demanded when no authenticator is enrolled`() {
        // Possible if the preference were set without completing enrolment.
        store.setTotpRequired(true)
        assertTrue(store.policy.value.totpRequired)
        assertFalse(totp.isEnabled)
        assertFalse(store.effectivePolicy(BiometricAvailability.AVAILABLE).totpRequired)
    }

    @Test
    fun `turning a factor off takes effect immediately`() {
        store.setBiometricRequired(true)
        assertTrue(store.effectivePolicy(BiometricAvailability.AVAILABLE).biometricRequired)

        store.setBiometricRequired(false)
        assertFalse(store.effectivePolicy(BiometricAvailability.AVAILABLE).biometricRequired)
    }

    @Test
    fun `the summary describes what is actually enforced`() {
        store.setBiometricRequired(true)
        assertTrue(
            store.effectivePolicy(BiometricAvailability.AVAILABLE).summary
                .contains("Fingerprint")
        )
        // With no hardware the honest summary is passphrase only.
        assertTrue(
            store.effectivePolicy(BiometricAvailability.UNAVAILABLE).summary
                .contains("Passphrase")
        )
    }
}
