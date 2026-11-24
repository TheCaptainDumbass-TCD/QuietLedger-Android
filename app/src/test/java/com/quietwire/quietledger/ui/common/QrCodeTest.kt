package com.quietwire.quietledger.ui.common

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.quietwire.quietledger.core.totp.Totp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for a frozen enrolment screen.
 *
 * `QRCodeWriter.encode` returns a matrix of the size you ask for, scaling the
 * module grid up to fit. Asking for 512x512 therefore returns a 512x512 matrix,
 * and the Compose draw loop painted every cell of it as a separate rectangle:
 * 262,144 rectangles per frame, which locked the UI thread solid.
 *
 * Requesting 1x1 returns the module grid itself. The canvas scales it to the
 * display size afterwards, which is the cheap way round.
 *
 * These assertions are on ZXing's behaviour rather than on the composable,
 * because the composable cannot be exercised on the JVM and the behaviour being
 * guarded is entirely ZXing's.
 */
class QrCodeTest {

    private val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8"
    )

    private fun matrixFor(requested: Int) = QRCodeWriter()
        .encode(provisioningUri(), BarcodeFormat.QR_CODE, requested, requested, hints)

    private fun provisioningUri(): String =
        Totp.provisioningUri(ByteArray(20) { it.toByte() }, accountName = "My ledger")

    @Test
    fun `requesting one returns the module grid, not a scaled bitmap`() {
        val matrix = matrixFor(1)
        // 63x63 for this URI at correction level H. Asserted as a bound rather
        // than an exact number so a change to the URI does not fail the test
        // spuriously, while still catching a scaled matrix.
        assertTrue(
            "module grid should be small, was ${matrix.width}x${matrix.height}",
            matrix.width in 21..120
        )
        assertEquals(matrix.width, matrix.height)
    }

    @Test
    fun `the draw loop stays within a sane number of rectangles`() {
        val cells = matrixFor(1).let { it.width * it.height }
        // The frozen version drew 262,144. Anything in that region means the
        // requested size has crept back in.
        assertTrue("$cells cells per frame is too many", cells < 20_000)
    }

    @Test
    fun `asking for a large size really does return a large matrix`() {
        // Documents the trap directly, so nobody "tidies" the 1x1 back to 512.
        val matrix = matrixFor(512)
        assertEquals(512, matrix.width)
        assertTrue(512 * 512 > 200_000)
    }

    @Test
    fun `the provisioning uri encodes at high correction without overflowing`() {
        // Level H sacrifices capacity for robustness. If the URI ever grows past
        // what H can carry, encode throws, and this is where that shows up
        // rather than on a device.
        val matrix = matrixFor(1)
        assertTrue(matrix.width > 0)
        assertTrue(provisioningUri().startsWith("otpauth://totp/"))
    }
}
