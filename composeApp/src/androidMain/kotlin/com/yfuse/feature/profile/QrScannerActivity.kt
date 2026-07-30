package com.yfuse.feature.profile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Camera
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.yfuse.core.logging.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
class QrScannerActivity : Activity(), SurfaceHolder.Callback, Camera.PreviewCallback {
    private var camera: Camera? = null
    private var width = 0
    private var height = 0
    private val decoding = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            AppLog.warning(
                category = "server.migration",
                event = "scanner_permission_missing",
                message = "QR scanner opened without camera permission",
            )
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val surface = SurfaceView(this)
        surface.holder.addCallback(this)
        val hint = TextView(this).apply {
            text = "将服务器二维码放入画面中"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setBackgroundColor(0x66000000)
        }
        setContentView(
            FrameLayout(this).apply {
                addView(surface, FrameLayout.LayoutParams(-1, -1))
                addView(
                    hint,
                    FrameLayout.LayoutParams(-1, 120, Gravity.BOTTOM),
                )
            },
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        runCatching {
            camera = Camera.open().apply {
                setDisplayOrientation(90)
                parameters = parameters.apply {
                    focusMode = supportedFocusModes
                        ?.firstOrNull { it == Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE }
                        ?: focusMode
                }
                setPreviewDisplay(holder)
                setPreviewCallback(this@QrScannerActivity)
                startPreview()
                parameters.previewSize.let {
                    width = it.width
                    height = it.height
                }
            }
        }.onFailure {
            AppLog.error(
                category = "server.migration",
                event = "camera_open_failed",
                message = "Failed to open camera for QR scanning",
                throwable = it,
            )
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onPreviewFrame(data: ByteArray?, sourceCamera: Camera?) {
        if (data == null || width == 0 || height == 0 || !decoding.compareAndSet(false, true)) return
        executor.execute {
            try {
                val source = PlanarYUVLuminanceSource(
                    data,
                    width,
                    height,
                    0,
                    0,
                    width,
                    height,
                    false,
                )
                val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
                runOnUiThread {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, result.text))
                    finish()
                }
            } catch (_: Throwable) {
                reader.reset()
                decoding.set(false)
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = releaseCamera()
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun onPause() {
        releaseCamera()
        super.onPause()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun releaseCamera() {
        camera?.setPreviewCallback(null)
        camera?.stopPreview()
        camera?.release()
        camera = null
    }

    companion object {
        const val EXTRA_RESULT = "yfuse.qr.result"
    }
}
