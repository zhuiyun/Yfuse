package com.yfuse.feature.player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import com.yfuse.core.playback.PlaybackDiscChapter
import com.yfuse.core.playback.PlaybackDiscKind
import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState
import java.io.File

/**
 * Seekable local ISO source for the same `yfusebd://` runtime used by authenticated remote ISO.
 *
 * Both `file://` and seekable `content://` ISO sources use `pread(2)` so offsets stay 64-bit and no
 * temporary 50–100 GiB copy is created. BDMV directories use the dedicated `bd_open_files` VFS.
 */
internal class NativeLocalBluRaySource private constructor(
    private val descriptor: ParcelFileDescriptor,
) {
    private val hdmvSession = LocalHdmvSession()

    @Volatile
    private var nativeId: Long = 0L

    @Volatile
    private var closed = false

    fun bindNativeId(id: Long) {
        nativeId = id.takeIf { it > 0L } ?: 0L
        hdmvSession.bindNativeId(nativeId)
    }

    /** JNI callback. Returns complete 2048-byte UDF blocks, `0` at EOF, `-1` on I/O failure. */
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
        if (requested <= 0L || requested > Int.MAX_VALUE) return -1
        if (targetOffset > target.size || requested > target.size.toLong() - targetOffset) return -1
        val byteOffset = lba.toLong() * BLURAY_UDF_BLOCK_SIZE.toLong()
        var total = 0
        return try {
            while (total < requested.toInt()) {
                val count =
                    Os.pread(
                        descriptor.fileDescriptor,
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

    @Suppress("unused")
    fun onNativeSessionState(
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
        hdmvSession.update(
            titleCount,
            selectedTitleIndex,
            chapterCount,
            selectedChapterIndex,
            angleCount,
            selectedAngleIndex,
            menuSupported,
            menuActive,
        )
        NativeRemoteBluRaySessionRegistry.activate(hdmvSession)
    }

    @Suppress("unused")
    fun onNativeOverlayFrame(
        width: Int,
        height: Int,
        argb: IntArray,
    ) {
        val pixels = width.toLong() * height.toLong()
        if (closed || width <= 0 || height <= 0 || pixels != argb.size.toLong()) return
        NativeRemoteBluRaySessionRegistry.updateOverlay(
            hdmvSession,
            NativeBluRayOverlayFrame(width = width, height = height, argb = argb.copyOf()),
        )
    }

    @Suppress("unused")
    fun onNativeOverlayCleared() {
        NativeRemoteBluRaySessionRegistry.updateOverlay(hdmvSession, null)
    }

    @Suppress("unused")
    fun onNativeSessionClosed() {
        hdmvSession.markClosed()
        NativeRemoteBluRaySessionRegistry.deactivate(hdmvSession)
    }

    @Synchronized
    @Suppress("unused")
    fun closeNativeSource() {
        if (closed) return
        closed = true
        onNativeSessionClosed()
        runCatching { descriptor.close() }
    }

    private inner class LocalHdmvSession : HdmvDiscSession {
        @Volatile
        private var id: Long = 0L

        @Volatile
        private var ended = false

        @Volatile
        private var state = PlaybackDiscNavigationState(kind = PlaybackDiscKind.BluRay)

        @Volatile
        private var listener: (() -> Unit)? = null

        fun bindNativeId(value: Long) {
            id = value
        }

        fun update(
            titleCount: Int,
            selectedTitleIndex: Int,
            chapterCount: Int,
            selectedChapterIndex: Int,
            angleCount: Int,
            selectedAngleIndex: Int,
            menuSupported: Boolean,
            menuActive: Boolean,
        ) {
            if (ended) return
            state =
                PlaybackDiscNavigationState(
                    kind = PlaybackDiscKind.BluRay,
                    titleCount = titleCount.coerceAtLeast(0),
                    selectedTitleIndex = selectedTitleIndex.coerceAtLeast(0),
                    chapterCount = chapterCount.coerceAtLeast(0),
                    selectedChapterIndex = selectedChapterIndex.coerceAtLeast(0),
                    angleCount = angleCount.coerceAtLeast(0),
                    selectedAngleIndex =
                        selectedAngleIndex
                            .coerceAtLeast(0)
                            .coerceAtMost((angleCount - 1).coerceAtLeast(0)),
                    chapters = List(chapterCount.coerceAtLeast(0)) { PlaybackDiscChapter(it) },
                    menuSupported = menuSupported,
                    menuActive = menuSupported && menuActive,
                )
            listener?.invoke()
        }

        fun markClosed() {
            if (ended) return
            ended = true
            state = state.copy(menuActive = false, menuSupported = false)
            listener?.invoke()
        }

        override fun navigation(): PlaybackDiscNavigationState = state
        override fun selectTitle(index: Int): Boolean = false
        override fun selectChapter(index: Int): Boolean = false

        override fun selectAngle(index: Int): Boolean =
            !ended &&
                index in 0 until state.effectiveAngleCount &&
                NativeRemoteBluRayRegistry.selectAngle(id, index)

        override fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean =
            !ended && NativeRemoteBluRayRegistry.sendMenuCommand(id, command.localNativeMenuCode())

        override fun selectMenuPoint(x: Int, y: Int, activate: Boolean): Boolean =
            !ended && state.menuActive && NativeRemoteBluRayRegistry.selectMenuPoint(id, x, y, activate)

        override fun setNavigationChangedListener(listener: (() -> Unit)?) {
            this.listener = listener
        }

        override fun close() {
            listener = null
        }
    }

    companion object {
        fun create(context: Context, uri: String): NativeLocalBluRaySource? {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
            val descriptor =
                when (parsed.scheme?.lowercase()) {
                    "file" -> {
                        val path = parsed.path?.takeIf(String::isNotBlank) ?: return null
                        val file = File(path)
                        if (!file.isFile) return null
                        runCatching {
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        }.getOrNull()
                    }
                    "content" -> runCatching { context.contentResolver.openFileDescriptor(parsed, "r") }.getOrNull()
                    else -> null
                } ?: return null
            return NativeLocalBluRaySource(descriptor)
        }
    }
}

internal object NativeLocalBluRayRegistry {
    private const val REGISTRY_CLASS = "dev.yfuse.mpv.YfuseBluRayRegistry"

    fun register(source: NativeLocalBluRaySource): Long? {
        if (!installedMpvNativeBuildCapabilities.remoteRawBluRay) return null
        return runCatching {
            val clazz = Class.forName(REGISTRY_CLASS, false, MpvVideoEngine::class.java.classLoader)
            (clazz.getMethod("register", Any::class.java).invoke(null, source) as? Number)
                ?.toLong()?.takeIf { it > 0L }?.also(source::bindNativeId)
        }.getOrNull()
    }

    fun unregister(id: Long) {
        if (id <= 0L) return
        runCatching {
            val clazz = Class.forName(REGISTRY_CLASS, false, MpvVideoEngine::class.java.classLoader)
            clazz.getMethod("unregister", java.lang.Long.TYPE).invoke(null, id)
        }
    }
}

private fun PlaybackDiscMenuCommand.localNativeMenuCode(): Int =
    when (this) {
        PlaybackDiscMenuCommand.ShowMenu -> 0
        PlaybackDiscMenuCommand.Back -> 1
        PlaybackDiscMenuCommand.Up -> 2
        PlaybackDiscMenuCommand.Down -> 3
        PlaybackDiscMenuCommand.Left -> 4
        PlaybackDiscMenuCommand.Right -> 5
        PlaybackDiscMenuCommand.Select -> 6
    }
