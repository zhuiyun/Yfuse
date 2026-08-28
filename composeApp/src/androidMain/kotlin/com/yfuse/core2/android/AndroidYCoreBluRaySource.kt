package com.yfuse.core2.android

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import com.yfuse.core.playback.PlaybackDiscChapter
import com.yfuse.core.playback.PlaybackDiscKind
import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState
import com.yfuse.core.playback.bluRayDiscRoot
import com.yfuse.core2.api.YDiscKind
import com.yfuse.core2.api.YMediaItem
import com.yfuse.feature.player.HttpRangeDiscBlockSource
import com.yfuse.feature.player.HdmvDiscSession
import com.yfuse.feature.player.NativeBluRayOverlayFrame
import com.yfuse.feature.player.NativeRemoteBluRaySessionRegistry
import com.yfuse.feature.player.RemoteDiscHeaderProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Credential-free source object retained by the YCore libbluray registry.
 *
 * Filesystem ISO/BDMV inputs are opened by libbluray from a canonical path. Seekable content URIs
 * and authenticated HTTP images expose only the 2048-byte UDF callback; their URI and headers never
 * cross JNI or appear in native diagnostics.
 */
internal class AndroidYCoreBluRaySource private constructor(
    private val discPath: String?,
    private val descriptor: ParcelFileDescriptor?,
    private val remoteSource: HttpRangeDiscBlockSource?,
) {
    private val mutableNavigation =
        MutableStateFlow(PlaybackDiscNavigationState(kind = PlaybackDiscKind.BluRay))
    val navigation: StateFlow<PlaybackDiscNavigationState> = mutableNavigation.asStateFlow()
    val menuSession: HdmvDiscSession = YCoreHdmvDiscSession()

    @Volatile
    private var nativeId = 0L

    @Volatile
    private var closed = false

    fun bindNativeId(id: Long) {
        nativeId = id.takeIf { it > 0L } ?: 0L
    }

    /** JNI callback. A null result selects bd_open_stream() and [readBlocksNative]. */
    @Suppress("unused")
    fun discPathNative(): String? = discPath

    /** JNI callback. Returns complete 2048-byte blocks, zero at EOF, or -1 on failure. */
    @Synchronized
    @Suppress("unused")
    fun readBlocksNative(
        lba: Int,
        blockCount: Int,
        target: ByteArray,
        targetOffset: Int,
    ): Int {
        if (closed || lba < 0 || blockCount <= 0 || targetOffset < 0) return -1
        val requested = blockCount.toLong() * BLURAY_UDF_BLOCK_SIZE
        if (
            requested <= 0L ||
            requested > Int.MAX_VALUE ||
            targetOffset > target.size ||
            requested > target.size.toLong() - targetOffset
        ) {
            return -1
        }
        remoteSource?.let {
            return it.readBlocks(
                lba = lba,
                blockCount = blockCount,
                target = target,
                targetOffset = targetOffset,
            )
        }
        val fileDescriptor = descriptor?.fileDescriptor ?: return -1
        val byteOffset = lba.toLong() * BLURAY_UDF_BLOCK_SIZE
        var total = 0
        return try {
            while (total < requested.toInt()) {
                val count =
                    Os.pread(
                        fileDescriptor,
                        target,
                        targetOffset + total,
                        requested.toInt() - total,
                        byteOffset + total,
                    )
                if (count <= 0) break
                total += count
            }
            total / BLURAY_UDF_BLOCK_SIZE
        } catch (_: ErrnoException) {
            -1
        }
    }

    /** JNI callback pushed after open and every title/chapter/angle transition. */
    @Suppress("unused")
    fun onNativeDiscState(
        titleCount: Int,
        selectedTitleIndex: Int,
        chapterCount: Int,
        selectedChapterIndex: Int,
        angleCount: Int,
        selectedAngleIndex: Int,
        menuSupported: Boolean,
        menuActive: Boolean,
    ) {
        if (closed) return
        val safeTitleCount = titleCount.coerceAtLeast(0)
        val safeChapterCount = chapterCount.coerceAtLeast(0)
        val safeAngleCount = angleCount.coerceAtLeast(0)
        mutableNavigation.value =
            PlaybackDiscNavigationState(
                kind = PlaybackDiscKind.BluRay,
                titleCount = safeTitleCount,
                selectedTitleIndex =
                    selectedTitleIndex.coerceIn(0, (safeTitleCount - 1).coerceAtLeast(0)),
                chapterCount = safeChapterCount,
                selectedChapterIndex =
                    selectedChapterIndex.coerceIn(0, (safeChapterCount - 1).coerceAtLeast(0)),
                angleCount = safeAngleCount,
                selectedAngleIndex =
                    selectedAngleIndex.coerceIn(0, (safeAngleCount - 1).coerceAtLeast(0)),
                chapters = List(safeChapterCount) { PlaybackDiscChapter(index = it) },
                menuSupported = menuSupported,
                menuActive = menuSupported && menuActive,
            )
        (menuSession as YCoreHdmvDiscSession).notifyNavigationChanged()
        NativeRemoteBluRaySessionRegistry.activate(menuSession)
    }

    /** JNI callback: complete authored IG plane, published only on libbluray FLUSH. */
    @Suppress("unused")
    fun onNativeOverlayFrame(
        width: Int,
        height: Int,
        argb: IntArray,
    ) {
        val pixels = width.toLong() * height.toLong()
        if (
            closed ||
            width <= 0 ||
            height <= 0 ||
            pixels !in 1..MAX_BLURAY_OVERLAY_PIXELS ||
            argb.size.toLong() != pixels
        ) {
            return
        }
        NativeRemoteBluRaySessionRegistry.updateOverlay(
            menuSession,
            NativeBluRayOverlayFrame(width = width, height = height, argb = argb.copyOf()),
        )
    }

    @Suppress("unused")
    fun onNativeOverlayCleared() {
        NativeRemoteBluRaySessionRegistry.updateOverlay(menuSession, null)
    }

    @Synchronized
    @Suppress("unused")
    fun closeNativeSource() {
        if (closed) return
        closed = true
        mutableNavigation.value = mutableNavigation.value.copy(menuSupported = false, menuActive = false)
        (menuSession as YCoreHdmvDiscSession).notifyNavigationChanged()
        NativeRemoteBluRaySessionRegistry.deactivate(menuSession)
        runCatching { remoteSource?.close() }
        runCatching { descriptor?.close() }
    }

    private inner class YCoreHdmvDiscSession : HdmvDiscSession {
        @Volatile
        private var listener: (() -> Unit)? = null

        fun notifyNavigationChanged() = listener?.invoke()

        override fun navigation(): PlaybackDiscNavigationState = mutableNavigation.value

        override fun selectTitle(index: Int): Boolean = false

        override fun selectChapter(index: Int): Boolean = false

        override fun selectAngle(index: Int): Boolean =
            !closed && FfmpegNativeBridge.selectDiscAngle(nativeId, index)

        override fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean =
            !closed &&
                mutableNavigation.value.menuSupported &&
                FfmpegNativeBridge.sendDiscMenuCommand(nativeId, command.nativeMenuCode())

        override fun selectMenuPoint(
            x: Int,
            y: Int,
            activate: Boolean,
        ): Boolean =
            !closed &&
                mutableNavigation.value.menuActive &&
                FfmpegNativeBridge.selectDiscMenuPoint(nativeId, x, y, activate)

        override fun setNavigationChangedListener(listener: (() -> Unit)?) {
            this.listener = listener
        }

        override fun close() {
            listener = null
        }
    }

    companion object {
        fun create(
            context: Context,
            item: YMediaItem,
        ): AndroidYCoreBluRaySource? {
            val disc = item.disc ?: return null
            if (disc.kind !in setOf(YDiscKind.BluRay, YDiscKind.Bdmv, YDiscKind.Iso)) return null
            val uri = runCatching { Uri.parse(item.uri) }.getOrNull() ?: return null
            return when (uri.scheme?.lowercase()) {
                "file" -> {
                    val rawPath = uri.path?.takeIf(String::isNotBlank) ?: return null
                    val path = bluRayDiscRoot(rawPath)
                    File(path).takeIf { it.exists() } ?: return null
                    AndroidYCoreBluRaySource(
                        discPath = runCatching { File(path).canonicalPath }.getOrNull() ?: return null,
                        descriptor = null,
                        remoteSource = null,
                    )
                }

                "content" -> {
                    if (disc.kind == YDiscKind.Bdmv) return null
                    val descriptor =
                        runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
                            ?: return null
                    AndroidYCoreBluRaySource(
                        discPath = null,
                        descriptor = descriptor,
                        remoteSource = null,
                    )
                }

                "http", "https" -> {
                    if (disc.kind == YDiscKind.Bdmv) return null
                    val source =
                        HttpRangeDiscBlockSource(
                            sourceUrl = item.uri,
                            headerProvider = RemoteDiscHeaderProvider { item.headers },
                        )
                    if (!source.probeRangeSupport()) {
                        source.close()
                        return null
                    }
                    AndroidYCoreBluRaySource(
                        discPath = null,
                        descriptor = null,
                        remoteSource = source,
                    )
                }

                else -> null
            }
        }
    }
}

private const val BLURAY_UDF_BLOCK_SIZE = 2_048
private const val MAX_BLURAY_OVERLAY_PIXELS = 4_096L * 2_160L

private fun PlaybackDiscMenuCommand.nativeMenuCode(): Int =
    when (this) {
        PlaybackDiscMenuCommand.ShowMenu -> 0
        PlaybackDiscMenuCommand.Back -> 1
        PlaybackDiscMenuCommand.Up -> 2
        PlaybackDiscMenuCommand.Down -> 3
        PlaybackDiscMenuCommand.Left -> 4
        PlaybackDiscMenuCommand.Right -> 5
        PlaybackDiscMenuCommand.Select -> 6
    }
