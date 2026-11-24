package com.quietwire.quietledger.core.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpTest {

    /**
     * RFC 6238 Appendix B. The SHA-1 vectors use the ASCII secret
     * "12345678901234567890" and eight digit codes.
     *
     * This is the test that matters. A TOTP implementation that is subtly wrong
     * still emits plausible six digit codes, and the first sign of trouble would
     * otherwise be a real authenticator app disagreeing.
     */
    private val rfcSecret = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    @Test
    fun `matches the RFC 6238 test vectors`() {
        val vectors = listOf(
            59L to "94287082",
            1111111109L to "07081804",
            1111111111L to "14050471",
            1234567890L to "89005924",
            2000000000L to "69279037",
            20000000000L to "65353130"
        )
        vectors.forEach { (seconds, expected) ->
            assertEquals(
                "at T=$seconds",
                expected,
                Totp.code(rfcSecret, timeMillis = seconds * 1000, digits = 8)
            )
        }
    }

    @Test
    fun `produces six digits by default`() {
        val code = Totp.code(Totp.generateSecret(), System.currentTimeMillis())
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test
    fun `keeps leading zeros`() {
        // Truncating an int would drop them, which breaks roughly one code in ten.
        var found = false
        val secret = Totp.generateSecret()
        for (window in 0 until 2000) {
            if (Totp.code(secret, window * 30_000L).startsWith("0")) {
                found = true
                assertEquals(6, Totp.code(secret, window * 30_000L).length)
                break
            }
        }
        assertTrue("expected to encounter a code starting with zero", found)
    }

    @Test
    fun `the code is stable within a window and changes between them`() {
        val secret = Totp.generateSecret()
        val base = 1_700_000_000_000L - (1_700_000_000_000L % 30_000L)

        assertEquals(Totp.code(secret, base), Totp.code(secret, base + 29_999))
        assertTrue(Totp.code(secret, base) != Totp.code(secret, base + 30_000))
    }

    @Test
    fun `verify accepts the current code`() {
        val secret = Totp.generateSecret()
        val now = System.currentTimeMillis()
        assertTrue(Totp.verify(secret, Totp.code(secret, now), now))
    }

    @Test
    fun `verify tolerates one window of clock drift either way`() {
        val secret = Totp.generateSecret()
        val now = 1_700_000_000_000L

        assertTrue(Totp.verify(secret, Totp.code(secret, now - 30_000), now))
        assertTrue(Totp.verify(secret, Totp.code(secret, now + 30_000), now))
        // Two windows out is too far.
        assertFalse(Totp.verify(secret, Totp.code(secret, now - 90_000), now))
        assertFalse(Totp.verify(secret, Totp.code(secret, now + 90_000), now))
    }

    @Test
    fun `verify rejects a wrong code`() {
        val secret = Totp.generateSecret()
        val now = System.currentTimeMillis()
        val correct = Totp.code(secret, now)
        val wrong = if (correct == "000000") "111111" else "000000"
        assertFalse(Totp.verify(secret, wrong, now))
    }

    @Test
    fun `verify rejects the wrong length and ignores spacing`() {
        val secret = Totp.generateSecret()
        val now = System.currentTimeMillis()
        val correct = Totp.code(secret, now)

        assertFalse(Totp.verify(secret, "", now))
        assertFalse(Totp.verify(secret, correct.take(5), now))
        assertFalse(Totp.verify(secret, correct + "1", now))
        // Users paste codes with a space in the middle.
        assertTrue(Totp.verify(secret, "${correct.take(3)} ${correct.drop(3)}", now))
    }

    @Test
    fun `verify rejects another secret's code`() {
        val now = System.currentTimeMillis()
        val a = Totp.generateSecret()
        val b = Totp.generateSecret()
        assertFalse(Totp.verify(a, Totp.code(b, now), now))
    }

    @Test
    fun `secrets are 160 bits and not repeated`() {
        val first = Totp.generateSecret()
        assertEquals(20, first.size)
        assertFalse(first.contentEquals(Totp.generateSecret()))
    }

    @Test
    fun `countdown runs from the period down to one`() {
        val base = 1_700_000_000_000L - (1_700_000_000_000L % 30_000L)
        assertEquals(30, Totp.secondsRemaining(base))
        assertEquals(29, Totp.secondsRemaining(base + 1_000))
        assertEquals(1, Totp.secondsRemaining(base + 29_000))
    }

    @Test
    fun `the provisioning uri is shaped the way authenticator apps expect`() {
        val uri = Totp.provisioningUri(rfcSecret, accountName = "My ledger")
        assertEquals(
            "otpauth://totp/QuietLedger:My%20ledger" +
                "?secret=${Base32.encode(rfcSecret)}&issuer=QuietLedger",
            uri
        )
    }

    @Test
    fun `the issuer and account are separated by a literal colon`() {
        // A percent-encoded colon is permitted by the spec and rejected by a
        // fair number of real authenticator apps, which parse the label by
        // splitting on the character itself.
        val uri = Totp.provisioningUri(rfcSecret, accountName = "My ledger")
        assertTrue("found an encoded colon in $uri", !uri.contains("%3A"))
        assertTrue(uri.contains("/QuietLedger:"))
    }

    @Test
    fun `default parameters are omitted rather than stated`() {
        // SHA1, 6 digits and a 30 second period are the defaults. Sending them
        // explicitly is a known compatibility hazard, and leaving them out keeps
        // the QR smaller too.
        val uri = Totp.provisioningUri(rfcSecret, accountName = "ledger")
        assertTrue(!uri.contains("algorithm"))
        assertTrue(!uri.contains("digits"))
        assertTrue(!uri.contains("period"))
    }

    @Test
    fun `spaces and awkward characters in the account name are encoded`() {
        val uri = Totp.provisioningUri(rfcSecret, accountName = "Dennis & Roberta")
        assertTrue(uri.contains("Dennis%20%26%20Roberta"))
        // The separator must survive encoding of the parts around it.
        assertTrue(uri.contains("/QuietLedger:Dennis"))
    }
}

class Base32Test {

    /** RFC 4648 section 10 test vectors. */
    @Test
    fun `matches the RFC 4648 vectors, without padding`() {
        val vectors = listOf(
            "" to "",
            "f" to "MY",
            "fo" to "MZXQ",
            "foo" to "MZXW6",
            "foob" to "MZXW6YQ",
            "fooba" to "MZXW6YTB",
            "foobar" to "MZXW6YTBOI"
        )
        vectors.forEach { (plain, encoded) ->
            assertEquals(encoded, Base32.encode(plain.toByteArray()))
        }
    }

    @Test
    fun `round trips arbitrary bytes`() {
        repeat(50) {
            val data = Totp.generateSecret()
            assertTrue(data.contentEquals(Base32.decode(Base32.encode(data))))
        }
    }

    @Test
    fun `decoding forgives what people actually type`() {
        val expected = Base32.decode("MZXW6YTBOI")
        listOf("mzxw6ytboi", "MZXW 6YTB OI", "MZXW-6YTB-OI", "MZXW6YTBOI======")
            .forEach { assertTrue(it, expected.contentEquals(Base32.decode(it))) }
    }

    @Test
    fun `invalid characters are rejected with a usable message`() {
        // 0, 1 and 8 are not in the base32 alphabet, and are exactly what someone
        // mistypes for O, I and B.
        val error = assertThrows(IllegalArgumentException::class.java) {
            Base32.decode("MZXW60TB")
        }
        assertTrue(error.message!!.contains("not a valid character"))
    }

    @Test
    fun `display grouping is in fours`() {
        assertEquals("MZXW 6YTB OI", Base32.formatForDisplay("MZXW6YTBOI"))
    }
}
