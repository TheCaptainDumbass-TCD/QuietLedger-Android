package com.quietwire.quietledger.data.backup

import com.quietwire.quietledger.core.crypto.KeyEnvelope
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.GCMParameterSpec

/**
 * The on-disk format for an encrypted export.
 *
 * ```
 * offset  size  field
 * 0       4     magic "QLBK"
 * 4       1     format version
 * 5       4     PBKDF2 iteration count, big endian
 * 9       16    salt
 * 25      12    GCM nonce
 * 37      ..    AES-256-GCM ciphertext, 16 byte tag appended
 * ```
 *
 * The whole 37 byte header is passed to GCM as additional authenticated data,
 * so nobody can downgrade the iteration count or swap the salt without the tag
 * failing.
 *
 * The plaintext is gzipped JSON. JSON rather than a copy of the database file,
 * for two reasons. It survives schema changes, so an export taken today can
 * still be read after the tables move on. And nothing has to write a decrypted
 * database to storage in order to produce it, so there is no window where
 * plaintext financial data exists on disk.
 *
 * AES-256-GCM rather than the desktop app's AES-128-CBC with a separate HMAC.
 * GCM authenticates as part of the cipher, which removes a whole category of
 * mistake.
 *
 * Iterations are stored rather than assumed, so the figure can be raised later
 * without old exports becoming unreadable.
 */
object BackupEnvelope {

    private val MAGIC = byteArrayOf('Q'.code.toByte(), 'L'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())

    /** Passphrase only. Still written when a passphrase is supplied. */
    const val VERSION: Byte = 1

    /**
     * Carries a mode byte, so a backup can be sealed instead of passphrased.
     *
     * Sealed mode exists so routine backups need nothing typed. The phone holds
     * only a public key and can write a backup it cannot itself read, which is
     * the point: a private key in the APK would be extractable and would then
     * open every backup ever written.
     */
    const val VERSION_SEALED: Byte = 2
    const val MODE_SEALED: Byte = 2

    const val FILE_EXTENSION = "qlbk"

    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128
    private const val HEADER_SIZE = 4 + 1 + 4 + SALT_SIZE + NONCE_SIZE

    private const val AES_GCM = "AES/GCM/NoPadding"

    /**
     * RSA-OAEP with SHA-256, matching the server.
     *
     * Named in full rather than left to the provider's default, because
     * "RSA/ECB/OAEPWithSHA-256AndMGF1Padding" uses SHA-1 for MGF1 on some
     * providers while the server uses SHA-256 for both. That mismatch produces
     * a file that encrypts cleanly here and cannot be opened there, which is
     * exactly the kind of divergence that only shows up when a restore is
     * needed.
     */
    private const val RSA_OAEP = "RSA/ECB/OAEPPadding"
    private const val CONTENT_KEY_BITS = 256
    private const val KEY_ID_SIZE = 8

    /** Minimum passphrase length for an export. Short ones defeat the KDF. */
    const val MIN_PASSPHRASE_LENGTH = 10

    /**
     * Caps on untrusted input. A backup of a few thousand debts and a long
     * history is well under a megabyte, so these leave enormous headroom while
     * refusing anything that could only be an attempt to exhaust memory.
     */
    const val MAX_CIPHERTEXT_BYTES = 64 * 1024 * 1024
    const val MAX_PLAINTEXT_BYTES = 256 * 1024 * 1024

    fun write(output: OutputStream, json: String, passphrase: CharArray) {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) {
            "Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters"
        }

        val salt = KeyEnvelope.randomBytes(SALT_SIZE)
        val nonce = KeyEnvelope.randomBytes(NONCE_SIZE)
        val iterations = KeyEnvelope.PBKDF2_ITERATIONS

        val header = buildHeader(iterations, salt, nonce)
        val key = KeyEnvelope.deriveKey(passphrase, salt, iterations)

