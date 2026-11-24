package com.quietwire.quietledger.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockPolicyTest {

    @Test
    fun `no factors is satisfied immediately`() {
        val progress = UnlockProgress(UnlockPolicy.NONE)
        assertTrue(progress.satisfied)
        assertEquals("Passphrase only", UnlockPolicy.NONE.summary)
    }

    @Test
    fun `biometric only needs the fingerprint`() {
        val policy = UnlockPolicy(biometricRequired = true)
        var progress = UnlockProgress(policy)

        assertFalse(progress.satisfied)
        assertTrue(progress.biometricOutstanding)
        assertFalse(progress.totpOutstanding)

        progress = progress.withBiometric()
        assertTrue(progress.satisfied)
    }

    @Test
    fun `totp only needs the code`() {
        var progress = UnlockProgress(UnlockPolicy(totpRequired = true))

        assertFalse(progress.satisfied)
        assertTrue(progress.totpOutstanding)

        progress = progress.withTotp()
        assertTrue(progress.satisfied)
    }

    @Test
    fun `both means both, in either order`() {
        val policy = UnlockPolicy(biometricRequired = true, totpRequired = true)
        assertTrue(policy.requiresBoth)

        // Fingerprint first.
        var a = UnlockProgress(policy).withBiometric()
        assertFalse("one factor is not enough", a.satisfied)
        assertTrue(a.withTotp().satisfied)

        // Code first.
        var b = UnlockProgress(policy).withTotp()
        assertFalse("one factor is not enough", b.satisfied)
        assertTrue(b.withBiometric().satisfied)
    }

    @Test
    fun `satisfying a factor that is not required changes nothing`() {
        val progress = UnlockProgress(UnlockPolicy(totpRequired = true)).withBiometric()
        assertFalse("the code is still outstanding", progress.satisfied)
    }

    @Test
    fun `summaries describe each combination`() {
        assertEquals(
            "Fingerprint, face or device PIN",
            UnlockPolicy(biometricRequired = true).summary
        )
        assertEquals(
            "A code from your authenticator",
            UnlockPolicy(totpRequired = true).summary
        )
        assertEquals(
            "Fingerprint and a code from your authenticator",
            UnlockPolicy(biometricRequired = true, totpRequired = true).summary
        )
    }
}
