package com.yfuse.core.playback

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import com.yfuse.core.logging.AppLog
import com.yfuse.core.util.androidAppContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal actual fun createPlaybackDeviceCapabilitiesProvider(): PlaybackDeviceCapabilitiesProvider {
    val context = androidAppContext ?: return PlaybackDeviceCapabilitiesProvider {
        PlaybackDeviceCapabilities.conservative()
    }
    return AndroidPlaybackDeviceCapabilitiesProvider(context.applicationContext)
}

/** A short cache keeps queue negotiation and immediate Activity launch on one consistent route. */
private class AndroidPlaybackDeviceCapabilitiesProvider(
    private val context: Context,
) : PlaybackDeviceCapabilitiesProvider {
    private val probe = AndroidPlaybackDeviceCapabilitiesProbe(context)
    private var cachedAtMs = Long.MIN_VALUE
    private var cached: PlaybackDeviceCapabilities? = null
    private val videoSupportCache = mutableMapOf<PlaybackVideoRequirements, PlaybackVideoSupport>()
    private val revision = MutableStateFlow(0L)
    private val callbackHandler = Handler(Looper.getMainLooper())
    private var displayHdrSnapshot = runCatching(probe::displayHdrFormats).getOrDefault(emptySet())

    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                invalidate("audio_devices_added")
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                invalidate("audio_devices_removed")
            }
        }
    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = invalidateDisplay("display_added")

            override fun onDisplayRemoved(displayId: Int) = invalidateDisplay("display_removed")

            override fun onDisplayChanged(displayId: Int) = invalidateDisplay("display_changed")
        }

    init {
        if (Build.VERSION.SDK_INT >= 23) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, callbackHandler)
        }
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, callbackHandler)
    }

    @Synchronized
    override fun current(): PlaybackDeviceCapabilities {
        val now = SystemClock.elapsedRealtime()
        cached?.takeIf { now - cachedAtMs in 0 until CAPABILITY_CACHE_MS }?.let { return it }
        return runCatching(probe::probe)
            .onFailure { error ->
                AppLog.warning(
                    category = "player.capabilities",
                    event = "probe_failed",
                    message = "Playback capability discovery failed; using safe defaults",
                    throwable = error,
                )
            }.getOrElse { PlaybackDeviceCapabilities.conservative() }
            .also { value ->
                cached = value
                cachedAtMs = now
            }
    }

    @Synchronized
    override fun videoSupport(requirements: PlaybackVideoRequirements): PlaybackVideoSupport {
        videoSupportCache[requirements]?.let { return it }
        return runCatching { probe.videoSupport(requirements) }
            .onFailure { error ->
                AppLog.warning(
                    category = "player.capabilities",
                    event = "video_support_probe_failed",
                    message = "Exact video decoder capability check failed",
                    throwable = error,
                )
            }.getOrElse { current().videoSupport(requirements) }
            .also { videoSupportCache[requirements] = it }
    }

    override fun revisions(): Flow<Long> = revision.asStateFlow()

    @Synchronized
    private fun invalidate(reason: String) {
        cached = null
        cachedAtMs = Long.MIN_VALUE
        videoSupportCache.clear()
        revision.value = revision.value + 1L
        AppLog.info(
            category = "player.capabilities",
            event = "output_changed",
            message = "Playback output capabilities invalidated",
            attributes = mapOf("reason" to reason, "revision" to revision.value.toString()),
        )
    }

    @Synchronized
    private fun invalidateDisplay(reason: String) {
        val detected = runCatching(probe::displayHdrFormats).getOrElse { return }
        // Refresh-rate matching also triggers onDisplayChanged. It must not restart playback.
        if (!displayCapabilitiesChanged(displayHdrSnapshot, detected)) return
        displayHdrSnapshot = detected
        invalidate(reason)
    }
}

internal fun displayCapabilitiesChanged(
    previous: Set<PlaybackHdrFormat>,
    current: Set<PlaybackHdrFormat>,
): Boolean = previous != current

