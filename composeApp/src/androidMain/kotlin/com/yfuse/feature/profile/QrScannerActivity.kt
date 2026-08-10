package com.yfuse.feature.profile

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

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
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
            cameraPermission.launch(Manifest.permission.CAMERA)
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
            this.addView(this@QrScannerActivity.torch, LinearLayout.LayoutParams(0, 48.dp, 1f))
            this.addView(gallery, LinearLayout.LayoutParams(0, 48.dp, 1f))
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(18.dp, 12.dp, 18.dp, 20.dp)
            setBackgroundColor(0x88000000.toInt())
            this.addView(this@QrScannerActivity.hint, LinearLayout.LayoutParams(-1, 54.dp))
            this.addView(actions, LinearLayout.LayoutParams(-1, 48.dp))
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                this.addView(this@QrScannerActivity.previewView, FrameLayout.LayoutParams(-1, -1))
                this.addView(this@QrScannerActivity.overlay, FrameLayout.LayoutParams(-1, -1))
                this.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
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
        private const val ROI_FRACTION = 0.68f
    }
}
