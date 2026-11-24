package com.quietwire.quietledger.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Draws a QR code straight onto a Compose canvas.
 *
 * No bitmap is created at any point, which matters here: the content is a
 * `otpauth://` URI containing the TOTP shared secret, and a bitmap would be an
 * object sitting on the heap holding that secret in a form that could end up in
 * a screenshot buffer or a memory dump. Drawing the modules directly keeps it to
 * a short-lived bit matrix.
 *
 * Quiet zone is set to 1 rather than the default 4, because the composable is
 * already padded by its container and a doubled margin wastes a lot of space on
 * the cover display.
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    foreground: Color = Color.Black,
    background: Color = Color.White
) {
    val matrix = remember(content) {
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            // Request 1x1 to get the raw module grid, 63x63 for a provisioning
            // URI at this correction level. The canvas scales it afterwards.
            //
            // Not a cosmetic detail. Asking for 512x512 makes the writer return
            // a 512x512 matrix, which it will happily do, and the draw loop then
            // paints 262,144 rectangles every frame instead of 3,969. That
            // froze the enrolment screen solid.
            1,
            1,
            mapOf(
                // High correction, because this is often scanned off a glossy
                // phone screen by another phone's camera at an angle.
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
        )
    }

    Canvas(modifier.size(size)) {
        val moduleWidth = this.size.width / matrix.width
        val moduleHeight = this.size.height / matrix.height

        drawRect(color = background, size = this.size)

        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                if (matrix.get(x, y)) {
                    drawRect(
                        color = foreground,
                        topLeft = Offset(x * moduleWidth, y * moduleHeight),
                        // A hair of overlap, otherwise rounding leaves hairline
                        // gaps between modules that some scanners choke on.
                        size = Size(moduleWidth + 0.5f, moduleHeight + 0.5f)
                    )
                }
            }
        }
    }
}
