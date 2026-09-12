package com.yfuse.core2.android

import android.content.Context
import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.currentPlaybackNetworkClass
import com.yfuse.core2.adaptive.YAdaptiveBandwidthEstimator
import com.yfuse.core2.adaptive.YAdaptiveEncryptionMethod
import com.yfuse.core2.adaptive.YAdaptiveSelectionConditions
import com.yfuse.core2.adaptive.YAdaptiveVariant
import com.yfuse.core2.adaptive.YAdaptiveVariantSelector
import com.yfuse.core2.adaptive.YDashManifest
import com.yfuse.core2.adaptive.YDashPlaybackCapabilities
import com.yfuse.core2.adaptive.YDashRepresentation
import com.yfuse.core2.adaptive.YDashResourceKind
import com.yfuse.core2.adaptive.YDashSegmentTemplate
import com.yfuse.core2.adaptive.YHlsAlignedSegment
import com.yfuse.core2.adaptive.YHlsPlaybackCapabilities
import com.yfuse.core2.adaptive.YHlsPlaylist
import com.yfuse.core2.adaptive.YHlsResourceKind
import com.yfuse.core2.adaptive.YHlsVariantMediaPlaylist
import com.yfuse.core2.adaptive.alignYDashSwitchingRepresentations
import com.yfuse.core2.adaptive.alignYHlsVariantSegments
import com.yfuse.core2.adaptive.buildYDashPlaybackManifest
import com.yfuse.core2.adaptive.buildYHlsPlaybackMaster
import com.yfuse.core2.adaptive.compatibleYDashReopenRepresentations
import com.yfuse.core2.adaptive.compatibleYHlsReopenVariants
import com.yfuse.core2.adaptive.manifestForPeriod
import com.yfuse.core2.adaptive.parseYDashManifest
import com.yfuse.core2.adaptive.parseYHlsPlaylist
import com.yfuse.core2.adaptive.periodForPositionUs
import com.yfuse.core2.adaptive.renderDashTemplate
import com.yfuse.core2.adaptive.rewriteYHlsResourceUris
import com.yfuse.core2.adaptive.selectYDashPlaybackRepresentations
import com.yfuse.core2.adaptive.selectYHlsPlaybackSet
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class YAdaptivePlaybackFeedback(
    val bufferedDurationUs: Long,
    val playing: Boolean,
    val speed: Float,
    val generation: Long,
)

/**
 * ABR buffer estimate backed by player truth when it is available.
 *
 * The manifest proxy used to drain a synthetic ten-second startup value against wall clock time.
 * That made pause, seek, and non-1x playback select the wrong rendition. Feedback replaces the
 * synthetic value; between reports the model advances only while playback is actually consuming.
 */
internal class YAdaptiveBufferModel(
    initialBufferedDurationUs: Long = STARTUP_BUFFER_US,
) {
    private var bufferedDurationUs = initialBufferedDurationUs.coerceIn(0L, MAX_ABR_BUFFER_US)
    private var updatedAtNs = System.nanoTime()
    private var playing = true
    private var speed = 1f
    private var feedbackGeneration = Long.MIN_VALUE
    private var lastFeedbackRecordedAtNs = Long.MIN_VALUE

    @Synchronized
    fun applyFeedback(
        feedback: YAdaptivePlaybackFeedback,
        recordedAtNs: Long,
    ) {
        if (
            feedback.generation < feedbackGeneration ||
            (feedback.generation == feedbackGeneration && recordedAtNs <= lastFeedbackRecordedAtNs)
        ) {
            return
        }
        feedbackGeneration = feedback.generation
        lastFeedbackRecordedAtNs = recordedAtNs
        bufferedDurationUs = feedback.bufferedDurationUs.coerceIn(0L, MAX_ABR_BUFFER_US)
        playing = feedback.playing
        speed = feedback.speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        updatedAtNs = recordedAtNs
    }

    @Synchronized
    fun estimate(nowNs: Long = System.nanoTime()): Long {
        drain(nowNs)
        return bufferedDurationUs
    }

    @Synchronized
    fun completeSegment(
        durationUs: Long,
        contiguous: Boolean,
        nowNs: Long = System.nanoTime(),
    ) {
        drain(nowNs)
        // Fresh player feedback already includes bytes arriving through this proxy. Adding the
        // segment again would double count it. The wall-clock fallback remains for startup and for
        // callers that cannot provide feedback.
        if (
            lastFeedbackRecordedAtNs != Long.MIN_VALUE &&
            nowNs - lastFeedbackRecordedAtNs <= ABR_FEEDBACK_STALE_NS
        ) {
            return
        }
        bufferedDurationUs =
            if (contiguous) {
                (bufferedDurationUs + durationUs).coerceAtMost(MAX_ABR_BUFFER_US)
            } else {
                durationUs.coerceIn(0L, MAX_ABR_BUFFER_US)
            }
    }

    private fun drain(nowNs: Long) {
        val elapsedUs = (nowNs - updatedAtNs).coerceAtLeast(0L) / NANOS_PER_MICROSECOND
        if (playing && elapsedUs > 0L) {
            val consumedUs = (elapsedUs.toDouble() * speed.toDouble()).toLong()
            bufferedDurationUs = (bufferedDurationUs - consumedUs).coerceAtLeast(0L)
        }
        updatedAtNs = nowNs
    }
}

private data class TimedAdaptivePlaybackFeedback(
    val value: YAdaptivePlaybackFeedback,
    val recordedAtNs: Long,
)

/**
 * Process-local HTTP boundary for native demuxers that only accept URLs.
 *
 * Every upstream request still goes through [YMediaTransport]. HLS child manifests, segments,
 * initialization data, and keys are rewritten back through this boundary. Persistent bytes use
 * YCore's anonymous sparse block cache; live media and encryption keys are never written to disk.
 */
