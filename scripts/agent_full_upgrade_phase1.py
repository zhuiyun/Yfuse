from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    text = read(path)
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:180]!r}")
    write(path, text.replace(old, new, count))


# Idempotent after the source commit pushed by the verification workflow.
if (ROOT / "composeApp/src/commonMain/kotlin/com/yfuse/app/AppDependencies.kt").exists():
    print("phase1 already applied")
    raise SystemExit(0)

# ---------------------------------------------------------------- CameraX dependencies
replace(
    "gradle/libs.versions.toml",
    'activity = "1.13.0"\n',
    'activity = "1.13.0"\ncameraX = "1.6.1"\n',
)
replace(
    "gradle/libs.versions.toml",
    'androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activity" }\n',
    'androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activity" }\n'
    'androidx-camera-core = { module = "androidx.camera:camera-core", version.ref = "cameraX" }\n'
    'androidx-camera-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "cameraX" }\n'
    'androidx-camera-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "cameraX" }\n'
    'androidx-camera-view = { module = "androidx.camera:camera-view", version.ref = "cameraX" }\n',
)
replace(
    "composeApp/build.gradle.kts",
    '            implementation(libs.androidx.activity.compose)\n',
    '            implementation(libs.androidx.activity.compose)\n'
    '            implementation(libs.androidx.camera.core)\n'
    '            implementation(libs.androidx.camera.camera2)\n'
    '            implementation(libs.androidx.camera.lifecycle)\n'
    '            implementation(libs.androidx.camera.view)\n',
)

