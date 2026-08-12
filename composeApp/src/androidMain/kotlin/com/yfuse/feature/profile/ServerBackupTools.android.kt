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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.logging.AppLog
import com.yfuse.core.security.ServerMigrationCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun ServerBackupTools(
    serverCount: Int,
    onExport: (CharArray, Long) -> Result<String>,
    onImport: (String, CharArray, Long) -> Result<Int>,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val activity = context.findActivity()
    var showQr by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingFilePayload by remember { mutableStateOf<String?>(null) }

    // The passphrase fields and generated QR are sensitive even though the on-disk package is
    // encrypted. Keep screenshots/recents previews disabled for this sub-screen.
    DisposableEffect(activity) {
        val existingFlags = activity?.window?.attributes?.flags ?: 0
        val wasAlreadySecure = existingFlags.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!wasAlreadySecure) {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    fun validatePassphrase(forExport: Boolean): Boolean {
        if (passphrase.length !in
            ServerMigrationCrypto.MIN_PASSPHRASE_LENGTH..ServerMigrationCrypto.MAX_PASSPHRASE_LENGTH
        ) {
            message = "迁移口令至少 ${ServerMigrationCrypto.MIN_PASSPHRASE_LENGTH} 个字符"
            return false
        }
        if (forExport && passphrase != confirmation) {
            message = "两次输入的迁移口令不一致"
            return false
        }
        return true
    }

    suspend fun createProtectedPayload(): String? {
        if (!validatePassphrase(forExport = true)) return null
        val secret = passphrase.toCharArray()
        val now = System.currentTimeMillis() / 1_000L
        val result = withContext(Dispatchers.Default) {
            try {
                onExport(secret, now)
            } finally {
                secret.fill('\u0000')
            }
        }
        return result.onSuccess {
            passphrase = ""
            confirmation = ""
            message = "迁移包已用 AES-256-GCM 加密，15 分钟后自动失效"
        }.onFailure {
            AppLog.warning(
                category = "server.migration",
                event = "protected_content_create_failed",
                message = "Protected server migration content could not be created",
                throwable = it,
            )
            message = it.message ?: "生成受保护迁移包失败"
        }.getOrNull()
    }

    fun importText(text: String) {
        if (!validatePassphrase(forExport = false)) return
        val secret = passphrase.toCharArray()
        val now = System.currentTimeMillis() / 1_000L
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    runCatching { decodeQrPayload(text) }
                        .mapCatching { onImport(it, secret, now).getOrThrow() }
                } finally {
                    secret.fill('\u0000')
                }
            }
            result.onSuccess {
                AppLog.info(
                    category = "server.migration",
                    event = "protected_content_imported",
                    message = "Protected server migration content imported",
                    attributes = mapOf("serverCount" to it.toString()),
                )
                passphrase = ""
                confirmation = ""
                message = "已安全导入 $it 个服务器；请删除迁移包"
            }.onFailure {
                AppLog.warning(
                    category = "server.migration",
                    event = "protected_content_import_failed",
                    message = "Protected server migration content import failed",
                    throwable = it,
                )
                message = it.message ?: "导入失败"
            }
        }
    }

    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val payload = pendingFilePayload
        pendingFilePayload = null
        if (uri != null && payload != null) {
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
                context.contentResolver.openInputStream(uri)?.use(::readLimitedText)
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
        val bitmap = qrBitmap
        if (uri != null && bitmap != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
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
                    if (serverCount > 0) {
                        "$serverCount 个服务器；登录状态仅通过受保护迁移包传输"
                    } else {
                        "暂无可迁移的服务器"
                    },
                    style = mr(10.5f, 450),
                    color = palette.sub2,
                )
            }
        }

        OutlinedTextField(
            value = passphrase,
            onValueChange = {
                passphrase = it.take(ServerMigrationCrypto.MAX_PASSPHRASE_LENGTH)
                message = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("迁移口令（至少 12 个字符）") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        OutlinedTextField(
            value = confirmation,
            onValueChange = {
                confirmation = it.take(ServerMigrationCrypto.MAX_PASSPHRASE_LENGTH)
                message = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("再次输入口令（仅导出时校验）") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MigrationPrimaryAction(
                label = "生成迁移码",
                icon = AppIcons.Server,
                primary = true,
                enabled = serverCount > 0,
                modifier = Modifier.weight(1f),
            ) {
                scope.launch {
                    val payload = createProtectedPayload() ?: return@launch
                    val encoded = runCatching { encodeQrPayload(payload) }.getOrElse {
                        message = it.message ?: "迁移包无法编码为二维码"
                        return@launch
                    }
                    qrBitmap = runCatching { qrBitmap(encoded) }
                        .onFailure {
                            AppLog.warning(
                                category = "server.migration",
                                event = "qr_generation_failed",
                                message = "Protected server migration QR code generation failed",
                                throwable = it,
                                attributes = mapOf("payloadChars" to encoded.length.toString()),
                            )
                        }
                        .getOrNull()
                    if (qrBitmap == null) {
                        message = "服务器过多，二维码容量不足，请使用受保护文件导出"
                    } else {
                        showQr = true
                    }
                }
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
                scope.launch {
                    createProtectedPayload()?.let {
                        pendingFilePayload = it
                        exportFile.launch("Yfuse-servers.secure.json")
                    }
                }
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
            "口令通过 PBKDF2-HMAC-SHA256（600,000 次）派生密钥，内容使用 AES-256-GCM 认证加密。" +
                "迁移包 15 分钟后失效；每次导出都会轮换盐值和随机数。导入后请删除文件/二维码；" +
                "若曾外泄，请在 Emby 注销会话以轮换访问令牌。",
            style = mr(9.5f, 400),
            color = palette.sub2,
        )
    }

    val displayedQr = qrBitmap
    if (showQr && displayedQr != null) {
        GlassDialog(onDismiss = { showQr = false }) {
            OverlayHeader(
                title = "受保护的服务器迁移码",
                subtitle = "已加密并将在 15 分钟后失效；口令请通过另一条可信渠道传递。",
                onClose = { showQr = false },
            )
            Image(
                bitmap = displayedQr.asImageBitmap(),
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
            .pressable(enabled = enabled, onClick = onClick)
            .glass(
                RoundedCornerShape(16.dp),
                if (primary) {
                    Brand.Primary.copy(alpha = if (enabled) 0.76f else 0.25f)
                } else {
                    palette.card2
                },
                if (primary) Color.White.copy(alpha = 0.28f) else palette.border,
            )
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
            .pressable(enabled = enabled, onClick = onClick)
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
    require(raw.length <= MAX_MIGRATION_TEXT_CHARS) { "受保护迁移包过大" }
    val bytes = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(raw.encodeToByteArray()) }
        output.toByteArray()
    }
    return "YFUSE2:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal fun decodeQrPayload(value: String): String {
    val trimmed = value.trim()
    if (trimmed.startsWith("YFUSE1:")) {
        error("旧版二维码包含未保护凭据，已停止导入；请在原设备重新生成")
    }
    if (!trimmed.startsWith("YFUSE2:")) {
        require(trimmed.length <= MAX_MIGRATION_TEXT_CHARS) { "受保护迁移包过大" }
        return trimmed
    }
    require(trimmed.length <= MAX_QR_ENCODED_CHARS) { "迁移二维码数据过大" }
    val bytes = try {
        Base64.getUrlDecoder().decode(trimmed.removePrefix("YFUSE2:"))
    } catch (e: IllegalArgumentException) {
        error("不是有效的迁移二维码（编码格式不匹配）")
    }
    return try {
        GZIPInputStream(ByteArrayInputStream(bytes)).use(::readLimitedText)
    } catch (e: java.util.zip.ZipException) {
        error("不是有效的迁移二维码（数据已损坏）")
    } catch (e: java.io.IOException) {
        error("不是有效的迁移二维码（数据已损坏）")
    }
}

private fun readLimitedText(input: InputStream): String {
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
