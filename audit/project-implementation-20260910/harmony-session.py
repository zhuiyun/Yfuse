from pathlib import Path
p=Path('harmonyApp/entry/src/main/cangjie/src/provider/connected_session.cj')
p.write_text('''package com.yfuse.harmony.entry.provider

import std.collection.*
import com.yfuse.harmony.entry.data.*
import com.yfuse.harmony.entry.storage.*
import com.yfuse.harmony.entry.network.*

/** One foreground UI session. Generation guards prevent late requests replacing a newer page. */
public class ConnectedMediaSession {
    public let registry: ServerRegistry
    private let repository: MediaRepository
    private let progress: PlaybackProgressStore
    public var changed: () -> Unit = { => }
    public var items: ArrayList<MediaItemModel> = ArrayList<MediaItemModel>()
    public var selected: Option<MediaDetailModel> = None
    public var activeServer: Option<SavedServer> = None
    public var source: String = ""
    public var sourceId: String = ""
    public var resumeMs: Int64 = 0
    public var loading: Bool = false
    public var ready: Bool = false
    public var error: String = ""
    public var hasMore: Bool = false
    public var route: Int64 = 0
    private var query: String = ""
    private var generation: Int64 = 0
    private var checkpointMs: Int64 = -15000

    public init(documents: StringPreferenceStore, secrets: SecretStore, references: SecretReferenceFactory, transport: HttpTransport) {
        registry = ServerRegistry(secrets, PreferenceRegistryDocumentStore(documents), JsonRegistryCodec(), references)
        repository = MediaRepository(transport, registry)
        progress = PlaybackProgressStore(documents)
    }
    private func failed(problem: ApiFailure): Unit { loading = false; error = problem.message; changed() }
    public func restore(): Unit {
        match (registry.restore()) {
            case Failure(problem) => failed(problem); return
            case Success(_) => ready = true
        }
        match (progress.restore()) {
            case Failure(problem) => error = problem.message
            case Success(_) => ()
        }
        for (server in registry.servers) {
            if (server.id == registry.defaultServerId) { activeServer = Some(server) }
        }
        changed()
    }
    public func login(kind: ProviderKind, endpoint: String, user: String, password: String, cleartext: Bool): Unit {
        if (!ready || loading) { return }
        generation++; let request = generation
        loading = true; error = ""; changed()
        repository.login(kind, endpoint, user, password, cleartext, { result =>
            if (request != generation) { return }
            match (result) {
                case Failure(problem) => failed(problem)
                case Success(server) => activeServer = Some(server); loading = false; load("")
            }
        })
    }
    public func chooseServer(server: SavedServer): Unit {
        match (registry.setDefault(server.id)) {
            case Failure(problem) => failed(problem)
            case Success(_) => activeServer = Some(server); load("")
        }
    }
    public func load(value: String, append!: Bool = false): Unit {
        if (!ready) { return }
        let server = match (activeServer) { case Some(value) => value; case None => return }
        generation++; let request = generation
        route = 0; selected = None; source = ""; query = value
        loading = true; error = ""
        if (!append) { items = ArrayList<MediaItemModel>() }
        let start = if (append) { items.size } else { 0 }
        changed()
        repository.search(server, value, start, 60, { result =>
            if (request != generation) { return }
            match (result) {
                case Failure(problem) => failed(problem)
                case Success(page) =>
                    let next = ArrayList<MediaItemModel>()
                    for (item in items) { next.add(item) }
                    for (item in page.items) { next.add(item) }
                    items = next; hasMore = !page.items.isEmpty() && next.size < page.totalCount
                    loading = false; changed()
            }
        })
    }
    public func more(): Unit { if (!loading && hasMore) { load(query, append: true) } }
    public func open(item: MediaItemModel): Unit {
        let server = match (activeServer) { case Some(value) => value; case None => return }
        if (item.serverId != server.id) { return }
        generation++; let request = generation
        route = 1; selected = None; loading = true; error = ""; changed()
        repository.detail(server, item.id, { result =>
            if (request != generation) { return }
            match (result) {
                case Failure(problem) => failed(problem)
                case Success(detail) => selected = Some(detail); loading = false; changed()
            }
        })
    }
    public func play(): Unit {
        if (loading) { return }
        let server = match (activeServer) { case Some(value) => value; case None => return }
        let detail = match (selected) { case Some(value) => value; case None => return }
        generation++; let request = generation
        loading = true; error = ""; changed()
        repository.playbackSources(server, detail.item.id, "{}", { result =>
            if (request != generation) { return }
            match (result) {
                case Failure(problem) => failed(problem)
                case Success(sources) =>
                    if (sources.isEmpty()) {
                        failed(ApiFailure(FailureKind.NotFound, "此条目没有可播放片源，请打开具体电影或单集")); return
                    }
                    match (repository.playbackUrl(server, sources[0])) {
                        case Failure(problem) => failed(problem)
                        case Success(url) =>
                            source = url; sourceId = sources[0].id
                            resumeMs = progress.position(server.id, detail.item.id, detail.resumePositionMs)
                            if (detail.durationMs > 0 && resumeMs >= detail.durationMs) { resumeMs = 0 }
                            checkpointMs = -15000; route = 2; loading = false; changed()
                    }
            }
        })
    }
    public func checkpoint(positionMs: Int64, paused: Bool, force: Bool): Unit {
        if (route != 2 || positionMs < 0) { return }
        if (!force && positionMs >= checkpointMs && positionMs - checkpointMs < 15000) { return }
        checkpointMs = positionMs
        let server = match (activeServer) { case Some(value) => value; case None => return }
        let detail = match (selected) { case Some(value) => value; case None => return }
        match (progress.save(server.id, detail.item.id, positionMs)) {
            case Failure(problem) => error = problem.message; changed()
            case Success(_) => ()
        }
        let request = generation
        repository.progress(server, detail.item.id, sourceId, positionMs, paused, { result =>
            if (request != generation) { return }
            match (result) {
                case Failure(_) => error = "服务器进度暂未同步；本机续播记录仍会保留"; changed()
                case Success(_) => ()
            }
        })
    }
    public func back(): Unit { generation++; route = 0; loading = false; source = ""; selected = None; changed() }
    public func leave(): Unit { generation++; source = ""; changed = { => } }
}
''',encoding='utf-8')
# keep numeric parsing consistent with installed stdx JSON API
p=Path('harmonyApp/entry/src/main/cangjie/src/storage/playback_progress.cj');s=p.read_text(encoding='utf-8');s=s.replace('''                        match (JsonValue.fromStr(value)) {
                            case object: JsonObject => records = object
                            case _ => throw Exception("invalid document")
                        }''','''                        records = JsonValue.fromStr(value).asObject()''');s=s.replace('''                match (value) {
                    case number: JsonInt => return if (number.getValue() >= 0) { number.getValue() } else { fallback }
                    case _ => return fallback
                }''','''                try { let position = value.asInt().getValue(); return if (position >= 0) { position } else { fallback } }
                catch (_: Exception) { return fallback }''');p.write_text(s,encoding='utf-8')
