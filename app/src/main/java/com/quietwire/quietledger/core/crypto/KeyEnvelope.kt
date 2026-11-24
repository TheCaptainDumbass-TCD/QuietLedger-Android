package com.quietwire.quietledger.core.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wrapping and unwrapping of the database key, kept free of any Android
 * dependency so it can be tested on the JVM.
 *
 * This was split out after a real bug: the Android Keystore rejects a
 * caller-supplied IV on encryption, which only shows up on a device. Pulling
 * the format and the pure cryptography down here means the parts that can be
 * tested without hardware actually are.
 */
object KeyEnvelope {

    const val DATABASE_KEY_BYTES = 32
    const val GCM_IV_BYTES = 12
    const val SALT_BYTES = 16

    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"

    /** OWASP guidance for PBKDF2-HMAC-SHA256. */
    const val PBKDF2_ITERATIONS = 600_000

    private const val FORMAT_VERSION: Byte = 1

    fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { SecureRandom().nextBytes(it) }

    /**
     * Length-prefixed so the IV size is never assumed. The Keystore picks its
     * own IV, and hardcoding 12 bytes would be a latent bug the day that
     * changes.
     */
    fun pack(iv: ByteArray, ciphertext: ByteArray): String {
        require(iv.size in 1..255) { "IV length ${iv.size} out of range" }
        val blob = ByteArray(2 + iv.size + ciphertext.size)
        blob[0] = FORMAT_VERSION
        blob[1] = iv.size.toByte()
        iv.copyInto(blob, 2)
        ciphertext.copyInto(blob, 2 + iv.size)
        return Base64.getEncoder().encodeToString(blob)
    }

    fun unpack(encoded: String): Pair<ByteArray, ByteArray> {
        val blob = Base64.getDecoder().decode(encoded)
        require(blob.size > 2) { "Key blob is truncated" }
        require(blob[0] == FORMAT_VERSION) { "Unsupported key blob version ${blob[0]}" }

        val ivSize = blob[1].toInt() and 0xFF
        require(blob.size > 2 + ivSize) { "Key blob is truncated" }

        val iv = blob.copyOfRange(2, 2 + ivSize)
        val ciphertext = blob.copyOfRange(2 + ivSize, blob.size)
        return iv to ciphertext
    }

    /**
     * Encrypts with a key whose IV we control. Not for Keystore keys, which
     * must generate their own.
     */
    fun wrap(key: SecretKey, plaintext: ByteArray): String {
        val iv = randomBytes(GCM_IV_BYTES)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return pack(iv, cipher.doFinal(plaintext))
    }

    /**
     * Encrypts with a key that supplies its own IV, which is what the Android
     * Keystore requires. Passing a GCMParameterSpec here fails with
     * "Caller-provided IV not permitted".
     */
    fun wrapWithGeneratedIv(key: SecretKey, plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plaintext)
        return pack(cipher.iv, ciphertext)
    }

    /** Decryption always takes the stored IV, for both kinds of key. */
    fun unwrap(key: SecretKey, encoded: String): ByteArray {
        val (iv, ciphertext) = unpack(encoded)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = PBKDF2_ITERATIONS
    ): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, iterations, DATABASE_KEY_BYTES * 8)
        try {
            val bytes = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun decode(encoded: String): ByteArray = Base64.getDecoder().decode(encoded)
}
