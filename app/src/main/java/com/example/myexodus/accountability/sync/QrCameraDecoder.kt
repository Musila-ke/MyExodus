package com.example.myexodus.accountability.sync

import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * §11 receiver: CameraX ImageAnalysis analyzer that decodes one animated-QR frame per
 * camera frame. QR is high-contrast black-on-white, so only the Y (luminance) plane is
 * used — this is layout-agnostic across planar/semi-planar YUV_420_888 and cheaper than a
 * full RGB conversion. A rotated capture (landscape sensor) is decoded via a 90°-rotated
 * luminance source before giving up. Decoded bytes go to [onFrame] synchronously.
 */
class QrCameraDecoder(
    private val onFrame: (ByteArray) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "ISO-8859-1",
            ),
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            decode(image)?.let(onFrame)
        } finally {
            image.close()
        }
    }

    private fun decode(image: ImageProxy): ByteArray? {
        val yPlane = image.planes[0]
        val width = image.width
        val height = image.height
        val luminance = ByteArray(width * height)
        for (row in 0 until height) {
            val dst = row * width
            val src = row * yPlane.rowStride
            for (col in 0 until width) luminance[dst + col] = yPlane.buffer.get(src + col * yPlane.pixelStride)
        }
        val rotation = image.imageInfo.rotationDegrees
        val attempts = if (rotation == 90 || rotation == 270) 2 else 1
        for (i in 0 until attempts) {
            val source = if (i == 0) {
                PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
            } else {
                PlanarYUVLuminanceSource(rotate90(luminance, width, height), height, width, 0, 0, height, width, false)
            }
            val result = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))) }
                .getOrNull() ?: continue
            return result.text.toByteArray(Charsets.ISO_8859_1)
        }
        return null
    }

    /** 90° clockwise rotation of raw luminance; width/height swap accordingly. */
    private fun rotate90(src: ByteArray, width: Int, height: Int): ByteArray {
        val dst = ByteArray(src.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                dst[x * height + (height - 1 - y)] = src[y * width + x]
            }
        }
        return dst
    }

    companion object {
        /** §11: fixed 640x480 analysis at KEEP_ONLY_LATEST — drop frames rather than queue them. */
        fun builder(): ImageAnalysis.Builder = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    }
}