private class AndroidPlaybackDeviceCapabilitiesProbe(
    private val context: Context,
) {
    @SuppressLint("NewApi")
    fun probe(): PlaybackDeviceCapabilities {
        val decoders = decoderCapabilities()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val directAudio = directAudioFormats()
        val outputDevices =
            if (Build.VERSION.SDK_INT >= 23) {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            } else {
                emptyList()
            }
        return PlaybackDeviceCapabilities(
            hdrFormats = displayHdrFormats(),
            videoDecoders = decoders.video,
            hdrDecoders = decoders.hdrVideo,
            audioDecoders = decoders.audio,
            directAudioFormats = directAudio,
            dolbyVisionCodecProfiles = decoders.dolbyVisionProfiles,
            dolbyVisionBaseCodecs = dolbyVisionBaseCodecs(decoders.dolbyVisionProfiles),
            audioRoutes = outputDevices.audioRoutes(),
            maxAudioChannels = maxPlayableAudioChannels(decoders.maxAudioChannels, directAudio),
        )
    }

    fun videoSupport(requirements: PlaybackVideoRequirements): PlaybackVideoSupport {
        val codec = requirements.codec
            ?: return PlaybackVideoSupport.unknown("片源没有提供视频编码")
        val mimeTypes = VIDEO_MIME_TYPES.filterValues { it == codec }.keys
        if (mimeTypes.isEmpty()) {
            return PlaybackVideoSupport.unknown("${codec.name} 没有对应的 Android MIME")
        }
        val candidates =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                !info.isEncoder &&
                    info.supportedTypes.any { type -> type.lowercase() in mimeTypes } &&
                    isHardwareDecoder(info)
            }
        if (candidates.isEmpty()) {
            return PlaybackVideoSupport.unsupported("设备没有 ${codec.name} 硬件解码器")
        }

        val rejected = mutableListOf<String>()
        candidates.forEach { info ->
            val rawType = info.supportedTypes.first { it.lowercase() in mimeTypes }
            val capabilities =
                runCatching { info.getCapabilitiesForType(rawType) }
                    .getOrElse {
                        rejected += "${info.name}: 无法读取能力"
                        return@forEach
                    }
            val profiles = capabilities.profileLevels.map { it.profile }
            val requiredHdr = requirements.hdrFormat
            if (requiredHdr != null && requiredHdr !in decoderHdrFormats(codec, profiles)) {
                rejected += "${info.name}: 不支持 ${requiredHdr.name} profile"
                return@forEach
            }
            if (!decoderSupportsBitDepth(codec, profiles, requirements.bitDepth)) {
                rejected += "${info.name}: 不支持 ${requirements.bitDepth} bit"
                return@forEach
            }
            val video = capabilities.videoCapabilities
            val width = requirements.width?.takeIf { it > 0 }
            val height = requirements.height?.takeIf { it > 0 }
            val frameRate = requirements.frameRate?.takeIf { it.isFinite() && it > 0.0 }
            if (video != null && width != null && height != null) {
                val modeSupported =
                    runCatching {
                        if (frameRate != null) {
                            video.areSizeAndRateSupported(width, height, frameRate) ||
                                video.areSizeAndRateSupported(height, width, frameRate)
                        } else {
                            video.isSizeSupported(width, height) || video.isSizeSupported(height, width)
                        }
                    }.getOrDefault(false)
                if (!modeSupported) {
                    rejected += "${info.name}: 不支持 ${width}x$height" +
                        (frameRate?.let { "@${formatFrameRate(it)}" } ?: "")
                    return@forEach
                }
            }
            val bitrate = requirements.bitrateBitsPerSecond?.takeIf { it > 0 }
            if (video != null && bitrate != null && !video.bitrateRange.contains(bitrate)) {
                rejected += "${info.name}: 码率 ${bitrate / 1_000_000}Mbps 超出范围"
                return@forEach
            }
            return PlaybackVideoSupport.supported(
                buildString {
                    append(info.name)
                    if (width != null && height != null) append(" · ${width}x$height")
                    if (frameRate != null) append("@${formatFrameRate(frameRate)}")
                    requirements.level?.let { append(" · Level $it") }
                },
            )
        }
        return PlaybackVideoSupport.unsupported(
            rejected.firstOrNull() ?: "${codec.name} 硬件解码器不支持当前片源参数",
        )
    }

    @Suppress("DEPRECATION")
    fun displayHdrFormats(): Set<PlaybackHdrFormat> {
        if (Build.VERSION.SDK_INT < 24) return emptySet()
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: manager.displays.firstOrNull()
        val supported = display?.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()
        return buildSet {
            if (Display.HdrCapabilities.HDR_TYPE_HDR10 in supported) add(PlaybackHdrFormat.Hdr10)
            if (Display.HdrCapabilities.HDR_TYPE_HLG in supported) add(PlaybackHdrFormat.Hlg)
            if (Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in supported) {
                add(PlaybackHdrFormat.DolbyVision)
            }
            if (
                Build.VERSION.SDK_INT >= 29 &&
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in supported
            ) {
                add(PlaybackHdrFormat.Hdr10Plus)
            }
        }
    }

    private fun decoderCapabilities(): DecoderCapabilities {
        val video = mutableSetOf<PlaybackVideoCodec>()
        val hdrVideo = mutableMapOf<PlaybackVideoCodec, MutableSet<PlaybackHdrFormat>>()
        val audio = mutableSetOf<PlaybackAudioCodec>()
        val dolbyVisionProfiles = mutableSetOf<Int>()
        var maxAudioChannels = 2
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.forEach { info ->
            if (info.isEncoder) return@forEach
            info.supportedTypes.forEach { rawType ->
                val type = rawType.lowercase()
                val videoCodec = VIDEO_MIME_TYPES[type]
                videoCodec?.let(video::add)
                val audioCodec = AUDIO_MIME_TYPES[type]
                audioCodec?.let(audio::add)
                val codecCapabilities =
                    runCatching { info.getCapabilitiesForType(rawType) }.getOrNull()
                if (audioCodec != null) {
                    val reportedChannels =
                        codecCapabilities
                            ?.audioCapabilities
                            ?.maxInputChannelCount
                            ?: 0
                    maxAudioChannels = maxOf(maxAudioChannels, reportedChannels)
                }
                if (type == DOLBY_VISION_MIME) {
                    codecCapabilities
                        ?.profileLevels
                        ?.mapTo(dolbyVisionProfiles) { level -> level.profile }
                }
                if (videoCodec != null) {
                    val profiles = codecCapabilities?.profileLevels?.map { it.profile }.orEmpty()
                    val hdrFormats = decoderHdrFormats(videoCodec, profiles)
                    if (hdrFormats.isNotEmpty()) {
                        hdrVideo.getOrPut(videoCodec) { mutableSetOf() }.addAll(hdrFormats)
                    }
                }
            }
        }
        // Raw PCM is the safe terminal format after any decoded audio track.
        audio += PlaybackAudioCodec.Pcm
        return DecoderCapabilities(
            video = video,
            hdrVideo = hdrVideo,
            audio = audio,
            dolbyVisionProfiles = dolbyVisionProfiles,
            maxAudioChannels = maxAudioChannels.coerceIn(2, 8),
        )
    }

    @SuppressLint("NewApi")
    private fun directAudioFormats(): Set<PlaybackAudioCodec> {
        if (Build.VERSION.SDK_INT < 29) return emptySet()
        val attributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        return DIRECT_AUDIO_ENCODINGS.mapNotNullTo(mutableSetOf()) { candidate ->
            val format =
                runCatching {
                    AudioFormat
                        .Builder()
                        .setEncoding(candidate.encoding)
                        .setSampleRate(48_000)
                        .setChannelMask(candidate.channelMask)
                        .build()
                }.getOrNull() ?: return@mapNotNullTo null
            val supported =
                runCatching {
                    if (Build.VERSION.SDK_INT >= 33) {
                        AudioManager.getDirectPlaybackSupport(format, attributes) and
                            AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED != 0
                    } else {
                        AudioTrack.isDirectPlaybackSupported(format, attributes)
                    }
                }.getOrDefault(false)
            candidate.codec.takeIf { supported }
        }
    }
}

