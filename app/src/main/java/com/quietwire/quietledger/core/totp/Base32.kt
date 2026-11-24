package com.quietwire.quietledger.core.totp

/**
 * RFC 4648 base32, which is what authenticator apps expect for a shared secret.
 *
 * Hand-rolled because `android.util.Base64` is a different alphabet entirely and
 * the JDK has no base32. Decoding is deliberately lenient about the things users
 * get wrong when typing a key by hand: lowercase, spaces, and the padding that
 * authenticator apps usually omit.
 */
object Base32 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val out = StringBuilder()
        var buffer = 0
        var bitsLeft = 0

        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                out.append(ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            out.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return out.toString()
    }

    /** @throws IllegalArgumentException on a character that is not valid base32. */
    fun decode(encoded: String): ByteArray {
        val cleaned = encoded
            .uppercase()
            .replace(" ", "")
            .replace("-", "")
            .trimEnd('=')
        if (cleaned.isEmpty()) return ByteArray(0)

        val out = ArrayList<Byte>(cleaned.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0

        for (character in cleaned) {
            val value = ALPHABET.indexOf(character)
            require(value >= 0) { "'$character' is not a valid character in a secret key" }
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }

    /** Groups into fours, which is how authenticator apps display a manual key. */
    fun formatForDisplay(encoded: String): String =
        encoded.chunked(4).joinToString(" ")
}
