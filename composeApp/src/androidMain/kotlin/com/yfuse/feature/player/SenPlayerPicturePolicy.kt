package com.yfuse.feature.player

/** Mirrors the supplied APK's op3.w/x gates; 1000 nits is guidance, not a measured threshold. */
internal fun senPlayerPictureEligibility(
    displaySupportsHdr10: Boolean,
    dynamicRange: String?,
    dolbyVisionProfile: Int,
    durationMs: Long,
    upscalingAvailable: Boolean?,
): SenPlayerPictureEligibility =
    SenPlayerPictureEligibility(
        upscalingUnavailableReason =
            when {
                upscalingAvailable == null -> "等待超清能力信息"
                durationMs <= 0 -> "超清仅适用于时长已知的视频"
                !upscalingAvailable -> "当前片源或设备不支持 SenPlayer 超清"
                else -> null
            },
        sdrToHdrUnavailableReason =
            when {
                !displaySupportsHdr10 -> "屏幕未报告 HDR10 支持"
                dynamicRange == null -> "等待视频色彩信息"
                !dynamicRange.equals("sdr", ignoreCase = true) || dolbyVisionProfile != 0 -> "仅适用于 SDR 非杜比片源"
                else -> null
            },
    )
