package com.quietwire.quietledger.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class BackupEnvelopeTest {

    private val passphrase = "a long enough passphrase"
    private val payload = """{"schema":1,"debts":[{"name":"Visa"}]}"""

    private fun write(json: String = payload, pass: String = passphrase): ByteArray =
        ByteArrayOutputStream().also {
            BackupEnvelope.write(it, json, pass.toCharArray())
        }.toByteArray()

    @Test
    fun `round trips`() {
        val bytes = write()
        assertEquals(payload, BackupEnvelope.read(bytes.inputStream(), passphrase.toCharArray()))
    }

    @Test
    fun `survives a large payload`() {
        val big = buildString {
            append("""{"schema":1,"notes":"""")
            repeat(50_000) { append("abcdefghij") }
            append(""""}""")
        }
        val bytes = write(big)
        assertEquals(big, BackupEnvelope.read(bytes.inputStream(), passphrase.toCharArray()))
    }

    @Test
    fun `compresses, so the file is smaller than the plaintext`() {
        // Repetitive JSON should shrink a long way. If this ever fails,
        // compression has silently stopped happening.
        val repetitive = buildString {
            append("[")
            repeat(2_000) { append("""{"name":"Visa","balance":100000},""") }
            append("""{"name":"end"}]""")
        }
        val bytes = write(repetitive)
        assertTrue(
            "expected compression, got ${bytes.size} bytes for ${repetitive.length} chars",
            bytes.size < repetitive.length / 4
        )
    }

    @Test
    fun `the wrong passphrase is rejected`() {
        val bytes = write()
        assertThrows(WrongBackupPassphraseException::class.java) {
            BackupEnvelope.read(bytes.inputStream(), "a different passphrase".toCharArray())
        }
    }

    @Test
    fun `a tampered ciphertext is rejected`() {
        val bytes = write()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        assertThrows(WrongBackupPassphraseException::class.java) {
            BackupEnvelope.read(bytes.inputStream(), passphrase.toCharArray())
        }
    }

    @Test
    fun `tampering with the header is rejected, because it is authenticated`() {
        // The iteration count sits in the header. Lowering it would weaken the
        // KDF, so the header is fed to GCM as additional authenticated data and
        // any change breaks the tag.
        val bytes = write()
        bytes[8] = 1  // iterations now ends in 1 instead of the real value
        assertThrows(Exception::class.java) {
            BackupEnvelope.read(bytes.inputStream(), passphrase.toCharArray())
        }
    }

    @Test
    fun `a foreign file is reported as such, not as a bad passphrase`() {
        val notOurs = "this is just some text file".toByteArray()
        val error = assertThrows(BackupFormatException::class.java) {
            BackupEnvelope.read(notOurs.inputStream(), passphrase.toCharArray())
        }
        assertTrue(error.message!!.contains("not a QuietLedger export"))
    }

    @Test
    fun `a truncated export is distinguished from a foreign file`() {
        // Correct magic bytes, so this is one of ours, just cut short.
        val bytes = write().copyOfRange(0, 20)
        val error = assertThrows(BackupFormatException::class.java) {
            BackupEnvelope.read(bytes.inputStream(), passphrase.toCharArray())
        }
        assertTrue("got: ${error.message}", error.message!!.contains("incomplete"))
    }

    @Test
    fun `a short foreign file still reports the right problem`() {
        // Shorter than our header. The useful message is still that this is the
        // wrong kind of file, not that it is damaged.
        val error = assertThrows(BackupFormatException::class.java) {
            BackupEnvelope.read("nope".toByteArray().inputStream(), passphrase.toCharArray())
        }
        assertTrue("got: ${error.message}", error.message!!.contains("not a QuietLedger export"))
    }

    @Test
    fun `an unknown format version is refused rather than misread`() {
        val bytes = write()
        bytes[4] = 99
        val error = assertThrows(BackupFormatException::class.java) {
            BackupEnvelope.read(bytes.inputStream(), passphrase.toCharArray())
        }
        assertTrue(error.message!!.contains("different version"))
    }

    @Test
    fun `short passphrases are refused at export time`() {
        assertThrows(IllegalArgumentException::class.java) {
            write(pass = "short")
        }
    }

    @Test
    fun `two exports of the same data differ, because salt and nonce are fresh`() {
        assertNotEquals(write().toList(), write().toList())
    }

    @Test
    fun `the file is identifiable by its magic bytes`() {
        val bytes = write()
        assertEquals("QLBK", String(bytes.copyOfRange(0, 4)))
        assertEquals(BackupEnvelope.VERSION, bytes[4])
    }
}

/** Decompression is bounded, because gzip expands and a small file need not stay small. */
class BackupDecompressionTest {

    private val passphrase = "a long enough passphrase"

    @Test
    fun `a gzip bomb is refused rather than exhausting memory`() {
        // 8 MB of a single repeated character compresses to a few kilobytes, so
        // this is a real compression bomb in miniature: a valid envelope with a
        // valid tag whose contents are wildly out of proportion to the file.
        //
        // The limit is passed in rather than using the production constant,
        // because building a payload beyond the real 256 MB cap would exhaust
        // the test JVM before the code under test ever saw it. The first attempt
        // at this test did exactly that.
        val payload = "A".repeat(8 * 1024 * 1024)
        val bytes = java.io.ByteArrayOutputStream().also {
            BackupEnvelope.write(it, payload, passphrase.toCharArray())
        }.toByteArray()

        assertTrue(
            "expected a small file holding a large payload, got ${bytes.size} bytes",
            bytes.size < 64 * 1024
        )

        val error = assertThrows(BackupFormatException::class.java) {
            BackupEnvelope.read(
                bytes.inputStream(),
                passphrase.toCharArray(),
                maxPlaintextBytes = 1024 * 1024
            )
        }
        assertTrue("got: ${error.message}", error.message!!.contains("expands to more than"))
    }

    @Test
    fun `a payload within the limit still reads back`() {
        val payload = "B".repeat(512 * 1024)
        val bytes = java.io.ByteArrayOutputStream().also {
            BackupEnvelope.write(it, payload, passphrase.toCharArray())
        }.toByteArray()
        assertEquals(
            payload,
            BackupEnvelope.read(
                bytes.inputStream(), passphrase.toCharArray(), maxPlaintextBytes = 1024 * 1024
            )
        )
    }

    @Test
    fun `a normal backup is unaffected by the limits`() {
        val payload = """{"schema":1,"debts":[{"name":"Visa","balance":125463}]}"""
        val bytes = java.io.ByteArrayOutputStream().also {
            BackupEnvelope.write(it, payload, passphrase.toCharArray())
        }.toByteArray()
        assertEquals(payload, BackupEnvelope.read(bytes.inputStream(), passphrase.toCharArray()))
    }

    @Test
    fun `the limits leave real headroom`() {
        assertTrue(BackupEnvelope.MAX_CIPHERTEXT_BYTES > 10 * 1024 * 1024)
        assertTrue(BackupEnvelope.MAX_PLAINTEXT_BYTES > BackupEnvelope.MAX_CIPHERTEXT_BYTES)
    }
}
