package com.yfuse.feature.player

import android.media.MediaMetadataRetriever
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.MainActivity
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.sync.watchKey
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.playback.PlaybackSyncManager
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.SavedServer
import com.yfuse.core.offline.OfflineDownloadRequest
import com.yfuse.core.offline.OfflineMediaManager
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class PopulatedLibraryDeviceTest {
    @Test fun actualAccountAndMediaOperations() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val input = JSONObject(File(ctx.filesDir, "functional-input.json").readText())
        val out = File(ctx.filesDir, "domain-results.jsonl")
        val failures = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                withContext(Dispatchers.IO) {
                    suspend fun checkCase(name: String, action: suspend () -> Unit) {
                        val line = JSONObject().put("name", name)
                        val start = android.os.SystemClock.elapsedRealtime()
                        try {
                            withTimeout(90000) { action() }
                            line.put("result", "passed")
                        } catch (e: Exception) {
                            failures.add(name)
                            line.put("result", "failed").put("cause", e.javaClass.simpleName)
                        } finally {
                            out.appendText(line.put("ms", android.os.SystemClock.elapsedRealtime()-start).toString()+"\n")
                        }
                    }
                    val koin = GlobalContext.get()
                    val repo = koin.get<EmbyRepository>()
                    val registry = koin.get<ServerRegistry>()
                    val manager = koin.get<OfflineMediaManager>()
                    val host = "http://127.0.0.1:18196"
                    checkCase("invalid password rejected") {
                        check(repo.authenticate(host,"seekqa","separate-invalid-test-value").isFailure)
                    }
                    lateinit var server: SavedServer
                    checkCase("authenticate and save default server") {
                        val auth = repo.authenticate(host,"seekqa",input.getString("password")).getOrThrow()
                        check(auth.userId.isNotEmpty() && auth.accessToken.isNotEmpty())
                        server = SavedServer("qa-library",auth.baseUrl,auth.serverName,auth.userId,auth.userName,auth.accessToken)
                        registry.addOrUpdate(server)
                        registry.setDefault(server.id)
                    }
                    val mediaId = input.getString("item")
                    checkCase("library paging returns distinct film IDs") {
                        val lib=repo.libraries(server).getOrThrow().first { it.name=="Seek" }
                        val first=repo.libraryItems(server,lib.id,limit=1).getOrThrow()
                        val second=repo.libraryItems(server,lib.id,startIndex=1,limit=1).getOrThrow()
                        check(first.totalCount>=2 && first.items.size==1 && second.items.size==1)
                        check(first.items.first().id!=second.items.first().id)
                    }
                    checkCase("title search returns correct film and excludes unrelated film") {
                        val result=repo.search(server,"ProbeA").getOrThrow()
                        check(result.any { it.id==mediaId } && result.none { it.title=="ProbeB" })
                    }
                    checkCase("film detail belongs to selected title") {
                        check(repo.itemDetail(server,mediaId).getOrThrow().title=="ProbeA")
                    }
                    checkCase("favorite survives reread and removal") {
                        for (value in listOf(true,false)) {
                            repo.setFavorite(server,mediaId,value).getOrThrow()
                            check(repo.itemDetail(server,mediaId).getOrThrow().isFavorite==value)
                        }
                    }
                    checkCase("watched flag through the detail action survives reread and removal") {
                        for (value in listOf(true,false)) {
                            koin.get<PlaybackSyncManager>().markWatched(mediaKey=repo.itemDetail(server,mediaId).getOrThrow().providerIds.watchKey(mediaId),watched=value,serverId=server.id,serverItemId=mediaId)
                            koin.get<ServerSyncManager>().setPlayed(server,mediaId,"ProbeA",value).getOrThrow()
                            withTimeout(6000) {
                                while (repo.itemDetail(server,mediaId).getOrThrow().played!=value) kotlinx.coroutines.delay(150)
                            }
                        }
                    }
                    checkCase("playback negotiation includes video audio and external subtitle") {
                        val sources=repo.playbackInfo(server,mediaId,playSessionId="qa-domain").getOrThrow().MediaSources
                        val tracks=sources.flatMap { it.MediaStreams.orEmpty() }.map { it.Type?.lowercase() }
                        check(listOf("video","audio","subtitle").all { it in tracks })
                    }
                    checkCase("download finishes with original bytes and decodable local video") {
                        manager.enqueue(OfflineDownloadRequest(server.id,mediaId,"ProbeA"))
                        val entry=manager.items.first { list -> list.any { it.itemId==mediaId && (it.playable || it.status.name=="Failed") } }.first { it.itemId==mediaId }
                        check(entry.playable)
                        val path=checkNotNull(entry.localPath)
                        val file=File(path.removePrefix("file://"))
                        check(file.length()>0)
                        val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                        check(hash==input.getString("sha"))
                        val reader=MediaMetadataRetriever()
                        try {
                            reader.setDataSource(file.absolutePath)
                            check((reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L) in 149000L..151000L)
                            checkNotNull(reader.getFrameAtTime(65_000_000L)).recycle()
                        } finally { reader.release() }
                    }
                    checkCase("completed download removed from offline library") {
                        val row=manager.items.value.first { it.itemId==mediaId && it.playable }
                        manager.remove(row.id)
                        manager.items.first { list -> list.none { it.id==row.id } }
                    }
                }
            }
        }
        check(failures.isEmpty()) { failures.joinToString() }
    }
}
