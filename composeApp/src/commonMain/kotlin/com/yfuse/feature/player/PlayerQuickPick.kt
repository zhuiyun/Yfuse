package com.yfuse.feature.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.russhwolf.settings.Settings
import com.yfuse.core.cast.CastDevice
import com.yfuse.core.sync.WatchChatMessage
import com.yfuse.core.sync.WatchSticker
import com.yfuse.core.sync.WatchStickers
import com.yfuse.core.sync.sticker
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ------------------------------------------------------------ 一起看贴纸轮盘

/** How many stickers the chat key's fan holds. */
internal const val QUICK_STICKER_COUNT = 6

/** What the fan offers a room that has sent no stickers yet: one of each kind of reaction. */
private val QUICK_STICKER_DEFAULTS = listOf("laugh", "wow", "clap", "heart", "fire", "cry")

/**
 * The stickers the chat key's fan offers: the ones this viewer sends most in the room, the more
 * recent first among equals; then what the rest of the room has been sending; then defaults.
 */
internal fun quickStickers(
    messages: List<WatchChatMessage>,
    count: Int = QUICK_STICKER_COUNT,
): List<WatchSticker> {
    // Walked newest first, so each map keeps the order in which a sticker was last used.
    val mine = LinkedHashMap<String, Int>()
    val theirs = LinkedHashSet<String>()
    messages.asReversed().forEach { message ->
        val sticker = message.sticker ?: return@forEach
        if (message.isMine) {
            mine[sticker.id] = (mine[sticker.id] ?: 0) + 1
        } else {
            theirs += sticker.id
        }
    }
    val frequent = mine.entries.sortedByDescending { it.value }.map { it.key }
    return (frequent + theirs + QUICK_STICKER_DEFAULTS).distinct().mapNotNull(WatchStickers::byId).take(count)
}

/** The fan opens under the key, from lower left round to lower right: degrees clockwise from east. */
internal const val FAN_FROM_DEGREES = 160f
internal const val FAN_TO_DEGREES = 20f

/** [count] centres on an arc of [radius] about [pivot], from [fromDegrees] to [toDegrees]. */
internal fun fanCenters(
    pivot: Offset,
    count: Int,
    radius: Float,
    fromDegrees: Float = FAN_FROM_DEGREES,
    toDegrees: Float = FAN_TO_DEGREES,
): List<Offset> {
    if (count <= 0) return emptyList()
    val step = if (count == 1) 0f else (toDegrees - fromDegrees) / (count - 1)
    val start = if (count == 1) (fromDegrees + toDegrees) / 2f else fromDegrees
    return List(count) { index ->
        val radians = (start + step * index) * PI.toFloat() / 180f
        Offset(pivot.x + radius * cos(radians), pivot.y + radius * sin(radians))
    }
}

/**
 * [centers] slid together — only as far as it takes — so every disc of [itemRadius] stays
 * [margin] inside [bounds]. A key near the edge of the screen keeps its whole fan.
 */
internal fun keepInside(
    centers: List<Offset>,
    itemRadius: Float,
    bounds: Rect,
    margin: Float,
): List<Offset> {
    if (centers.isEmpty()) return centers
    val inset = itemRadius + margin
    val dx = shiftInto(centers.minOf { it.x } - inset, centers.maxOf { it.x } + inset, bounds.left, bounds.right)
    val dy = shiftInto(centers.minOf { it.y } - inset, centers.maxOf { it.y } + inset, bounds.top, bounds.bottom)
    return if (dx == 0f && dy == 0f) centers else centers.map { Offset(it.x + dx, it.y + dy) }
}

private fun shiftInto(
    low: Float,
    high: Float,
    from: Float,
    to: Float,
): Float =
    when {
        low < from -> from - low
        high > to -> (to - high).coerceAtLeast(from - low)
        else -> 0f
    }

/** The disc under [finger]: the nearest centre within [hitRadius], or null. */
internal fun discAt(
    finger: Offset,
    centers: List<Offset>,
    hitRadius: Float,
): Int? {
    var best: Int? = null
    var bestDistance = hitRadius
    centers.forEachIndexed { index, centre ->
        val distance = (finger - centre).getDistance()
        if (distance <= bestDistance) {
            best = index
            bestDistance = distance
        }
    }
    return best
}

// ------------------------------------------------------------ 按住拖送

/** [count] rows stacked under [anchor], centred on it and slid inside [bounds] less [margin]. */
internal fun columnRects(
    anchor: Rect,
    count: Int,
    rowWidth: Float,
    rowHeight: Float,
    gap: Float,
    bounds: Rect,
    margin: Float,
): List<Rect> {
    val maxLeft = (bounds.right - margin - rowWidth).coerceAtLeast(bounds.left + margin)
    val left = (anchor.center.x - rowWidth / 2f).coerceIn(bounds.left + margin, maxLeft)
    val top = anchor.bottom + gap
    return List(count) { index ->
        val rowTop = top + index * (rowHeight + gap)
        Rect(left, rowTop, left + rowWidth, rowTop + rowHeight)
    }
}

/**
 * The row under [finger], or null. Half the [gap] above and below each row belongs to it, so a
 * finger sliding down the column never falls between two; [sideSlop] forgives a drift sideways.
 */
internal fun rowAt(
    finger: Offset,
    rows: List<Rect>,
    gap: Float,
    sideSlop: Float,
): Int? =
    rows
        .indexOfFirst { row ->
            finger.x in (row.left - sideSlop)..(row.right + sideSlop) &&
                finger.y in (row.top - gap / 2f)..(row.bottom + gap / 2f)
        }.takeIf { it >= 0 }

