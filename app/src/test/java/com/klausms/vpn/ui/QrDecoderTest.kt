package com.klausms.vpn.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    private val key = "vless://0f4b1c9e-7a31-4c8e-9d52-3b6f1e2a7c80@nl.example.com:443" +
        "?security=reality&sni=www.example.com&fp=chrome&pbk=abc&type=tcp#Амстердам 🇳🇱"

    /** A camera-like frame: the code on grey, rows padded to [stride], optionally inverted. */
    private fun frame(text: String, stride: Int, inverted: Boolean = false): Pair<ByteArray, Int> {
        val size = 480
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 360, 360, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        val out = ByteArray(stride * size) { 0x80.toByte() }
        for (y in 0 until size) {
            for (x in 0 until size) {
                val inside = x in 60 until 420 && y in 60 until 420
                val dark = inside && m.get(x - 60, y - 60)
                val light = if (inverted) !dark else dark
                out[y * stride + x] = (if (!inside) 0x80 else if (light) 20 else 235).toByte()
            }
        }
        return out to size
    }

    @Test
    fun readsAKeyWithACyrillicName() {
        val (luma, size) = frame(key, stride = 512)
        assertEquals(key, QrDecoder().decode(luma, 512, 0, 0, size, size))
    }

    @Test
    fun readsALightCodeOnDarkWithinTwoFrames() {
        val (luma, size) = frame("https://sub.example.com/AbCdEf123456", stride = 480, inverted = true)
        val decoder = QrDecoder()
        val results = List(2) { decoder.decode(luma, 480, 0, 0, size, size) }
        assertEquals("https://sub.example.com/AbCdEf123456", results.firstNotNullOf { it })
    }

    @Test
    fun aFrameWithoutACodeGivesNothing() {
        val luma = ByteArray(320 * 240) { (it * 7 % 256).toByte() }
        assertNull(QrDecoder().decode(luma, 320, 0, 0, 320, 240))
        assertNull(QrDecoder().decode(luma, 320, 0, 0, 0, 240))
        assertNull(QrDecoder().decode(luma, 320, 10, 0, 320, 240))
    }
}
