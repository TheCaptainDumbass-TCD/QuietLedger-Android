package com.quietwire.quietledger.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * Sealing a backup to the server's public key.
 *
 * The important part of this file is the file it writes. There are two
 * implementations of one binary format, Kotlin here and Python on the server,
 * and nothing in a Kotlin test can prove they agree. So one test writes a
 * sealed backup to a known path and `scripts/verify-sealed-backup.sh` opens it
 * with the server's private key. If that fails, a restore would have failed.
 */
class SealedEnvelopeTest {

    private fun serverPublicKey(): PublicKey {
        // Read from the committed resource rather than generated, so this tests
        // the key that actually ships in the APK.
        val der = File("src/main/res/raw/server_public_key.der").readBytes()
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
    }

    private fun keyId(key: PublicKey): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(key.encoded).copyOf(8)

    private fun seal(json: String): ByteArray {
        val key = serverPublicKey()
        return ByteArrayOutputStream().use {
            BackupEnvelope.seal(it, json, key, keyId(key))
            it.toByteArray()
        }
    }

    @Test
    fun `the header identifies itself as sealed`() {
        val sealed = seal("""{"debts":[]}""")

        assertEquals('Q'.code.toByte(), sealed[0])
        assertEquals('L'.code.toByte(), sealed[1])
        assertEquals('B'.code.toByte(), sealed[2])
        assertEquals('K'.code.toByte(), sealed[3])
        assertEquals(BackupEnvelope.VERSION_SEALED, sealed[4])
        assertEquals(BackupEnvelope.MODE_SEALED, sealed[5])
    }

    @Test
    fun `the key id says which public key it was sealed to`() {
        val key = serverPublicKey()
        val sealed = seal("""{"debts":[]}""")

        val expected = keyId(key)
        val actual = sealed.copyOfRange(6, 14)

        assertTrue(expected.contentEquals(actual))
    }

    @Test
    fun `the content is not readable in the file`() {
        val sealed = seal("""{"debts":[{"name":"Northbridge Credit Union"}]}""")

        assertTrue(
            "the debt name must not appear in the bytes",
            !String(sealed, Charsets.ISO_8859_1).contains("Northbridge")
        )
    }

    @Test
    fun `each backup uses a fresh content key and nonce`() {
        // Reuse under GCM leaks the difference between two plaintexts and
        // permits forgery. Compares the nonce and ciphertext specifically: the
        // RSA wrapping is randomised, so whole files differ even when the
        // content key is fixed.
        val first = seal("""{"debts":[]}""")
        val second = seal("""{"debts":[]}""")

        val wrappedLength = ((first[14].toInt() and 0xFF) shl 8) or (first[15].toInt() and 0xFF)
        val nonceStart = 16 + wrappedLength
        val bodyStart = nonceStart + 12

        assertNotEquals(
            "nonce was reused",
            first.copyOfRange(nonceStart, bodyStart).toList(),
            second.copyOfRange(nonceStart, bodyStart).toList()
        )
        assertNotEquals(
            "content key was reused",
            first.copyOfRange(bodyStart, first.size).toList(),
            second.copyOfRange(bodyStart, second.size).toList()
        )
    }

    @Test
    fun `a sealed file is written for the server to verify`() {
        // Not an assertion so much as a handover. The format has two
        // implementations and only the server can prove this one is correct.
        val json = """
            {"schema":1,"app_version":"kotlin-test","exported_at":1761000000000,
             "debts":[{"name":"Cross check","current_balance_minor":125463,
                       "category":"CREDIT_CARD","payment_frequency":"MONTHLY"}],
             "recurring_expenses":[],"savings_accounts":[],"holdings":[],
             "metal_lots":[],"metal_spot_prices":[],"balance_changes":[]}
        """.trimIndent()

        val target = File("build/sealed-by-kotlin.qlbk")
        target.parentFile?.mkdirs()
        target.writeBytes(seal(json))

        assertTrue(target.length() > 100)
    }
}
