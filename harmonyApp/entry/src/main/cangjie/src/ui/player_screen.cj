package com.yfuse.harmony.entry

import com.yfuse.harmony.entry.design.DesignTokens

import ohos.base.*
import ohos.arkui.component.*
import ohos.arkui.state_management.*
import ohos.arkui.state_macro_manage.*

/** System playback surface. The media URL is supplied by the provider repository at navigation time. */
@Component
class PlayerScreen {
    var source: String = ""
    var resumePositionMs: Int64 = 0
    var onCheckpoint: (Int64, Bool, Bool) -> Unit = { _, _, _ => }
    @State var restored: Bool = false

    func aboutToDisappear(): Unit {
        onCheckpoint(Int64(positionSeconds * 1000.0), true, true)
        controller.pause()
    }
    var onBack: () -> Unit = { => }
    @State var playing: Bool = false
    @State var prepared: Bool = false
    @State var phase: String = "等待媒体"
    @State var playbackRate: PlaybackSpeed = PlaybackSpeed.SpeedForward100X
    @State var speedLabel: String = "1.0×"
    @State var imageFit: ImageFit = ImageFit.Contain
    @State var fitLabel: String = "适应"
    @State var controlsVisible: Bool = true
    @State var locked: Bool = false
    @State var panel: Int64 = 0
    @State var positionSeconds: Float64 = 0.0
    @State var durationSeconds: Float64 = 0.0
    let controller: VideoController = VideoController()

