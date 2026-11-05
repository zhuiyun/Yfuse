package com.yfuse.harmony.entry

import com.yfuse.harmony.entry.design.DesignTokens

import ohos.base.*
import ohos.arkui.component.*
import ohos.arkui.state_management.*
import ohos.arkui.state_macro_manage.*

@Component
class SplashScreen {
    @State var restoring: Bool = true
    func build() {
        Column {
            Blank()
            Text("Yfuse").fontColor(Color(DesignTokens.darkText)).fontSize(26).fontWeight(FontWeight.Bold)
            Text(if (restoring) { "正在恢复服务器与播放会话…" } else { "把影院带回家" }).fontColor(Color(DesignTokens.darkText)).fontSize(13)
            Blank()
        }.width(100.percent).height(100.percent).justifyContent(FlexAlign.Center)
    }
}

@Component
class TmdbInfoScreen {
    var onOpenDetail: () -> Unit = { => }
    func build() {
        FeatureUnavailable("影视资料", "尚未配置 TMDB 资料请求与授权。请返回已连接服务器的媒体库查看实际内容。")
    }
}

@Component
class LibraryGridScreen {
    var onOpenDetail: () -> Unit = { => }
    let session = createConnectedMediaSession()
    func build() { ConnectedMediaScreen(mode: 1, session: session) }
}

@Component
class ServerEditorScreen {
    let session = createConnectedMediaSession()
    func build() { ConnectedMediaScreen(mode: 2, session: session) }
}
