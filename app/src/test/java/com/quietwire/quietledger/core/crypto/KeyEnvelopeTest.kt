package com.quietwire.quietledger.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class KeyEnvelopeTest {

    private fun aesKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    // Derivation at 600k iterations is slow on purpose. Tests that only need
    // a key, not the real cost, use a low count.
    private fun fastDerive(passphrase: String, salt: ByteArray) =
        KeyEnvelope.deriveKey(passphrase.toCharArray(), salt, iterations = 1_000)

    @Test
    fun `wrap then unwrap returns the original key`() {
        val key = aesKey()
        val databaseKey = KeyEnvelope.randomBytes(KeyEnvelope.DATABASE_KEY_BYTES)

        val blob = KeyEnvelope.wrap(key, databaseKey)
        assertArrayEquals(databaseKey, KeyEnvelope.unwrap(key, blob))
    }

    @Test
    fun `wrapWithGeneratedIv round trips, as the Keystore path requires`() {
        // The Android Keystore rejects a caller-provided IV on encryption and
        // generates its own. This is that path, minus the hardware.
        val key = aesKey()
        val databaseKey = KeyEnvelope.randomBytes(KeyEnvelope.DATABASE_KEY_BYTES)

        val blob = KeyEnvelope.wrapWithGeneratedIv(key, databaseKey)
        assertArrayEquals(databaseKey, KeyEnvelope.unwrap(key, blob))
    }

    @Test
    fun `both wrap styles produce blobs the same unwrap can read`() {
        val key = aesKey()
        val secret = KeyEnvelope.randomBytes(32)

        val a = KeyEnvelope.wrap(key, secret)
        val b = KeyEnvelope.wrapWithGeneratedIv(key, secret)

        assertArrayEquals(secret, KeyEnvelope.unwrap(key, a))
        assertArrayEquals(secret, KeyEnvelope.unwrap(key, b))
    }

    @Test
    fun `wrapping the same value twice gives different ciphertext`() {
        val key = aesKey()
        val secret = KeyEnvelope.randomBytes(32)
        assertNotEquals(KeyEnvelope.wrap(key, secret), KeyEnvelope.wrap(key, secret))
    }

    @Test
    fun `the wrong key cannot unwrap`() {
        val blob = KeyEnvelope.wrap(aesKey(), KeyEnvelope.randomBytes(32))
        assertThrows(AEADBadTagException::class.java) {
            KeyEnvelope.unwrap(aesKey(), blob)
        }
    }

    @Test
    fun `tampering with the ciphertext is detected`() {
        val key = aesKey()
        val blob = KeyEnvelope.wrap(key, KeyEnvelope.randomBytes(32))

        val raw = KeyEnvelope.decode(blob)
        raw[raw.size - 1] = (raw[raw.size - 1] + 1).toByte()

        assertThrows(AEADBadTagException::class.java) {
            KeyEnvelope.unwrap(key, KeyEnvelope.encode(raw))
        }
    }

    @Test
    fun `pack and unpack preserve the IV whatever its length`() {
        for (ivSize in listOf(1, 12, 16, 255)) {
            val iv = KeyEnvelope.randomBytes(ivSize)
            val ciphertext = KeyEnvelope.randomBytes(48)

            val (readIv, readCiphertext) = KeyEnvelope.unpack(KeyEnvelope.pack(iv, ciphertext))
            assertArrayEquals("iv of $ivSize bytes", iv, readIv)
            assertArrayEquals("ciphertext with $ivSize byte iv", ciphertext, readCiphertext)
        }
    }

    @Test
    fun `a truncated blob is rejected rather than misread`() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyEnvelope.unpack(KeyEnvelope.encode(byteArrayOf(1, 12)))
        }
    }

    @Test
    fun `an unknown format version is rejected`() {
        val blob = KeyEnvelope.encode(byteArrayOf(99, 1, 0, 0, 0))
        val error = assertThrows(IllegalArgumentException::class.java) {
            KeyEnvelope.unpack(blob)
        }
        assertTrue(error.message!!.contains("version"))
    }

    @Test
    fun `the correct passphrase unwraps and a wrong one does not`() {
        val salt = KeyEnvelope.randomBytes(KeyEnvelope.SALT_BYTES)
        val databaseKey = KeyEnvelope.randomBytes(KeyEnvelope.DATABASE_KEY_BYTES)

        val blob = KeyEnvelope.wrap(fastDerive("correct horse battery", salt), databaseKey)

        assertArrayEquals(
            databaseKey,
            KeyEnvelope.unwrap(fastDerive("correct horse battery", salt), blob)
        )
        assertThrows(AEADBadTagException::class.java) {
            KeyEnvelope.unwrap(fastDerive("correct horse batterz", salt), blob)
        }
    }

    @Test
    fun `a different salt yields a different key from the same passphrase`() {
        val a = fastDerive("same passphrase", KeyEnvelope.randomBytes(16))
        val b = fastDerive("same passphrase", KeyEnvelope.randomBytes(16))
        assertNotEquals(KeyEnvelope.encode(a.encoded), KeyEnvelope.encode(b.encoded))
    }

    @Test
    fun `derivation produces a 256 bit key`() {
        val key = fastDerive("anything", KeyEnvelope.randomBytes(16))
        assertEquals(32, key.encoded.size)
    }

    @Test
    fun `the shipped iteration count is at least the OWASP figure`() {
        assertTrue(
            "PBKDF2 iterations must not be weakened",
            KeyEnvelope.PBKDF2_ITERATIONS >= 600_000
        )
    }
}