private data class DecoderCapabilities(
    val video: Set<PlaybackVideoCodec>,
    val hdrVideo: Map<PlaybackVideoCodec, Set<PlaybackHdrFormat>>,
    val audio: Set<PlaybackAudioCodec>,
    val dolbyVisionProfiles: Set<Int>,
    val maxAudioChannels: Int,
)

internal fun decoderHdrFormats(
    codec: PlaybackVideoCodec,
    profiles: List<Int>,
): Set<PlaybackHdrFormat> =
    buildSet {
        when (codec) {
            PlaybackVideoCodec.DolbyVision -> {
                if (profiles.isNotEmpty()) add(PlaybackHdrFormat.DolbyVision)
            }

            PlaybackVideoCodec.Hevc -> {
                if (
                    profiles.any {
                        it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                            it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                            it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                    }
                ) {
                    add(PlaybackHdrFormat.Hlg)
                }
                if (
                    profiles.any {
                        it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                            it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                    }
                ) {
                    add(PlaybackHdrFormat.Hdr10)
                }
                if (
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus in profiles
                ) {
                    add(PlaybackHdrFormat.Hdr10Plus)
                }
            }

            PlaybackVideoCodec.Vp9 -> {
                if (
                    profiles.any {
                        it == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR ||
                            it == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR ||
                            it == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus ||
                            it == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10Plus
                    }
                ) {
                    add(PlaybackHdrFormat.Hdr10)
                    add(PlaybackHdrFormat.Hlg)
                }
                if (
                    profiles.any {
                        it == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus ||
                            it == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10Plus
                    }
                ) {
                    add(PlaybackHdrFormat.Hdr10Plus)
                }
            }

            PlaybackVideoCodec.Av1 -> {
                if (
                    profiles.any {
                        it == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 ||
                            it == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 ||
                            it == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
                    }
                ) {
                    add(PlaybackHdrFormat.Hlg)
                }
                if (
                    profiles.any {
                        it == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 ||
                            it == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
                    }
                ) {
                    add(PlaybackHdrFormat.Hdr10)
                }
                if (MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus in profiles) {
                    add(PlaybackHdrFormat.Hdr10Plus)
                }
            }

            else -> Unit
        }
    }