# ---------------------------------------------------------------- Modern CameraX QR scanner
write(
    "composeApp/src/androidMain/kotlin/com/yfuse/feature/profile/QrScannerActivity.kt",
    r'''package com.yfuse.feature.profile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.yfuse.core.logging.AppLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * CameraX QR scanner used by server migration.
 *
 * Preview and analysis are lifecycle-bound. ImageAnalysis keeps only the newest frame and each
 * frame is rotated once from ImageInfo.rotationDegrees before ZXing sees only the central scan
 * region. This replaces the legacy Camera preview/autofocus loop and its per-frame retry matrix.
 */
class QrScannerActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: ScannerOverlayView
    private lateinit var hint: TextView
    private lateinit var torch: TextView
    private var camera: Camera? = null
    private var torchEnabled = false
    private val decoding = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
    }

    private val galleryPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        executor.execute {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                    ?: error("image unavailable")
            }.mapCatching { bitmap ->
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                reader.decodeWithState(
                    BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))),
                ).text
            }.also { reader.reset() }
            result.onSuccess(::finishWithResult)
                .onFailure {
                    runOnUiThread { hint.text = "没有识别到二维码，请选择更清晰的图片" }
                }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    private fun buildUi() {
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        overlay = ScannerOverlayView()
        hint = TextView(this).apply {
            text = "将服务器二维码放入扫描框内"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
        }
        torch = actionText("手电筒") { toggleTorch() }
        val gallery = actionText("从相册识别") {
            galleryPicker.launch(arrayOf("image/*"))
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(torch, LinearLayout.LayoutParams(0, 48.dp, 1f))
            addView(gallery, LinearLayout.LayoutParams(0, 48.dp, 1f))
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(18.dp, 12.dp, 18.dp, 20.dp)
            setBackgroundColor(0x88000000.toInt())
            addView(hint, LinearLayout.LayoutParams(-1, 54.dp))
            addView(actions, LinearLayout.LayoutParams(-1, 48.dp))
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(previewView, FrameLayout.LayoutParams(-1, -1))
                addView(overlay, FrameLayout.LayoutParams(-1, -1))
                addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            },
        )
        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                focus(event.x, event.y)
                true
            } else {
                false
            }
        }
    }

    private fun actionText(label: String, click: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                val provider = runCatching { providerFuture.get() }.getOrElse {
                    AppLog.error("server.migration", "camera_provider_failed", "CameraX provider failed", it)
                    hint.text = "相机启动失败，你仍可以从相册识别二维码"
                    return@addListener
                }
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, ::analyze)
                runCatching {
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }.onFailure {
                    AppLog.error("server.migration", "camera_bind_failed", "CameraX binding failed", it)
                    hint.text = "无法使用相机，你仍可以从相册识别二维码"
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun analyze(image: ImageProxy) {
        if (!decoding.compareAndSet(false, true)) {
            image.close()
            return
        }
        try {
            val plane = image.planes.firstOrNull() ?: return
            val luma = copyLuma(plane, image.width, image.height)
            val rotated = rotateLuma(luma, image.width, image.height, image.imageInfo.rotationDegrees)
            val width = if (image.imageInfo.rotationDegrees % 180 == 0) image.width else image.height
            val height = if (image.imageInfo.rotationDegrees % 180 == 0) image.height else image.width
            val side = (min(width, height) * ROI_FRACTION).toInt().coerceAtLeast(1)
            val left = (width - side) / 2
            val top = (height - side) / 2
            val source = PlanarYUVLuminanceSource(rotated, width, height, left, top, side, side, false)
            val result = runCatching {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            }.getOrNull()
            reader.reset()
            if (!result.isNullOrBlank()) finishWithResult(result)
        } catch (error: Throwable) {
            AppLog.warning(
                category = "server.migration",
                event = "qr_frame_decode_failed",
                message = "QR analyzer skipped one frame",
                throwable = error,
            )
        } finally {
            image.close()
            decoding.set(false)
        }
    }

    private fun copyLuma(plane: ImageProxy.PlaneProxy, width: Int, height: Int): ByteArray {
        val output = ByteArray(width * height)
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var out = 0
        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (col in 0 until width) {
                output[out++] = buffer.get(rowStart + col * pixelStride)
            }
        }
        return output
    }

    private fun rotateLuma(src: ByteArray, width: Int, height: Int, degrees: Int): ByteArray = when (degrees) {
        90 -> ByteArray(src.size).also { dst ->
            var index = 0
            for (x in 0 until width) for (y in height - 1 downTo 0) dst[index++] = src[y * width + x]
        }
        180 -> ByteArray(src.size) { src[src.lastIndex - it] }
        270 -> ByteArray(src.size).also { dst ->
            var index = 0
            for (x in width - 1 downTo 0) for (y in 0 until height) dst[index++] = src[y * width + x]
        }
        else -> src
    }

    private fun focus(x: Float, y: Float) {
        val active = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        active.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
        overlay.showFocus(x, y)
    }

    private fun toggleTorch() {
        val active = camera ?: run {
            hint.text = "相机尚未就绪"
            return
        }
        if (!active.cameraInfo.hasFlashUnit()) {
            hint.text = "当前摄像头没有闪光灯"
            return
        }
        torchEnabled = !torchEnabled
        active.cameraControl.enableTorch(torchEnabled)
        torch.text = if (torchEnabled) "关闭手电筒" else "手电筒"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != REQUEST_CAMERA) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            hint.text = "相机权限被拒绝，可在系统设置开启，或直接从相册识别"
            AppLog.warning(
                category = "server.migration",
                event = "scanner_permission_denied",
                message = "QR scanner camera permission was denied",
            )
        }
    }

    private fun finishWithResult(value: String) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, value))
            finish()
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private inner class ScannerOverlayView : View(this) {
        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.dp.toFloat()
        }
        private val shadePaint = Paint().apply { color = 0x42000000 }
        private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8FB2E8.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.dp.toFloat()
        }
        private var focusX = -1f
        private var focusY = -1f

        fun showFocus(x: Float, y: Float) {
            focusX = x
            focusY = y
            invalidate()
            postDelayed({
                focusX = -1f
                focusY = -1f
                invalidate()
            }, 900)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val side = min(width, height) * ROI_FRACTION
            val left = (width - side) / 2f
            val top = (height - side) / 2f - 28.dp
            val frame = RectF(left, top, left + side, top + side)
            canvas.drawRect(0f, 0f, width.toFloat(), frame.top, shadePaint)
            canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), shadePaint)
            canvas.drawRect(0f, frame.top, frame.left, frame.bottom, shadePaint)
            canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, shadePaint)
            canvas.drawRoundRect(frame, 18.dp.toFloat(), 18.dp.toFloat(), framePaint)
            if (focusX >= 0f) canvas.drawCircle(focusX, focusY, 24.dp.toFloat(), focusPaint)
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_RESULT = "yfuse.qr.result"
        private const val REQUEST_CAMERA = 8401
        private const val ROI_FRACTION = 0.68f
    }
}
''',
)

