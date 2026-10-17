package com.example.myexodus.accountability.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §6.3 sender: the renderer was previously unexercised anywhere — this is the first proof
 * that renderer → QR → ISO-8859-1 → reassembler survives byte-for-byte, including every
 * byte value 0x00–0xFF (the wire is binary-safe only because both ends use ISO-8859-1).
 * The decoder-side counterpart lives in [QrCameraDecoderTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AnimatedQrRendererTest {

    @Test
    fun renderedFrames_surviveTheFullQrRoundTrip_byteForByte() {
        // Covers every byte value (0x00..0xFF) over the 600-byte payload → two frames.
        val payload = ByteArray(600) { it.toByte() }
        val sessionId = byteArrayOf(7, 8, 9, 10)
        val renderer = AnimatedQrRenderer()

        val frames = renderer.frames(payload, sessionId)
        assertEquals("600 bytes → 592 + 8, two chunks", 2, frames.size)

        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.CHARACTER_SET to "ISO-8859-1"))
        }
        val reassembler = QrReassembler()
        for (bitmap in frames) {
            val size = bitmap.width
            val luminance = ByteArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val rgb = bitmap.getPixel(x, y) and 0xFFFFFF
                    luminance[y * size + x] = if (rgb == 0) 0x00 else 0xFF.toByte()
                }
            }
            val text = reader.decode(
                BinaryBitmap(
                    HybridBinarizer(
                        PlanarYUVLuminanceSource(luminance, size, size, 0, 0, size, size, false),
                    ),
                ),
            ).text
            reassembler.accept(QrChunkCodec.decode(text.toByteArray(Charsets.ISO_8859_1)))
        }

        assertTrue("every rendered frame must decode back", reassembler.isComplete)
        assertArrayEquals("the payload survives every byte value", payload, reassembler.assembledPayload())
    }

    @Test
    fun render_producesASquareBitmapAtTheConfiguredSize() {
        val bitmap = AnimatedQrRenderer(sizePx = 256).render(QrChunkCodec.encode(byteArrayOf(1))[0])
        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
    }

    @Suppress("unused")
    private val context: Application = ApplicationProvider.getApplicationContext()
}
