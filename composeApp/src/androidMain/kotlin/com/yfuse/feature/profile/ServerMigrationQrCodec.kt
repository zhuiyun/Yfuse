package com.yfuse.feature.profile

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** QR/image transport codec for protected server-migration payloads. */
internal fun qrBitmap(value: String): Bitmap {
    val hints =
        mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 2,
        )
    val matrix: BitMatrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 768, 768, hints)
    val pixels =
        IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) {
                0xFF000000.toInt()
            } else {
                0xFFFFFFFF.toInt()
            }
        }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

internal fun decodeQrBitmap(bitmap: Bitmap): String {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    return MultiFormatReader()
        .decode(
            BinaryBitmap(HybridBinarizer(source)),
            mapOf(DecodeHintType.CHARACTER_SET to "UTF-8", DecodeHintType.TRY_HARDER to true),
        ).text
}

internal fun encodeQrPayload(raw: String): String {
    require(raw.length <= MAX_MIGRATION_TEXT_CHARS) { "受保护迁移包过大" }
    val bytes =
        ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(raw.encodeToByteArray()) }
            output.toByteArray()
        }
    val prefix = if (raw.contains("\"v\":3")) "YFUSE3:" else "YFUSE2:"
    return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal fun decodeQrPayload(value: String): String {
    val trimmed = value.trim()
    if (trimmed.startsWith("YFUSE1:")) {
        error("旧版二维码包含未保护凭据，已停止导入；请在原设备重新生成")
    }
    val prefix =
        when {
            trimmed.startsWith("YFUSE3:") -> "YFUSE3:"
            trimmed.startsWith("YFUSE2:") -> "YFUSE2:"
            else -> null
        }
    if (prefix == null) {
        require(trimmed.length <= MAX_MIGRATION_TEXT_CHARS) { "受保护迁移包过大" }
        return trimmed
    }
    require(trimmed.length <= MAX_QR_ENCODED_CHARS) { "迁移二维码数据过大" }
    val bytes =
        try {
            Base64.getUrlDecoder().decode(trimmed.removePrefix(prefix))
        } catch (_: IllegalArgumentException) {
            error("不是有效的迁移二维码（编码格式不匹配）")
        }
    return try {
        GZIPInputStream(ByteArrayInputStream(bytes)).use(::readLimitedText)
    } catch (_: java.util.zip.ZipException) {
        error("不是有效的迁移二维码（数据已损坏）")
    } catch (_: java.io.IOException) {
        error("不是有效的迁移二维码（数据已损坏）")
    }
}

internal fun readLimitedText(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1_024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        require(total <= MAX_MIGRATION_TEXT_CHARS) { "受保护迁移包过大" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray().decodeToString()
}

private const val MAX_MIGRATION_TEXT_CHARS = 512 * 1_024
private const val MAX_QR_ENCODED_CHARS = 768 * 1_024