# ---------------------------------------------------------------- Dependency holder: all global lookups stop at MainActivity.
write(
    "composeApp/src/commonMain/kotlin/com/yfuse/app/AppDependencies.kt",
    '''package com.yfuse.app

import com.yfuse.core.account.AccountRepository
import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.PlaybackRecoveryStore
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.data.ServerHealthMonitor
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.TmdbHomeCache
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.network.LanDiscovery
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.feature.player.PlaybackSourcePreloader
import com.yfuse.feature.watch.WatchInviteResolver

/** Process-scoped services resolved once at the Android composition root. */
data class AppDependencies(
    val calendarRepository: AiringCalendarRepository,
    val tmdbHomeCache: TmdbHomeCache,
    val offlineMediaManager: OfflineMediaManager,
    val playbackTrackRequest: PlaybackTrackRequest,
    val serverSyncManager: ServerSyncManager,
    val watchTogether: WatchTogetherClient,
    val watchTogetherPreferences: WatchTogetherPreferences,
    val inviteResolver: WatchInviteResolver,
    val playbackSourcePreloader: PlaybackSourcePreloader?,
    val playbackRecovery: PlaybackRecoveryStore,
    val playbackPreferences: PlaybackPreferences,
    val userAgentPreferences: UserAgentPreferences,
    val danmakuPreferences: DanmakuPreferences,
    val skipSegmentPreferences: SkipSegmentPreferences,
    val libraryCache: LibraryCache,
    val lanDiscovery: LanDiscovery,
    val account: AccountRepository,
    val serverHealthMonitor: ServerHealthMonitor,
)
''',
)

# ---------------------------------------------------------------- Server health monitor
write(
    "composeApp/src/commonMain/kotlin/com/yfuse/core/data/ServerHealthMonitor.kt",
    '''package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A lightweight health model used by server rows and playback failover decisions. */
enum class ServerHealthStatus { Unknown, Healthy, Degraded, Offline, AuthRequired }

data class ServerHealth(
    val status: ServerHealthStatus = ServerHealthStatus.Unknown,
    val latencyMs: Long? = null,
    val consecutiveFailures: Int = 0,
    val message: String? = null,
) {
    val summary: String
        get() = when (status) {
            ServerHealthStatus.Healthy -> latencyMs?.let { "在线 · ${it} ms" } ?: "在线"
            ServerHealthStatus.Degraded -> message ?: "连接不稳定"
            ServerHealthStatus.Offline -> "无法连接"
            ServerHealthStatus.AuthRequired -> "需要重新登录"
            ServerHealthStatus.Unknown -> "正在检查"
        }
}

class ServerHealthMonitor(
    private val repository: EmbyRepository,
    private val registry: ServerRegistry,
) {
    private val _health = MutableStateFlow<Map<String, ServerHealth>>(emptyMap())
    val health: StateFlow<Map<String, ServerHealth>> = _health.asStateFlow()
    private var started = false

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            registry.data.collectLatest { data ->
                val ids = data.servers.mapTo(hashSetOf()) { it.id }
                _health.value = _health.value.filterKeys { it in ids }
                refreshAll(data.servers)
            }
        }
        scope.launch {
            while (isActive) {
                delay(60_000L)
                refreshAll(registry.data.value.servers)
            }
        }
    }

    suspend fun refreshAll(servers: List<SavedServer> = registry.data.value.servers) = coroutineScope {
        servers.map { server -> async { refresh(server) } }.awaitAll()
        Unit
    }

    suspend fun refresh(server: SavedServer) {
        repository.probeServer(server)
            .onSuccess { latency -> recordSuccess(server.id, latency) }
            .onFailure { recordFailure(server.id, it) }
    }

    fun recordSuccess(serverId: String, latencyMs: Long? = null) {
        update(serverId) {
            ServerHealth(
                status = ServerHealthStatus.Healthy,
                latencyMs = latencyMs ?: it?.latencyMs,
                consecutiveFailures = 0,
            )
        }
    }

    fun recordFailure(serverId: String, error: Throwable) {
        val status = when (val emby = (error as? EmbyErrorException)?.error) {
            EmbyError.Unauthorized, is EmbyError.AccessDenied -> ServerHealthStatus.AuthRequired
            EmbyError.Network -> ServerHealthStatus.Offline
            is EmbyError.Server -> if (emby.code in 500..599) {
                ServerHealthStatus.Degraded
            } else {
                ServerHealthStatus.Offline
            }
            else -> ServerHealthStatus.Degraded
        }
        update(serverId) { previous ->
            ServerHealth(
                status = status,
                latencyMs = previous?.latencyMs,
                consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                message = when (status) {
                    ServerHealthStatus.AuthRequired -> "需要重新登录"
                    ServerHealthStatus.Offline -> "无法连接"
                    else -> "服务器暂时异常"
                },
            )
        }
        AppLog.warning(
            category = "server.health",
            event = "probe_failed",
            message = "Server health probe failed",
            throwable = error,
            attributes = mapOf("serverId" to serverId, "status" to status.name),
        )
    }

    private inline fun update(serverId: String, block: (ServerHealth?) -> ServerHealth) {
        _health.value = _health.value.toMutableMap().apply {
            this[serverId] = block(this[serverId])
        }
    }
}
''',
)