internal fun decoderSupportsBitDepth(
    codec: PlaybackVideoCodec,
    profiles: List<Int>,
    bitDepth: Int?,
): Boolean {
    if (bitDepth == null || bitDepth <= 8) return true
    return when (codec) {
        PlaybackVideoCodec.Hevc ->
            profiles.any { profile ->
                profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                    profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                    profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
            }
        PlaybackVideoCodec.Vp9 ->
            profiles.any { profile ->
                profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2 ||
                    profile == MediaCodecInfo.CodecProfileLevel.VP9Profile3 ||
                    profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR ||
                    profile == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR ||
                    profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus ||
                    profile == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10Plus
            }
        PlaybackVideoCodec.Av1 ->
            profiles.any { profile ->
                profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 ||
                    profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 ||
                    profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
            }
        PlaybackVideoCodec.DolbyVision -> profiles.isNotEmpty()
        else -> false
    }
}

private fun isHardwareDecoder(info: MediaCodecInfo): Boolean {
    if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
    val name = info.name.lowercase()
    return !name.startsWith("omx.google.") &&
        !name.startsWith("c2.android.") &&
        !name.contains("ffmpeg")
}

private fun formatFrameRate(rate: Double): String {
    val hundredths = (rate * 100.0 + 0.5).toInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}fps"
}

private fun dolbyVisionBaseCodecs(profiles: Set<Int>): Set<PlaybackVideoCodec> =
    buildSet {
        if (
            profiles.any {
                it == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavPen ||
                    it == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavPer
            }
        ) {
            add(PlaybackVideoCodec.H264)
        }
        if (
            profiles.any {
                it == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDen ||
                    it == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDer ||
                    it == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb ||
                    it == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDth ||
                    it == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtr ||
                    it == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn
            }
        ) {
            add(PlaybackVideoCodec.Hevc)
        }
    }

private data class DirectAudioEncoding(
    val codec: PlaybackAudioCodec,
    val encoding: Int,
    val channelMask: Int,
)

private fun List<AudioDeviceInfo>.audioRoutes(): Set<PlaybackAudioRoute> =
    mapTo(mutableSetOf()) { device ->
        when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            -> PlaybackAudioRoute.BuiltIn

            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            -> PlaybackAudioRoute.Hdmi

            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            -> PlaybackAudioRoute.Usb

            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            -> PlaybackAudioRoute.Bluetooth

            else -> PlaybackAudioRoute.Other
        }
    }.ifEmpty { setOf(PlaybackAudioRoute.BuiltIn) }

