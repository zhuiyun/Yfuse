package com.yfuse.feature.profile

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.flatGlass as glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.core.logging.AppLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Composable
actual fun ServerBackupTools(
    payload: String,
    serverCount: Int,
    onImport: (String) -> Result<Int>,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = LocalPalette.current
    var showQr by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val qrPayload = remember(payload) { encodeQrPayload(payload) }
    val qrBitmap = remember(qrPayload) {
        runCatching { qrBitmap(qrPayload) }
            .onFailure {
                AppLog.warning(
                    category = "server.migration",
                    event = "qr_generation_failed",
                    message = "Server migration QR code generation failed",
                    throwable = it,
                    attributes = mapOf("payloadChars" to qrPayload.length.toString()),
                )
            }
            .getOrNull()
    }

    fun importText(text: String) {
        runCatching { decodeQrPayload(text) }
            .mapCatching { onImport(it).getOrThrow() }
            .onSuccess {
                AppLog.info(
                    category = "server.migration",
                    event = "content_imported",
                    message = "Server migration content imported",
                    attributes = mapOf("serverCount" to it.toString()),
                )
                message = "已导入 $it 个服务器"
            }
            .onFailure {
                AppLog.warning(
                    category = "server.migration",
                    event = "content_import_failed",
                    message = "Server migration content import failed",
                    throwable = it,
                )
                message = it.message ?: "导入失败"
            }
    }

    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(payload) }
                    ?: error("无法写入文件")
            }.onSuccess {
                AppLog.info(
                    category = "server.migration",
                    event = "file_exported",
                    message = "Server backup file exported",
                )
                message = "服务器备份已保存"
            }.onFailure {
                AppLog.error(
                    category = "server.migration",
                    event = "file_export_failed",
                    message = "Server backup file export failed",
                    throwable = it,
                )
                message = it.message ?: "保存失败"
            }
        }
    }
    val importFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("无法读取文件")
            }.onSuccess(::importText)
                .onFailure {
                    AppLog.warning(
                        category = "server.migration",
                        event = "file_read_failed",
                        message = "Server backup file could not be read",
                        throwable = it,
                    )
                    message = it.message ?: "读取失败"
                }
        }
    }
    val saveQr = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        if (uri != null && qrBitmap != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    check(qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                } ?: error("无法写入图片")
            }.onSuccess {
                AppLog.info(
                    category = "server.migration",
                    event = "qr_exported",
                    message = "Server migration QR image exported",
                )
                message = "二维码已保存"
            }.onFailure {
                AppLog.error(
                    category = "server.migration",
                    event = "qr_export_failed",
                    message = "Server migration QR image export failed",
                    throwable = it,
                )
                message = it.message ?: "保存失败"
            }
        }
    }
    val importQrImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val bitmap = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                    ?: error("无法读取图片")
                decodeQrBitmap(bitmap)
            }.onSuccess(::importText)
                .onFailure {
                    AppLog.warning(
                        category = "server.migration",
                        event = "qr_image_unrecognized",
                        message = "Selected image did not contain a valid server migration QR code",
                        throwable = it,
                    )
                    message = "未识别到有效的服务器二维码"
                }
        }
    }
    val scanner = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.getStringExtra(QrScannerActivity.EXTRA_RESULT)?.let(::importText)
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scanner.launch(Intent(context, QrScannerActivity::class.java))
        } else {
            AppLog.warning(
                category = "server.migration",
                event = "camera_permission_denied",
                message = "Camera permission denied for server migration scanner",
            )
            message = "需要相机权限才能扫码；仍可从相册导入二维码"
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .glass(GlassShapes.card, palette.card, palette.border)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .glass(
                        CircleShape,
                        Brand.Primary.copy(alpha = 0.14f),
                        Brand.Primary.copy(alpha = 0.24f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.Server,
                    contentDescription = null,
                    tint = Brand.Primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text("迁移服务器", style = sc(14f, 750), color = palette.text)
                Spacer(Modifier.height(3.dp))
                Text(
                    if (serverCount > 0) "$serverCount 个服务器，可迁移登录状态" else "暂无可迁移的服务器",
                    style = mr(10.5f, 450),
                    color = palette.sub2,
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MigrationPrimaryAction(
                label = "生成迁移码",
                icon = AppIcons.Server,
                primary = true,
                enabled = serverCount > 0,
                modifier = Modifier.weight(1f),
            ) {
                if (qrBitmap == null) message = "服务器过多，二维码容量不足，请使用文件导出"
                else showQr = true
            }
            MigrationPrimaryAction(
                label = "扫描迁移码",
                icon = AppIcons.Search,
                primary = false,
                enabled = true,
                modifier = Modifier.weight(1f),
            ) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    scanner.launch(Intent(context, QrScannerActivity::class.java))
                } else {
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .glass(
                    RoundedCornerShape(13.dp),
                    palette.card2,
                    palette.border.copy(alpha = 0.72f),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MigrationLink("导出文件", Modifier.weight(1f), serverCount > 0) {
                exportFile.launch("Yfuse-servers.json")
            }
            MigrationDivider()
            MigrationLink("导入文件", Modifier.weight(1f), true) {
                importFile.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
            MigrationDivider()
            MigrationLink("相册识别", Modifier.weight(1f), true) {
                importQrImage.launch("image/*")
            }
        }

        message?.let {
            Text(
                it,
                style = mr(10.5f, 600),
                color = Brand.Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brand.Primary.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            )
        }
        Text(
            "迁移数据包含访问令牌，请只在可信设备间使用。",
            style = mr(9.5f, 400),
            color = palette.sub2,
        )
    }

    if (showQr && qrBitmap != null) {
        val activity = context.findActivity()
        DisposableEffect(activity) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        GlassDialog(onDismiss = { showQr = false }) {
            OverlayHeader(
                title = "服务器迁移二维码",
                subtitle = "二维码包含访问令牌，请勿发送给不可信的人。",
                onClose = { showQr = false },
            )
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "服务器迁移二维码",
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OverlayButton(
                    label = "关闭",
                    onClick = { showQr = false },
                    modifier = Modifier.weight(1f),
                )
                OverlayButton(
                    label = "保存二维码",
                    onClick = { saveQr.launch("Yfuse-servers-qr.png") },
                    modifier = Modifier.weight(1f),
                    tone = OverlayButtonTone.Primary,
                )
            }
        }
    }
}

@Composable
private fun MigrationPrimaryAction(
    label: String,
    icon: ImageVector,
    primary: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        modifier
            .glass(
                RoundedCornerShape(16.dp),
                if (primary) {
                    Brand.Primary.copy(alpha = if (enabled) 0.76f else 0.25f)
                } else {
                    palette.card2
                },
                if (primary) Color.White.copy(alpha = 0.28f) else palette.border,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (primary) Color.White else Brand.Primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(7.dp))
        Text(
            label,
            style = sc(11.5f, 700),
            color = if (primary) Color.White else palette.text,
        )
    }
}

@Composable
private fun MigrationLink(
    label: String,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = mr(10.5f, 650),
        color = if (enabled) Brand.Primary else LocalPalette.current.sub2,
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 11.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun MigrationDivider() {
    val palette = LocalPalette.current
    Box(
        Modifier
            .size(width = 1.dp, height = 18.dp)
            .background(palette.border.copy(alpha = if (palette.isDark) 0.24f else 0.48f)),
    )
}

private fun qrBitmap(value: String): Bitmap {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 2,
    )
    val matrix: BitMatrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 768, 768, hints)
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

private fun decodeQrBitmap(bitmap: Bitmap): String {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    return MultiFormatReader().decode(
        BinaryBitmap(HybridBinarizer(source)),
        mapOf(DecodeHintType.CHARACTER_SET to "UTF-8", DecodeHintType.TRY_HARDER to true),
    ).text
}

internal fun encodeQrPayload(raw: String): String {
    val bytes = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(raw.encodeToByteArray()) }
        output.toByteArray()
    }
    return "YFUSE1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal fun decodeQrPayload(value: String): String {
    if (!value.startsWith("YFUSE1:")) return value
    val bytes = try {
        Base64.getUrlDecoder().decode(value.removePrefix("YFUSE1:"))
    } catch (e: IllegalArgumentException) {
        error("不是有效的迁移二维码（编码格式不匹配）")
    }
    return try {
        GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader().use { it.readText() }
    } catch (e: java.util.zip.ZipException) {
        error("不是有效的迁移二维码（数据已损坏）")
    } catch (e: java.io.IOException) {
        error("不是有效的迁移二维码（数据已损坏）")
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