replace(
    "composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyRepository.kt",
    'import kotlin.time.TimeSource\n' if 'import kotlin.time.TimeSource\n' in read("composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyRepository.kt") else 'import kotlinx.serialization.json.Json\n',
    'import kotlin.time.TimeSource\n' if 'import kotlin.time.TimeSource\n' in read("composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyRepository.kt") else 'import kotlinx.serialization.json.Json\nimport kotlin.time.TimeSource\n',
)
replace(
    "composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyRepository.kt",
    '    /** Aggregates the home screen: continue-watching, latest-per-library, featured. */\n',
    '''    /** Authenticated, cheap server probe used by the health monitor. */
    suspend fun probeServer(server: SavedServer): Result<Long> = call("server_probe") {
        val mark = TimeSource.Monotonic.markNow()
        client.get("${normalizeBaseUrl(server.baseUrl)}/System/Info") {
            header("X-Emby-Token", server.accessToken)
        }.bodyAsText()
        mark.elapsedNow().inWholeMilliseconds
    }

    /** Aggregates the home screen: continue-watching, latest-per-library, featured. */
''',
)

replace(
    "composeApp/src/commonMain/kotlin/com/yfuse/di/AppModule.kt",
    'import com.yfuse.core.data.ServerRegistry\n',
    'import com.yfuse.core.data.ServerRegistry\nimport com.yfuse.core.data.ServerHealthMonitor\n',
)
replace(
    "composeApp/src/commonMain/kotlin/com/yfuse/di/AppModule.kt",
    '    single { EmbyRepository(get()) }\n',
    '    single { EmbyRepository(get()) }\n    single { ServerHealthMonitor(get(), get()) }\n',
)

# MainActivity constructs the dependency graph once.
replace(
    "composeApp/src/androidMain/kotlin/com/yfuse/MainActivity.kt",
    'import com.yfuse.app.AnimatedSplashApp\n',
    'import com.yfuse.app.AnimatedSplashApp\nimport com.yfuse.app.AppDependencies\n',
)
replace(
    "composeApp/src/androidMain/kotlin/com/yfuse/MainActivity.kt",
    'import com.yfuse.update.LocalAppUpdateManager\n',
    'import com.yfuse.update.LocalAppUpdateManager\nimport com.yfuse.feature.player.PlaybackSourcePreloader\n',
)
replace(
    "composeApp/src/androidMain/kotlin/com/yfuse/MainActivity.kt",
    '''                syncManager = koin.get<ServerSyncManager>(),
            )
''',
    '''                syncManager = koin.get<ServerSyncManager>(),
                dependencies = AppDependencies(
                    calendarRepository = koin.get(),
                    tmdbHomeCache = koin.get(),
                    offlineMediaManager = koin.get(),
                    playbackTrackRequest = koin.get(),
                    serverSyncManager = koin.get(),
                    watchTogether = koin.get(),
                    watchTogetherPreferences = koin.get(),
                    inviteResolver = koin.get(),
                    playbackSourcePreloader = runCatching { koin.get<PlaybackSourcePreloader>() }.getOrNull(),
                    playbackRecovery = koin.get(),
                    playbackPreferences = koin.get(),
                    userAgentPreferences = koin.get(),
                    danmakuPreferences = koin.get(),
                    skipSegmentPreferences = koin.get(),
                    libraryCache = koin.get(),
                    lanDiscovery = koin.get(),
                    account = koin.get(),
                    serverHealthMonitor = koin.get(),
                ),
            )
''',
)

