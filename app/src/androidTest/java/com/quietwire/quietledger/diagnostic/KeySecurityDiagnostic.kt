package com.quietwire.quietledger.diagnostic

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.SecretKeyFactory

/**
 * Asks the Keystore directly where the database wrapping key lives.
 *
 * Read only. It does not create, move or delete a key.
 *
 * Exists because the About screen's answer comes from code written at the same
 * time as the migration, by the same person, so it is not independent evidence.
 * This queries the key itself. If the two disagree, the screen is wrong.
 */
@RunWith(AndroidJUnit4::class)
class KeySecurityDiagnostic {

    private val tag = "QLKeySec"

    @Test
    fun whereDoesTheKeyLive() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        Log.i(
            tag,
            "device advertises StrongBox: " +
                context.packageManager.hasSystemFeature(
                    PackageManager.FEATURE_STRONGBOX_KEYSTORE
                )
        )

        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        for (alias in store.aliases().toList().filter { it.startsWith("quietledger") }) {
            val key = (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            if (key == null) {
                Log.i(tag, "$alias: present but not a secret key")
                continue
            }

            val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo

            val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX"
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TRUSTED_ENVIRONMENT"
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE"
                    else -> "UNKNOWN(${info.securityLevel})"
                }
            } else {
                "pre-S, isInsideSecureHardware=" +
                    @Suppress("DEPRECATION") info.isInsideSecureHardware
            }

            Log.i(tag, "$alias -> $level, ${info.keySize} bit ${key.algorithm}")
        }

        // Which wrapping the app is actually reading. A StrongBox key that
        // exists but is not used would be no benefit at all.
        val prefs = context.getSharedPreferences("quietledger_keys", Context.MODE_PRIVATE)
        Log.i(
            tag,
            "wrapped key in use: " + when {
                prefs.contains("db_key_keystore_sb") -> "db_key_keystore_sb (StrongBox)"
                prefs.contains("db_key_keystore") -> "db_key_keystore (legacy)"
                else -> "none"
            }
        )
        Log.i(tag, "legacy wrapping still present: ${prefs.contains("db_key_keystore")}")
    }
}
