package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackFailureKind

data class PlaybackProblemExplanation(
    val stage: String,
    val advice: String,
)

/** Structured failure evidence wins; an unmeasured output is never called a decoder failure. */
fun explainPlaybackProblem(state: PlaybackState): PlaybackProblemExplanation =
    when (state.errorKind) {
        PlaybackFailureKind.Authorization -> PlaybackProblemExplanation("服务器登录", "登录凭据已失效或无权访问，请重新登录此服务器。")
        PlaybackFailureKind.Drm -> PlaybackProblemExplanation("许可证", "检查当前片源的许可证配置与设备支持情况。")
        PlaybackFailureKind.Network -> PlaybackProblemExplanation("连接与读取", "检查服务器可达性与下载速度，再重试当前片源。")
        PlaybackFailureKind.Container -> PlaybackProblemExplanation("媒体解析", "当前文件无法正确拆包，可检查文件完整性或切换另一个版本。")
        PlaybackFailureKind.Decoder -> PlaybackProblemExplanation("音视频解码", "当前设备解码此轨道失败，可尝试其他音轨或同片的其他版本。")
        PlaybackFailureKind.Renderer -> PlaybackProblemExplanation("画面输出", "解码后的画面未能正常输出，可重新打开播放页。")
        PlaybackFailureKind.AudioSink -> PlaybackProblemExplanation("声音输出", "检查当前音轨和蓝牙 / HDMI 输出设备，尝试重新选择音轨。")
        else ->
            when {
                state.error != null -> PlaybackProblemExplanation("阶段未确定", "现有证据不足以确定原因，请导出诊断包保留本次播放记录。")
                state.ended -> PlaybackProblemExplanation("播放结束", "当前媒体已结束。")
                state.buffering -> PlaybackProblemExplanation("等待播放数据或输出", "查看前向缓冲与输出状态；缓冲为零不能单独证明网络故障。")
                state.playing -> PlaybackProblemExplanation("正在播放", "可在这里检查当前播放与恢复状态。")
                else -> PlaybackProblemExplanation("已暂停", "当前播放处于暂停状态。")
            }
    }
