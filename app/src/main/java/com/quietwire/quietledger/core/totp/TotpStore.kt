package com.quietwire.quietledger.core.totp

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.quietwire.quietledger.core.crypto.KeyEnvelope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/** How many recovery codes are issued when TOTP is turned on. */
private const val RECOVERY_CODE_COUNT = 8

/**
 * Stores the TOTP shared secret and the recovery codes.
 *
 * The secret is wrapped by its own hardware Keystore key, kept separate from the
 * one that wraps the database key so that turning TOTP off cannot disturb the
 * ability to open the database.
 *
 * Worth being clear about what this is: the secret has to live on the device for
 * codes to be verifiable locally, so anyone who can read this app's private
 * storage can generate valid codes themselves. TOTP is therefore a gate in front
 * of the app, not a second lock on the encryption. It defends against someone
 * holding your unlocked phone. It does not defend against someone running code
 * as this app.
 *
 * Recovery codes exist so that a lost authenticator is an inconvenience rather
 * than a lockout. They are stored as salted SHA-256 hashes and each one works
 * once.
 */
/*
 * commit() rather than apply() throughout, deliberately. apply() is asynchronous
 * and returns Unit, so there is no way to know whether a key actually reached
 * storage. Here a silent failure would mean a database that can never be opened
 * again, which is worth a few milliseconds on the calling thread.
 */
@Suppress("ApplySharedPref")
@Singleton
class TotpStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isEnabled: Boolean
        get() = prefs.contains(KEY_SECRET)

    val unusedRecoveryCodeCount: Int
        get() = recoveryHashes().size

    /**
     * Creates a secret but does not enable TOTP yet. The caller must confirm with
     * a working code first, otherwise a user who mistypes the key into their
     * authenticator locks themselves into a factor they cannot satisfy.
     */
    fun beginEnrolment(): ByteArray = Totp.generateSecret()

    /**
     * Completes enrolment once a code has been verified against [secret].
     * Returns the recovery codes, which are shown once and never again.
     */
    fun completeEnrolment(secret: ByteArray): List<String> {
        val wrapped = KeyEnvelope.wrapWithGeneratedIv(wrappingKey(), secret)
        val codes = List(RECOVERY_CODE_COUNT) { generateRecoveryCode() }
        val salt = KeyEnvelope.randomBytes(16)

        val saved = prefs.edit()
            .putString(KEY_SECRET, wrapped)
            .putString(KEY_RECOVERY_SALT, KeyEnvelope.encode(salt))
            .putStringSet(KEY_RECOVERY_HASHES, codes.map { hash(it, salt) }.toSet())
            .commit()
        if (!saved) throw IllegalStateException("Two-step settings could not be saved.")

        return codes
    }

    fun disable() {
        prefs.edit(commit = true) { clear() }
        runCatching { keyStore().deleteEntry(KEYSTORE_ALIAS) }
    }

    /** True if [candidate] is a currently valid code. */
    fun verifyCode(candidate: String, timeMillis: Long = System.currentTimeMillis()): Boolean {
        val secret = readSecret() ?: return false
        return try {
            Totp.verify(secret, candidate, timeMillis)
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Consumes a recovery code. Returns false if it is unknown or already used.
     * Single use, so an intercepted code cannot be replayed.
     */
    fun consumeRecoveryCode(candidate: String): Boolean {
        val saltBlob = prefs.getString(KEY_RECOVERY_SALT, null) ?: return false
        val salt = KeyEnvelope.decode(saltBlob)
        val normalised = candidate.trim().uppercase().replace(" ", "").replace("-", "")
        if (normalised.isEmpty()) return false

        val hashes = recoveryHashes()
        val match = hash(normalised, salt)
        if (match !in hashes) return false

        prefs.edit().putStringSet(KEY_RECOVERY_HASHES, hashes - match).commit()
        return true
    }

    /** For showing the current code during enrolment, so setup can be checked. */
    fun currentCodeForEnrolment(secret: ByteArray): String =
        Totp.code(secret, System.currentTimeMillis())

    private fun readSecret(): ByteArray? {
        val wrapped = prefs.getString(KEY_SECRET, null) ?: return null
        return runCatching { KeyEnvelope.unwrap(wrappingKey(), wrapped) }.getOrNull()
    }

    private fun recoveryHashes(): Set<String> =
        prefs.getStringSet(KEY_RECOVERY_HASHES, emptySet()) ?: emptySet()

    private fun hash(code: String, salt: ByteArray): String =
        KeyEnvelope.encode(
            MessageDigest.getInstance("SHA-256").digest(salt + code.toByteArray(Charsets.UTF_8))
        )

    /** Base32 alphabet, so there is no 0/O or 1/I to mistype. */
    private fun generateRecoveryCode(): String {
        val bytes = KeyEnvelope.randomBytes(10)
        return Base32.encode(bytes).take(16).chunked(4).joinToString("-")
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun wrappingKey(): SecretKey {
        val store = keyStore()
        (store.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "quietledger_totp"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "quietledger_totp_wrapping_key"

        private const val KEY_SECRET = "totp_secret"
        private const val KEY_RECOVERY_SALT = "recovery_salt"
        private const val KEY_RECOVERY_HASHES = "recovery_hashes"
    }
}