# Root and App no longer resolve business services themselves.
root_path = "composeApp/src/commonMain/kotlin/com/yfuse/app/RootComponent.kt"
replace(root_path, 'import org.koin.core.context.GlobalContext\n', '')
replace(
    root_path,
    '    syncManager: ServerSyncManager,\n) : ComponentContext by componentContext {\n',
    '    syncManager: ServerSyncManager,\n    val dependencies: AppDependencies,\n) : ComponentContext by componentContext {\n',
)
replace(root_path, '    private val watchTogether: WatchTogetherClient = GlobalContext.get().get()\n    private val inviteResolver: WatchInviteResolver = GlobalContext.get().get()\n', '    private val watchTogether: WatchTogetherClient = dependencies.watchTogether\n    private val inviteResolver: WatchInviteResolver = dependencies.inviteResolver\n')
replace(root_path, '        syncManager.start(scope)\n', '        syncManager.start(scope)\n        dependencies.serverHealthMonitor.start(scope)\n')
replace(root_path, '        calendarRepository = GlobalContext.get().get(),\n', '        calendarRepository = dependencies.calendarRepository,\n        dependencies = dependencies,\n')
replace(root_path, '        registry = registry,\n    )\n\n    val search = SearchComponent(', '        registry = registry,\n        dependencies = dependencies,\n    )\n\n    val search = SearchComponent(')
replace(root_path, '        history = searchHistory,\n    )\n\n    val profile = ProfileTabComponent(', '        history = searchHistory,\n        dependencies = dependencies,\n    )\n\n    val profile = ProfileTabComponent(')
replace(root_path, '        onEnterWatchRoom = ::enterWatchRoom,\n    )\n', '        onEnterWatchRoom = ::enterWatchRoom,\n        dependencies = dependencies,\n    )\n')

app_path = "composeApp/src/commonMain/kotlin/com/yfuse/app/App.kt"
replace(app_path, 'import androidx.compose.animation.scaleIn\n', '')
replace(app_path, 'import org.koin.core.context.GlobalContext\n', '')
replace(app_path, '        val watchTogether = remember { GlobalContext.get().get<WatchTogetherClient>() }\n        val inviteResolver = remember { GlobalContext.get().get<WatchInviteResolver>() }\n        val watchPreferences = remember { GlobalContext.get().get<WatchTogetherPreferences>() }\n', '        val watchTogether = root.dependencies.watchTogether\n        val inviteResolver = root.dependencies.inviteResolver\n        val watchPreferences = root.dependencies.watchTogetherPreferences\n')
replace(
    app_path,
    '''                            val fade = tween<Float>(duration, easing = Motion.Curve)
                            val zoom = tween<Float>(duration, easing = Motion.Curve)
                            (
                                fadeIn(fade) +
                                    scaleIn(zoom, initialScale = Motion.TAB_SCALE_FROM)
                                ) togetherWith fadeOut(fade) using
''',
    '''                            val fade = tween<Float>(duration, easing = Motion.Curve)
                            fadeIn(fade) togetherWith fadeOut(fade) using
''',
)

# Home cache injection and hero visibility/manual pause.
home_comp = "composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeComponent.kt"
replace(home_comp, 'import org.koin.core.context.GlobalContext\n', '')
replace(home_comp, '    registry: ServerRegistry,\n', '    registry: ServerRegistry,\n    cache: TmdbHomeCache,\n')
replace(home_comp, '        cache = GlobalContext.get().get<TmdbHomeCache>(),\n', '        cache = cache,\n')

home_tab = "composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeTabComponent.kt"
replace(home_tab, 'import com.yfuse.core.data.TmdbRepository\n', 'import com.yfuse.core.data.TmdbRepository\nimport com.yfuse.app.AppDependencies\n')
replace(home_tab, '    private val calendarRepository: AiringCalendarRepository,\n', '    private val calendarRepository: AiringCalendarRepository,\n    private val dependencies: AppDependencies,\n')
replace(home_tab, '                registry = registry,\n                onOpenEmbyItem =', '                registry = registry,\n                cache = dependencies.tmdbHomeCache,\n                onOpenEmbyItem =')
replace(home_tab, '                serverId = config.serverId,\n                onBack =', '                serverId = config.serverId,\n                dependencies = dependencies,\n                onBack =')

