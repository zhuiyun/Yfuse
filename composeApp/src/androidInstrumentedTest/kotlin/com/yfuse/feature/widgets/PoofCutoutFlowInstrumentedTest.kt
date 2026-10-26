package com.yfuse.feature.widgets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.russhwolf.settings.SharedPreferencesSettings
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.logging.DiagnosticLogStore
import com.yfuse.core.security.SecureStore
import com.yfuse.core.widget.LocalWidgetCutoutRuntime
import com.yfuse.core.widget.PoofCutoutClient
import com.yfuse.core.widget.PoofWidgetCutoutPipeline
import com.yfuse.core.widget.WidgetCutoutPhase
import com.yfuse.core.widget.WidgetCutoutPreferences
import com.yfuse.core.widget.WidgetCutoutRuntime
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.preparePoofSource
import com.yfuse.launchMainActivityForInput
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/** Real Coil GET + multipart POST + private disk cache + the production Compose renderer. */
@RunWith(AndroidJUnit4::class)
class PoofCutoutFlowInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext

    @Test(timeout = 120_000L)
    fun downloaded_poster_is_segmented_rendered_above_the_frame_and_reused_after_restart() {
        PosterOrigin(holdPost = true).use { origin ->
            val prefs = preferences("private-poof-fixture-key")
            val folder = File(context.cacheDir, "poof-flow-${UUID.randomUUID()}")
            val rate = context.getSharedPreferences("poof-flow-rate-${UUID.randomUUID()}", 0)
            val pipeline =
                PoofWidgetCutoutPipeline(
                    context,
                    prefs,
                    PoofCutoutClient(endpoint = origin.endpoint.toHttpUrl()),
                    directory = folder,
                    rate = rate,
                    requestSpacingMillis = 0,
                )
            try {
                launchMainActivityForInput().use { scenario ->
                    val bounds = AtomicReference<Rect>()
                    scenario.onActivity { activity ->
                        activity.setContent {
                            YfuseTheme(dark = false) {
                                CompositionLocalProvider(
                                    LocalRouteVisible provides true,
                                    LocalWidgetCutoutRuntime provides
                                        WidgetCutoutRuntime(
                                            prefs.configuration,
                                            pipeline,
                                        ),
                                ) {
                                    Box(
                                        Modifier
                                            .width(
                                                300.dp,
                                            ).background(androidx.compose.ui.graphics.Color.White)
                                            .onGloballyPositioned {
                                                bounds.set(it.boundsInWindow())
                                            },
                                    ) {
                                        WidgetBreakoutPoster(
                                            listOf(
                                                WidgetMedia(
                                                    id = "actual-network-poster",
                                                    title = "真实网络链路测试",
                                                    poster = origin.poster,
                                                ),
                                            ),
                                            true,
                                            false,
                                            {
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    await("The original poster never reached the fixed background frame") {
                        bounds.get()?.let(::backgroundFrameBounds) != null
                    }
                    val pendingBounds = checkNotNull(bounds.get())
                    assertFixedFrame(pendingBounds)
                    origin.releasePost()
                    await(
                        "The card never displayed its downloaded transparent foreground",
                    ) { hasText("抠图完成", stateOnly = true) }
                    assertFalse("Successful cutout must not cover the artwork with a badge", hasText("抠图完成"))
                    assertEquals(1, origin.posts.get())
                    assertEquals(512, origin.uploadWidth.get())
                    assertEquals(768, origin.uploadHeight.get())
                    assertNull(origin.failure.get())
                    assertFixedFrame(checkNotNull(bounds.get()))
                    assertEquals(
                        "Finishing the cutout changed the component height",
                        pendingBounds.height,
                        checkNotNull(bounds.get()).height,
                        1f,
                    )
                    val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        val frame = checkNotNull(bounds.get())
                        val density = context.resources.displayMetrics.density
                        var headPixels = 0
                        var leakedBackground = 0
                        // Sample above the frame inside the fixture's red head, independent of hero height.
                        val artwork = checkNotNull(pipeline.peek(origin.poster))
                        val crop =
                            breakoutFraming(
                                artwork.sourceWidth,
                                artwork.sourceHeight,
                                artwork.subjectTop,
                                artwork.subjectBottom,
                            )
                        val cardWidth = frame.width * BREAKOUT_CARD_WIDTH
                        val headStart = frame.top + 12 * density + cardWidth * .15f
                        val frameStart =
                            frame.top + 12 * density + cardWidth * BREAKOUT_WINDOW_HEIGHT * crop.frameTopFraction
                        for (y in headStart.toInt() until frameStart.toInt()) {
                            for (x in (frame.left + frame.width * .25f).toInt() until
                                (frame.left + frame.width * .75f).toInt()) {
                                val pixel = screenshot.getPixel(x, y)
                                if (Color.red(pixel) > 180 &&
                                    Color.green(pixel) < 100 &&
                                    Color.blue(pixel) < 100
                                ) {
                                    headPixels++
                                }
                                if (Color.green(pixel) > 110 &&
                                    Color.red(pixel) < 110 &&
                                    Color.blue(pixel) < 180
                                ) {
                                    leakedBackground++
                                }
                            }
                        }
                        assertTrue(
                            "The actual foreground head was not drawn outside the background frame",
                            headPixels > 50,
                        )
                        assertTrue(
                            "The original rectangular background leaked into the breakout region",
                            leakedBackground < maxOf(30, headPixels / 8),
                        )
                        val evidence =
                            File(
                                context.getExternalFilesDir(null),
                                "validation/poof-flow",
                            ).apply { mkdirs() }
                        File(evidence, "actual-network-breakout.png").outputStream().use {
                            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally {
                        screenshot.recycle()
                    }
                    val first = runBlocking { pipeline.get(origin.poster, true) }
                    assertNotNull(first)
                    val restarted =
                        PoofWidgetCutoutPipeline(
                            context,
                            prefs,
                            PoofCutoutClient(endpoint = origin.endpoint.toHttpUrl()),
                            directory = folder,
                            rate = rate,
                            requestSpacingMillis = 0,
                        )
                    try {
                        assertEquals(first, runBlocking { restarted.get(origin.poster, false) })
                        assertEquals(1, origin.posts.get())
                        assertExportedDiagnostics(origin.poster)
                    } finally {
                        restarted.close()
                    }
                }
            } finally {
                pipeline.close()
                folder.deleteRecursively()
            }
        }
    }

    @Test(timeout = 60_000L)
    fun missing_key_is_visible_on_the_card_and_never_submits_a_paid_request() {
        PosterOrigin().use { origin ->
            val prefs = preferences("")
            val folder = File(context.cacheDir, "poof-missing-${UUID.randomUUID()}")
            val pipeline = PoofWidgetCutoutPipeline(context, prefs, directory = folder, requestSpacingMillis = 0)
            try {
                launchMainActivityForInput().use { scenario ->
                    scenario.onActivity { activity ->
                        activity.setContent {
                            YfuseTheme(dark = false) {
                                CompositionLocalProvider(
                                    LocalRouteVisible provides true,
                                    LocalWidgetCutoutRuntime provides
                                        WidgetCutoutRuntime(
                                            prefs.configuration,
                                            pipeline,
                                        ),
                                ) {
                                    WidgetBreakoutPoster(
                                        listOf(
                                            WidgetMedia(id = "missing-key", title = "未配置密钥", poster = origin.poster),
                                        ),
                                        true,
                                        false,
                                        {},
                                    )
                                }
                            }
                        }
                    }
                    await("Missing key was silently rendered as an ordinary poster") { hasText("先配置 Poof Key") }
                    assertEquals(0, origin.posts.get())
                }
            } finally {
                pipeline.close()
                folder.deleteRecursively()
            }
        }
    }

    @Test(timeout = 60_000L)
    fun a_real_quota_response_is_reported_and_does_not_repeat_the_post() =
        runBlocking {
            PosterOrigin(status = 402).use { origin ->
                val folder = File(context.cacheDir, "poof-quota-${UUID.randomUUID()}")
                val pipeline =
                    PoofWidgetCutoutPipeline(
                        context,
                        preferences("fixture-key"),
                        PoofCutoutClient(endpoint = origin.endpoint.toHttpUrl()),
                        directory = folder,
                        rate = context.getSharedPreferences("poof-quota-rate-${UUID.randomUUID()}", 0),
                        requestSpacingMillis = 0,
                    )
                try {
                    assertNull(pipeline.get(origin.poster, true))
                    assertEquals(WidgetCutoutPhase.NoCredits, pipeline.observe(origin.poster).value.phase)
                    assertNull(pipeline.get(origin.poster, true))
                    assertEquals(WidgetCutoutPhase.NoCredits, pipeline.observe(origin.poster).value.phase)
                    assertEquals(1, origin.posts.get())
                } finally {
                    pipeline.close()
                    folder.deleteRecursively()
                }
            }
        }

    @Test
    fun an_oversized_shared_bitmap_is_normalized_without_recycling_the_coil_owned_image() {
        val original = Bitmap.createBitmap(1080, 1620, Bitmap.Config.ARGB_8888)
        try {
            original.eraseColor(Color.RED)
            val image = preparePoofSource(original)
            assertEquals(512, image.width)
            assertEquals(768, image.height)
            assertFalse(original.isRecycled)
            assertEquals(1080, original.width)
            val decoded = checkNotNull(BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size))
            try {
                assertEquals(Color.RED, decoded.getPixel(100, 100))
            } finally {
                decoded.recycle()
            }
        } finally {
            original.recycle()
        }
    }

    private fun preferences(key: String): WidgetCutoutPreferences {
        val secrets =
            object : SecureStore {
                private val data = mutableMapOf<String, ByteArray>()

                override fun get(key: String) = data[key]?.copyOf()

                override fun put(
                    key: String,
                    value: ByteArray,
                ) {
                    data[key] = value.copyOf()
                }

                override fun remove(key: String) = data.remove(key) != null

                override fun clear() = data.clear()
            }
        return WidgetCutoutPreferences(
            SharedPreferencesSettings(context.getSharedPreferences("poof-fixture-${UUID.randomUUID()}", 0)),
            secrets,
        ).also {
            it.save(true, key)
        }
    }

    private fun assertExportedDiagnostics(source: String) {
        val output = ByteArrayOutputStream()
        DiagnosticLogStore.export(output)
        val contents = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                contents[entry.name] = zip.readBytes().decodeToString()
            }
        }
        val snapshot = checkNotNull(contents["reports/widget-cutout.json"])
        val report = Json.parseToJsonElement(snapshot).jsonObject
        assertNotNull(report["configured"])
        assertNotNull(report["cacheBytes"])
        val events = checkNotNull(report["events"]).jsonArray
        assertTrue(events.size <= 128)
        val names =
            events
                .map {
                    it.jsonObject
                        .getValue("event")
                        .jsonPrimitive.content
                }.toSet()
        for (name in listOf(
            "download_ready",
            "source_normalized",
            "http_response",
            "foreground_assessed",
            "cache_write_finished",
            "renderer_ready",
            "cache_hit",
        )) {
            assertTrue("Missing cutout diagnostic event: $name", name in names)
        }
        val sourceReferences =
            events.mapNotNull {
                it.jsonObject["attributes"]
                    ?.jsonObject
                    ?.get("sourceRef")
                    ?.jsonPrimitive
                    ?.content
            }
        assertTrue(sourceReferences.any { it.matches(Regex("p[0-9a-f]{24}")) })
        assertTrue(contents.filterKeys { it.startsWith("logs/") }.values.any { it.contains("widget_cutout") })
        val exported = contents.values.joinToString("\n")
        assertFalse(exported.contains(source))
        assertFalse(exported.contains("private-poof-fixture-key"))
        assertFalse(exported.contains("private-fixture-poster"))
    }

    private fun await(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 40_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(message, condition())
    }

    private fun assertFixedFrame(bounds: Rect) {
        val density = context.resources.displayMetrics.density
        val background = checkNotNull(backgroundFrameBounds(bounds))
        // Independent pixel edges: the original, processing and cutout views share this rectangle.
        val artworkTop = bounds.top + 12 * density
        val artworkHeight = bounds.width * .88f * 1.5f
        assertTrue(
            "The background frame moved with the subject",
            kotlin.math.abs(
                background.first -
                    (artworkTop + artworkHeight * .26f),
            ) <= 2 * density,
        )
        assertTrue(
            "The background frame bottom moved",
            kotlin.math.abs(
                background.second + 1 -
                    (artworkTop + artworkHeight),
            ) <= 2 * density,
        )
    }

    private fun backgroundFrameBounds(bounds: Rect): Pair<Int, Int>? {
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val density = context.resources.displayMetrics.density
            // Outside the red/blue foreground, the source's green background exposes the real frame.
            val x = (bounds.center.x - bounds.width * .88f * .35f).toInt()
            val bottom = (bounds.top + 12 * density + bounds.width * .88f * 1.5f).toInt()
            val rows =
                (bounds.top.toInt().coerceAtLeast(0) until bottom.coerceAtMost(screenshot.height)).filter { y ->
                    val pixel = screenshot.getPixel(x, y)
                    Color.green(pixel) > 110 && Color.red(pixel) < 110 && Color.blue(pixel) < 180
                }
            return rows.firstOrNull()?.let { it to rows.last() }
        } finally {
            screenshot.recycle()
        }
    }

    private fun hasText(
        text: String,
        stateOnly: Boolean = false,
    ): Boolean {
        fun search(node: AccessibilityNodeInfo?): Boolean =
            node != null &&
                (
                    (if (stateOnly) node.stateDescription?.toString() == text else node.text?.toString() == text) ||
                        (0 until node.childCount).any { search(node.getChild(it)) }
                )
        return search(instrumentation.uiAutomation.rootInActiveWindow)
    }
}

private class PosterOrigin(
    private val status: Int = 200,
    holdPost: Boolean = false,
) : Closeable {
    private val postGate = CountDownLatch(if (holdPost) 1 else 0)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val poster = "http://127.0.0.1:${server.localPort}/poster.png?access_token=private-fixture-poster"
    val endpoint = "http://127.0.0.1:${server.localPort}/remove"
    val posts = AtomicInteger()
    val uploadWidth = AtomicInteger()
    val uploadHeight = AtomicInteger()
    val failure = AtomicReference<Throwable?>()

    @Volatile private var closed = false
    private val source =
        Bitmap.createBitmap(1080, 1620, Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                Canvas(bitmap).apply {
                    drawColor(Color.rgb(40, 180, 120))
                    val paint = Paint().apply { color = Color.BLUE }
                    drawRect(300f, 480f, 780f, 1400f, paint)
                    paint.color = Color.RED
                    drawCircle(540f, 324f, 170f, paint)
                }
                ByteArrayOutputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    it.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    private val thread =
        Thread({
            while (!closed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 15_000
                        val input = DataInputStream(socket.getInputStream().buffered())

                        fun line(): String {
                            val out = ByteArrayOutputStream()
                            while (true) {
                                val b = input.read()
                                check(b >= 0)
                                if (b ==
                                    10
                                ) {
                                    return out.toString("US-ASCII").trimEnd('\r')
                                }
                                out.write(b)
                                check(out.size() < 16_384)
                            }
                        }
                        val request = line()
                        var length = 0
                        while (true) {
                            val h = line()
                            if (h.isEmpty()) break
                            if (h.startsWith("Content-Length:", true)) {
                                length =
                                    h.substringAfter(':').trim().toInt()
                            }
                        }
                        check(length in 0..8 * 1024 * 1024)
                        val requestBody = ByteArray(length).also(input::readFully)
                        val isPost = request.startsWith("POST ")
                        val bytes =
                            if (!isPost) {
                                source
                            } else {
                                posts.incrementAndGet()
                                check(postGate.await(45, TimeUnit.SECONDS))
                                if (status != 200) "quota exhausted".toByteArray() else foreground(requestBody)
                            }
                        val code = if (isPost) status else 200
                        val contentType = if (code == 200) "image/png" else "text/plain"
                        val headers =
                            "HTTP/1.1 $code Test\r\nContent-Type: $contentType\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(headers.toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                } catch (error: Throwable) {
                    if (!closed) failure.set(error)
                }
            }
        }, "Poof-real-flow-fixture").apply {
            isDaemon = true
            start()
        }

    private fun foreground(body: ByteArray): ByteArray {
        val start =
            (0 until body.size - 8).first {
                body[it] == 0x89.toByte() &&
                    body[it + 1] == 0x50.toByte() &&
                    body[it + 2] == 0x4e.toByte() &&
                    body[it + 3] == 0x47.toByte()
            }
        var end = start + 8
        while (end + 12 <= body.size) {
            val length = ByteBuffer.wrap(body, end, 4).int
            check(length >= 0 && length <= body.size - end - 12)
            val last = body.copyOfRange(end + 4, end + 8).contentEquals("IEND".toByteArray())
            end += length + 12
            if (last) break
        }
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(body, start, end - start))
        try {
            uploadWidth.set(bitmap.width)
            uploadHeight.set(bitmap.height)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (i in pixels.indices) {
                if ((pixels[i] and 0xffffff) ==
                    (Color.rgb(40, 180, 120) and 0xffffff)
                ) {
                    pixels[i] = 0
                }
            }
            val output = Bitmap.createBitmap(pixels, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            try {
                return ByteArrayOutputStream().use {
                    output.compress(Bitmap.CompressFormat.PNG, 100, it)
                    it.toByteArray()
                }
            } finally {
                output.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun releasePost() = postGate.countDown()

    override fun close() {
        closed = true
        releasePost()
        server.close()
        thread.join(2_000)
        check(!thread.isAlive)
    }
}