private fun maxPlayableAudioChannels(
    decoderChannels: Int,
    directAudioFormats: Set<PlaybackAudioCodec>,
): Int {
    val directMinimum =
        when {
            PlaybackAudioCodec.TrueHd in directAudioFormats ||
                PlaybackAudioCodec.DtsHd in directAudioFormats -> 8
            directAudioFormats.any {
                it == PlaybackAudioCodec.Ac3 ||
                    it == PlaybackAudioCodec.Eac3 ||
                    it == PlaybackAudioCodec.Eac3Joc ||
                    it == PlaybackAudioCodec.Dts
            } -> 6
            else -> 2
        }
    return maxOf(decoderChannels, directMinimum).coerceIn(2, 8)
}

private const val DOLBY_VISION_MIME = "video/dolby-vision"
private const val CAPABILITY_CACHE_MS = 5_000L

private val VIDEO_MIME_TYPES =
    mapOf(
        "video/avc" to PlaybackVideoCodec.H264,
        "video/hevc" to PlaybackVideoCodec.Hevc,
        "video/x-vnd.on2.vp8" to PlaybackVideoCodec.Vp8,
        "video/x-vnd.on2.vp9" to PlaybackVideoCodec.Vp9,
        "video/av01" to PlaybackVideoCodec.Av1,
        "video/mpeg2" to PlaybackVideoCodec.Mpeg2,
        "video/mp4v-es" to PlaybackVideoCodec.Mpeg4,
        "video/wvc1" to PlaybackVideoCodec.Vc1,
        DOLBY_VISION_MIME to PlaybackVideoCodec.DolbyVision,
    )

private val AUDIO_MIME_TYPES =
    mapOf(
        "audio/mp4a-latm" to PlaybackAudioCodec.Aac,
        "audio/mpeg" to PlaybackAudioCodec.Mp3,
        "audio/ac3" to PlaybackAudioCodec.Ac3,
        "audio/eac3" to PlaybackAudioCodec.Eac3,
        "audio/eac3-joc" to PlaybackAudioCodec.Eac3Joc,
        "audio/true-hd" to PlaybackAudioCodec.TrueHd,
        "audio/vnd.dts" to PlaybackAudioCodec.Dts,
        "audio/vnd.dts.hd" to PlaybackAudioCodec.DtsHd,
        "audio/ac4" to PlaybackAudioCodec.Ac4,
        "audio/flac" to PlaybackAudioCodec.Flac,
        "audio/opus" to PlaybackAudioCodec.Opus,
        "audio/vorbis" to PlaybackAudioCodec.Vorbis,
        "audio/raw" to PlaybackAudioCodec.Pcm,
    )

@Suppress("DEPRECATION")
private val DIRECT_AUDIO_ENCODINGS =
    listOf(
        DirectAudioEncoding(
            PlaybackAudioCodec.Ac3,
            AudioFormat.ENCODING_AC3,
            AudioFormat.CHANNEL_OUT_5POINT1,
        ),
        DirectAudioEncoding(
            PlaybackAudioCodec.Eac3,
            AudioFormat.ENCODING_E_AC3,
            AudioFormat.CHANNEL_OUT_5POINT1,
        ),
        DirectAudioEncoding(
            PlaybackAudioCodec.Eac3Joc,
            AudioFormat.ENCODING_E_AC3_JOC,
            AudioFormat.CHANNEL_OUT_5POINT1,
        ),
        DirectAudioEncoding(
            PlaybackAudioCodec.TrueHd,
            AudioFormat.ENCODING_DOLBY_TRUEHD,
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
        ),
        DirectAudioEncoding(
            PlaybackAudioCodec.Dts,
            AudioFormat.ENCODING_DTS,
            AudioFormat.CHANNEL_OUT_5POINT1,
        ),
        DirectAudioEncoding(
            PlaybackAudioCodec.DtsHd,
            AudioFormat.ENCODING_DTS_HD,
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
        ),
        DirectAudioEncoding(
            PlaybackAudioCodec.Ac4,
            AudioFormat.ENCODING_AC4,
            AudioFormat.CHANNEL_OUT_5POINT1,
        ),
    )
