package com.quietwire.quietledger.core.totp

import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

/**
 * Time-based one-time passwords, RFC 6238.
 *
 * Verified against the official RFC test vectors, which is the only sensible way
 * to be confident: a TOTP implementation that is subtly wrong still produces
 * plausible-looking six digit codes, and you would not notice until a real
 * authenticator app disagreed with it.
 *
 * SHA-1 rather than something modern, not by preference but because it is what
 * authenticator apps actually implement. Proton Pass, Aegis, Google
 * Authenticator and the rest default to SHA-1, and a secret they cannot read is
 * useless. HMAC-SHA1 is not broken for this purpose; the SHA-1 collision work
 * does not apply to HMAC.
 */
object Totp {

    const val DIGITS = 6
    const val PERIOD_SECONDS = 30L
    private const val ALGORITHM = "HmacSHA1"

    /**
     * How many windows either side of now are accepted. One window means a code
     * stays valid for up to 90 seconds, which covers a phone clock drifting and
     * a user typing slowly without widening the window unreasonably.
     */
    const val DRIFT_WINDOWS = 1

    /** 160 bits, matching the HMAC-SHA1 block size, as RFC 4226 recommends. */
    fun generateSecret(): ByteArray =
        ByteArray(20).also { SecureRandom().nextBytes(it) }

    fun code(
        secret: ByteArray,
        timeMillis: Long,
        digits: Int = DIGITS,
        periodSeconds: Long = PERIOD_SECONDS
    ): String {
        val counter = timeMillis / 1000L / periodSeconds
        return hotp(secret, counter, digits)
    }

    /**
     * Constant-time comparison against the accepted windows, so the time taken
     * does not reveal how much of a guess was correct.
     */
    fun verify(
        secret: ByteArray,
        candidate: String,
        timeMillis: Long,
        digits: Int = DIGITS,
        periodSeconds: Long = PERIOD_SECONDS,
        driftWindows: Int = DRIFT_WINDOWS
    ): Boolean {
        val entered = candidate.filter { it.isDigit() }
        if (entered.length != digits) return false

        val counter = timeMillis / 1000L / periodSeconds
        var matched = false
        for (offset in -driftWindows..driftWindows) {
            // No early exit: every window is checked so the work is constant.
            if (constantTimeEquals(hotp(secret, counter + offset, digits), entered)) {
                matched = true
            }
        }
        return matched
    }

    /** Seconds until the current code expires, for a countdown in the UI. */
    fun secondsRemaining(timeMillis: Long, periodSeconds: Long = PERIOD_SECONDS): Int =
        (periodSeconds - (timeMillis / 1000L) % periodSeconds).toInt()

    /**
     * The `otpauth://` URI an authenticator app reads from a QR code. Also
     * usable as a tappable link if the user has an authenticator installed on
     * the same device.
     */
    fun provisioningUri(
        secret: ByteArray,
        accountName: String,
        issuer: String = "QuietLedger"
    ): String {
        // Literal colon between issuer and account. The Key URI spec permits a
        // percent-encoded one, but a good number of authenticator apps only
        // split on the literal character, so %3A gets treated as part of the
        // account name or rejected outright.
        val encodedIssuer = URLEncoder.encode(issuer, "UTF-8").replace("+", "%20")
        val encodedAccount = URLEncoder.encode(accountName, "UTF-8").replace("+", "%20")

        // Only the two parameters that carry information. algorithm, digits and
        // period are all at their defaults of SHA1, 6 and 30, and sending them
        // explicitly is a known source of incompatibility: some readers expect
        // "SHA1", others "SHA-1", and a few reject the parameter entirely.
        // Omitting them is both more compatible and a smaller QR code.
        val params = listOf(
            "secret=${Base32.encode(secret)}",
            "issuer=$encodedIssuer"
        )
        return "otpauth://totp/$encodedIssuer:$encodedAccount?${params.joinToString("&")}"
    }

    private fun hotp(secret: ByteArray, counter: Long, digits: Int): String {
        val message = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            message[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }

        val mac = Mac.getInstance(ALGORITHM).apply {
            init(SecretKeySpec(secret, ALGORITHM))
        }
        val hash = mac.doFinal(message)

        // Dynamic truncation, RFC 4226 section 5.3.
        val offset = (hash[hash.size - 1] and 0x0F).toInt()
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        var modulus = 1
        repeat(digits) { modulus *= 10 }
        return (binary % modulus).toString().padStart(digits, '0')
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var difference = 0
        for (i in a.indices) {
            difference = difference or (a[i].code xor b[i].code)
        }
        return difference == 0
    }
}