library_comp = "composeApp/src/commonMain/kotlin/com/yfuse/feature/library/LibraryComponent.kt"
replace(library_comp, 'import com.yfuse.core.data.ServerRegistry\n', 'import com.yfuse.core.data.ServerRegistry\nimport com.yfuse.app.AppDependencies\n')
replace(library_comp, '    private val registry: ServerRegistry,\n) : ComponentContext', '    private val registry: ServerRegistry,\n    private val dependencies: AppDependencies,\n) : ComponentContext')
replace(library_comp, '                autoPlay = config.autoPlay,\n                onBack =', '                autoPlay = config.autoPlay,\n                dependencies = dependencies,\n                onBack =')

search_comp = "composeApp/src/commonMain/kotlin/com/yfuse/feature/search/SearchComponent.kt"
replace(search_comp, 'import com.yfuse.core.data.ServerRegistry\n', 'import com.yfuse.core.data.ServerRegistry\nimport com.yfuse.app.AppDependencies\n')
replace(search_comp, '    private val history: SearchHistory,\n) : ComponentContext', '    private val history: SearchHistory,\n    private val dependencies: AppDependencies,\n) : ComponentContext')
replace(search_comp, '                serverId = config.serverId,\n                onBack =', '                serverId = config.serverId,\n                dependencies = dependencies,\n                onBack =')

profile_tab = "composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/ProfileTabComponent.kt"
replace(profile_tab, 'import com.yfuse.core.data.ThemePreferences\n', 'import com.yfuse.core.data.ThemePreferences\nimport com.yfuse.app.AppDependencies\n')
replace(profile_tab, '    private val onEnterWatchRoom: () -> Unit,\n) : ComponentContext', '    private val onEnterWatchRoom: () -> Unit,\n    private val dependencies: AppDependencies,\n) : ComponentContext')
replace(profile_tab, '                onEnterWatchRoom = onEnterWatchRoom,\n', '                onEnterWatchRoom = onEnterWatchRoom,\n                dependencies = dependencies,\n')

# Detail's Store/Screen/Downloader dependencies become explicit.
detail_comp = "composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailComponent.kt"
replace(detail_comp, 'import com.yfuse.core.util.componentScope\n', 'import com.yfuse.core.util.componentScope\nimport com.yfuse.app.AppDependencies\n')
replace(detail_comp, 'import org.koin.core.context.GlobalContext\n', '')
replace(detail_comp, '    private val autoPlay: Boolean = false,\n', '    private val autoPlay: Boolean = false,\n    val dependencies: AppDependencies,\n')
replace(detail_comp, '    val store = DetailStoreFactory(storeFactory, repo, registry, itemId, serverId).create()\n', '    val store = DetailStoreFactory(\n        storeFactory, repo, registry, itemId, serverId,\n        playbackTrackRequest = dependencies.playbackTrackRequest,\n        syncManager = dependencies.serverSyncManager,\n    ).create()\n')
replace(detail_comp, '        GlobalContext.get().get<OfflineMediaManager>().enqueue(\n', '        dependencies.offlineMediaManager.enqueue(\n')
replace(detail_comp, '        val sourcePreloader = runCatching {\n            GlobalContext.get().get<PlaybackSourcePreloader>()\n        }.getOrNull()\n', '        val sourcePreloader = dependencies.playbackSourcePreloader\n')

# Store constructor injection.
detail_store = "composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailStore.kt"
replace(detail_store, 'import org.koin.core.context.GlobalContext\n', '')
replace(detail_store, '    private val mainContext: CoroutineContext = Dispatchers.Main,\n) {\n', '    private val mainContext: CoroutineContext = Dispatchers.Main,\n    private val playbackTrackRequest: PlaybackTrackRequest,\n    private val syncManager: ServerSyncManager,\n) {\n')
replace(detail_store, '            GlobalContext.get().get<PlaybackTrackRequest>().set(\n', '            playbackTrackRequest.set(\n')
replace(detail_store, '            val sync = GlobalContext.get().get<ServerSyncManager>()\n', '            val sync = syncManager\n', count=2)

