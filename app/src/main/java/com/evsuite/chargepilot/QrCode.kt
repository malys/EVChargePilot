package com.evsuite.chargepilot

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

/**
 * The repository address as a QR, encoded on the device: this app holds no network
 * capability of its own beyond routing, and an About screen is not the place to gain one.
 * Null when the encoder refuses; the caller leaves the image blank rather than guessing.
 */
object QrCode {

    fun generate(content: String, sizePx: Int): Bitmap? = runCatching {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = MultiFormatWriter()
            .encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx) { index ->
            if (matrix.get(index % sizePx, index / sizePx)) Color.BLACK else Color.WHITE
        }
        Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.RGB_565)
    }.getOrNull()
}
