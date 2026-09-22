from pathlib import Path
p=Path('harmonyApp/entry/src/main/cangjie/src/provider/connected_session.cj');s=p.read_text(encoding='utf-8').replace('''    public func restore(): Unit {
        match''','''    public func restore(): Unit {
        ready = false
        let previousServerId = match (activeServer) { case Some(value) => value.id; case None => "" }
        match''',1).replace('''        for (server in registry.servers) {''','''        activeServer = None
        for (server in registry.servers) {''',1).replace('''        changed()
    }
    public func login''','''        let restoredServerId = match (activeServer) { case Some(value) => value.id; case None => "" }
        if (restoredServerId != previousServerId) {
            generation++; route = 0; selected = None; source = ""; items = ArrayList<MediaItemModel>()
        }
        changed()
    }
    public func login''',1).replace('''    public func chooseServer(server: SavedServer): Unit {
        match''','''    public func chooseServer(server: SavedServer): Unit {
        if (!ready) { return }
        match''',1).replace('''    public func open(item: MediaItemModel): Unit {
        let''','''    public func open(item: MediaItemModel): Unit {
        if (!ready) { return }
        let''',1).replace('''        if (loading) { return }
        let server''','''        if (!ready || loading) { return }
        let server''',1)
s=s.replace('''        match (progress.save(server.id, detail.item.id, positionMs)) {''','''        var savedLocally = false
        match (progress.save(server.id, detail.item.id, positionMs)) {''').replace('''            case Success(_) => ()
        }
        let request = generation''','''            case Success(_) => savedLocally = true
        }
        let request = generation''',1).replace('''case Failure(_) => error = "服务器进度暂未同步；本机续播记录仍会保留"; changed()''','''case Failure(_) =>
                    error = if (savedLocally) { "服务器进度暂未同步；本机续播记录已保存" }
                        else { "本机和服务器均未能保存播放进度，请检查存储与网络" }
                    changed()''')
p.write_text(s,encoding='utf-8')
p=Path('harmonyApp/entry/src/main/cangjie/src/ui/connected_media_screen.cj');s=p.read_text(encoding='utf-8').replace('''        if (!session.ready) {
            session.restore()
            if (mode != 2) { session.load("") }
        }''','''        session.restore()
        if (mode != 2 && session.route == 0 && session.items.isEmpty()) { session.load(query) }''');p.write_text(s,encoding='utf-8')
p=Path('harmonyApp/entry/src/main/cangjie/src/storage/server_registry.cj');s=p.read_text(encoding='utf-8').replace('''                if (document.isEmpty()) {
                    ApiResult<Unit>.Success(())''','''                if (document.isEmpty()) {
                    mutableServers = ArrayList<SavedServer>()
                    mutableDefaultServerId = ""
                    ApiResult<Unit>.Success(())''',1);p.write_text(s,encoding='utf-8')
p=Path('harmonyApp/entry/src/main/cangjie/src/storage/playback_progress.cj');s=p.read_text(encoding='utf-8').replace('''    public func restore(): ApiResult<Unit> {
        match''','''    public func restore(): ApiResult<Unit> {
        readable = false
        match''').replace('''                    if (!value.isEmpty()) {''','''                    records = JsonObject()
                    if (!value.isEmpty()) {''');p.write_text(s,encoding='utf-8')