# ProfileComponent had the largest concentration of service-locator calls.
profile_comp = "composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/ProfileComponent.kt"
replace(profile_comp, 'import com.yfuse.feature.servers.ServersStoreFactory\n', 'import com.yfuse.feature.servers.ServersStoreFactory\nimport com.yfuse.app.AppDependencies\n')
replace(profile_comp, 'import org.koin.core.context.GlobalContext\n', '')
replace(profile_comp, '    val onEnterWatchRoom: () -> Unit,\n) : ComponentContext', '    val onEnterWatchRoom: () -> Unit,\n    val dependencies: AppDependencies,\n) : ComponentContext')
replace(profile_comp, '        discovery = GlobalContext.get().get<LanDiscovery>(),\n', '        discovery = dependencies.lanDiscovery,\n')
replace(
    profile_comp,
    '''    val offlineMedia: OfflineMediaManager = GlobalContext.get().get()
    val syncManager: ServerSyncManager = GlobalContext.get().get()
    val playbackRecovery: PlaybackRecoveryStore = GlobalContext.get().get()
    val playbackPreferences: PlaybackPreferences = GlobalContext.get().get()
    val userAgentPreferences: UserAgentPreferences = GlobalContext.get().get()
    val danmakuPreferences: DanmakuPreferences = GlobalContext.get().get()
    val skipSegmentPreferences: SkipSegmentPreferences = GlobalContext.get().get()
    private val libraryCache: LibraryCache = GlobalContext.get().get()
    val watchTogetherPreferences: WatchTogetherPreferences = GlobalContext.get().get()
    val watchTogether: WatchTogetherClient = GlobalContext.get().get()
    val account: AccountRepository = GlobalContext.get().get()
''',
    '''    val offlineMedia: OfflineMediaManager = dependencies.offlineMediaManager
    val syncManager: ServerSyncManager = dependencies.serverSyncManager
    val playbackRecovery: PlaybackRecoveryStore = dependencies.playbackRecovery
    val playbackPreferences: PlaybackPreferences = dependencies.playbackPreferences
    val userAgentPreferences: UserAgentPreferences = dependencies.userAgentPreferences
    val danmakuPreferences: DanmakuPreferences = dependencies.danmakuPreferences
    val skipSegmentPreferences: SkipSegmentPreferences = dependencies.skipSegmentPreferences
    private val libraryCache: LibraryCache = dependencies.libraryCache
    val watchTogetherPreferences: WatchTogetherPreferences = dependencies.watchTogetherPreferences
    val watchTogether: WatchTogetherClient = dependencies.watchTogether
    val account: AccountRepository = dependencies.account
    val serverHealthMonitor = dependencies.serverHealthMonitor
''',
)

# DetailScreen uses component-owned services.
detail_screen = "composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailScreen.kt"
replace(detail_screen, 'import org.koin.core.context.GlobalContext\n', '')
replace(detail_screen, '    val watchTogether = remember { GlobalContext.get().get<WatchTogetherClient>() }\n    val watchPreferences = remember { GlobalContext.get().get<WatchTogetherPreferences>() }\n', '    val watchTogether = component.dependencies.watchTogether\n    val watchPreferences = component.dependencies.watchTogetherPreferences\n')

# Profile server health display.
profile_screen = "composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/ProfileScreen.kt"
replace(profile_screen, 'import com.yfuse.core.data.ThemePreferences\n', 'import com.yfuse.core.data.ThemePreferences\nimport com.yfuse.core.data.ServerHealth\nimport com.yfuse.core.data.ServerHealthStatus\n')
replace(profile_screen, 'import com.yfuse.core.designsystem.Brand\n', 'import com.yfuse.core.designsystem.Brand\nimport com.yfuse.core.designsystem.Semantic\n')
replace(profile_screen, '    val serversState by component.serversStore.states\n        .collectAsState(component.serversStore.state)\n', '    val serversState by component.serversStore.states\n        .collectAsState(component.serversStore.state)\n    val serverHealth by component.serverHealthMonitor.health.collectAsState()\n')
replace(profile_screen, '                                                    isCurrent = server.id == state.currentServer?.id,\n', '                                                    isCurrent = server.id == state.currentServer?.id,\n                                                    health = serverHealth[server.id],\n')
replace(profile_screen, '    isCurrent: Boolean,\n    onClick: () -> Unit,\n', '    isCurrent: Boolean,\n    health: ServerHealth?,\n    onClick: () -> Unit,\n')
replace(profile_screen, '                        .background(if (isCurrent) Brand.Online else Brand.Offline),\n', '''                        .background(
                            when (health?.status) {
                                ServerHealthStatus.Healthy -> Semantic.Success
                                ServerHealthStatus.Degraded -> Semantic.Warning
                                ServerHealthStatus.AuthRequired -> Semantic.Error
                                ServerHealthStatus.Offline -> Semantic.Offline
                                else -> Semantic.Offline
                            },
                        ),
''')
replace(profile_screen, '                    if (isCurrent) "当前使用 · ${server.userName}" else server.userName,\n', '                    listOfNotNull(\n                        "当前使用".takeIf { isCurrent },\n                        server.userName.takeIf { it.isNotBlank() },\n                        health?.summary,\n                    ).joinToString(" · "),\n')

