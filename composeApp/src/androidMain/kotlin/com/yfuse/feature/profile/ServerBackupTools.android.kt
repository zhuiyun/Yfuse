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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.YfButton
import com.yfuse.core.designsystem.YfButtonTone
import com.yfuse.core.designsystem.flatGlass as glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sc
import com.yfuse.core.logging.AppLog
import com.yfuse.core.migration.MigrationRelayApi
import com.yfuse.core.security.RelayMigrationDescriptor
import com.yfuse.core.security.RelayMigrationPackage
import com.yfuse.core.security.ServerMigrationCrypto
import com.yfuse.core.security.toBase64Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun ServerBackupTools(
    serverCount: Int,
    onExport: (CharArray, Long) -> Result<String>,
    onImport: (String, CharArray, Long) -> Result<Int>,
    onExportRelay: (Long) -> Result<RelayMigrationPackage>,
    onInspectRelay: (String) -> RelayMigrationDescriptor,
    onIsRelay: (String) -> Boolean,
    onImportRelay: (String, ByteArray, Long) -> Result<Int>,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val activity = context.findActivity()
    var showQr by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingFilePayload by remember { mutableStateOf<String?>(null) }
    var pendingImportPayload by remember { mutableStateOf<String?>(null) }
    var activeMigrationCode by remember { mutableStateOf<String?>(null) }
    val relayApi = migrationRelayApi

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

    fun validateLegacyPassphrase(): Boolean {
        if (passphrase.length !in
            ServerMigrationCrypto.MIN_PASSPHRASE_LENGTH..ServerMigrationCrypto.MAX_PASSPHRASE_LENGTH
        ) {
            message = "迁移口令至少 ${ServerMigrationCrypto.MIN_PASSPHRASE_LENGTH} 个字符"
            return false
        }
        return true
    }

    suspend fun createProtectedPayload(): String? {
        val now = System.currentTimeMillis() / 1_000L
        val migration =
            withContext(Dispatchers.Default) { onExportRelay(now) }
                .onFailure {
                    AppLog.warning(
                        category = "server.migration",
                        event = "protected_content_create_failed",
                        message = "Protected server migration content could not be created",
                        throwable = it,
                    )
                    message = it.message ?: "生成受保护迁移包失败"
                }.getOrNull() ?: return null
        return try {
            val ticket =
                relayApi.create(
                    migration.relayId,
                    migration.transferSecret.toBase64Url(),
                    migration.payloadSha256,
                )
            activeMigrationCode = ticket.code
            message = "迁移码 ${ticket.code}，15 分钟内有效且只能使用一次"
            migration.envelope
        } catch (error: Exception) {
            AppLog.warning(
                category = "server.migration",
                event = "relay_create_failed",
                message = "One-time migration relay could not be created",
                throwable = error,
            )
            message = error.message ?: "迁移服务暂不可用，请检查网络"
            null
        } finally {
            migration.clearSecret()
        }
    }

    fun importText(text: String) {
        val decoded =
            runCatching { decodeQrPayload(text) }.getOrElse {
                message = it.message ?: "迁移包无效"
                return
            }
        pendingImportPayload = decoded
        passphrase = ""
        message =
            if (onIsRelay(decoded)) {
                "已读取迁移包，请输入源设备显示的 6 位迁移码"
            } else {
                "这是旧版迁移包，请输入原来的至少 12 位保护口令"
            }
    }

    fun importPendingPayload() {
        val payload =
            pendingImportPayload ?: run {
                message = "请先扫描二维码或选择迁移文件"
                return
            }
        scope.launch {
            val now = System.currentTimeMillis() / 1_000L
            val result =
                if (onIsRelay(payload)) {
                    if (passphrase.length != 6 || passphrase.any { it !in '0'..'9' }) {
                        message = "请输入 6 位数字迁移码"
                        return@launch
                    }
                    runCatching {
                        val descriptor = onInspectRelay(payload)
                        val secret = relayApi.redeem(descriptor.relayId, passphrase, descriptor.payloadSha256)
                        try {
                            withContext(Dispatchers.Default) {
                                onImportRelay(payload, secret, now).getOrThrow()
                            }
                        } finally {
                            secret.fill(0)
                        }
                    }
                } else {
                    if (!validateLegacyPassphrase()) return@launch
                    val secret = passphrase.toCharArray()
                    withContext(Dispatchers.Default) {
                        try {
                            onImport(payload, secret, now)
                        } finally {
                            secret.fill('\u0000')
                        }
                    }
                }
            result
                .onSuccess {
                    AppLog.info(
                        category = "server.migration",
                        event = "protected_content_imported",
                        message = "Protected server migration content imported",
                        attributes = mapOf("serverCount" to it.toString()),
                    )
                    passphrase = ""
                    pendingImportPayload = null
                    message = "已安全导入 $it 个服务器；迁移码已失效，请删除文件/二维码"
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

    val exportFile =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            val payload = pendingFilePayload
            pendingFilePayload = null
            if (uri != null && payload != null) {
                runCatching {
                    context.contentResolver
                        .openOutputStream(uri)
                        ?.bufferedWriter()
                        ?.use { it.write(payload) }
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
    val importFile =
        rememberLauncherForActivityResult(
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
    val saveQr =
        rememberLauncherForActivityResult(
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
    val importQrImage =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent(),
        ) { uri ->
            if (uri != null) {
                runCatching {
                    val bitmap =
                        context.contentResolver
                            .openInputStream(uri)
                            ?.use(BitmapFactory::decodeStream)
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
    val scanner =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            result.data?.getStringExtra(QrScannerActivity.EXTRA_RESULT)?.let(::importText)
        }
    val cameraPermission =
        rememberLauncherForActivityResult(
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
                passphrase =
                    if (pendingImportPayload?.let(onIsRelay) == true) {
                        it.filter(Char::isDigit).take(6)
                    } else {
                        it.take(ServerMigrationCrypto.MAX_PASSPHRASE_LENGTH)
                    }
                message = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    if (pendingImportPayload?.let(onIsRelay) == true) {
                        "6 位数字迁移码"
                    } else {
                        "迁移码（新文件为 6 位；旧文件为原口令）"
                    },
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType =
                        if (pendingImportPayload?.let(onIsRelay) == true) {
                            KeyboardType.NumberPassword
                        } else {
                            KeyboardType.Password
                        },
                ),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        if (pendingImportPayload != null) {
            YfButton(
                label = "确认导入",
                enabled = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = ::importPendingPayload,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YfButton(
                label = "生成迁移码",
                onClick = {
                    scope.launch {
                        val payload = createProtectedPayload() ?: return@launch
                        val encoded =
                            runCatching { encodeQrPayload(payload) }.getOrElse {
                                message = it.message ?: "迁移包无法编码为二维码"
                                return@launch
                            }
                        qrBitmap =
                            runCatching { qrBitmap(encoded) }
                                .onFailure {
                                    AppLog.warning(
                                        category = "server.migration",
                                        event = "qr_generation_failed",
                                        message = "Protected server migration QR code generation failed",
                                        throwable = it,
                                        attributes = mapOf("payloadChars" to encoded.length.toString()),
                                    )
                                }.getOrNull()
                        if (qrBitmap == null) {
                            message = "服务器过多，二维码容量不足，请使用受保护文件导出"
                        } else {
                            showQr = true
                        }
                    }
                },
                enabled = serverCount > 0,
                modifier = Modifier.weight(1f),
            )
            YfButton(
                label = "扫描迁移码",
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        scanner.launch(Intent(context, QrScannerActivity::class.java))
                    } else {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
                enabled = true,
                modifier = Modifier.weight(1f),
                tone = YfButtonTone.Secondary,
            )
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
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(Brand.Primary.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 11.dp, vertical = 8.dp),
            )
        }
        Text(
            "新迁移包由随机 256 位密钥使用 AES-256-GCM 加密；6 位迁移码仅能在线尝试 5 次，" +
                "15 分钟后失效且只能兑换一次。服务端不接收或保存备份内容。旧版强口令文件仍可导入。" +
                "导入后请删除文件/二维码；" +
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
                subtitle = "迁移码 ${activeMigrationCode ?: "------"} · 15 分钟内仅可使用一次",
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

/** Process-wide, immutable client; it owns a pooled HTTP engine and is safe to reuse. */
private val migrationRelayApi: MigrationRelayApi by lazy { MigrationRelayApi() }

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
        modifier =
            modifier
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

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