internal enum class QuickCastKind(
    val caption: String,
) {
    Chromecast("Chromecast"),
    Dlna("DLNA"),

    /** Another Yfuse device of this account, reached through 设备接力 rather than a cast. */
    Handoff("Yfuse 接力"),
}

/** A device this phone cast or handed off to, as remembered between films. */
internal data class RecentCastTarget(
    val kind: QuickCastKind,
    val id: String,
    val name: String,
)

/** A device of this account that 设备接力 says can take playback now. */
internal data class HandoffReceiver(
    val sessionId: String,
    val name: String,
)

/** One row of the cast key's list. */
internal data class QuickCastTarget(
    val kind: QuickCastKind,
    val id: String,
    val name: String,
    /**
     * Seen on the network, or online on the account, right now. A remembered cast device not yet
     * found by this session's scan is still offered, and looked for when it is picked.
     */
    val found: Boolean,
    /** The device this player is already casting to. */
    val active: Boolean = false,
) {
    /** What a screen reader's custom action says. */
    val actionLabel: String get() = if (kind == QuickCastKind.Handoff) "接力到 $name" else "投屏到 $name"

    fun remembered(): RecentCastTarget = RecentCastTarget(kind, id, name)
}

/** How many devices the cast key's list shows. */
internal const val QUICK_CAST_LIMIT = 5

/** `CAST_PREFIX` in CastManager.android.kt: how a Chromecast route's id is told from a DLNA device's. */
private const val CHROMECAST_ID_PREFIX = "chromecast:"
private const val CHROMECAST_NAME_SUFFIX = " · Chromecast"

internal fun castKindOf(deviceId: String): QuickCastKind =
    if (deviceId.startsWith(CHROMECAST_ID_PREFIX)) QuickCastKind.Chromecast else QuickCastKind.Dlna

/** A cast device's name as a row shows it: the row already says Chromecast underneath. */
internal fun castDisplayName(name: String): String = name.removeSuffix(CHROMECAST_NAME_SUFFIX)

/**
 * The cast key's list: remembered devices, most recently used first, then whatever else is found
 * now — cast devices, then this account's other devices that can take a 接力 — up to [limit].
 *
 * A remembered cast device stays even before this session's scan finds it again; a remembered
 * 接力 device appears only while it is online, since there is no finding it later. [handoffAllowed]
 * is false in a watch-together room, which 接力 would leave behind.
 */
internal fun quickCastTargets(
    recent: List<RecentCastTarget>,
    castDevices: List<CastDevice>,
    receivers: List<HandoffReceiver>,
    activeCastId: String?,
    handoffAllowed: Boolean,
    limit: Int = QUICK_CAST_LIMIT,
): List<QuickCastTarget> {
    val devices = castDevices.associateBy(CastDevice::id)
    val online = receivers.associateBy(HandoffReceiver::sessionId)
    val seen = HashSet<Pair<QuickCastKind, String>>()
    val targets = mutableListOf<QuickCastTarget>()

    fun add(target: QuickCastTarget) {
        if (seen.add(target.kind to target.id)) targets += target
    }

    recent.forEach { remembered ->
        if (remembered.kind == QuickCastKind.Handoff) {
            val receiver = online[remembered.id]
            if (handoffAllowed && receiver != null) {
                add(QuickCastTarget(QuickCastKind.Handoff, receiver.sessionId, receiver.name, found = true))
            }
        } else {
            val device = devices[remembered.id]
            add(
                QuickCastTarget(
                    kind = remembered.kind,
                    id = remembered.id,
                    name = device?.name?.let(::castDisplayName) ?: remembered.name,
                    found = device != null,
                    active = remembered.id == activeCastId,
                ),
            )
        }
    }
    castDevices.forEach { device ->
        add(
            QuickCastTarget(
                kind = castKindOf(device.id),
                id = device.id,
                name = castDisplayName(device.name),
                found = true,
                active = device.id == activeCastId,
            ),
        )
    }
    if (handoffAllowed) {
        receivers.forEach { add(QuickCastTarget(QuickCastKind.Handoff, it.sessionId, it.name, found = true)) }
    }
    return targets.take(limit)
}

/**
 * The devices this phone cast or handed off to, most recent first. Only ever a handful of lines,
 * so it is kept as text — one device a line, its fields split by tabs — rather than as JSON.
 */
internal class RecentCastTargets(
    private val settings: Settings,
) {
    fun load(): List<RecentCastTarget> = decodeRecentCastTargets(settings.getStringOrNull(KEY))

    /** Puts [target] first and returns the new list. */
    fun record(target: RecentCastTarget): List<RecentCastTarget> {
        val next =
            (listOf(target) + load().filterNot { it.kind == target.kind && it.id == target.id })
                .take(RECENT_CAST_LIMIT)
        settings.putString(KEY, encodeRecentCastTargets(next))
        return next
    }

    companion object {
        const val KEY = "player.quickCast.recent"
        const val RECENT_CAST_LIMIT = 8
    }
}

internal fun encodeRecentCastTargets(targets: List<RecentCastTarget>): String =
    targets.joinToString("\n") { target ->
        listOf(target.kind.name, target.id, target.name).joinToString("\t") { field ->
            field.replace('\t', ' ').replace('\n', ' ')
        }
    }

internal fun decodeRecentCastTargets(raw: String?): List<RecentCastTarget> =
    raw.orEmpty().lines().mapNotNull { line ->
        val fields = line.split('\t')
        val kind = QuickCastKind.entries.firstOrNull { it.name == fields.firstOrNull() }
        if (fields.size != 3 || kind == null || fields[1].isBlank()) {
            null
        } else {
            RecentCastTarget(kind, fields[1], fields[2])
        }
    }
