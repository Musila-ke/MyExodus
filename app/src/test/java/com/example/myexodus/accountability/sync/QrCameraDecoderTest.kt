package com.example.myexodus.accountability.sync

import android.app.Application
import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CameraX 1.6.2 installs no catch around the analyzer call — verified in the pinned
 * artifact's bytecode (`ImageAnalysisAbstractAnalyzer.lambda$analyzeImage$0` has no
 * exception table around `Analyzer.analyze`) — so [QrCameraDecoder.analyze] must be
 * total: a hostile/corrupt frame or a throwing [QrCameraDecoder] callback costs one
 * frame, never the process. The round-trip test proves the whole decode path end to end
 * with a real zxing-rendered QR (the class previously had no direct test at all).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QrCameraDecoderTest {

    @Test
    fun qrFrame_decodesToTheOriginalBytes() {
        val text = "MyExodus-QR-roundtrip-0123456789"
        val proxy = FakeImageProxy(320, 320, qrLuminance(text))
        val received = ArrayList<ByteArray>()
        QrCameraDecoder(onFrame = { received.add(it) }).analyze(proxy)
        assertEquals(1, received.size)
        assertArrayEquals(text.toByteArray(Charsets.ISO_8859_1), received[0])
        assertTrue(proxy.closed)
    }

    @Test
    fun blankLuminance_producesNoCallback_andCloses() {
        val proxy = FakeImageProxy(64, 64, ByteArray(64 * 64) { 0xFF.toByte() })
        var called = false
        QrCameraDecoder(onFrame = { called = true }).analyze(proxy)
        assertFalse(called)
        assertTrue(proxy.closed)
    }

    @Test
    fun truncatedPlane_isContainedAndCloses() {
        // 8x2 needs 16 luminance bytes; the plane supplies 4, so the extraction loop throws.
        // Before the containment catch, that exception escaped into CameraX and killed the app.
        val proxy = FakeImageProxy(8, 2, ByteArray(4))
        QrCameraDecoder(onFrame = { }).analyze(proxy) // must not throw
        assertTrue(proxy.closed)
    }

    @Test
    fun throwingOnFrame_isContainedAndCloses() {
        val proxy = FakeImageProxy(320, 320, qrLuminance("boom"))
        QrCameraDecoder(onFrame = { throw IllegalStateException("caller bug") }).analyze(proxy)
        assertTrue(proxy.closed)
    }

    @Test
    fun landscapeRotation_roundTripsThroughTheRotatedAttempt() {
        // rotationDegrees 90/270 makes analyze() run its second, 90°-rotated source attempt,
        // so this pins that branch's behavior on the landscape-capture shape (the upright QR
        // turned 90° counter-clockwise — one of the orientations attempt 1 must resolve).
        // zxing itself tolerates 90° multiples, so attempt 0 may also resolve this buffer;
        // what's pinned either way is byte-exact output and never dropping the callback.
        for (rotation in listOf(90, 270)) {
            val text = "MyExodus-QR-landscape-$rotation"
            val proxy = FakeImageProxy(320, 320, rotateCCW(qrLuminance(text), 320, 320), rotation = rotation)
            val received = ArrayList<ByteArray>()
            QrCameraDecoder(onFrame = { received.add(it) }).analyze(proxy)
            assertEquals(1, received.size)
            assertArrayEquals(text.toByteArray(Charsets.ISO_8859_1), received[0])
            assertTrue(proxy.closed)
        }
    }

    /** 90° counter-clockwise, mapped independently from (x,y) → (W−1−x, y) — deliberately
     *  not a copy of the production helper it exercises. */
    private fun rotateCCW(src: ByteArray, width: Int, height: Int): ByteArray {
        val dst = ByteArray(src.size)
        for (y in 0 until height) {
            for (x in 0 until width) dst[(width - 1 - x) * height + y] = src[y * width + x]
        }
        return dst
    }

    /** One luminance byte per pixel, black = 0x00 white = 0xFF — what the Y plane holds. */
    private fun qrLuminance(text: String, size: Int = 320): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val luminance = ByteArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                luminance[y * size + x] = if (matrix.get(x, y)) 0x00 else 0xFF.toByte()
            }
        }
        return luminance
    }

    private class FakeImageProxy(
        private val width: Int,
        private val height: Int,
        private val luminance: ByteArray,
        private val rotation: Int = 0,
    ) : ImageProxy {

        var closed = false
            private set

        private val plane = object : ImageProxy.PlaneProxy {
            override fun getRowStride(): Int = width
            override fun getPixelStride(): Int = 1
            override fun getBuffer(): ByteBuffer = ByteBuffer.wrap(luminance)
        }

        private val info = object : ImageInfo {
            override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()
            override fun getTimestamp(): Long = 0L
            override fun getRotationDegrees(): Int = rotation
            override fun populateExifData(builder: ExifData.Builder) = Unit
        }

        override fun close() {
            closed = true
        }

        override fun getCropRect(): Rect = Rect(0, 0, width, height)
        override fun setCropRect(rect: Rect?) = Unit
        override fun getFormat(): Int = ImageFormat.YUV_420_888
        override fun getHeight(): Int = height
        override fun getWidth(): Int = width
        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(plane)
        override fun getImageInfo(): ImageInfo = info
        override fun getImage(): android.media.Image? = null
    }
}
