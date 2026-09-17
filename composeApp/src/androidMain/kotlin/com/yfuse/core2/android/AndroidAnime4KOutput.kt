package com.yfuse.core2.android

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.view.Surface
import com.yfuse.core.data.Anime4KMode
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core2.api.YMediaItem
import org.koin.core.context.GlobalContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.sqrt
import android.opengl.GLES30 as GL

/** MediaCodec → external texture → Anime4K Original → window; no CPU pixel readback.
 * The upstream six passes are preserved, including signed floating-point gradients.
 * Owns only the intermediate decoder surface, never the caller's display Surface.
 */
internal class AndroidAnime4KOutput private constructor(
    private val context: Context,
    private var output: Surface,
    private val width: Int,
    private val height: Int,
    private val mode: Anime4KMode,
    private val frameBudgetNs: Long,
) {
    private val thread = HandlerThread("YCore-Anime4K").apply { start() }
    private val handler = Handler(thread.looper)
    private var display = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private lateinit var config: EGLConfig
    private var inputTexture = 0
    private lateinit var texture: SurfaceTexture
    lateinit var decoderSurface: Surface
        private set
    private var copyProgram = 0
    private val programs = mutableListOf<Int>()
    private val targets = mutableListOf<Target>()
    private var passes = emptyList<Pass>()

    @Volatile private var scale = mode.scale

    @Volatile private var effectEnabled = true
    val description: String
        get() = if (effectEnabled) "Anime4K Original · $scale×" else "Anime4K 已自动关闭 · 纹理输出"
    private var samples = 0
    private var slowSamples = 0
    private var thermalCheckNs = 0L
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Volatile private var closed = false

    @Volatile var failure: Throwable? = null
        private set

    @Volatile var onPresented: ((Long, Long) -> Unit)? = null
    private val frameLedger = Anime4KFrameLedger()
    private val vertices =
        ByteBuffer
            .allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
                position(0)
            }
    private val transform = FloatArray(16)

    private data class Target(
        val texture: Int,
        val framebuffer: Int,
        val width: Int,
        val height: Int,
    )

    private data class Pass(
        val bindings: List<String>,
        val save: String?,
        val body: String,
    )

    fun recordFrame(
        timestampNs: Long,
        codecTimeUs: Long,
    ) {
        handler.post {
            if (closed) return@post
            frameLedger.scheduled(timestampNs, codecTimeUs)
        }
    }

    fun flush() {
        handler.post { frameLedger.clear() }
    }

    fun setOutput(surface: Surface) =
        onGlThread {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, eglContext)
            EGL14.eglDestroySurface(display, window)
            output = surface
            createWindow()
        }

    private fun createWindow() {
        window = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE) { "Anime4K window unavailable" }
        check(EGL14.eglMakeCurrent(display, window, window, eglContext))
        EGL14.eglSwapInterval(display, 0)
    }

    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE,
                    0x40,
                    EGL14.EGL_SURFACE_TYPE,
                    EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE,
                    8,
                    EGL14.EGL_GREEN_SIZE,
                    8,
                    EGL14.EGL_BLUE_SIZE,
                    8,
                    EGL14.EGL_ALPHA_SIZE,
                    8,
                    EGL14.EGL_NONE,
                ),
                0,
                configs,
                0,
                1,
                count,
                0,
            ) &&
                count[0] > 0,
        )
        config = requireNotNull(configs[0])
        eglContext =
            EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0,
            )
        check(eglContext != EGL14.EGL_NO_CONTEXT)
        createWindow()
        val extensions = GL.glGetString(GL.GL_EXTENSIONS).orEmpty()
        check("GL_EXT_color_buffer_half_float" in extensions || "GL_EXT_color_buffer_float" in extensions)
        copyProgram =
            program(
                """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 uv;
uniform samplerExternalOES video;
uniform mat4 transform;
out vec4 color;
void main() { color = texture(video, (transform * vec4(uv, 0.0, 1.0)).xy); }
""",
            )
        val source =
            context.assets
                .open(
                    "anime4k/Anime4K_Upscale_Original_x2.glsl",
                ).bufferedReader()
                .use { it.readText() }
        passes =
            source.split("//!DESC ").drop(1).map { block ->
                val lines = block.lines().drop(1)
                Pass(
                    lines.filter { it.startsWith("//!BIND ") }.map { it.removePrefix("//!BIND ").trim() },
                    lines.firstOrNull { it.startsWith("//!SAVE ") }?.removePrefix("//!SAVE ")?.trim(),
                    lines.filterNot { it.startsWith("//!") }.joinToString("\n"),
                )
            }
        check(passes.size == 6)
        passes.forEach { pass ->
            val declarations =
                pass.bindings.joinToString("\n") { name ->
                    "uniform sampler2D ${name}_sampler;\n#define ${name}_tex(p) texture(${name}_sampler, p)"
                }
            programs +=
                program(
                    """#version 300 es
precision highp float;
in vec2 uv;
out vec4 color;
uniform vec2 HOOKED_pt;
#define HOOKED_pos uv
$declarations
${pass.body}
void main() { color = hook(); }
""",
                )
        }
        inputTexture = textureId(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        texture = SurfaceTexture(inputTexture).apply { setDefaultBufferSize(width, height) }
        decoderSurface = Surface(texture)
        texture.setOnFrameAvailableListener(
            {
                if (!closed && failure == null) {
                    try {
                        drawFrame()
                    } catch (error: Throwable) {
                        failure = error
                    }
                }
            },
            handler,
        )
        allocateTargets()
    }

    private fun allocateTargets() {
        targets.forEach(::deleteTarget)
        targets.clear()
        // Source RGBA8 + luma RG16F + four RG16F gradients; total textures <= 64 MiB.
        val available = (64L * 1024 * 1024 - width.toLong() * height * 8).coerceAtLeast(1)
        val allowedScale = sqrt(available.toDouble() / (width.toLong() * height * 16)).toFloat()
        val chosen = min(scale, allowedScale).coerceAtLeast(0.25f)
        scale = chosen
        val pw = (width * chosen).toInt().coerceAtLeast(1)
        val ph = (height * chosen).toInt().coerceAtLeast(1)
        targets += target(width, height, false)
        targets += target(width, height, true)
        repeat(4) { targets += target(pw, ph, true) }
    }

    private fun drawFrame() {
        val begin = System.nanoTime()
        texture.updateTexImage()
        texture.getTransformMatrix(transform)
        val timestamp = texture.timestamp
        val size = IntArray(2)
        EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, size, 0)
        EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, size, 1)
        if (size[0] <= 0 || size[1] <= 0) return
        val destinationFramebuffer = if (effectEnabled) targets[0].framebuffer else 0
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, destinationFramebuffer)
        GL.glViewport(0, 0, if (effectEnabled) width else size[0], if (effectEnabled) height else size[1])
        GL.glUseProgram(copyProgram)
        bindTexture(copyProgram, "video", inputTexture, 0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        GL.glUniformMatrix4fv(GL.glGetUniformLocation(copyProgram, "transform"), 1, false, transform, 0)
        quad(copyProgram)
        if (effectEnabled) {
            val bindings = mutableMapOf("HOOKED" to targets[0].texture)
            passes.forEachIndexed { i, pass ->
                val target = targets.getOrNull(i + 1)
                GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, target?.framebuffer ?: 0)
                GL.glViewport(0, 0, target?.width ?: size[0], target?.height ?: size[1])
                val p = programs[i]
                GL.glUseProgram(p)
                GL.glUniform2f(GL.glGetUniformLocation(p, "HOOKED_pt"), 1f / width, 1f / height)
                pass.bindings.forEachIndexed {
                        unit,
                        name,
                    ->
                    bindTexture(p, "${name}_sampler", bindings.getValue(name), unit)
                }
                quad(p)
                if (pass.save != null && target != null) bindings[pass.save] = target.texture
            }
        }
        check(GL.glGetError() == GL.GL_NO_ERROR) { "Anime4K GL draw failed" }
        EGLExt.eglPresentationTimeANDROID(display, window, System.nanoTime())
        check(EGL14.eglSwapBuffers(display, window)) { "Anime4K presentation failed" }
        val now = System.nanoTime()
        // Report only frames actually swapped, not frames merely decoded into the texture.
        frameLedger.presented(timestamp)?.let { onPresented?.invoke(it, now) }
        samples++
        if (now - begin > frameBudgetNs) slowSamples++
        val hot =
            if (Build.VERSION.SDK_INT >= 29 && now - thermalCheckNs > 1_000_000_000L) {
                thermalCheckNs = now
                power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
            } else {
                false
            }
        if (effectEnabled && (hot || samples >= 45 && slowSamples >= 15)) {
            if (hot || scale <= 1f) {
                effectEnabled = false
                targets.forEach(::deleteTarget)
                targets.clear()
            } else {
                scale = (scale - 0.5f).coerceAtLeast(1f)
                allocateTargets()
            }
            AppLog.warning(
                category = "player.anime4k",
                event = "quality_reduced",
                message =
                    if (effectEnabled) {
                        "Anime4K reduced processing resolution"
                    } else {
                        "Anime4K bypassed for playback performance"
                    },
            )
        }
        if (samples >= 45) {
            samples = 0
            slowSamples = 0
        }
    }

    private fun textureId(type: Int): Int {
        val id = IntArray(1)
        GL.glGenTextures(1, id, 0)
        GL.glBindTexture(type, id[0])
        GL.glTexParameteri(type, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(type, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(type, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(type, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    private fun target(
        w: Int,
        h: Int,
        floating: Boolean,
    ): Target {
        val id = textureId(GL.GL_TEXTURE_2D)
        GL.glTexImage2D(
            GL.GL_TEXTURE_2D,
            0,
            if (floating) GL.GL_RG16F else GL.GL_RGBA8,
            w,
            h,
            0,
            if (floating) GL.GL_RG else GL.GL_RGBA,
            if (floating) GL.GL_HALF_FLOAT else GL.GL_UNSIGNED_BYTE,
            null,
        )
        val fbo = IntArray(1)
        GL.glGenFramebuffers(1, fbo, 0)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo[0])
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, id, 0)
        if (GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) != GL.GL_FRAMEBUFFER_COMPLETE) {
            GL.glDeleteTextures(1, intArrayOf(id), 0)
            GL.glDeleteFramebuffers(1, fbo, 0)
            error("Anime4K floating point framebuffer unavailable")
        }
        return Target(id, fbo[0], w, h)
    }

    private fun deleteTarget(target: Target) {
        GL.glDeleteTextures(1, intArrayOf(target.texture), 0)
        GL.glDeleteFramebuffers(1, intArrayOf(target.framebuffer), 0)
    }

    private fun bindTexture(
        program: Int,
        name: String,
        texture: Int,
        unit: Int,
        type: Int = GL.GL_TEXTURE_2D,
    ) {
        GL.glActiveTexture(GL.GL_TEXTURE0 + unit)
        GL.glBindTexture(type, texture)
        GL.glUniform1i(GL.glGetUniformLocation(program, name), unit)
    }

    private fun quad(program: Int) {
        val location = GL.glGetAttribLocation(program, "position")
        vertices.position(0)
        GL.glEnableVertexAttribArray(location)
        GL.glVertexAttribPointer(location, 2, GL.GL_FLOAT, false, 0, vertices)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        GL.glDisableVertexAttribArray(location)
    }

    private fun program(fragment: String): Int {
        fun shader(
            type: Int,
            source: String,
        ): Int {
            val id = GL.glCreateShader(type)
            GL.glShaderSource(id, source)
            GL.glCompileShader(id)
            val ok = IntArray(1)
            GL.glGetShaderiv(id, GL.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val error = GL.glGetShaderInfoLog(id)
                GL.glDeleteShader(id)
                error(error)
            }
            return id
        }
        val vertex =
            shader(
                GL.GL_VERTEX_SHADER,
                """#version 300 es
in vec2 position;
out vec2 uv;
void main() { uv = position * 0.5 + 0.5; gl_Position = vec4(position, 0.0, 1.0); }
""",
            )
        var fragmentId = 0
        val id = GL.glCreateProgram()
        try {
            fragmentId = shader(GL.GL_FRAGMENT_SHADER, fragment)
            GL.glAttachShader(id, vertex)
            GL.glAttachShader(id, fragmentId)
            GL.glLinkProgram(id)
            val ok = IntArray(1)
            GL.glGetProgramiv(id, GL.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { GL.glGetProgramInfoLog(id) }
            return id
        } catch (
            error: Throwable,
        ) {
            GL.glDeleteProgram(id)
            throw error
        } finally {
            GL.glDeleteShader(vertex)
            if (fragmentId != 0) GL.glDeleteShader(fragmentId)
        }
    }

    private fun <T> onGlThread(block: () -> T): T {
        val task = FutureTask(block)
        check(handler.post(task))
        return task.get(5, TimeUnit.SECONDS)
    }

    fun close() {
        if (closed) return
        closed = true
        onPresented = null
        // Cleanup is queued after any in-flight initialization/frame; never races EGL ownership.
        handler.post {
            runCatching {
                if (::texture.isInitialized) {
                    texture.setOnFrameAvailableListener(null)
                    texture.release()
                }
                if (::decoderSurface.isInitialized) decoderSurface.release()
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    targets.forEach(::deleteTarget)
                    programs.forEach(GL::glDeleteProgram)
                    GL.glDeleteProgram(copyProgram)
                    GL.glDeleteTextures(1, intArrayOf(inputTexture), 0)
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
                    EGL14.eglDestroyContext(display, eglContext)
                }
                if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
                EGL14.eglReleaseThread()
            }
            thread.quitSafely()
        }
    }

    companion object {
        fun create(
            context: Context,
            surface: Surface,
            format: MediaFormat,
            mode: Anime4KMode,
        ): AndroidAnime4KOutput? {
            if (mode == Anime4KMode.Off) return null
            val w = format.animeIntegerOrNull(MediaFormat.KEY_WIDTH) ?: return null
            val h = format.animeIntegerOrNull(MediaFormat.KEY_HEIGHT) ?: return null
            if (w <= 0 || h <= 0 || w.toLong() * h > 1920L * 1080) return null
            if (format.animeIntegerOrNull(MediaFormat.KEY_ROTATION).let { it != null && it != 0 }) return null
            val fps =
                runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }
                    .getOrElse { format.animeIntegerOrNull(MediaFormat.KEY_FRAME_RATE)?.toFloat() ?: 30f }
                    .takeIf { it.isFinite() && it > 0f } ?: 30f
            val renderer =
                AndroidAnime4KOutput(
                    context.applicationContext,
                    surface,
                    w,
                    h,
                    mode,
                    (1_000_000_000L / fps.coerceIn(24f, 120f)).toLong(),
                )
            return try {
                renderer.onGlThread { renderer.initialize() }
                renderer
            } catch (error: Throwable) {
                renderer.close()
                AppLog.warning(
                    category = "player.anime4k",
                    event = "unavailable",
                    message = "Anime4K unavailable; retaining direct output",
                    throwable = error,
                )
                null
            }
        }
    }
}

internal fun requestedAnime4KMode(): Anime4KMode =
    GlobalContext
        .getOrNull()
        ?.getOrNull<PlaybackPreferences>()
        ?.anime4KMode
        ?.value ?: Anime4KMode.Off

internal fun anime4KRequestedFor(item: YMediaItem): Boolean =
    requestedAnime4KMode() != Anime4KMode.Off &&
        item.sourceHints?.dolbyVision != true &&
        item.sourceHints?.dynamicRange?.let { it.isBlank() || it.equals("SDR", ignoreCase = true) } != false

private fun MediaFormat.animeIntegerOrNull(key: String): Int? =
    runCatching { if (containsKey(key)) getInteger(key) else null }.getOrNull()
