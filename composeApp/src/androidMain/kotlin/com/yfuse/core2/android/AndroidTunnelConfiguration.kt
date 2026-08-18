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
            // Public MediaFormat key value used by the platform tunneled codec contract.
            setInteger(KEY_AUDIO_SESSION_ID_COMPAT, audioSessionId)
        }

    fun audioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .setFlags(AUDIO_FLAG_HW_AV_SYNC)
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

// AudioAttributes.FLAG_HW_AV_SYNC is the platform flag used by multimedia tunneling. Keeping the
// numeric wire value here avoids depending on hidden/vendor SDK surfaces while still using the
// public Builder.setFlags API.
private const val AUDIO_FLAG_HW_AV_SYNC = 0x10
private const val KEY_AUDIO_SESSION_ID_COMPAT = "audio-session-id"
