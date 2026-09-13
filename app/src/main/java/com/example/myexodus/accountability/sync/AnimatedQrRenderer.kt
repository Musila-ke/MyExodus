package com.example.myexodus.accountability.sync

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * §6.3 sender: renders chunk frames as high-contrast QR bitmaps (§15 moiré mitigation:
 * white background, black modules, medium error correction). Frames are encoded binary-safe
 * via ISO-8859-1 so arbitrary envelope bytes survive the QR text pipeline byte-for-byte.
 * The 10 FPS cycling is the UI's loop; this class owns the frame → bitmap mapping.
 */
class AnimatedQrRenderer(
    private val sizePx: Int = 512,
    errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
) {

    private val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "ISO-8859-1",
        EncodeHintType.ERROR_CORRECTION to errorCorrection,
        EncodeHintType.MARGIN to 2,
    )

    /** One bitmap per chunk frame, in order — the UI cycles them at 10 FPS (§6.3). */
    fun frames(payload: ByteArray, sessionId: ByteArray = QrChunkCodec.newSessionId()): List<Bitmap> =
        QrChunkCodec.encode(payload, sessionId).map(::render)

    fun render(frame: ByteArray): Bitmap {
        val matrix = QRCodeWriter().encode(
            String(frame, Charsets.ISO_8859_1),
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            hints,
        )
        val bitmap = createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap[x, y] = if (matrix[x, y]) BLACK else WHITE
            }
        }
        return bitmap
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
