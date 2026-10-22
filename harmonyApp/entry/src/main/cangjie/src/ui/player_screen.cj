package com.yfuse.harmony.entry

import com.yfuse.harmony.entry.design.DesignTokens

import ohos.base.*
import ohos.component.*
import ohos.state_manage.*
import ohos.state_macro_manage.*

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
                Video(src: source, controller: controller)
                    .width(100.percent)
                    .height(100.percent)
                    .autoPlay(true)
                    .controls(false)
                    .onPrepared({ event =>
                        durationSeconds = event.duration
                        if (!restored) {
                            restored = true
                            let target = min(Float64(resumePositionMs) / 1000.0, durationSeconds)
                            if (target > 0.0) { controller.setCurrentTime(Int32(target), None) }
                        }
                        playing = true
                    })
                    .onUpdate({ event => positionSeconds = event.time; onCheckpoint(Int64(event.time * 1000.0), !playing, false) })
                    .onFinish(() => { playing = false; onCheckpoint(Int64(durationSeconds * 1000.0), true, true) })
                    .onError(() => { playing = false; panel = 8 })
            }

            if (controlsVisible) {
                Column {
                    Row {
                        Button("‹").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { onCheckpoint(Int64(positionSeconds * 1000.0), true, true); onBack() })
                        Text("正在播放").fontColor(Color(DesignTokens.darkText)).fontSize(18).fontWeight(FontWeight.Bold)
                        Blank()
                        Button(if (locked) { "解锁" } else { "锁定" }).backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { locked = !locked })
                        Button("画中画").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall)
                    }
                    Blank()
                    if (!locked) {
                        Column {
                            Slider(value: positionSeconds, min: 0.0, max: if (durationSeconds > 0.0) { durationSeconds } else { 1.0 })
                                .onChange({ value, mode => positionSeconds = value; controller.setCurrentTime(Int32(value), None) })
                            Row {
                                Text(formatTime(positionSeconds)).fontColor(Color(DesignTokens.darkText))
                                Blank()
                                Text(formatTime(durationSeconds)).fontColor(Color(DesignTokens.darkText))
                            }
                            Row {
                                Button("弹幕").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { panel = 7 })
                                Button("-10").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { controller.setCurrentTime(Int32(max(0.0, positionSeconds - 10.0)), None) })
                                Button(if (playing) { "暂停" } else { "播放" }).backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => {
                                    if (playing) { controller.pause() } else { controller.start() }
                                    playing = !playing
                                    onCheckpoint(Int64(positionSeconds * 1000.0), !playing, true)
                                })
                                Button("+10").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { controller.setCurrentTime(Int32(positionSeconds + 10.0), None) })
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

    @Builder func PanelButton(label: String, value: Int64) { Button(label).backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { panel = value }).layoutWeight(1) }
    @Builder func PlayerPanel(value: Int64) {
        Column {
            Row { Text(panelTitle(value)).fontColor(Color(DesignTokens.darkText)).fontSize(18).fontWeight(FontWeight.Bold); Blank(); Button("关闭").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { panel = 0 }) }
            if (value == 1) { Button("原始画质 · 直接播放").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("兼容画质 · 转码").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall) }
            else if (value == 2) { Button("默认音轨").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("原始音频 / 直通").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall) }
            else if (value == 3) { Button("关闭字幕").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("主字幕").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("双字幕").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("字幕样式与偏移").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall) }
            else if (value == 4) { Button("下一集").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("剧集列表").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall) }
            else if (value == 5) { Row { Button("0.75×").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("1.0×").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("1.25×").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("1.5×").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("2.0×").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall) } }
            else if (value == 6) { Row { Button("适应").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("填充").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("裁切").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall) } }
            else if (value == 7) { Button("显示弹幕").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("发送弹幕").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("屏蔽词").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall) }
            else { Text("内核 · 解码器 · 渲染器 · HDR/杜比输出证据 · A/V 同步").fontColor(Color(DesignTokens.darkText)); Button("切换内核").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("导出诊断").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall) }
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
