package com.yfuse.tv

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.arkivanov.decompose.retainedComponent
import com.yfuse.app.RootComponent
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.performance.preferHighRefreshRateForUi
import com.yfuse.feature.player.PlayerActivity
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.tv.integration.CastConnectHostAction
import com.yfuse.tv.integration.CastConnectHostActionResolver
import com.yfuse.tv.integration.CastConnectIntentResult
import com.yfuse.tv.integration.CastConnectLoadHandler
import com.yfuse.tv.integration.CastConnectReceiverBridge
import com.yfuse.tv.integration.TvPlaybackDeepLinkResolver
import com.yfuse.tv.ui.TvApp
import java.util.UUID

/** Android TV launcher hosting the real shared server graph and native TV navigation surface. */
class TvMainActivity : ComponentActivity() {
    private lateinit var graph: TvApplicationGraph
    private lateinit var rootComponent: RootComponent
    private lateinit var castActionResolver: CastConnectHostActionResolver
    private val castLoadHandler =
        CastConnectLoadHandler { request ->
            if (!::castActionResolver.isInitialized || isFinishing || isDestroyed) {
                false
            } else {
                val action = castActionResolver.resolve(request)
                if (action == null) {
                    false
                } else {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        dispatchCastAction(action)
                    } else {
                        runOnUiThread { dispatchCastAction(action) }
                    }
                    true
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferHighRefreshRateForUi()
        graph = (application as TvApplication).graph
        rootComponent = retainedComponent { componentContext ->
            graph.createRootComponent(componentContext)
        }
        castActionResolver =
            CastConnectHostActionResolver {
                graph.serverRegistry.data.value.servers
            }
        // LOAD can arrive with the cold-start intent, so the host must be ready before the SDK
        // sees that intent. The callback resolves only TV-local identities or a transient URL.
        CastConnectReceiverBridge.installLoadHandler(castLoadHandler)
        setContent {
            TvApp(rootComponent)
        }
        consumeIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIncomingIntent(intent)
    }

    override fun onDestroy() {
        CastConnectReceiverBridge.clearLoadHandler(castLoadHandler)
        super.onDestroy()
    }

    private fun consumeIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (CastConnectReceiverBridge.onNewIntent(intent) is CastConnectIntentResult.Handled) return
        consumePlaybackDeepLink(intent)
    }

    private fun consumePlaybackDeepLink(intent: Intent) {
        val uri = intent.takeIf { it.action == Intent.ACTION_VIEW }?.data?.toString() ?: return
        val target =
            TvPlaybackDeepLinkResolver {
                graph.serverRegistry.data.value.servers
            }.resolve(uri)
        if (target == null) {
            AppLog.warning(
                category = "tv.deep_link",
                event = "unresolved",
                message = "Rejected an invalid or stale TV playback route",
            )
            return
        }
        rootComponent.resumePlayback(
            serverId = target.serverId,
            itemId = target.itemId,
            positionMs = target.positionMs,
            startPlaybackRequested = true,
        )
        intent.data = null
    }

    private fun dispatchCastAction(action: CastConnectHostAction) {
        runCatching {
            when (action) {
                is CastConnectHostAction.ResolveLibraryPlayback ->
                    rootComponent.resumePlayback(
                        serverId = action.target.serverId,
                        itemId = action.target.itemId,
                        positionMs = action.target.positionMs,
                        startPlaybackRequested = action.autoplay,
                    )

                is CastConnectHostAction.PlayDirect -> {
                    check(!action.transcodeAllowed)
                    val media =
                        PlayerMediaItem(
                            id = "cast-direct-${UUID.randomUUID()}",
                            url = action.url,
                            transcodeUrl = "",
                            fallbackTranscodeUrl = "",
                            title = action.title ?: "Cast 媒体",
                            playMethod = PlaybackMethod.DirectPlay,
                            serverTranscodeSupported = false,
                        )
                    startActivity(
                        PlayerActivity.intent(
                            context = this,
                            items = listOf(media),
                            startIndex = 0,
                            startPositionMs = action.positionMs,
                            engine = PlayerEngine.Exo,
                            decoder = DecoderMode.Hardware,
                            autoNext = false,
                            startPlaybackRequested = action.autoplay,
                        ),
                    )
                }
            }
        }.onFailure { error ->
            // Never include Cast payloads here: a transient URL can contain expiring credentials.
            AppLog.error(
                category = "cast.connect",
                event = "host_action_failed",
                message = "Cast Connect playback could not be queued",
                throwable = error,
            )
        }
    }
}
