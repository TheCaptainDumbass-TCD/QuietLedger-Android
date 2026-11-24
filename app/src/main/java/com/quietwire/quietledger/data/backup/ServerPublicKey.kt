package com.quietwire.quietledger.data.backup

import android.content.Context
import com.quietwire.quietledger.R
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.inject.Singleton

/**
 * The key backups are sealed to, shipped inside the APK.
 *
 * Only the public half is here, and that is the whole design. Extracting it
 * from the APK permits writing a backup, not reading one. A private key in the
 * app would be extractable and would then open every backup ever written, which
 * is why the phone cannot read what it seals.
 *
 * The key id is derived from the key rather than stored beside it, so both this
 * and the server compute the same value independently with nothing to keep in
 * step. It goes in every sealed backup so the server knows which key to try,
 * which is what makes rotation possible.
 */
@Singleton
class ServerPublicKey(
    private val context: Context
) {

    /** Null when no key is bundled, so sealing is simply unavailable. */
    private val loaded: Pair<PublicKey, ByteArray>? by lazy {
        runCatching {
            val der = context.resources.openRawResource(R.raw.server_public_key)
                .use { it.readBytes() }

            val key = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(der))

            // SHA-256 of the DER encoding, first eight bytes. Matches
            // serverkey.key_id on the server.
            val id = MessageDigest.getInstance("SHA-256").digest(der).copyOf(KEY_ID_SIZE)

            key to id
        }.getOrNull()
    }

    val available: Boolean get() = loaded != null

    val key: PublicKey? get() = loaded?.first

    val id: ByteArray? get() = loaded?.second?.copyOf()

    /** For the About screen, so which key is in use can be checked. */
    val idHex: String
        get() = loaded?.second?.joinToString("") { "%02x".format(it) } ?: "none bundled"

    companion object {
        const val KEY_ID_SIZE = 8
    }
}
