package dev.offshare.app.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Renders a pairing URI as a QR bitmap. */
object QrEncoder {

    fun encode(content: String, sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            // A pairing code is short, and the screen it is scanned from may
            // be dim, smudged, or held at an angle. High correction costs a
            // few modules and buys tolerance for all of that.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 2,
        )

        val matrix = MultiFormatWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            hints,
        )

        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }

        // createBitmap defaults to ARGB_8888.
        return createBitmap(width, height).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}

/**
 * CameraX analyzer that reports the first QR code it can read.
 *
 * Reads only the luminance plane of the YUV frame, which the camera already
 * produces -- no colour conversion, no bitmap allocation per frame. Barcode
 * decoding is a brightness problem, so the chroma planes are dead weight.
 */
class QrAnalyzer(
    private val onDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    @Volatile
    private var stopped = false

    override fun analyze(image: ImageProxy) {
        if (stopped) {
            image.close()
            return
        }

        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val source = PlanarYUVLuminanceSource(
                data,
                plane.rowStride,
                image.height,
                0,
                0,
                image.width.coerceAtMost(plane.rowStride),
                image.height,
                false,
            )

            val result = runCatching {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            }.getOrNull()

            if (result != null) {
                stopped = true
                onDecoded(result.text)
            }
        } catch (_: Exception) {
            // A frame that will not decode is the normal case, not an error.
        } finally {
            reader.reset()
            image.close()
        }
    }

    /** Stops reporting further codes, e.g. once one has been accepted. */
    fun stop() {
        stopped = true
    }
}