        val cipher = Cipher.getInstance(AES_GCM).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header)
        }

        val compressed = ByteArrayOutputStream().use { buffer ->
            GZIPOutputStream(buffer).use { it.write(json.toByteArray(Charsets.UTF_8)) }
            buffer.toByteArray()
        }

        output.write(header)
        output.write(cipher.doFinal(compressed))
        output.flush()
    }

    /**
     * Writes a backup only this server's private key can open.
     *
     * A fresh AES-256 content key per backup, wrapped to the public key. Fresh
     * because reusing a key and nonce under GCM leaks the difference between
     * two plaintexts and permits forgery.
     *
     * The layout, which `src/sync/qlbk.py` reads:
     *
     *     magic        4   "QLBK"
     *     version      1   2
     *     mode         1   2
     *     key id       8   which public key this was sealed to
     *     wrapped len  2   big endian
     *     wrapped key  n   RSA-OAEP-SHA256 of the content key
     *     nonce       12
     *     ciphertext   rest
     *
     * The whole header is authenticated, so none of it can be altered.
     */
    fun seal(output: OutputStream, json: String, publicKey: PublicKey, keyId: ByteArray) {
        require(keyId.size == KEY_ID_SIZE) { "Key id must be $KEY_ID_SIZE bytes" }

        val contentKey = KeyGenerator.getInstance("AES").apply {
            init(CONTENT_KEY_BITS)
        }.generateKey()
        val nonce = KeyEnvelope.randomBytes(NONCE_SIZE)

        val wrapped = Cipher.getInstance(RSA_OAEP).apply {
            init(
                Cipher.ENCRYPT_MODE,
                publicKey,
                OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    PSource.PSpecified.DEFAULT
                )
            )
        }.doFinal(contentKey.encoded)

        val header = buildSealedHeader(keyId, wrapped, nonce)

        val cipher = Cipher.getInstance(AES_GCM).apply {
            init(Cipher.ENCRYPT_MODE, contentKey, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header)
        }

        val compressed = ByteArrayOutputStream().use { buffer ->
            GZIPOutputStream(buffer).use { it.write(json.toByteArray(Charsets.UTF_8)) }
            buffer.toByteArray()
        }

        output.write(header)
        output.write(cipher.doFinal(compressed))
        output.flush()
    }

    private fun buildSealedHeader(
        keyId: ByteArray,
        wrapped: ByteArray,
        nonce: ByteArray
    ): ByteArray = ByteArrayOutputStream().use { buffer ->
        buffer.write(MAGIC)
        buffer.write(byteArrayOf(VERSION_SEALED, MODE_SEALED))
        buffer.write(keyId)
        // Big endian, two bytes, matching struct ">H" on the server.
        buffer.write(byteArrayOf((wrapped.size shr 8).toByte(), wrapped.size.toByte()))
        buffer.write(wrapped)
        buffer.write(nonce)
        buffer.toByteArray()
    }

    /**
     * @throws BackupFormatException if this is not a QuietLedger export.
     * @throws WrongBackupPassphraseException if the passphrase is wrong or the
     *         file has been altered. GCM cannot tell those apart, and it would
     *         be misleading to claim otherwise.
     */
    /**
     * @param maxPlaintextBytes overridable so the decompression limit can be
     *        exercised in a test without allocating hundreds of megabytes. The
     *        default is what production uses.
     */
    fun read(
        input: InputStream,
        passphrase: CharArray,
        maxPlaintextBytes: Int = MAX_PLAINTEXT_BYTES
    ): String {
        val header = ByteArray(HEADER_SIZE)
        val headerBytes = input.readNBytesCompat(header)

        // Magic is checked before length. If the user picked the wrong file
        // entirely, "this is not a QuietLedger export" is the useful message,
        // whereas "the file is truncated" sends them looking for corruption
        // that is not there.
        if (headerBytes < MAGIC.size || !header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupFormatException("This is not a QuietLedger export file.")
        }

        if (headerBytes != HEADER_SIZE) {
            throw BackupFormatException(
                "This export file is incomplete. It may have been cut short while being saved."
            )
        }

        val version = header[4]
        if (version != VERSION) {
            throw BackupFormatException(
                "This export was written by a different version of QuietLedger " +
                    "(format $version, this build reads $VERSION)."
            )
        }

        val iterations = ((header[5].toInt() and 0xFF) shl 24) or
            ((header[6].toInt() and 0xFF) shl 16) or
            ((header[7].toInt() and 0xFF) shl 8) or
            (header[8].toInt() and 0xFF)
        if (iterations < 1) throw BackupFormatException("The export header is damaged.")

        val salt = header.copyOfRange(9, 9 + SALT_SIZE)
        val nonce = header.copyOfRange(9 + SALT_SIZE, HEADER_SIZE)

        val key = KeyEnvelope.deriveKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance(AES_GCM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header)
        }

        val ciphertext = input.readAtMost(MAX_CIPHERTEXT_BYTES) {
            BackupFormatException(
                "That file is larger than any QuietLedger backup should be."
            )
        }

        val compressed = try {
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw WrongBackupPassphraseException(
                "That passphrase is not correct, or the file has been altered.", e
            )
        }

        // Bounded decompression. Gzip expands, and a small archive can inflate to
        // gigabytes, which would take the process down with an
        // OutOfMemoryError. GCM authentication means a hostile file has to be
        // built by someone who knows the passphrase, which is a narrow case but
        // not an impossible one: "here is a backup, the passphrase is X" is a
        // plausible thing to be told. Costs nothing to bound it.
        return GZIPInputStream(compressed.inputStream()).use { gzip ->
            gzip.readAtMost(maxPlaintextBytes) {
                BackupFormatException(
                    "That backup expands to more than " +
                        "${maxPlaintextBytes / 1024 / 1024} MB, which is not a " +
                        "QuietLedger backup."
                )
            }
        }.toString(Charsets.UTF_8)
    }

    private fun buildHeader(iterations: Int, salt: ByteArray, nonce: ByteArray): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        MAGIC.copyInto(header, 0)
        header[4] = VERSION
        header[5] = (iterations ushr 24).toByte()
        header[6] = (iterations ushr 16).toByte()
        header[7] = (iterations ushr 8).toByte()
        header[8] = iterations.toByte()
        salt.copyInto(header, 9)
        nonce.copyInto(header, 9 + SALT_SIZE)
        return header
    }

    /**
     * Reads until the stream ends, or throws once more than [limit] bytes have
     * arrived. The point is to stop before allocating, rather than discovering
     * the size after the heap has gone.
     */
    private inline fun InputStream.readAtMost(
        limit: Int,
        onTooLarge: () -> Exception
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw onTooLarge()
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** readNBytes is not available on every supported API level. */
    private fun InputStream.readNBytesCompat(into: ByteArray): Int {
        var read = 0
        while (read < into.size) {
            val n = try {
                read(into, read, into.size - read)
            } catch (e: EOFException) {
                -1
            }
            if (n < 0) break
            read += n
        }
        return read
    }
}

class BackupFormatException(message: String) : Exception(message)

class WrongBackupPassphraseException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
