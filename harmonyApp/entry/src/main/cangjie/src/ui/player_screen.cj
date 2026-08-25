package com.yfuse.harmony.entry

import ohos.base.*
import ohos.component.*
import ohos.state_manage.*
import ohos.state_macro_manage.*

/** System playback surface. The media URL is supplied by the provider repository at navigation time. */
@Component
class PlayerScreen {
    var source: String = ""
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
                    Text("播放器").fontSize(26).fontWeight(FontWeight.Bold)
                    Text("等待媒体源")
                    Button("返回").onClick(() => { onBack() })
                }
            } else {
                Video(src: source, controller: controller)
                    .width(100.percent)
                    .height(100.percent)
                    .autoPlay(true)
                    .controls(false)
                    .onPrepared({ event =>
                        durationSeconds = event.duration
                        playing = true
                    })
                    .onUpdate({ event => positionSeconds = event.time })
                    .onFinish(() => { playing = false })
                    .onError(() => { playing = false; panel = 8 })
            }

            if (controlsVisible) {
                Column {
                    Row {
                        Button("‹").onClick(() => { onBack() })
                        Text("正在播放").fontSize(18).fontWeight(FontWeight.Bold)
                        Blank()
                        Button(if (locked) { "解锁" } else { "锁定" }).onClick(() => { locked = !locked })
                        Button("画中画")
                    }
                    Blank()
                    if (!locked) {
                        Column {
                            Slider(value: positionSeconds, min: 0.0, max: if (durationSeconds > 0.0) { durationSeconds } else { 1.0 })
                                .onChange({ value, mode => positionSeconds = value; controller.setCurrentTime(Int32(value)) })
                            Row {
                                Text(formatTime(positionSeconds))
                                Blank()
                                Text(formatTime(durationSeconds))
                            }
                            Row {
                                Button("弹幕").onClick(() => { panel = 7 })
                                Button("-10").onClick(() => { controller.setCurrentTime(Int32(max(0.0, positionSeconds - 10.0))) })
                                Button(if (playing) { "暂停" } else { "播放" }).onClick(() => {
                                    if (playing) { controller.pause() } else { controller.start() }
                                    playing = !playing
                                })
                                Button("+10").onClick(() => { controller.setCurrentTime(Int32(positionSeconds + 10.0)) })
                                Button("选集").onClick(() => { panel = 4 })
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

    @Builder func PanelButton(label: String, value: Int64) { Button(label).onClick(() => { panel = value }).layoutWeight(1) }
    @Builder func PlayerPanel(value: Int64) {
        Column {
            Row { Text(panelTitle(value)).fontSize(18).fontWeight(FontWeight.Bold); Blank(); Button("关闭").onClick(() => { panel = 0 }) }
            if (value == 1) { Button("原始画质 · 直接播放"); Button("兼容画质 · 转码") }
            else if (value == 2) { Button("默认音轨"); Button("原始音频 / 直通") }
            else if (value == 3) { Button("关闭字幕"); Button("主字幕"); Button("双字幕"); Button("字幕样式与偏移") }
            else if (value == 4) { Button("下一集"); Button("剧集列表") }
            else if (value == 5) { Row { Button("0.75×"); Button("1.0×"); Button("1.25×"); Button("1.5×"); Button("2.0×") } }
            else if (value == 6) { Row { Button("适应"); Button("填充"); Button("裁切") } }
            else if (value == 7) { Button("显示弹幕"); Button("发送弹幕"); Button("屏蔽词") }
            else { Text("内核 · 解码器 · 渲染器 · HDR/杜比输出证据 · A/V 同步"); Button("切换内核"); Button("导出诊断") }
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
