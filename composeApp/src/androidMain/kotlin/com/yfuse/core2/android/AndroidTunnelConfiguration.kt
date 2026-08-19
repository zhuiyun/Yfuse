package com.yfuse.core2.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaFormat

/** One audio-clock identity shared by tunneled video codec and HW-AV-sync AudioTrack. */
internal data class AndroidTunnelConfiguration(
    val audioSessionId: Int,
) {
    init {
        require(audioSessionId > 0) { "Tunnel audio session id must be positive" }
    }

    fun configureVideoFormat(format: MediaFormat): MediaFormat =
        format.apply {
            setFeatureEnabled(
                MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback,
                true,
            )
            setInteger(MediaFormat.KEY_AUDIO_SESSION_ID, audioSessionId)
        }

    fun audioAttributes(): AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .setFlags(AudioAttributes.FLAG_HW_AV_SYNC)
            .build()
}

/** Creates the shared session id only after Strategy selected a tunneled-capable decoder. */
internal object AndroidTunnelConfigurationFactory {
    fun create(context: Context): AndroidTunnelConfiguration? {
        val manager = context.applicationContext.getSystemService(AudioManager::class.java) ?: return null
        val id = manager.generateAudioSessionId()
        if (id <= 0 || id == AudioManager.ERROR) return null
        return AndroidTunnelConfiguration(id)
    }
}