internal class AndroidYCoreHttpProxy(
    context: Context? = null,
    private val userAgent: String,
    private val cacheMaximumBytes: Long,
    private val createTransport: () -> YMediaTransport = {
        AndroidHttpMediaTransport(
            followSafeRedirects = true,
            allowCrossProtocolRedirects = true,
        )
    },
    private val isMeteredNetwork: () -> Boolean = {
        currentPlaybackNetworkClass() == PlaybackNetworkClass.Metered
    },
    private val forwardCacheTargetUs: Long = 60_000_000L,
    cacheDirectory: File? = null,
) : Closeable {
    private data class Route(
        val upstreamUri: String,
        val upstreamHeaders: Map<String, String>,
        val credentials: YTransportCredentials?,
        val cacheable: Boolean,
        val cacheIdentity: YCacheIdentity?,
        val maximumWidth: Int?,
        val maximumHeight: Int?,
        val hlsManifest: Boolean,
        val dashManifest: Boolean,
        val drmProtected: Boolean,
        val allowDolbyVisionHls: Boolean,
        val allowDolbyAtmosHls: Boolean,
        val dashTemplate: DashTemplateRoute? = null,
        val dashAbrResource: DashAbrResourceRoute? = null,
        val hlsAbrResource: HlsAbrResourceRoute? = null,
        val mediaBitRateBitsPerSecond: Long = 0L,
        val playbackTarget: AdaptiveTargetConfiguration? = null,
        val credentialOrigin: String = upstreamUri,
    )

    private class AdaptiveTargetConfiguration(
        val presentation: AdaptivePresentation,
        val revision: Long,
        val variantId: String,
        val hls: HlsPlaybackManifest? = null,
        val dash: YDashManifest? = null,
    )

    private data class DashTemplateRoute(
        val representation: YDashRepresentation,
        val upstreamTemplate: String,
        val usesNumber: Boolean,
        val usesTime: Boolean,
        val localExtension: String,
        val switchingRepresentations: List<YDashRepresentation> = emptyList(),
        val abrSession: DashAbrSession? = null,
    )

    private data class DashAbrResourceRoute(
        val session: DashAbrSession,
        val sequence: Long,
        val durationUs: Long,
        val selectedRepresentationId: String,
    )

    private data class HlsPlaybackManifest(
        val text: String,
        val uri: String,
        val media: YHlsPlaylist.Media,
        val abrSession: HlsAbrSession? = null,
        val alignedSegments: Map<Long, YHlsAlignedSegment> = emptyMap(),
    )

    private data class HlsAbrResourceRoute(
        val session: HlsAbrSession,
        val segment: YHlsAlignedSegment,
        val selectedVariantId: String? = null,
    )

    private inner class AdaptivePresentation(
        val rootUri: String,
        val rootRoute: Route,
        val dash: YDashManifest? = null,
        val hlsMaster: YHlsPlaylist.Master? = null,
        val hlsRootText: String? = null,
        initialHls: Pair<YAdaptiveVariant, HlsPlaybackManifest>? = null,
    ) {
        val gate = YAdaptiveReopenGate()
        private val hlsChoices = linkedMapOf<String, Pair<YAdaptiveVariant, HlsPlaybackManifest>>()
        private var hlsVariantId = initialHls?.first?.id
        private val dashVariantIds = mutableMapOf<String, String>()
        private var currentDashPeriodId: String? = null
        private var currentHlsSession: HlsAbrSession? = null

        init {
            initialHls?.let { hlsChoices[it.first.id] = it }
        }

        @Synchronized
        fun addHlsChoice(
            variant: YAdaptiveVariant,
            playback: HlsPlaybackManifest,
        ) {
            hlsChoices[variant.id] = variant to playback
            currentHlsSession?.addVariant(YHlsVariantMediaPlaylist(variant, playback.media))
        }

        @Synchronized
        fun hasHlsChoice(id: String): Boolean = id in hlsChoices

        @Synchronized
        fun pollTarget(globalPositionMs: Long): YAdaptivePlaybackTarget? {
            val generation = latestPlaybackFeedback()?.value?.generation ?: return null
            val positionUs = globalPositionMs.coerceIn(0L, Long.MAX_VALUE / 1_000L) * 1_000L
            if (dash != null && dash.periodForPositionUs(positionUs).id != currentDashPeriodId) {
                gate.discardPending()
                return null
            }
            val desired = gate.consume(generation) ?: return null
            return target(globalPositionMs, desired)
        }

        @Synchronized
        fun target(
            globalPositionMs: Long,
            forcedVariantId: String? = null,
        ): YAdaptivePlaybackTarget {
            val generation = latestPlaybackFeedback()?.value?.generation ?: 0L
            val revision = gate.beginTarget(generation)
            val requestedUs = globalPositionMs.coerceIn(0L, Long.MAX_VALUE / 1_000L) * 1_000L
            val period = dash?.periodForPositionUs(requestedUs)
            val offsetMs = period?.startUs?.div(1_000L) ?: 0L
            val durationMs: Long
            val configuration: AdaptiveTargetConfiguration
            if (dash != null && period != null) {
                val manifest = dash.manifestForPeriod(period)
                val default =
                    selectYDashPlaybackRepresentations(
                        manifest,
                        rootRoute.startupSelectionConditions(),
                        YDashPlaybackCapabilities(rootRoute.allowDolbyVisionHls, rootRoute.allowDolbyAtmosHls),
                    ).video.id
                val variantId = forcedVariantId ?: dashVariantIds[period.id] ?: default
                require(manifest.representations.any { it.id == variantId }) {
                    "DASH reopen variant is outside its Period"
                }
                dashVariantIds[period.id] = variantId
                currentDashPeriodId = period.id
                configuration = AdaptiveTargetConfiguration(this, revision, variantId, dash = manifest)
                durationMs = dash.mediaPresentationDurationUs?.div(1_000L) ?: 0L
            } else {
                val variantId = forcedVariantId ?: requireNotNull(hlsVariantId)
                val (variant, playback) =
                    requireNotNull(hlsChoices[variantId]) { "HLS reopen variant is not discovered" }
                hlsVariantId = variantId
                val initial = YHlsVariantMediaPlaylist(variant, playback.media)
                val session =
                    HlsAbrSession(variantId, initial, isMeteredNetwork, ::latestPlaybackFeedback) { desired ->
                        latestPlaybackFeedback()?.value?.generation?.let { feedbackGeneration ->
                            gate.propose(revision, feedbackGeneration, desired)
                        }
                    }
                hlsChoices.values.forEach { (candidate, candidatePlayback) ->
                    session.addVariant(YHlsVariantMediaPlaylist(candidate, candidatePlayback.media))
                }
                currentHlsSession = session
                val aligned =
                    alignYHlsVariantSegments(listOf(initial), variantId).associateBy(YHlsAlignedSegment::sequence)
                configuration =
                    AdaptiveTargetConfiguration(
                        this,
                        revision,
                        variantId,
                        hls = playback.copy(abrSession = session, alignedSegments = aligned),
                    )
                durationMs =
                    playback.media.segments
                        .lastOrNull()
                        ?.let { (it.startTimeUs + it.durationUs) / 1_000L } ?: 0L
            }
            val targetRoute =
                rootRoute.copy(
                    playbackTarget = configuration,
                    cacheIdentity =
                        period?.let {
                            rootRoute.cacheIdentity?.forAdaptiveResourceKey("dash-period:${it.id}:${it.startUs}")
                        } ?: rootRoute.cacheIdentity,
                )
            retirePlaybackTargets(this, revision - 1L)
            val targetUri =
                localRouteUrl(targetRoute, if (configuration.dash != null) "/manifest.mpd" else "/playlist.m3u8")
            return YAdaptivePlaybackTarget(
                rootUri = rootUri,
                uri = targetUri,
                localPositionMs = (globalPositionMs - offsetMs).coerceAtLeast(0L),
                presentationOffsetMs = offsetMs,
                presentationDurationMs = durationMs,
                revision = revision,
                periodEndGlobalMs = period?.endUs?.div(1_000L),
                feedbackGeneration = generation,
            )
        }
    }

    private class HlsAbrSession(
        initialVariantId: String,
        private val initialPlaylist: YHlsVariantMediaPlaylist,
        private val isMeteredNetwork: () -> Boolean,
        private val latestFeedback: () -> TimedAdaptivePlaybackFeedback?,
        private val requestReopen: ((String) -> Unit)? = null,
    ) {
        private val bandwidthEstimator = YAdaptiveBandwidthEstimator()
        private var currentVariantId = initialVariantId
        private val bufferModel = YAdaptiveBufferModel()
        private var lastCompletedSequence: Long? = null
        private val discoveredVariants = linkedMapOf(initialVariantId to initialPlaylist)
        private var alignedSegments = emptyMap<Long, YHlsAlignedSegment>()
        private var reopenVariants = listOf(initialPlaylist.variant)

        @Synchronized
        fun addVariant(candidate: YHlsVariantMediaPlaylist) {
            discoveredVariants[candidate.variant.id] = candidate
            alignedSegments =
                alignYHlsVariantSegments(discoveredVariants.values.toList(), initialPlaylist.variant.id)
                    .associateBy(YHlsAlignedSegment::sequence)
            reopenVariants =
                compatibleYHlsReopenVariants(initialPlaylist, discoveredVariants.values.toList()).map { it.variant }
        }

        @Synchronized
        fun select(segment: YHlsAlignedSegment): com.yfuse.core2.adaptive.YHlsAlignedSegmentResource {
            reconsiderReopen(segment)
            val resources = alignedSegments[segment.sequence]?.resources ?: segment.resources
            val currentForSegment =
                currentVariantId.takeIf { current -> resources.any { it.variant.id == current } }
                    ?: resources
                        .minBy { it.variant.selectionBandwidthBitsPerSecond }
                        .variant.id
            val selected =
                YAdaptiveVariantSelector.select(
                    variants = resources.map { it.variant },
                    conditions = selectionConditions(),
                    currentVariantId = currentForSegment,
                )
            currentVariantId = selected.id
            return resources.first { it.variant.id == selected.id }
        }

        @Synchronized
        fun reconsiderReopen(segment: YHlsAlignedSegment) {
            applyLatestFeedback()
            val resources = alignedSegments[segment.sequence]?.resources ?: segment.resources
            requestReopen?.let { request ->
                val ideal =
                    YAdaptiveVariantSelector.select(
                        variants = reopenVariants,
                        conditions = selectionConditions(),
                        currentVariantId = currentVariantId,
                    )
                if (resources.none { it.variant.id == ideal.id }) request(ideal.id)
            }
        }

        private fun selectionConditions() =
            YAdaptiveSelectionConditions(
                estimatedBandwidthBitsPerSecond =
                    bandwidthEstimator.estimateBitsPerSecond.takeIf { it > 0L } ?: INITIAL_BANDWIDTH_BITS_PER_SECOND,
                bufferedDurationUs = bufferModel.estimate(),
                metered = isMeteredNetwork(),
            )

        @Synchronized
        fun recordNetworkSample(
            bytes: Long,
            durationMs: Long,
        ) {
            bandwidthEstimator.addSample(bytes, durationMs)
        }

        @Synchronized
        fun complete(
            sequence: Long,
            durationUs: Long,
        ) {
            applyLatestFeedback()
            if (lastCompletedSequence == sequence) return
            bufferModel.completeSegment(
                durationUs = durationUs,
                contiguous = lastCompletedSequence == null || sequence == lastCompletedSequence?.plus(1L),
            )
            lastCompletedSequence = sequence
        }

        private fun applyLatestFeedback() {
            latestFeedback()?.let { feedback ->
                bufferModel.applyFeedback(feedback.value, feedback.recordedAtNs)
            }
        }
    }

    private class DashAbrSession(
        initialRepresentationId: String,
        private val isMeteredNetwork: () -> Boolean,
        private val latestFeedback: () -> TimedAdaptivePlaybackFeedback?,
        private val reopenCandidates: List<YDashRepresentation> = emptyList(),
        private val requestReopen: ((String) -> Unit)? = null,
    ) {
        private val bandwidthEstimator = YAdaptiveBandwidthEstimator()
        private var currentRepresentationId = initialRepresentationId
        private val bufferModel = YAdaptiveBufferModel()
        private var lastCompletedSequence: Long? = null

        @Synchronized
        fun select(representations: List<YDashRepresentation>): YDashRepresentation {
            require(representations.isNotEmpty())
            reconsiderReopen(representations)
            val selected =
                YAdaptiveVariantSelector.select(
                    variants = representations.map(YDashRepresentation::asAdaptiveVariant),
                    conditions = selectionConditions(),
                    currentVariantId = currentRepresentationId,
                )
            currentRepresentationId = selected.id
            return representations.first { it.id == selected.id }
        }

        @Synchronized
        fun reconsiderReopen(representations: List<YDashRepresentation>) {
            applyLatestFeedback()
            val variants = representations.map(YDashRepresentation::asAdaptiveVariant)
            if (reopenCandidates.isNotEmpty() && requestReopen != null) {
                val ideal =
                    YAdaptiveVariantSelector.select(
                        reopenCandidates.map(YDashRepresentation::asAdaptiveVariant),
                        selectionConditions(),
                        currentRepresentationId,
                    )
                if (variants.none { it.id == ideal.id }) requestReopen.invoke(ideal.id)
            }
        }

        private fun selectionConditions() =
            YAdaptiveSelectionConditions(
                estimatedBandwidthBitsPerSecond =
                    bandwidthEstimator.estimateBitsPerSecond.takeIf { it > 0L } ?: INITIAL_BANDWIDTH_BITS_PER_SECOND,
                bufferedDurationUs = bufferModel.estimate(),
                metered = isMeteredNetwork(),
            )

        @Synchronized
        fun recordNetworkSample(
            bytes: Long,
            durationMs: Long,
        ) {
            bandwidthEstimator.addSample(bytes, durationMs)
        }

        @Synchronized
        fun complete(
            sequence: Long,
            durationUs: Long,
        ) {
            applyLatestFeedback()
            if (lastCompletedSequence == sequence) return
            bufferModel.completeSegment(
                durationUs = durationUs,
                contiguous = lastCompletedSequence == null || sequence == lastCompletedSequence?.plus(1L),
            )
            lastCompletedSequence = sequence
        }

        private fun applyLatestFeedback() {
            latestFeedback()?.let { feedback ->
                bufferModel.applyFeedback(feedback.value, feedback.recordedAtNs)
            }
        }
    }

    private val cacheDirectory = cacheDirectory ?: requireNotNull(context).applicationContext.cacheDir
    private val routesLock = Any()
    private val routes = LinkedHashMap<String, Route>()
    private val routeIds = HashMap<Route, String>()
    private val resolvedResources = LinkedHashMap<String, Route>(16, 0.75f, true)
    private val responsesStarted = ConcurrentHashMap.newKeySet<Socket>()
    private val closed = AtomicBoolean(false)
    private val requests = YCoreProxyRequests()
    private val activeRangeSources = ConcurrentHashMap.newKeySet<AndroidTransportMediaDataSource>()
    private val manifestDiscovery = AndroidAdaptiveManifestDiscovery()
    private val presentations = ConcurrentHashMap<String, AdaptivePresentation>()
    private val unmanagedManifestRoots = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var adaptivePlaybackFeedback: TimedAdaptivePlaybackFeedback? = null
    private val workers: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "YCore-HttpProxy-worker").apply { isDaemon = true }
        }
    private val server = ServerSocket(0, LOOPBACK_BACKLOG, InetAddress.getByName(LOOPBACK_HOST))
    private val acceptThread =
        Thread(::acceptLoop, "YCore-HttpProxy-accept").apply {
            isDaemon = true
            start()
        }

    fun localUrl(
        upstreamUri: String,
        upstreamHeaders: Map<String, String> = emptyMap(),
        credentials: YTransportCredentials? = null,
        cacheable: Boolean,
        cacheIdentity: YCacheIdentity?,
        maximumWidth: Int? = null,
        maximumHeight: Int? = null,
        hlsManifest: Boolean = upstreamUri.isHlsManifestUri(),
        dashManifest: Boolean = upstreamUri.isDashManifestUri(),
        drmProtected: Boolean = false,
        allowDolbyVisionHls: Boolean = false,
        allowDolbyAtmosHls: Boolean = false,
        mediaBitRateBitsPerSecond: Long = 0L,
        credentialOrigin: String = upstreamUri,
    ): String {
        if (closed.get() || upstreamUri.sourceProtocolOrNull() == null) return upstreamUri
        val route =
            Route(
                upstreamUri = upstreamUri,
                upstreamHeaders = upstreamHeaders,
                credentialOrigin = credentialOrigin,
                credentials = credentials,
                cacheable = cacheable,
                cacheIdentity = cacheIdentity,
                maximumWidth = maximumWidth,
                maximumHeight = maximumHeight,
                hlsManifest = hlsManifest,
                dashManifest = dashManifest,
                drmProtected = drmProtected,
                allowDolbyVisionHls = allowDolbyVisionHls,
                allowDolbyAtmosHls = allowDolbyAtmosHls,
                mediaBitRateBitsPerSecond = mediaBitRateBitsPerSecond,
            )
        val syntheticPath =
            when {
                hlsManifest -> "/playlist.m3u8"
                dashManifest -> "/manifest.mpd"
                else -> upstreamUri.safeSyntheticExtension()?.let { "/resource.$it" }.orEmpty()
            }
        return localRouteUrl(route, syntheticPath)
    }

    fun updatePlaybackFeedback(feedback: YAdaptivePlaybackFeedback) {
        if (closed.get()) return
        adaptivePlaybackFeedback =
            TimedAdaptivePlaybackFeedback(
                value = feedback,
                recordedAtNs = System.nanoTime(),
            )
        activeRangeSources.forEach(::updateRangePlaybackWindow)
    }

    /** Resolves a complete source before the player probes/configures it; never splices new init into old samples. */
    suspend fun resolvePlaybackTarget(
        rootUri: String,
        globalPositionMs: Long,
    ): YAdaptivePlaybackTarget? =
        withContext(Dispatchers.IO) {
            if (closed.get() || rootUri in unmanagedManifestRoots) return@withContext null
            val route = routeForLocalUri(rootUri) ?: return@withContext null
            val existing = route.playbackTarget?.presentation ?: presentations[rootUri]
            if (existing != null) return@withContext existing.target(globalPositionMs)
            if (!route.hlsManifest && !route.dashManifest) return@withContext null
            val maximumBytes = if (route.dashManifest) MAX_DASH_MANIFEST_BYTES else MAX_HLS_MANIFEST_BYTES
            val text = loadBoundedCancellable(route.upstreamUri, maximumBytes, route).decodeToString()
            val created =
                if (route.dashManifest) {
                    val manifest = parseYDashManifest(text, route.upstreamUri)
                    // Live Period insertion/removal needs a moving-window controller, separate from VOD concatenation.
                    if (manifest.isLive) {
                        unmanagedManifestRoots.add(rootUri)
                        return@withContext null
                    }
                    require(manifest.periods.all { it.durationUs != null }) { "Static DASH Period duration is unknown" }
                    AdaptivePresentation(rootUri, route, dash = manifest)
                } else {
                    require(route.drmProtected || !text.hasHlsSessionKey()) {
                        "HLS session keys require the native DRM route"
                    }
                    val master = parseYHlsPlaylist(text, route.upstreamUri) as? YHlsPlaylist.Master
                    if (master == null || text.hasSeparateYCoreHlsRenditions()) {
                        unmanagedManifestRoots.add(rootUri)
                        return@withContext null
                    }
                    val playbackSet =
                        selectYHlsPlaybackSet(
                            master,
                            route.startupSelectionConditions(),
                            YHlsPlaybackCapabilities(route.allowDolbyVisionHls, route.allowDolbyAtmosHls),
                        )
                    val selected = playbackSet.initialVariant
                    val selectedText =
                        loadBoundedCancellable(selected.uri, MAX_HLS_MANIFEST_BYTES, route)
                            .decodeToString()
                            .withAuthoredHlsSessionKeysFrom(text, route.upstreamUri)
                    val selectedMedia = selectedText.requireExecutableHlsMedia(selected.uri, route.drmProtected)
                    if (selectedMedia.isLive || selectedText.hasLowLatencyHlsParts()) {
                        unmanagedManifestRoots.add(rootUri)
                        return@withContext null
                    }
                    AdaptivePresentation(
                        rootUri,
                        route,
                        hlsMaster = master.copy(variants = playbackSet.variants),
                        hlsRootText = text,
                        initialHls = selected to HlsPlaybackManifest(selectedText, selected.uri, selectedMedia),
                    )
                }
            currentCoroutineContext().ensureActive()
            check(!closed.get()) { "Adaptive presentation was closed" }
            val presentation = presentations.putIfAbsent(rootUri, created) ?: created
            val target = presentation.target(globalPositionMs)
            if (presentation === created) discoverPresentationHlsVariants(presentation)
            target
        }

    /** Single-use and generation-bound; caller rebuilds at the returned global/local timeline coordinates. */
    fun pollPlaybackTransition(
        rootUri: String,
        globalPositionMs: Long,
    ): YAdaptivePlaybackTarget? {
        if (closed.get()) return null
        val presentation =
            presentations[rootUri] ?: routeForLocalUri(rootUri)?.playbackTarget?.presentation ?: return null
        return presentation.pollTarget(globalPositionMs)
    }

    private fun routeForLocalUri(uri: String): Route? {
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
        if (parsed.host != LOOPBACK_HOST || parsed.port != server.localPort) return null
        val prefix = "/$ROUTE_PREFIX/"
        val path = parsed.path ?: return null
        if (!path.startsWith(prefix)) return null
        return findRoute(path.removePrefix(prefix).substringBefore('/'))
    }

    /** Keep the current and immediately preceding target while the actor closes the old decoder. */
    private fun retirePlaybackTargets(
        presentation: AdaptivePresentation,
        keepFromRevision: Long,
    ) {
        synchronized(routesLock) {
            val iterator = routes.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val target = entry.value.playbackTarget
                if (target != null && target.presentation === presentation && target.revision < keepFromRevision) {
                    routeIds.remove(entry.value)
                    resolvedResources.keys.removeAll { it.startsWith("${entry.key}/") }
                    iterator.remove()
                }
            }
        }
    }

    private fun Route.startupSelectionConditions() =
        YAdaptiveSelectionConditions(
            estimatedBandwidthBitsPerSecond = INITIAL_BANDWIDTH_BITS_PER_SECOND,
            bufferedDurationUs = STARTUP_BUFFER_US,
            maximumWidth = maximumWidth,
            maximumHeight = maximumHeight,
            metered = isMeteredNetwork(),
        )

    private fun discoverPresentationHlsVariants(presentation: AdaptivePresentation) {
        val master = presentation.hlsMaster ?: return
        val route = presentation.rootRoute
        master.variants
            .filter { it.fits(route.maximumWidth, route.maximumHeight) && !presentation.hasHlsChoice(it.id) }
            .sortedBy(YAdaptiveVariant::selectionBandwidthBitsPerSecond)
            .take(MAX_HLS_ABR_VARIANTS - 1)
            .forEach { variant ->
                manifestDiscovery.submit(presentation to variant.id) { budget ->
                    val text =
                        loadBounded(variant.uri, MAX_HLS_MANIFEST_BYTES, route, budget)
                            .decodeToString()
                            .withAuthoredHlsSessionKeysFrom(presentation.hlsRootText.orEmpty(), route.upstreamUri)
                    val media = text.requireExecutableHlsMedia(variant.uri, route.drmProtected)
                    if (!media.isLive && !text.hasLowLatencyHlsParts()) {
                        budget.publishIfActive {
                            presentation.addHlsChoice(variant, HlsPlaybackManifest(text, variant.uri, media))
                        }
                    }
                }
            }
    }

    private fun updateRangePlaybackWindow(source: AndroidTransportMediaDataSource) {
        val feedback = adaptivePlaybackFeedback?.value ?: return
        source.updatePlaybackWindow(
            YTransportPlaybackWindow(
                targetAheadUs = (forwardCacheTargetUs * feedback.speed.toDouble()).toLong(),
                speed = feedback.speed,
                bufferedUs = feedback.bufferedDurationUs,
                minimumWarmBufferUs = 2_000_000L,
                playing = feedback.playing,
            ),
        )
    }

    private fun latestPlaybackFeedback(): TimedAdaptivePlaybackFeedback? = adaptivePlaybackFeedback

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Stop admission and close client sockets before cancelling upstream I/O. No worker wait
        // belongs here: range sources retain their cache/memory leases until their finally runs.
        runCatching { server.close() }
        activeRangeSources.forEach(AndroidTransportMediaDataSource::cancelReads)
        requests.close()
        manifestDiscovery.close()
        presentations.clear()
        unmanagedManifestRoots.clear()
        workers.shutdownNow()
        synchronized(routesLock) {
            resolvedResources.clear()
            routes.clear()
            routeIds.clear()
        }
    }

    private fun trackedTransport() = YCoreProxyTransport(createTransport(), requests)

    private fun registerRoute(route: Route): String {
        while (routes.size >= MAX_ROUTES) {
            val oldest =
                routes.entries.firstOrNull {
                    !it.value.hlsManifest &&
                        !it.value.dashManifest &&
                        it.value.playbackTarget == null
                }
                    ?: routes.entries.first()
            routes.remove(oldest.key)
            routeIds.remove(oldest.value)
            val prefix = "${oldest.key}/"
            resolvedResources.keys.removeAll { it.startsWith(prefix) }
        }
        val routeId = UUID.randomUUID().toString().replace("-", "")
        routes[routeId] = route
        routeIds[route] = routeId
        return routeId
    }

    private fun localRouteUrl(
        route: Route,
        pathSuffix: String,
    ): String {
        val routeId =
            synchronized(routesLock) {
                check(!closed.get()) { "Playback proxy is closed" }
                routeIds[route] ?: registerRoute(route)
            }
        return "http://$LOOPBACK_HOST:${server.localPort}/$ROUTE_PREFIX/$routeId$pathSuffix"
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket =
                try {
                    server.accept()
                } catch (error: Exception) {
                    if (!closed.get()) {
                        AppLog.error(
                            "player.proxy",
                            "accept_failed",
                            "Playback proxy stopped accepting connections unexpectedly",
                            throwable = error,
                        )
                    }
                    break
                }
            val registration = requests.register { runCatching { socket.close() } }
            if (registration == null) continue
            runCatching {
                workers.execute {
                    try {
                        socket.use { if (!closed.get()) serve(it) }
                    } catch (_: Exception) {
                        // Closing a client can interrupt header reads before serve installs its response handling.
                    } finally {
                        responsesStarted.remove(socket)
                        registration.close()
                    }
                }
            }.onFailure {
                registration.close()
                runCatching { socket.close() }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = CLIENT_SOCKET_TIMEOUT_MS
        val reader =
            BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1),
            )
        val requestLine = readProxyLine(reader, MAX_REQUEST_LINE_BYTES).orEmpty()
        val requestParts = requestLine.split(' ', limit = 3)
        val method = requestParts.getOrNull(0)?.uppercase().orEmpty()
        val requestTarget = requestParts.getOrNull(1).orEmpty()
        val localQuery = requestTarget.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
        val localPath =
            requestTarget
                .substringBefore('?')
                ?.substringAfter("/$ROUTE_PREFIX/", missingDelimiterValue = "")
                ?.takeIf(String::isNotEmpty)
        val routeId = localPath?.substringBefore('/')
        val routeSuffix = localPath?.substringAfter('/', missingDelimiterValue = "").orEmpty()
        val resolvedRoute =
            routeId?.let { id ->
                synchronized(routesLock) {
                    val base = findRoute(id) ?: return@synchronized null
                    if (base.hlsManifest || base.dashManifest) {
                        base
                    } else {
                        val key = "$id/$routeSuffix"
                        resolvedResources[key]?.also {
                            // A retry keeps its original bytes, but fresh bandwidth/buffer feedback
                            // must still be allowed to schedule a new decoder target.
                            base.hlsAbrResource?.let { abr -> abr.session.reconsiderReopen(abr.segment) }
                            base.dashTemplate?.let { template ->
                                template.abrSession?.reconsiderReopen(template.switchingRepresentations)
                            }
                        }
                            ?: base.resolveDashTemplate(routeSuffix)?.resolveHlsAbr()?.also { resolved ->
                                resolvedResources[key] = resolved
                                while (resolvedResources.size > MAX_ROUTES) {
                                    resolvedResources.remove(resolvedResources.keys.first())
                                }
                            }
                    }
                }
            }
        val route =
            resolvedRoute?.let { candidate ->
                if (candidate.hlsManifest) {
                    candidate.copy(upstreamUri = mergeYCoreHlsReloadQuery(candidate.upstreamUri, localQuery))
                } else {
                    candidate
                }
            }
        if (method !in ALLOWED_METHODS || route == null) {
            writeEmptyResponse(socket, 404, "Not Found")
            return
        }
        val headers = readRequestHeaders(reader)
        runCatching {
            if (route.hlsManifest) {
                serveHls(socket, route, method)
            } else if (route.dashManifest) {
                serveDash(socket, route, method)
            } else {
                serveBinary(socket, route, method, headers["range"])
            }
        }.onFailure {
            if (socket !in responsesStarted) runCatching { writeEmptyResponse(socket, 502, "Bad Gateway") }
        }
    }

    private fun findRoute(routeId: String): Route? =
        synchronized(routesLock) {
            routes.remove(routeId)?.also { route -> routes[routeId] = route }
        }

    private fun Route.resolveDashTemplate(pathSuffix: String): Route? {
        val templateRoute = dashTemplate ?: return this
        val coordinatePattern =
            when {
                templateRoute.usesNumber && templateRoute.usesTime -> "(\\d+)-(\\d+)"
                else -> "(\\d+)"
            }
        val match =
            Regex("^dash-$coordinatePattern\\.${Regex.escape(templateRoute.localExtension)}$")
                .matchEntire(pathSuffix)
                ?: return null
        val first = match.groupValues[1].toLongOrNull() ?: return null
        val number =
            if (templateRoute.usesNumber) {
                first
            } else {
                templateRoute.representation.segmentTemplate?.startNumber ?: 1L
            }
        val time =
            when {
                templateRoute.usesNumber && templateRoute.usesTime -> match.groupValues[2].toLongOrNull()
                templateRoute.usesTime -> first
                else -> null
            }
        val activeRepresentation =
            templateRoute.abrSession
                ?.select(templateRoute.switchingRepresentations)
                ?: templateRoute.representation
        val activeTemplate = requireNotNull(activeRepresentation.segmentTemplate)
        val resolvedUri =
            renderDashTemplate(
                template = activeTemplate.media,
                representation = activeRepresentation,
                number = number,
                time = time,
            )
        val dashAbrResource =
            templateRoute.abrSession?.let { session ->
                DashAbrResourceRoute(
                    session = session,
                    sequence = number,
                    durationUs = activeTemplate.segmentDurationUs(number, time),
                    selectedRepresentationId = activeRepresentation.id,
                )
            }
        return copy(
            upstreamUri = resolvedUri,
            cacheIdentity =
                cacheIdentity?.forAdaptiveResourceKey(
                    "dash-segment:${activeRepresentation.id}:$number:${time ?: "none"}",
                ),
            dashTemplate = null,
            dashAbrResource = dashAbrResource,
        )
    }

    private fun Route.resolveHlsAbr(): Route {
        val abr = hlsAbrResource ?: return this
        val selected = abr.session.select(abr.segment)
        return copy(
            upstreamUri = selected.uri,
            cacheIdentity =
                cacheIdentity?.forAdaptiveResourceKey(
                    "hls-segment:${selected.variant.id}:${abr.segment.sequence}",
                ),
            hlsAbrResource = abr.copy(selectedVariantId = selected.variant.id),
        )
    }

    private fun readRequestHeaders(reader: BufferedReader): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        repeat(MAX_REQUEST_HEADER_COUNT) {
            val line = readProxyLine(reader, MAX_REQUEST_HEADER_BYTES) ?: return headers
            if (line.isEmpty()) return headers
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim().take(MAX_REQUEST_HEADER_BYTES)
            }
        }
        return headers
    }

    private fun serveHls(
        socket: Socket,
        route: Route,
        method: String,
    ) {
        val pinned = route.playbackTarget?.hls
        val rootText = pinned?.text ?: loadBounded(route.upstreamUri, MAX_HLS_MANIFEST_BYTES, route).decodeToString()
        require(route.drmProtected || !rootText.hasHlsSessionKey()) {
            "HLS session keys require the native DRM route"
        }
        val root = pinned?.media ?: parseYHlsPlaylist(rootText, route.upstreamUri)
        if (root is YHlsPlaylist.Master && rootText.hasSeparateYCoreHlsRenditions()) {
            val conditions =
                YAdaptiveSelectionConditions(
                    estimatedBandwidthBitsPerSecond = INITIAL_BANDWIDTH_BITS_PER_SECOND,
                    bufferedDurationUs = STARTUP_BUFFER_US,
                    maximumWidth = route.maximumWidth,
                    maximumHeight = route.maximumHeight,
                    metered = isMeteredNetwork(),
                )
            val playback =
                selectYHlsPlaybackSet(
                    master = root,
                    conditions = conditions,
                    capabilities =
                        YHlsPlaybackCapabilities(
                            dolbyVisionOutput = route.allowDolbyVisionHls,
                            dolbyAtmosOutput = route.allowDolbyAtmosHls,
                        ),
                )
            val selectedMaster =
                buildYHlsPlaybackMaster(playback) { upstreamUri, _ ->
                    localUrl(
                        upstreamUri = upstreamUri,
                        upstreamHeaders = route.upstreamHeaders,
                        credentialOrigin = route.credentialOrigin,
                        credentials = route.credentials,
                        cacheable = false,
                        cacheIdentity = route.cacheIdentity?.forStableAdaptiveUri(upstreamUri),
                        maximumWidth = route.maximumWidth,
                        maximumHeight = route.maximumHeight,
                        hlsManifest = true,
                        dashManifest = false,
                        drmProtected = route.drmProtected,
                        allowDolbyVisionHls = route.allowDolbyVisionHls,
                        allowDolbyAtmosHls = route.allowDolbyAtmosHls,
                    )
                }
            val localizedMaster =
                selectedMaster
                    .withLocalizedHlsSessionKeysFrom(rootText, route)
                    .encodeToByteArray()
            writeHeaders(
                socket = socket,
                status = 200,
                reason = "OK",
                contentType = HLS_CONTENT_TYPE,
                contentLength = localizedMaster.size.toLong(),
            )
            if (method == "GET") socket.getOutputStream().write(localizedMaster)
            socket.getOutputStream().flush()
            return
        }
        val playback =
            when (root) {
                is YHlsPlaylist.Media -> pinned ?: HlsPlaybackManifest(rootText, route.upstreamUri, root)
                is YHlsPlaylist.Master -> loadHlsPlaybackManifest(root, route)
            }
        playback.media.requireSupportedEncryption(route.drmProtected)
        var segmentIndex = 0
        val rewritten =
            rewriteYHlsResourceUris(playback.text, playback.uri) { upstreamUri, kind ->
                val persistent =
                    route.cacheable &&
                        !playback.media.isLive &&
                        kind in setOf(YHlsResourceKind.MediaSegment, YHlsResourceKind.InitializationSegment)
                val mediaSegment =
                    if (kind == YHlsResourceKind.MediaSegment) {
                        playback.media.segments
                            .getOrNull(segmentIndex)
                            ?.takeIf { it.uri == upstreamUri }
                    } else {
                        null
                    }
                if (mediaSegment != null) segmentIndex++
                val aligned = mediaSegment?.let { playback.alignedSegments[it.sequence] }
                if (aligned != null && playback.abrSession != null) {
                    localHlsAbrUrl(route, persistent, aligned, playback.abrSession)
                } else {
                    localUrl(
                        upstreamUri = upstreamUri,
                        upstreamHeaders = route.upstreamHeaders,
                        credentialOrigin = route.credentialOrigin,
                        credentials = route.credentials,
                        cacheable = persistent,
                        cacheIdentity =
                            when {
                                mediaSegment != null ->
                                    route.cacheIdentity?.forAdaptiveResourceKey(
                                        "hls-segment:${mediaSegment.sequence}:${stableAdaptiveResourceKey(
                                            upstreamUri,
                                        )}",
                                    )
                                kind == YHlsResourceKind.InitializationSegment ->
                                    route.cacheIdentity?.forAdaptiveResourceKey(
                                        "hls-init:${stableAdaptiveResourceKey(upstreamUri)}",
                                    )
                                else -> route.cacheIdentity?.forStableAdaptiveUri(upstreamUri)
                            },
                        maximumWidth = route.maximumWidth,
                        maximumHeight = route.maximumHeight,
                        hlsManifest =
                            kind in
                                setOf(YHlsResourceKind.VariantPlaylist, YHlsResourceKind.RenditionPlaylist),
                        drmProtected = route.drmProtected,
                    )
                }
            }.encodeToByteArray()
        writeHeaders(
            socket = socket,
            status = 200,
            reason = "OK",
            contentType = HLS_CONTENT_TYPE,
            contentLength = rewritten.size.toLong(),
        )
        if (method == "GET") socket.getOutputStream().write(rewritten)
        socket.getOutputStream().flush()
    }

    private fun loadHlsPlaybackManifest(
        root: YHlsPlaylist.Master,
        route: Route,
    ): HlsPlaybackManifest {
        val playbackSet =
            selectYHlsPlaybackSet(
                root,
                route.startupSelectionConditions(),
                YHlsPlaybackCapabilities(route.allowDolbyVisionHls, route.allowDolbyAtmosHls),
            )
        val selected = playbackSet.initialVariant
        val selectedText = loadBounded(selected.uri, MAX_HLS_MANIFEST_BYTES, route).decodeToString()
        val selectedMedia = selectedText.requireExecutableHlsMedia(selected.uri, route.drmProtected)
        if (selectedMedia.isLive || selectedText.hasLowLatencyHlsParts()) {
            return HlsPlaybackManifest(selectedText, selected.uri, selectedMedia)
        }
        val eligibleAlternates =
            playbackSet.variants
                .filter { it.id != selected.id && it.fits(route.maximumWidth, route.maximumHeight) }
                .sortedBy(YAdaptiveVariant::selectionBandwidthBitsPerSecond)
                .take(MAX_HLS_ABR_VARIANTS - 1)
        val initialPlaylist = YHlsVariantMediaPlaylist(selected, selectedMedia)
        val aligned = alignYHlsVariantSegments(listOf(initialPlaylist), selected.id)
        val session =
            HlsAbrSession(selected.id, initialPlaylist, isMeteredNetwork, ::latestPlaybackFeedback)
        eligibleAlternates.forEach { variant ->
            manifestDiscovery.submit(session to variant.id) { budget ->
                val text = loadBounded(variant.uri, MAX_HLS_MANIFEST_BYTES, route, budget).decodeToString()
                if (!text.hasLowLatencyHlsParts()) {
                    val candidate =
                        YHlsVariantMediaPlaylist(
                            variant,
                            text.requireExecutableHlsMedia(variant.uri, route.drmProtected),
                        )
                    budget.publishIfActive { session.addVariant(candidate) }
                }
            }
        }
        return HlsPlaybackManifest(
            text = selectedText,
            uri = selected.uri,
            media = selectedMedia,
            abrSession = session,
            alignedSegments = aligned.associateBy(YHlsAlignedSegment::sequence),
        )
    }

    private fun String.requireExecutableHlsMedia(
        uri: String,
        drmProtected: Boolean,
    ): YHlsPlaylist.Media {
        require(drmProtected || !hasHlsSessionKey()) { "HLS session keys require the native DRM route" }
        val media = parseYHlsPlaylist(this, uri) as? YHlsPlaylist.Media
        requireNotNull(media) { "Nested HLS master playlists are not executable" }
        media.requireSupportedEncryption(drmProtected)
        return media
    }

    private fun YHlsPlaylist.Media.requireSupportedEncryption(drmProtected: Boolean) {
        val encryption = segments.mapNotNull { it.encryption }.distinct()
        require(encryption.none { it.method == YAdaptiveEncryptionMethod.Other }) {
            "HLS encryption method is unsupported by YCore"
        }
        val sampleEncryption = encryption.filter { it.method == YAdaptiveEncryptionMethod.SampleAes }
        require(drmProtected || sampleEncryption.isEmpty()) {
            "HLS sample encryption requires the native DRM route"
        }
        require(
            sampleEncryption.all { protection ->
                protection.keyFormat.orEmpty().let { keyFormat ->
                    keyFormat.contains(WIDEVINE_SYSTEM_ID, ignoreCase = true) ||
                        keyFormat.contains("widevine", ignoreCase = true)
                }
            },
        ) { "YCore currently supports Widevine HLS sample encryption only" }
    }

    private fun String.withLocalizedHlsSessionKeysFrom(
        authoredMaster: String,
        route: Route,
    ): String {
        val sessionKeys =
            authoredMaster
                .lineSequence()
                .map(String::trim)
                .filter { it.startsWith("#EXT-X-SESSION-KEY:", ignoreCase = true) }
                .toList()
        if (sessionKeys.isEmpty()) return this
        val localized =
            rewriteYHlsResourceUris(
                text = (listOf("#EXTM3U") + sessionKeys).joinToString("\n"),
                baseUri = route.upstreamUri,
            ) { upstreamUri, _ ->
                localUrl(
                    upstreamUri = upstreamUri,
                    upstreamHeaders = route.upstreamHeaders,
                    credentialOrigin = route.credentialOrigin,
                    credentials = route.credentials,
                    cacheable = false,
                    cacheIdentity = null,
                    drmProtected = true,
                )
            }.lineSequence()
                .drop(1)
                .joinToString("\n")
        val lines = lineSequence().toList()
        return buildString {
            appendLine(lines.firstOrNull() ?: "#EXTM3U")
            appendLine(localized)
            append(lines.drop(1).joinToString("\n"))
        }.trimEnd()
    }

    private fun String.withAuthoredHlsSessionKeysFrom(
        master: String,
        masterUri: String,
    ): String {
        val keys =
            master.lineSequence().filter { it.trim().startsWith("#EXT-X-SESSION-KEY:", ignoreCase = true) }.toList()
        if (keys.isEmpty()) return this
        val absoluteKeys =
            rewriteYHlsResourceUris((listOf("#EXTM3U") + keys).joinToString("\n"), masterUri) { uri, _ -> uri }
                .lineSequence()
                .drop(1)
                .joinToString("\n")
        return lineSequence().first() + "\n" + absoluteKeys + "\n" + lineSequence().drop(1).joinToString("\n")
    }

    private fun localHlsAbrUrl(
        parent: Route,
        cacheable: Boolean,
        segment: YHlsAlignedSegment,
        session: HlsAbrSession,
    ): String {
        val selected = segment.resources.first()
        val route =
            parent.copy(
                upstreamUri = selected.uri,
                cacheable = cacheable,
                cacheIdentity = parent.cacheIdentity,
                hlsManifest = false,
                dashManifest = false,
                hlsAbrResource = HlsAbrResourceRoute(session, segment),
            )
        val suffix =
            selected.uri
                .safeSyntheticExtension()
                ?.let { "/resource.$it" }
                .orEmpty()
        return localRouteUrl(route, suffix)
    }

    private fun serveDash(
        socket: Socket,
        route: Route,
        method: String,
    ) {
        val pinned = route.playbackTarget
        val manifest =
            pinned?.dash ?: run {
                val sourceXml = loadBounded(route.upstreamUri, MAX_DASH_MANIFEST_BYTES, route).decodeToString()
                require(DASH_PERIOD_TAG.findAll(sourceXml).count() == 1) {
                    "Multi-period DASH must be opened through the presentation controller"
                }
                parseYDashManifest(sourceXml, route.upstreamUri)
            }
        val initialSelection =
            selectYDashPlaybackRepresentations(
                manifest = manifest,
                conditions =
                    YAdaptiveSelectionConditions(
                        estimatedBandwidthBitsPerSecond = INITIAL_BANDWIDTH_BITS_PER_SECOND,
                        bufferedDurationUs = STARTUP_BUFFER_US,
                        maximumWidth = route.maximumWidth,
                        maximumHeight = route.maximumHeight,
                        metered = isMeteredNetwork(),
                    ),
                capabilities =
                    YDashPlaybackCapabilities(
                        dolbyVisionOutput = route.allowDolbyVisionHls,
                        dolbyAtmosOutput = route.allowDolbyAtmosHls,
                    ),
            )
        val selection =
            pinned?.variantId?.let { selectedId ->
                initialSelection.copy(video = manifest.representations.single { it.id == selectedId })
            } ?: initialSelection
        val switchingRepresentations =
            alignYDashSwitchingRepresentations(
                manifest =
                    manifest.copy(
                        representations =
                            manifest.representations.filter {
                                it.id == selection.video.id ||
                                    it.asAdaptiveVariant().fits(route.maximumWidth, route.maximumHeight)
                            },
                    ),
                selectedRepresentationId = selection.video.id,
                maximumRepresentations = MAX_DASH_ABR_REPRESENTATIONS,
            )
        val dashAbrSession =
            DashAbrSession(
                selection.video.id,
                isMeteredNetwork,
                ::latestPlaybackFeedback,
                reopenCandidates =
                    if (pinned != null) {
                        compatibleYDashReopenRepresentations(selection.video, manifest.representations)
                            .filter {
                                it.id == selection.video.id ||
                                    it.asAdaptiveVariant().fits(route.maximumWidth, route.maximumHeight)
                            }.filterNot { it.id == selection.video.id }
                            .sortedBy(YDashRepresentation::bandwidthBitsPerSecond)
                            .take(MAX_DASH_ABR_REPRESENTATIONS - 1) + selection.video
                    } else {
                        emptyList()
                    },
                requestReopen =
                    pinned?.let { target ->
                        { desired ->
                            latestPlaybackFeedback()?.value?.generation?.let { generation ->
                                target.presentation.gate.propose(target.revision, generation, desired)
                            }
                            Unit
                        }
                    },
            ).takeIf { switchingRepresentations.size > 1 || pinned != null }
        val rewritten =
            buildYDashPlaybackManifest(
                manifest = manifest,
                selection = selection,
                allowContentProtection = route.drmProtected,
            ) { representation, template, kind ->
                when (kind) {
                    YDashResourceKind.Initialization -> {
                        val timelineStart =
                            representation.segmentTemplate
                                ?.timeline
                                ?.firstOrNull()
                                ?.startTime ?: 0L
                        val upstreamUri =
                            renderDashTemplate(
                                template = template,
                                representation = representation,
                                number = representation.segmentTemplate?.startNumber ?: 1L,
                                time = timelineStart,
                            )
                        localUrl(
                            upstreamUri = upstreamUri,
                            upstreamHeaders = route.upstreamHeaders,
                            credentialOrigin = route.credentialOrigin,
                            credentials = route.credentials,
                            cacheable = route.cacheable,
                            cacheIdentity =
                                route.cacheIdentity?.forAdaptiveResourceKey(
                                    "dash-init:${representation.id}",
                                ),
                            hlsManifest = false,
                            dashManifest = false,
                        )
                    }
                    YDashResourceKind.MediaTemplate ->
                        localDashTemplate(
                            route = route,
                            representation = representation,
                            upstreamTemplate = template,
                            switchingRepresentations =
                                switchingRepresentations
                                    .takeIf { representation.id == selection.video.id }
                                    .orEmpty(),
                            abrSession = dashAbrSession.takeIf { representation.id == selection.video.id },
                        )
                }
            }.encodeToByteArray()
        writeHeaders(
            socket = socket,
            status = 200,
            reason = "OK",
            contentType = DASH_CONTENT_TYPE,
            contentLength = rewritten.size.toLong(),
        )
        if (method == "GET") socket.getOutputStream().write(rewritten)
        socket.getOutputStream().flush()
    }

    private fun localDashTemplate(
        route: Route,
        representation: YDashRepresentation,
        upstreamTemplate: String,
        switchingRepresentations: List<YDashRepresentation> = emptyList(),
        abrSession: DashAbrSession? = null,
    ): String {
        val usesNumber = DASH_NUMBER_TOKEN.containsMatchIn(upstreamTemplate)
        val usesTime = DASH_TIME_TOKEN.containsMatchIn(upstreamTemplate)
        require(usesNumber || usesTime) { "DASH media template has no segment coordinate" }
        val extension = upstreamTemplate.safeTemplateExtension() ?: "m4s"
        val localTemplate =
            when {
                usesNumber && usesTime -> "dash-\$Number\$-\$Time\$.$extension"
                usesNumber -> "dash-\$Number\$.$extension"
                else -> "dash-\$Time\$.$extension"
            }
        val templateRoute =
            route.copy(
                upstreamUri = representation.baseUri,
                cacheIdentity = route.cacheIdentity,
                hlsManifest = false,
                dashManifest = false,
                dashTemplate =
                    DashTemplateRoute(
                        representation = representation,
                        upstreamTemplate = upstreamTemplate,
                        usesNumber = usesNumber,
                        usesTime = usesTime,
                        localExtension = extension,
                        switchingRepresentations = switchingRepresentations,
                        abrSession = abrSession,
                    ),
            )
        return localRouteUrl(templateRoute, "/$localTemplate")
    }

    private fun YDashSegmentTemplate.segmentDurationUs(
        number: Long,
        time: Long?,
    ): Long {
        val rawDuration =
            duration
                ?: timeline.durationForCoordinate(number = number, time = time, startNumber = startNumber)
                ?: timescale
                    .coerceAtMost(Long.MAX_VALUE / DEFAULT_DASH_SEGMENT_DURATION_SECONDS)
                    .times(DEFAULT_DASH_SEGMENT_DURATION_SECONDS)
        return rawDuration
            .coerceAtMost(Long.MAX_VALUE / MICROS_PER_SECOND_LONG)
            .times(MICROS_PER_SECOND_LONG)
            .div(timescale)
            .coerceAtLeast(1L)
    }

    private fun List<com.yfuse.core2.adaptive.YDashTimelineEntry>.durationForCoordinate(
        number: Long,
        time: Long?,
        startNumber: Long,
    ): Long? {
        var nextTime = 0L
        var nextNumber = startNumber
        for (entry in this) {
            val entryStart = entry.startTime ?: nextTime
            if (time != null && time >= entryStart && (time - entryStart) % entry.duration == 0L) {
                val offset = (time - entryStart) / entry.duration
                if (entry.repeat < 0 || offset <= entry.repeat.toLong()) return entry.duration
            }
            if (number >= nextNumber) {
                val offset = number - nextNumber
                if (entry.repeat < 0 || offset <= entry.repeat.toLong()) return entry.duration
            }
            if (entry.repeat < 0) return null
            val count = entry.repeat.toLong() + 1L
            nextNumber += count
            nextTime = entryStart + entry.duration * count
        }
        return null
    }

    private fun serveBinary(
        socket: Socket,
        route: Route,
        method: String,
        rawRange: String?,
    ) {
        val requestedRange = parseYCoreHttpByteRange(rawRange)
        if (rawRange != null && requestedRange == null) {
            writeEmptyResponse(socket, 416, "Range Not Satisfiable")
            return
        }
        if (requestedRange == null && !route.cacheable) {
            serveSequential(socket, route, method)
            return
        }
        val source =
            AndroidTransportMediaDataSource(
                uri = route.upstreamUri,
                protocol = requireNotNull(route.upstreamUri.sourceProtocolOrNull()),
                headers = route.upstreamHeadersWithUserAgent(),
                credentials = route.credentialsFor(route.upstreamUri),
                createTransport = ::trackedTransport,
                initialMediaBitRateBitsPerSecond = route.mediaBitRateBitsPerSecond,
                cacheDirectory = cacheDirectory.takeIf { route.cacheable },
                cacheIdentity = route.cacheIdentity.takeIf { route.cacheable },
                cacheMaximumBytes = cacheMaximumBytes.takeIf { route.cacheable } ?: 0L,
                onNetworkSample =
                    when {
                        route.hlsAbrResource != null -> {
                            val abr = route.hlsAbrResource
                            { bytes: Long, durationMs: Long -> abr.session.recordNetworkSample(bytes, durationMs) }
                        }
                        route.dashAbrResource != null -> {
                            val abr = route.dashAbrResource
                            { bytes: Long, durationMs: Long -> abr.session.recordNetworkSample(bytes, durationMs) }
                        }
                        else -> null
                    },
            )
        try {
            activeRangeSources.add(source)
            check(!closed.get()) { "Playback proxy is closed" }
            updateRangePlaybackWindow(source)
            val totalLength = source.getSize()
            require(totalLength >= 0L) { "Upstream media length is unknown" }
            val start = requestedRange?.startInclusive ?: 0L
            if (start >= totalLength) {
                writeRangeNotSatisfiable(socket, totalLength)
                return
            }
            val endInclusive =
                minOf(
                    requestedRange?.endInclusive ?: (totalLength - 1L),
                    totalLength - 1L,
                )
            val contentLength = endInclusive - start + 1L
            writeHeaders(
                socket = socket,
                status = if (requestedRange == null) 200 else 206,
                reason = if (requestedRange == null) "OK" else "Partial Content",
                contentType = route.upstreamUri.guessContentType(),
                contentLength = contentLength,
                contentRange =
                    if (requestedRange == null) {
                        null
                    } else {
                        "bytes $start-$endInclusive/$totalLength"
                    },
            )
            if (method == "HEAD") return
            copySource(source, socket, start, contentLength)
            route.hlsAbrResource?.let { abr ->
                abr.session.complete(abr.segment.sequence, abr.segment.durationUs)
            }
            route.dashAbrResource?.let { abr ->
                abr.session.complete(abr.sequence, abr.durationUs)
            }
        } finally {
            activeRangeSources.remove(source)
            source.close()
        }
    }

    private fun serveSequential(
        socket: Socket,
        route: Route,
        method: String,
    ) = runBlocking {
        val transport = trackedTransport()
        try {
            val response =
                transport.open(
                    YMediaTransportRequest(
                        uri = route.upstreamUri,
                        protocol = requireNotNull(route.upstreamUri.sourceProtocolOrNull()),
                        headers = route.upstreamHeadersWithUserAgent(),
                        credentials = route.credentialsFor(route.upstreamUri),
                    ),
                )
            require(response.statusCode in 200..299) { "Upstream returned ${response.statusCode}" }
            writeHeaders(
                socket = socket,
                status = 200,
                reason = "OK",
                contentType = route.upstreamUri.guessContentType(),
                contentLength = response.contentLength,
            )
            if (method == "GET") {
                var networkReadDurationNs = 0L
                var transferredBytes = 0L
                val buffer = ByteArray(NETWORK_BUFFER_BYTES)
                while (true) {
                    val readStartedNs = System.nanoTime()
                    val count = transport.read(buffer, 0, buffer.size)
                    networkReadDurationNs += (System.nanoTime() - readStartedNs).coerceAtLeast(0L)
                    if (count < 0) break
                    if (count > 0) {
                        // Local loopback backpressure is deliberately outside networkReadDurationNs.
                        socket.getOutputStream().write(buffer, 0, count)
                        transferredBytes += count
                    }
                }
                route.hlsAbrResource?.let { abr ->
                    abr.session.recordNetworkSample(
                        transferredBytes,
                        (networkReadDurationNs / NANOS_PER_MILLISECOND).coerceAtLeast(1L),
                    )
                    abr.session.complete(abr.segment.sequence, abr.segment.durationUs)
                }
                route.dashAbrResource?.let { abr ->
                    abr.session.recordNetworkSample(
                        transferredBytes,
                        (networkReadDurationNs / NANOS_PER_MILLISECOND).coerceAtLeast(1L),
                    )
                    abr.session.complete(abr.sequence, abr.durationUs)
                }
            }
            socket.getOutputStream().flush()
        } finally {
            transport.close()
        }
    }

    private suspend fun loadBoundedCancellable(
        upstreamUri: String,
        maximumBytes: Int,
        route: Route,
    ): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val budget = YManifestDiscoveryBudget { !closed.get() }
            val registration = requests.register { continuation.cancel() }
            if (registration == null) return@suspendCancellableCoroutine
            val future =
                try {
                    workers.submit {
                        try {
                            continuation.resume(loadBounded(upstreamUri, maximumBytes, route, budget))
                        } catch (error: Throwable) {
                            continuation.resumeWithException(error)
                        } finally {
                            registration.close()
                        }
                    }
                } catch (failure: Exception) {
                    registration.close()
                    continuation.resumeWithException(failure)
                    return@suspendCancellableCoroutine
                }
            continuation.invokeOnCancellation {
                budget.cancel()
                future.cancel(false)
                registration.close()
            }
        }

    private fun loadBounded(
        upstreamUri: String,
        maximumBytes: Int,
        route: Route,
        discoveryBudget: YManifestDiscoveryBudget? = null,
    ): ByteArray =
        runBlocking {
            val transport = trackedTransport()
            val cancellation = discoveryBudget?.onCancel(transport::cancel)
            try {
                check(!closed.get()) { "Adaptive presentation was closed" }
                discoveryBudget?.checkActive()
                val response =
                    transport.open(
                        YMediaTransportRequest(
                            uri = upstreamUri,
                            protocol = requireNotNull(upstreamUri.sourceProtocolOrNull()),
                            headers = route.upstreamHeadersWithUserAgent(upstreamUri),
                            credentials = route.credentialsFor(upstreamUri),
                        ),
                    )
                require(response.statusCode in 200..299) { "Manifest returned ${response.statusCode}" }
                response.contentLength?.let { require(it <= maximumBytes) { "Manifest exceeds the byte limit" } }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(NETWORK_BUFFER_BYTES)
                while (true) {
                    discoveryBudget?.checkActive()
                    val count = transport.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) continue
                    require(output.size() <= maximumBytes - count) { "Manifest exceeds the byte limit" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } finally {
                cancellation?.close()
                transport.close()
            }
        }

    private fun copySource(
        source: AndroidTransportMediaDataSource,
        socket: Socket,
        start: Long,
        contentLength: Long,
    ) {
        val output = socket.getOutputStream()
        val buffer = ByteArray(NETWORK_BUFFER_BYTES)
        var position = start
        var remaining = contentLength
        while (remaining > 0L) {
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val count = source.readAt(position, buffer, 0, requested)
            require(count > 0) { "Cached source ended before the advertised response length" }
            output.write(buffer, 0, count)
            position += count
            remaining -= count
        }
        output.flush()
    }

    private fun Route.credentialsFor(targetUri: String): YTransportCredentials? =
        credentials.takeIf { mediaCredentialOriginsMatch(credentialOrigin, targetUri) }

    private fun Route.upstreamHeadersWithUserAgent(targetUri: String = upstreamUri): Map<String, String> =
        yCoreProxyUpstreamRequestContext(
            upstreamHeaders = scopedMediaHeaders(upstreamHeaders, credentialOrigin, targetUri),
            configuredUserAgent = userAgent,
            credentials = credentials,
        ).headers

    private fun writeHeaders(
        socket: Socket,
        status: Int,
        reason: String,
        contentType: String,
        contentLength: Long?,
        contentRange: String? = null,
    ) {
        val output = socket.getOutputStream()
        responsesStarted.add(socket)
        output.write("HTTP/1.1 $status $reason\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write("Content-Type: $contentType\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write("Accept-Ranges: bytes\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        contentRange?.let {
            output.write("Content-Range: $it\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        }
        contentLength?.takeIf { it >= 0L }?.let {
            output.write("Content-Length: $it\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        }
        output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun writeRangeNotSatisfiable(
        socket: Socket,
        totalLength: Long,
    ) {
        val output = socket.getOutputStream()
        responsesStarted.add(socket)
        output.write(
            "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$totalLength\r\n".toByteArray(
                StandardCharsets.ISO_8859_1,
            ),
        )
        output.write("Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
    }

    private fun writeEmptyResponse(
        socket: Socket,
        status: Int,
        reason: String,
    ) {
        responsesStarted.add(socket)
        socket.getOutputStream().write(
            "HTTP/1.1 $status $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(
                StandardCharsets.ISO_8859_1,
            ),
        )
        socket.getOutputStream().flush()
    }
}

internal data class YCoreHttpByteRange(
    val startInclusive: Long,
    val endInclusive: Long?,
)

internal data class YCoreProxyUpstreamRequestContext(
    val headers: Map<String, String>,
    val credentials: YTransportCredentials?,
)

internal fun yCoreProxyUpstreamRequestContext(
    upstreamHeaders: Map<String, String>,
    configuredUserAgent: String,
    credentials: YTransportCredentials?,
): YCoreProxyUpstreamRequestContext {
    val headers =
        if (upstreamHeaders.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
            upstreamHeaders
        } else {
            configuredUserAgent
                .trim()
                .takeIf(String::isNotEmpty)
                ?.let { upstreamHeaders + ("User-Agent" to it) }
                ?: upstreamHeaders
        }
    return YCoreProxyUpstreamRequestContext(headers = headers, credentials = credentials)
}

internal fun parseYCoreHttpByteRange(value: String?): YCoreHttpByteRange? {
    val match = value?.trim()?.let(HTTP_BYTE_RANGE::matchEntire) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].takeIf(String::isNotEmpty)?.toLongOrNull()
    if (end != null && end < start) return null
    return YCoreHttpByteRange(startInclusive = start, endInclusive = end)
}

/**
 * Carries only RFC 8216 low-latency reload coordinates from the loopback client to the origin.
 * Authentication and arbitrary local query parameters must never be able to cross this boundary.
 */
internal fun mergeYCoreHlsReloadQuery(
    upstreamUri: String,
    localRawQuery: String,
): String {
    val accepted = linkedMapOf<String, String>()
    localRawQuery
        .split('&')
        .asSequence()
        .filter(String::isNotBlank)
        .take(MAX_HLS_RELOAD_QUERY_PARAMETERS + 1)
        .forEach { parameter ->
            val rawName = parameter.substringBefore('=')
            val rawValue = parameter.substringAfter('=', missingDelimiterValue = "")
            val canonicalName = HLS_RELOAD_QUERY_NAMES[rawName.lowercase()] ?: return@forEach
            val valid =
                when (canonicalName) {
                    "_HLS_msn" -> rawValue.toLongOrNull()?.let { it >= 0L } == true
                    "_HLS_part" -> rawValue.toIntOrNull()?.let { it >= 0 } == true
                    "_HLS_skip" ->
                        rawValue.equals("YES", ignoreCase = true) || rawValue.equals("v2", ignoreCase = true)
                    else -> false
                }
            if (valid) accepted[canonicalName] = rawValue
        }
    if (accepted.isEmpty()) return upstreamUri

    val fragment = upstreamUri.substringAfter('#', missingDelimiterValue = "")
    val withoutFragment = upstreamUri.substringBefore('#')
    val path = withoutFragment.substringBefore('?')
    val retained =
        withoutFragment
            .substringAfter('?', missingDelimiterValue = "")
            .split('&')
            .filter(String::isNotBlank)
            .filterNot { parameter -> parameter.substringBefore('=').lowercase() in HLS_RELOAD_QUERY_NAMES }
    val mergedQuery = (retained + accepted.map { (name, value) -> "$name=$value" }).joinToString("&")
    return buildString {
        append(path)
        if (mergedQuery.isNotEmpty()) append('?').append(mergedQuery)
        if (fragment.isNotEmpty()) append('#').append(fragment)
    }
}

private fun String.sourceProtocolOrNull(): YSourceProtocol? =
    when (runCatching { URI(this).scheme?.lowercase() }.getOrNull()) {
        "http" -> YSourceProtocol.Http
        "https" -> YSourceProtocol.Https
        else -> null
    }

private fun String.isHlsManifestUri(): Boolean =
    runCatching {
        URI(this)
            .path
            .orEmpty()
            .lowercase()
            .endsWith(".m3u8")
    }.getOrDefault(false)

private fun String.isDashManifestUri(): Boolean =
    runCatching {
        URI(this)
            .path
            .orEmpty()
            .lowercase()
            .endsWith(".mpd")
    }.getOrDefault(false)

internal fun String.hasSeparateYCoreHlsRenditions(): Boolean =
    lineSequence().any { line ->
        val normalized = line.trim().uppercase()
        normalized.startsWith("#EXT-X-MEDIA:") ||
            normalized.startsWith("#EXT-X-STREAM-INF:") &&
            ("AUDIO=" in normalized || "VIDEO=" in normalized || "SUBTITLES=" in normalized)
    }

private fun String.hasHlsSessionKey(): Boolean =
    lineSequence().any { line ->
        line
            .trim()
            .uppercase()
            .startsWith("#EXT-X-SESSION-KEY:")
    }

private fun String.hasLowLatencyHlsParts(): Boolean =
    lineSequence().any { line ->
        val normalized = line.trim().uppercase()
        normalized.startsWith("#EXT-X-PART:") || normalized.startsWith("#EXT-X-PRELOAD-HINT:")
    }

private fun YAdaptiveVariant.fits(
    maximumWidth: Int?,
    maximumHeight: Int?,
): Boolean =
    (maximumWidth == null || width == null || width <= maximumWidth) &&
        (maximumHeight == null || height == null || height <= maximumHeight)

private fun String.guessContentType(): String =
    when (
        runCatching {
            URI(this)
                .path
                .orEmpty()
                .substringAfterLast('.')
                .lowercase()
        }.getOrDefault("")
    ) {
        "m3u8" -> HLS_CONTENT_TYPE
        "mpd" -> DASH_CONTENT_TYPE
        "ts" -> "video/mp2t"
        "mp4", "m4s", "m4v" -> "video/mp4"
        "aac" -> "audio/aac"
        "vtt" -> "text/vtt"
        else -> "application/octet-stream"
    }

private fun String.safeSyntheticExtension(): String? =
    runCatching {
        URI(this)
            .path
            .orEmpty()
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
    }.getOrNull()
        ?.takeIf(SAFE_SYNTHETIC_EXTENSIONS::contains)

private fun String.safeTemplateExtension(): String? =
    substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .takeIf(SAFE_SYNTHETIC_EXTENSIONS::contains)

private fun YCacheIdentity.forStableAdaptiveUri(upstreamUri: String): YCacheIdentity =
    forAdaptiveResourceKey(stableAdaptiveResourceKey(upstreamUri))

private fun YCacheIdentity.forAdaptiveResourceKey(resourceKey: String): YCacheIdentity =
    copy(
        version =
            buildString {
                append(version)
                append(':')
                append(resourceKey.sha256())
            },
    )

internal fun stableAdaptiveResourceKey(uri: String): String =
    runCatching {
        val parsed = URI(uri)
        buildString {
            parsed.scheme?.let { append(it.lowercase()).append("://") }
            parsed.host?.let { host ->
                append(host.lowercase())
                if (parsed.port >= 0) append(':').append(parsed.port)
            }
            append(parsed.path.orEmpty())
            val stableQuery =
                parsed.rawQuery
                    ?.split('&')
                    ?.filter(String::isNotBlank)
                    ?.filterNot { parameter ->
                        parameter.substringBefore('=').isEphemeralMediaCredentialParameter()
                    }?.sorted()
                    .orEmpty()
            if (stableQuery.isNotEmpty()) append('?').append(stableQuery.joinToString("&"))
        }.takeIf(String::isNotBlank)
    }.getOrNull() ?: uri.substringBefore('?').substringBefore('#')

private fun String.isEphemeralMediaCredentialParameter(): Boolean {
    val normalized = lowercase().replace("-", "").replace("_", "")
    return normalized in EPHEMERAL_MEDIA_QUERY_NAMES ||
        normalized.contains("signature") ||
        normalized.endsWith("token")
}

private fun String.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val LOOPBACK_HOST = "127.0.0.1"
private const val LOOPBACK_BACKLOG = 8
private const val ROUTE_PREFIX = "ycore-resource"
private const val CLIENT_SOCKET_TIMEOUT_MS = 30_000
private const val NETWORK_BUFFER_BYTES = 64 * 1024
private const val MAX_HLS_MANIFEST_BYTES = 4 * 1024 * 1024
private const val MAX_DASH_MANIFEST_BYTES = 8 * 1024 * 1024
private const val MAX_ROUTES = 50_000
private const val MAX_REQUEST_LINE_BYTES = 8 * 1024
private const val MAX_REQUEST_HEADER_BYTES = 8 * 1024
private const val MAX_REQUEST_HEADER_COUNT = 64
private const val MAX_HLS_RELOAD_QUERY_PARAMETERS = 8
private const val INITIAL_BANDWIDTH_BITS_PER_SECOND = 25_000_000L
private const val STARTUP_BUFFER_US = 10_000_000L
private const val MAX_ABR_BUFFER_US = 30_000_000L
private const val ABR_FEEDBACK_STALE_NS = 5_000_000_000L
private const val MAX_HLS_ABR_VARIANTS = 8
private const val MAX_DASH_ABR_REPRESENTATIONS = 8
private const val NANOS_PER_MICROSECOND = 1_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val MICROS_PER_SECOND_LONG = 1_000_000L
private const val DEFAULT_DASH_SEGMENT_DURATION_SECONDS = 2L
private const val HLS_CONTENT_TYPE = "application/vnd.apple.mpegurl"
private const val DASH_CONTENT_TYPE = "application/dash+xml"
private const val WIDEVINE_SYSTEM_ID = "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
private val ALLOWED_METHODS = setOf("GET", "HEAD")
private val HTTP_BYTE_RANGE = Regex("^bytes=(\\d+)-(\\d*)$", RegexOption.IGNORE_CASE)
private val HLS_RELOAD_QUERY_NAMES =
    mapOf(
        "_hls_msn" to "_HLS_msn",
        "_hls_part" to "_HLS_part",
        "_hls_skip" to "_HLS_skip",
    )
private val DASH_PERIOD_TAG = Regex("<\\s*(?:[A-Za-z0-9_.-]+:)?Period(?:\\s|>)", RegexOption.IGNORE_CASE)
private val DASH_NUMBER_TOKEN = Regex("\\\$Number(?:%0\\d+d)?\\\$")
private val DASH_TIME_TOKEN = Regex("\\\$Time(?:%0\\d+d)?\\\$")
private val SAFE_SYNTHETIC_EXTENSIONS =
    setOf("aac", "bin", "key", "m3u8", "m4s", "m4v", "mkv", "mov", "mp4", "mpd", "m2ts", "mts", "ts", "vtt", "webm")
private val EPHEMERAL_MEDIA_QUERY_NAMES =
    setOf(
        "authorization",
        "auth",
        "token",
        "accesstoken",
        "apikey",
        "expires",
        "expiry",
        "sig",
        "hmac",
        "hdnts",
        "hdnea",
        "policy",
        "keypairid",
        "xamzalgorithm",
        "xamzcredential",
        "xamzdate",
        "xamzexpires",
        "xamzsecuritytoken",
        "xamzsignature",
        "xamzsignedheaders",
    )