    func build() {
        Stack {
            if (source.isEmpty()) {
                Column {
                    Text("播放器").fontColor(Color(DesignTokens.darkText)).fontSize(26).fontWeight(FontWeight.Bold)
                    Text("等待媒体源").fontColor(Color(DesignTokens.darkText))
                    Button("返回").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { onCheckpoint(Int64(positionSeconds * 1000.0), true, true); onBack() })
                }
            } else {
                Video(src: source, currentProgressRate: playbackRate, controller: controller)
                    .width(100.percent)
                    .height(100.percent)
                    .autoPlay(true)
                    .controls(false)
                    .objectFit(imageFit)
                    .onStart({ => playing = true; phase = "播放中"; onCheckpoint(Int64(positionSeconds * 1000.0), false, true) })
                    .onPause({ => playing = false; phase = "已暂停"; onCheckpoint(Int64(positionSeconds * 1000.0), true, true) })
                    .onSeeking({ _ => phase = "正在定位" })
                    .onSeeked({ _ => phase = if (playing) { "播放中" } else { "已暂停" } })
                    .onPrepared({ event =>
                        durationSeconds = Float64(event.duration ?? 0)
                        prepared = true
                        phase = "已就绪"
                        if (!restored) {
                            restored = true
                            let target = min(Float64(resumePositionMs) / 1000.0, durationSeconds)
                            if (target > 0.0) { controller.setCurrentTime(Int32(target), None) }
                        }
                    })
                    .onUpdate({ event => positionSeconds = Float64(event.time ?? 0); onCheckpoint(Int64(positionSeconds * 1000.0), !playing, false) })
                    .onFinish(() => { playing = false; phase = "已结束"; onCheckpoint(Int64(durationSeconds * 1000.0), true, true) })
                    .onError(() => { playing = false; phase = "系统播放器错误（平台未提供错误码）"; panel = 8 })
            }

            if (controlsVisible) {
                Column {
                    Row {
                        Button("‹").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { onCheckpoint(Int64(positionSeconds * 1000.0), true, true); onBack() })
                        Text(phase).fontColor(Color(DesignTokens.darkText)).fontSize(18).fontWeight(FontWeight.Bold)
                        Blank()
                        Button(if (locked) { "解锁" } else { "锁定" }).backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { locked = !locked })
                        Button("画中画 · 暂不支持").enabled(false).backgroundColor(Color(DesignTokens.darkSurface)).fontColor(Color(DesignTokens.darkSecondary)).borderRadius(DesignTokens.radiusSmall)
                    }
                    Blank()
                    if (!locked) {
                        Column {
                            Slider(value: positionSeconds, min: 0.0, max: if (durationSeconds > 0.0) { durationSeconds } else { 1.0 })
                                .enabled(prepared && durationSeconds > 0.0)
                                .onChange({ value, mode => if (mode == SliderChangeMode.End || mode == SliderChangeMode.Click) { seek(value) } })
                            Row {
                                Text(formatTime(positionSeconds)).fontColor(Color(DesignTokens.darkText))
                                Blank()
                                Text(formatTime(durationSeconds)).fontColor(Color(DesignTokens.darkText))
                            }
                            Row {
                                Button("弹幕").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { panel = 7 })
                                Button("-10").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { seek(positionSeconds - 10.0) })
                                Button(if (playing) { "暂停" } else { "播放" }).backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => {
                                    if (playing) { controller.pause() } else { controller.start() }
                                })
                                Button("+10").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { seek(positionSeconds + 10.0) })
                                Button("选集").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { panel = 4 })
                            }
                            Row {
                                PanelButton("画质", 1); PanelButton("音轨", 2); PanelButton("字幕", 3)
                                PanelButton("倍速", 5); PanelButton("画面", 6); PanelButton("诊断", 8)
                            }
                        }.padding(18).width(100.percent)
                    }
                }.padding(12).width(100.percent).height(100.percent)
            }

            if (panel > 0) { PlayerPanel(panel).width(100.percent) }
        }.width(100.percent).height(100.percent).onClick(() => { controlsVisible = !controlsVisible })
    }

    private func seek(value: Float64): Unit {
        if (!prepared || durationSeconds <= 0.0) { return }
        controller.setCurrentTime(Int32(min(max(value, 0.0), durationSeconds)), None)
    }

    @Builder func Unavailable(label: String, reason: String) {
        Button(label + " · 暂不可用").enabled(false).backgroundColor(Color(DesignTokens.darkSurface)).fontColor(Color(DesignTokens.darkSecondary))
        Text(reason).fontColor(Color(DesignTokens.darkSecondary))
    }
    @Builder func SpeedButton(label: String, value: PlaybackSpeed) {
        Button(label).enabled(prepared).backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent))
            .onClick({ => playbackRate = value; speedLabel = label })
    }
    @Builder func FitButton(label: String, value: ImageFit) {
        Button(label).backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent))
            .onClick({ => imageFit = value; fitLabel = label })
    }

    @Builder func PanelButton(label: String, value: Int64) { Button(label).backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { panel = value }).layoutWeight(1) }
    @Builder func PlayerPanel(value: Int64) {
        Column {
            Row { Text(panelTitle(value)).fontColor(Color(DesignTokens.darkText)).fontSize(18).fontWeight(FontWeight.Bold); Blank(); Button("关闭").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { panel = 0 }) }
            if (value == 1) { Unavailable("播放中切换画质", "请返回媒体详情选择服务器实际提供的片源；本页未接入转码切换。") }
            else if (value == 2) { Unavailable("音轨选择 / 音频直通", "当前系统 Video 未接入音轨枚举与选择，无法确认默认音轨或直通状态。") }
            else if (value == 3) { Unavailable("主字幕 / 双字幕 / 样式与偏移", "字幕轨道与渲染接口尚未接入。") }
            else if (value == 4) { Unavailable("下一集 / 剧集列表", "当前播放会话只含一个真实媒体源，请返回媒体库选择单集。") }
            else if (value == 5) {
                Text("请求速度：" + speedLabel).fontColor(Color(DesignTokens.darkText))
                Row {
                    SpeedButton("0.75×", PlaybackSpeed.SpeedForward075X)
                    SpeedButton("1.0×", PlaybackSpeed.SpeedForward100X)
                    SpeedButton("1.25×", PlaybackSpeed.SpeedForward125X)
                    SpeedButton("1.75×", PlaybackSpeed.SpeedForward175X)
                    SpeedButton("2.0×", PlaybackSpeed.SpeedForward200X)
                }
                Text("实际支持的倍速由系统播放器决定。该接口没有实际倍速回调。").fontColor(Color(DesignTokens.darkSecondary))
            }
            else if (value == 6) {
                Text("画面模式：" + fitLabel).fontColor(Color(DesignTokens.darkText))
                Row { FitButton("适应", ImageFit.Contain); FitButton("拉伸填充", ImageFit.Fill); FitButton("等比裁切", ImageFit.Cover) }
            }
            else if (value == 7) { Unavailable("弹幕显示 / 发送 / 屏蔽词", "尚未连接弹幕数据源与绘制层。") }
            else {
                Text("内核：Harmony 系统 Video · 状态：" + phase).fontColor(Color(DesignTokens.darkText))
                Text("位置：" + formatTime(positionSeconds) + " / " + formatTime(durationSeconds)).fontColor(Color(DesignTokens.darkText))
                Text("请求速度：" + speedLabel + " · 画面：" + fitLabel).fontColor(Color(DesignTokens.darkText))
                Text("解码器、帧率、缓存区间、A/V 同步及 HDR/杜比实际输出：平台未提供证据").fontColor(Color(DesignTokens.darkSecondary))
                Unavailable("切换内核 / 导出诊断", "原生渲染和诊断导出适配尚未接入。")
            }
        }.padding(18).height(260)
    }
}

private func panelTitle(value: Int64): String {
    return match (value) {
        case 1 => "画质与片源"; case 2 => "音轨"; case 3 => "字幕"; case 4 => "选集"
        case 5 => "播放速度"; case 6 => "画面模式"; case 7 => "弹幕"; case _ => "内核与诊断"
    }
}

private func max(left: Float64, right: Float64): Float64 { if (left > right) { left } else { right } }
private func formatTime(value: Float64): String {
    let total = Int64(value)
    let hours = total / 3600
    let minutes = (total % 3600) / 60
    let seconds = total % 60
    return if (hours > 0) { "${hours}:${minutes}:${seconds}" } else { "${minutes}:${seconds}" }
}

private func min(left: Float64, right: Float64): Float64 { if (left < right) { left } else { right } }
