package com.yfuse.harmony.entry

import ohos.base.*
import ohos.component.*
import ohos.state_manage.*
import ohos.state_macro_manage.*
import com.yfuse.harmony.entry.data.*
import com.yfuse.harmony.entry.provider.*
import com.yfuse.harmony.entry.storage.*
import com.yfuse.harmony.entry.network.*

/** Real Emby/Jellyfin slice; other feature pages keep their existing capability gates. */
@Component
class ConnectedMediaScreen {
    var mode: Int64 = 0
    var onOpenTmdb: () -> Unit = { => }
    var onOpenCalendar: () -> Unit = { => }
    var onPlayingChanged: (Bool) -> Unit = { _ => }
    @State var revision: Int64 = 0
    @State var endpoint: String = ""
    @State var userName: String = ""
    @State var password: String = ""
    @State var query: String = ""
    @State var jellyfin: Bool = false
    @State var cleartext: Bool = false
    var session: ConnectedMediaSession = createConnectedMediaSession()

    func aboutToAppear(): Unit {
        session.changed = { => revision++; onPlayingChanged(session.route == 2) }
        session.restore()
        if (mode != 2 && session.route == 0 && session.items.isEmpty()) { session.load(query) }
        session.changed()
    }
    func aboutToDisappear(): Unit { session.leave(); onPlayingChanged(false) }

    func build() {
        Column {
            if (revision >= 0) {
                if (session.route == 2) {
                    PlayerScreen(source: session.source, resumePositionMs: session.resumeMs,
                        onCheckpoint: { position, paused, force => session.checkpoint(position, paused, force) },
                        onBack: { => session.back() })
                } else {
                    Scroll {
                        Column {
                            Text(if (mode == 2) { "我的服务器" } else if (mode == 3) { "搜索" } else { "媒体库" })
                                .fontSize(26).fontWeight(FontWeight.Bold)
                            if (mode == 0 && session.route == 0) {
                                Row {
                                    Button("影视资料").onClick({ => onOpenTmdb() })
                                    Button("追剧日历").onClick({ => onOpenCalendar() })
                                }
                            }
                            if (!session.error.isEmpty()) { Text(session.error) }
                            if (session.loading) { Text("正在加载…") }
                            if (session.route == 1) {
                                Button("返回媒体库").onClick({ => session.back() })
                                if (let Some(detail) <- session.selected) {
                                    Text(detail.item.title).fontSize(22).fontWeight(FontWeight.Bold)
                                    Text(detail.item.subtitle)
                                    Text(detail.item.overview)
                                    Button("播放 / 继续观看").enabled(!session.loading).onClick({ => session.play() })
                                }
                            } else {
                                if (mode == 2 || session.registry.servers.isEmpty()) { LoginForm() }
                                ForEach(session.registry.servers.toArray(), { server: SavedServer =>
                                    Button(server.serverName + " · " + server.userName)
                                        .onClick({ => session.chooseServer(server) })
                                })
                                if (mode != 2) {
                                    TextInput(text: query, placeholder: "搜索当前服务器")
                                        .onChange({ value => query = value })
                                    Row {
                                        Button("搜索").enabled(!session.loading).onClick({ => session.load(query) })
                                        Button("刷新").enabled(!session.loading).onClick({ => session.load("") })
                                    }
                                    ForEach(session.items.toArray(), { item: MediaItemModel =>
                                        Button(item.title + " · " + item.subtitle).width(100.percent)
                                            .onClick({ => session.open(item) })
                                    })
                                    if (!session.loading && session.items.isEmpty()) { Text("暂无媒体，请选择服务器或调整搜索词") }
                                    if (session.hasMore) {
                                        Button("加载更多").enabled(!session.loading).onClick({ => session.more() })
                                    }
                                }
                            }
                        }.padding(18).width(100.percent)
                    }.layoutWeight(1)
                }
            }
        }.width(100.percent).height(100.percent)
    }
    @Builder
    func LoginForm() {
        Column {
            Text("添加服务器").fontSize(20)
            Button(if (jellyfin) { "类型：Jellyfin" } else { "类型：Emby" }).onClick({ => jellyfin = !jellyfin })
            TextInput(text: endpoint, placeholder: "https://媒体服务器").onChange({ value => endpoint = value })
            TextInput(text: userName, placeholder: "用户名").onChange({ value => userName = value })
            TextInput(text: password, placeholder: "密码").type(InputType.Password).onChange({ value => password = value })
            Button(if (cleartext) { "已确认：仅在可信局域网使用 HTTP" } else { "允许可信局域网 HTTP（明文传输）" })
                .onClick({ => cleartext = !cleartext })
            Button("登录并保存").enabled(session.ready && !session.loading).onClick({ =>
                let submitted = password; password = ""
                session.login(if (jellyfin) { ProviderKind.Jellyfin } else { ProviderKind.Emby },
                    endpoint, userName, submitted, cleartext)
            })
        }.width(100.percent)
    }
}

func createConnectedMediaSession(): ConnectedMediaSession {
    return ConnectedMediaSession(NativeStringPreferenceStore(), AssetStoreSecretStore(),
        NativeSecretReferenceFactory(), HarmonyHttpTransport())
}
