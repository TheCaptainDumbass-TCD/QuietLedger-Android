package com.quietwire.quietledger.core.crypto

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.edit
import android.os.Build
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the SQLCipher passphrase for the database.
 *
 * There is one random 32 byte database key. It is never stored in the clear.
 * Instead it is wrapped twice:
 *
 *  - by an AES key in the Android Keystore, which is hardware backed and
 *    cannot be exported from the device
 *  - by a key derived from the user's passphrase
 *
 * Two wrappings because either alone has a bad failure mode. Keystore only
 * means a factory reset, a lost phone, or a wiped secure element takes the
 * data with it permanently, with no recovery even for the owner. Passphrase
 * only means typing it every single time. With both, daily use is one tap and
 * there is still a way back in.
 *
 * The cryptography itself lives in [KeyEnvelope] so it can be tested without
 * a device.
 */
/*
 * commit() rather than apply() throughout, deliberately. apply() is asynchronous
 * and returns Unit, so there is no way to know whether a key actually reached
 * storage. Here a silent failure would mean a database that can never be opened
 * again, which is worth a few milliseconds on the calling thread.
 */
@Suppress("ApplySharedPref")
@Singleton
class DatabaseKeyStore @Inject constructor(
    private val context: Context
) {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Whether a database key exists by any route.
     *
     * Both wrappings are checked, and that matters more than it looks. This
     * used to check only the legacy entry, which the StrongBox migration
     * removes. Had that removal succeeded, this would have turned false, the
     * app would have decided it needed setting up, and `createDatabaseKey`
     * would have generated a **new** key, orphaning the encrypted database
     * permanently.
     *
     * The removal failing is the only reason that did not happen. A question
     * phrased as "is the legacy key present" when it meant "is there a key"
     * was the whole fault.
     */
    val isInitialised: Boolean
        get() = prefs.contains(KEY_KEYSTORE_WRAPPED_STRONGBOX) ||
            prefs.contains(KEY_KEYSTORE_WRAPPED)

    val hasPassphrase: Boolean
        get() = prefs.contains(KEY_PASSPHRASE_WRAPPED)

    /**
     * Whether this device has a discrete security chip, a Titan M on Pixels.
     *
     * StrongBox is separate physical hardware with its own CPU, memory and
     * random number generator. The TEE is strong but shares the main processor,
     * so it is exposed to attacks that physical separation resists.
     */
    val strongBoxAvailable: Boolean
        get() = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_STRONGBOX_KEYSTORE
        )

    /** Whether the key actually in use lives in that chip. */
    val usingStrongBox: Boolean
        get() = prefs.contains(KEY_KEYSTORE_WRAPPED_STRONGBOX)

    /**
     * Where the wrapping key lives, for the About screen.
     *
     * Reported rather than assumed. Asking for StrongBox does not guarantee
     * getting it: some devices accept the request and quietly produce a TEE
     * key, so the only honest answer comes from asking the key itself.
     */
    fun securityLevel(): String = runCatching {
        val alias = if (usingStrongBox) KEYSTORE_ALIAS_STRONGBOX else KEYSTORE_ALIAS
        val key = (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)
            ?.secretKey
            ?: return "no key"

        val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "security chip (StrongBox)"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "trusted environment"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software only"
                else -> "unknown"
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "secure hardware" else "software only"
        }
    }.getOrElse { "unknown" }

    /**
     * Creates the database key and wraps it with the Keystore. Returns the raw
     * key for opening the database.
     *
     * Nothing is persisted unless the whole operation succeeds, so a failure
     * here leaves the app cleanly un-set-up rather than half configured.
     */
    fun createDatabaseKey(): ByteArray {
        check(!isInitialised) { "Database key already exists" }

        val databaseKey = KeyEnvelope.randomBytes(KeyEnvelope.DATABASE_KEY_BYTES)
        val blob = KeyEnvelope.wrapWithGeneratedIv(keystoreKey(), databaseKey)

        // Explicit commit() rather than the KTX edit {} extension, because the
        // extension discards the boolean and that boolean is the whole point: a
        // key that silently fails to persist means a database nobody can ever
        // open again.
        @Suppress("UseKtx")
        val stored = prefs.edit().putString(KEY_KEYSTORE_WRAPPED, blob).commit()
        if (!stored) {
            databaseKey.fill(0)
            throw IllegalStateException("The device key could not be saved.")
        }
        return databaseKey
    }

    fun unlockWithKeystore(): ByteArray {
        // The StrongBox wrapping wins when it exists. Both are checked because
        // an app installed before the upgrade has only the older one, and a
        // migration that cannot find its input is worse than no migration.
        val strongBoxBlob = prefs.getString(KEY_KEYSTORE_WRAPPED_STRONGBOX, null)
        val legacyBlob = prefs.getString(KEY_KEYSTORE_WRAPPED, null)

        val (alias, blob) = when {
            strongBoxBlob != null -> KEYSTORE_ALIAS_STRONGBOX to strongBoxBlob
            legacyBlob != null -> KEYSTORE_ALIAS to legacyBlob
            else -> throw NoKeyException("No database key has been created yet.")
        }

        return try {
            KeyEnvelope.unwrap(existingKey(alias) ?: throw NoKeyException(
                "The wrapping key is missing from the Keystore."
            ), blob)
        } catch (e: Exception) {
            // Enrolling a new fingerprint or changing the screen lock can
            // invalidate the Keystore key. Recoverable, but only through a
            // passphrase, so the distinction matters to the caller.
            throw KeystoreUnavailableException(
                if (hasPassphrase) {
                    "The device key could not be used. Unlock with your passphrase instead."
                } else {
                    "The device key could not be used, and no passphrase was set, " +
                        "so this data cannot be recovered."
                },
                e
            )
        }
    }

    /** Adds or replaces the passphrase route to the same database key. */
    fun setPassphrase(databaseKey: ByteArray, passphrase: CharArray) {
        val salt = KeyEnvelope.randomBytes(KeyEnvelope.SALT_BYTES)
        val derived = KeyEnvelope.deriveKey(passphrase, salt)
        val blob = KeyEnvelope.wrap(derived, databaseKey)

        val saved = prefs.edit()
            .putString(KEY_PASSPHRASE_SALT, KeyEnvelope.encode(salt))
            .putString(KEY_PASSPHRASE_WRAPPED, blob)
            .commit()

        if (!saved) throw IllegalStateException("The passphrase could not be saved.")
    }

    fun unlockWithPassphrase(passphrase: CharArray): ByteArray {
        val saltBlob = prefs.getString(KEY_PASSPHRASE_SALT, null)
        val wrapped = prefs.getString(KEY_PASSPHRASE_WRAPPED, null)
        if (saltBlob == null || wrapped == null) {
            throw NoKeyException("No passphrase has been set.")
        }

        val derived = KeyEnvelope.deriveKey(passphrase, KeyEnvelope.decode(saltBlob))
        return try {
            KeyEnvelope.unwrap(derived, wrapped)
        } catch (e: Exception) {
            throw WrongPassphraseException("That passphrase is not correct.", e)
        }
    }

    /**
     * Moves the wrapping into the device's security chip, if it has one.
     *
     * Only the *wrapping* changes. The database key itself is untouched and
     * nothing is re-encrypted, so the database file is never at risk. That is
     * what makes this safe to attempt on an existing install.
     *
     * The order matters and is deliberate:
     *
     *  1. wrap the same database key with a new StrongBox key
     *  2. unwrap it again and check it comes back byte for byte
     *  3. only then record it and remove the old wrapping
     *
     * Step 2 exists because some devices accept a StrongBox request and
     * produce a key that fails later. Verifying a round trip before discarding
     * the working path is the difference between an upgrade and a brick.
     *
     * The old wrapping is removed on success rather than kept as a fallback.
     * Keeping it would leave a trusted environment key that opens the same
     * database, so an attacker would simply use the weaker path and the
     * upgrade would buy nothing.
     *
     * Returns true if the key now lives in the chip.
     */
    fun upgradeToStrongBox(databaseKey: ByteArray): Boolean {
        if (usingStrongBox) return true
        if (!strongBoxAvailable) return false

        val strongBoxKey = try {
            generateKey(KEYSTORE_ALIAS_STRONGBOX, strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            // Advertised but refused these parameters. Nothing is changed.
            return false
        } catch (e: Exception) {
            return false
        }

        val blob = try {
            KeyEnvelope.wrapWithGeneratedIv(strongBoxKey, databaseKey)
        } catch (e: Exception) {
            runCatching { keyStore().deleteEntry(KEYSTORE_ALIAS_STRONGBOX) }
            return false
        }

        // Proved, not assumed.
        val recovered = try {
            KeyEnvelope.unwrap(strongBoxKey, blob)
        } catch (e: Exception) {
            runCatching { keyStore().deleteEntry(KEYSTORE_ALIAS_STRONGBOX) }
            return false
        }

        if (!recovered.contentEquals(databaseKey)) {
            recovered.fill(0)
            runCatching { keyStore().deleteEntry(KEYSTORE_ALIAS_STRONGBOX) }
            return false
        }
        recovered.fill(0)

        @Suppress("UseKtx")
        val stored = prefs.edit()
            .putString(KEY_KEYSTORE_WRAPPED_STRONGBOX, blob)
            .commit()

        if (!stored) {
            runCatching { keyStore().deleteEntry(KEYSTORE_ALIAS_STRONGBOX) }
            return false
        }

        // The weaker path goes, in this order on purpose.
        //
        // The Keystore key first: once it is gone the legacy blob cannot be
        // unwrapped by anything, so the trusted environment route is closed
        // even if removing the blob fails.
        //
        // Then the blob, with the result checked. An unchecked commit was how
        // this came to be in an inconsistent state in the first place: the key
        // was deleted, the blob stayed, and nothing noticed.
        runCatching { keyStore().deleteEntry(KEYSTORE_ALIAS) }

        @Suppress("UseKtx")
        val tidied = prefs.edit().remove(KEY_KEYSTORE_WRAPPED).commit()
        if (!tidied) {
            // Harmless. The blob cannot be used without the key that is now
            // gone, and isInitialised reads either entry, so nothing breaks.
            // Worth knowing about rather than silent.
            Log.w(TAG, "StrongBox upgrade left the legacy wrapping behind")
        }

        return true
    }

    /**
     * Re-wraps the key with a freshly generated Keystore key, to recover after
     * biometric enrolment invalidated the previous one.
     */
    fun repairKeystoreWrapping(databaseKey: ByteArray) {
        // Repairs whichever wrapping is in use, rather than always the legacy
        // one. Regenerating the TEE key while the StrongBox blob is the live
        // one would leave the broken key in place and write a wrapping nothing
        // reads.
        val alias = if (usingStrongBox) KEYSTORE_ALIAS_STRONGBOX else KEYSTORE_ALIAS
        val pref = if (usingStrongBox) KEY_KEYSTORE_WRAPPED_STRONGBOX else KEY_KEYSTORE_WRAPPED

        keyStore().deleteEntry(alias)
        val key = generateKey(alias, strongBox = usingStrongBox)
        val blob = KeyEnvelope.wrapWithGeneratedIv(key, databaseKey)

        @Suppress("UseKtx")
        val saved = prefs.edit().putString(pref, blob).commit()
        if (!saved) throw IllegalStateException("The device key could not be saved.")
    }

    /** Wipes every key. The database becomes permanently unreadable. */
    fun destroyAllKeys() {
        prefs.edit(commit = true) { clear() }
        runCatching { keyStore().deleteEntry(KEYSTORE_ALIAS) }
        runCatching { keyStore().deleteEntry(KEYSTORE_ALIAS_STRONGBOX) }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(alias: String): SecretKey? =
        (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    /**
     * The wrapping key, created in the security chip when there is one.
     *
     * A fresh install goes straight into StrongBox. The fallback is for
     * devices without the chip, and for those that advertise it then refuse
     * these parameters, which does happen.
     */
    private fun keystoreKey(): SecretKey {
        existingKey(KEYSTORE_ALIAS_STRONGBOX)?.let { return it }
        existingKey(KEYSTORE_ALIAS)?.let { return it }

        if (strongBoxAvailable) {
            runCatching { return generateKey(KEYSTORE_ALIAS_STRONGBOX, strongBox = true) }
        }
        return generateKey(KEYSTORE_ALIAS, strongBox = false)
    }

    private fun generateKey(alias: String, strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately not setRandomizedEncryptionRequired(false).
                // Leaving it on is what forces the Keystore to generate its
                // own IV, which is the safer default.
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "QLKeys"
        private const val PREFS_NAME = "quietledger_keys"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "quietledger_db_wrapping_key"

        // A separate alias rather than reusing the first. The old key has to
        // keep working until the new wrapping is proven, and one alias cannot
        // hold both.
        private const val KEYSTORE_ALIAS_STRONGBOX = "quietledger_db_wrapping_key_sb"

        private const val KEY_KEYSTORE_WRAPPED = "db_key_keystore"
        private const val KEY_KEYSTORE_WRAPPED_STRONGBOX = "db_key_keystore_sb"
        private const val KEY_PASSPHRASE_WRAPPED = "db_key_passphrase"
        private const val KEY_PASSPHRASE_SALT = "passphrase_salt"
    }
}

/** No key exists yet, so the app needs setting up rather than unlocking. */
class NoKeyException(message: String) : Exception(message)

class KeystoreUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class WrongPassphraseException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
