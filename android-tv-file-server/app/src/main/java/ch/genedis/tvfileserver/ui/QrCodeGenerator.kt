package ch.genedis.tvfileserver.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders the connection URL as a QR code so a phone can join without typing an IP.
 *
 * The whole matrix is written through a single `setPixels` call: per-pixel `setPixel` on a
 * 480 px bitmap is roughly a quarter of a million JNI round-trips, which is visible as a
 * stutter on a 1.5 GHz TV box.
 */
object QrCodeGenerator {

    private const val TAG = "QrCodeGenerator"

    fun encode(
        text: String,
        sizePx: Int,
        foreground: Int = Color.BLACK,
        background: Int = Color.WHITE,
    ): Bitmap? {
        if (text.isEmpty() || sizePx <= 0) return null
        return try {
            val hints = mapOf(
                // M tolerates roughly 15 % damage, which is plenty for a screen and keeps
                // the modules large enough to scan from a sofa.
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1,
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            toBitmap(matrix, foreground, background)
        } catch (error: WriterException) {
            Log.w(TAG, "Cannot encode the QR payload", error)
            null
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Rejected QR payload", error)
            null
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory for a ${sizePx}px QR code")
            null
        }
    }

    private fun toBitmap(matrix: BitMatrix, foreground: Int, background: Int): Bitmap {
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) foreground else background
            }
        }
        // RGB_565 halves the memory of an opaque two-colour image and renders identically.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