# Semantic colours and motion durations.
tokens = "composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/Tokens.kt"
replace(tokens, 'object Brand {\n', 'object Brand {\n')
replace(tokens, '}\n\n/** 主色渐变 135deg', '''}

/** Meaningful status colours; feature code should not repurpose the brand accent for state. */
object Semantic {
    val Success = Brand.Online
    val Warning = Color(0xFFD58A3A)
    val Error = Brand.Danger
    val Offline = Brand.Offline
}

/** 主色渐变 135deg''')
for old, new in [
    ('    const val STANDARD = 220\n', '    const val STANDARD = 180\n'),
    ('    const val EMPHASIZED = 360\n', '    const val EMPHASIZED = 280\n'),
    ('    const val AMBIENT = 520\n', '    const val AMBIENT = 500\n'),
    ('    const val POP = 300\n', '    const val POP = 260\n'),
    ('    const val TAB = 260\n', '    const val TAB = 180\n'),
    ('    const val MODAL = 400\n', '    const val MODAL = 280\n'),
    ('    const val EXPAND = 460\n', '    const val EXPAND = 300\n'),
    ('    const val DETAIL_CONTENT = 320\n', '    const val DETAIL_CONTENT = 260\n'),
    ('    const val TOP_BAR = 280\n', '    const val TOP_BAR = 220\n'),
    ('    const val CAROUSEL = 560\n', '    const val CAROUSEL = 500\n'),
]:
    replace(tokens, old, new)

# Hero stops when its first item is no longer visible, and manual steering only pauses briefly.
home_screen = "composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeScreen.kt"
replace(home_screen, '                        height = heroHeight,\n', '                        height = heroHeight,\n                        visible = heroVisible,\n')
replace(home_screen, '    height: androidx.compose.ui.unit.Dp,\n', '    height: androidx.compose.ui.unit.Dp,\n    visible: Boolean,\n')
replace(home_screen, '    var manuallySteered by remember { mutableStateOf(false) }\n\n    LaunchedEffect(items.map { it.id })', '''    var manuallySteered by remember { mutableStateOf(false) }

    LaunchedEffect(carouselDragging) {
        if (carouselDragging) manuallySteered = true
    }
    LaunchedEffect(manuallySteered) {
        if (manuallySteered) {
            delay(25_000L)
            manuallySteered = false
        }
    }

    LaunchedEffect(items.map { it.id })''')
replace(home_screen, '    LaunchedEffect(items.size, carouselDragging, reduceMotion, manuallySteered, routeVisible) {\n', '    LaunchedEffect(items.size, carouselDragging, reduceMotion, manuallySteered, routeVisible, visible) {\n')
replace(home_screen, '        if (!routeVisible || items.size <= 1 || carouselDragging || reduceMotion || manuallySteered) {\n', '        if (!routeVisible || !visible || items.size <= 1 || carouselDragging || reduceMotion || manuallySteered) {\n')

# URL paste behavior in server form.
servers_store = "composeApp/src/commonMain/kotlin/com/yfuse/feature/servers/ServersStore.kt"
replace(servers_store, '                is ServersIntent.HostChanged -> dispatch(Msg.Host(intent.value))\n', '''                is ServersIntent.HostChanged -> {
                    val parsed = parseServerAddress(intent.value)
                    if (parsed != null && (parsed.https != null || parsed.port != null)) {
                        parsed.https?.let { scheme ->
                            dispatch(Msg.Protocol(scheme))
                            if (parsed.port == null) dispatch(Msg.Port(defaultServerPort(scheme)))
                        }
                        parsed.port?.let { dispatch(Msg.Port(it)) }
                        dispatch(Msg.Host(parsed.host))
                    } else {
                        dispatch(Msg.Host(intent.value))
                    }
                }
''')

print("phase1 patch applied")
