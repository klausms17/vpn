package com.klausms.vpn.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Finds a QR code in the brightness plane of a camera frame (the Y plane of
 * YUV_420_888, rows [rowStride] bytes apart). Offline, nothing leaves the
 * phone. Every other frame is read inverted too, for light codes on a dark
 * screen. One per scanning session, used from one thread.
 */
class QrDecoder {
    private val reader = QRCodeReader()

    // Codes without an encoding marker are read as UTF-8 (server names).
    private val hints = mapOf(DecodeHintType.CHARACTER_SET to "UTF-8")
    private var frames = 0L

    fun decode(luma: ByteArray, rowStride: Int, left: Int, top: Int, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || left < 0 || top < 0 || left + width > rowStride) return null
        val source = PlanarYUVLuminanceSource(luma, rowStride, top + height, left, top, width, height, false)
        val inverted = frames++ % 2 == 1L
        val bitmap = BinaryBitmap(HybridBinarizer(if (inverted) source.invert() else source))
        return try {
            reader.decode(bitmap, hints).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
