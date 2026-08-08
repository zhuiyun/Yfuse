package com.yfuse.core.sync

/**
 * How a sticker moves once it lands.
 *
 * A 动图 here is a glyph plus a canned motion rather than a decoded image file. That is a
 * deliberate trade: the room is a chat between two phones on a relay that only forwards
 * short strings, and shipping frames — or fetching them — would mean an asset pipeline, a
 * decoder dependency and a payload per sticker, for artwork nobody has drawn yet. The motion
 * is what makes it read as animated, and it costs one float per frame.
 *
 * If real artwork does arrive later, only [WatchSticker] and the composable that draws it
 * need to change: the wire carries an id, and every other layer already speaks in ids.
 */
enum class WatchStickerMotion(
    /** One full cycle, in milliseconds. */
    val periodMs: Int,
) {
    /** Doesn't move. Sends as a plain 表情. */
    Still(0),

    /** Hops, with a pause at the top of each arc. */
    Bounce(920),

    /** Fast side-to-side. For anything alarmed or excited. */
    Shake(620),

    /** One full turn. */
    Spin(1_500),

    /** Breathes — grows and settles. For hearts and anything warm. */
    Pulse(1_100),

    /** Rocks about its top edge, like something hanging. */
    Swing(1_250),

    /** A lazy tilt-and-squash. The all-purpose "this is alive". */
    Wobble(840),
}

/**
 * One preset from the tray.
 *
 * @param id what travels over the wire, and the only part that must never change: a message
 *   sent today is still in a room's history tomorrow. Keep it short — the whole token has to
 *   fit inside one chat message, which `WatchStickerTest` checks.
 * @param glyph what is drawn.
 * @param label what a screen reader says, and the fallback for a client whose catalogue is
 *   older than the sender's.
 */
data class WatchSticker(
    val id: String,
    val glyph: String,
    val label: String,
    val motion: WatchStickerMotion = WatchStickerMotion.Still,
)

/**
 * The tray, and the wire format that carries it.
 *
 * A sticker is sent as an ordinary chat message whose whole text is a token. That is what
 * makes it show up on both sides without touching the relay: it lands in the transcript, it
 * flies past as 弹幕, it survives history replay, and a client that has never heard of
 * stickers still shows *something* rather than dropping the message. There is no second
 * delivery path to keep in step with the first.
 */
object WatchStickers {

    /**
     * The presets, in tray order: the eight the room already had first, so the keys people
     * have learned stay where they were, then the rest.
     */
    val presets: List<WatchSticker> = listOf(
        WatchSticker("laugh", "😂", "笑哭", WatchStickerMotion.Wobble),
        WatchSticker("wow", "😮", "惊讶", WatchStickerMotion.Pulse),
        WatchSticker("love", "😍", "喜欢", WatchStickerMotion.Pulse),
        WatchSticker("cry", "😭", "大哭", WatchStickerMotion.Shake),
        WatchSticker("clap", "👏", "鼓掌", WatchStickerMotion.Bounce),
        WatchSticker("fire", "🔥", "燃", WatchStickerMotion.Wobble),
        WatchSticker("think", "🤔", "思考", WatchStickerMotion.Swing),
        WatchSticker("dead", "💀", "笑死", WatchStickerMotion.Shake),

        WatchSticker("heart", "❤️", "爱心", WatchStickerMotion.Pulse),
        WatchSticker("rofl", "🤣", "笑翻", WatchStickerMotion.Wobble),
        WatchSticker("cool", "😎", "酷", WatchStickerMotion.Still),
        WatchSticker("shock", "😱", "吓到", WatchStickerMotion.Shake),
        WatchSticker("sleep", "😴", "困了", WatchStickerMotion.Swing),
        WatchSticker("sweat", "😅", "尴尬", WatchStickerMotion.Wobble),
        WatchSticker("angry", "😡", "生气", WatchStickerMotion.Shake),
        WatchSticker("blush", "☺️", "害羞", WatchStickerMotion.Pulse),

        WatchSticker("thumb", "👍", "赞", WatchStickerMotion.Bounce),
        WatchSticker("pray", "🙏", "拜托", WatchStickerMotion.Bounce),
        WatchSticker("wave", "👋", "招手", WatchStickerMotion.Swing),
        WatchSticker("muscle", "💪", "加油", WatchStickerMotion.Bounce),
        WatchSticker("eyes", "👀", "盯", WatchStickerMotion.Shake),
        WatchSticker("party", "🎉", "撒花", WatchStickerMotion.Wobble),
        WatchSticker("star", "⭐", "星星", WatchStickerMotion.Spin),
        WatchSticker("sparkle", "✨", "闪闪", WatchStickerMotion.Pulse),

        WatchSticker("popcorn", "🍿", "爆米花", WatchStickerMotion.Wobble),
        WatchSticker("cheers", "🍻", "干杯", WatchStickerMotion.Swing),
        WatchSticker("rocket", "🚀", "起飞", WatchStickerMotion.Bounce),
        WatchSticker("spin", "🌀", "晕", WatchStickerMotion.Spin),
        WatchSticker("bomb", "💣", "炸了", WatchStickerMotion.Shake),
        WatchSticker("snow", "❄️", "冷", WatchStickerMotion.Spin),
        WatchSticker("cat", "🐱", "猫", WatchStickerMotion.Bounce),
        WatchSticker("ghost", "👻", "鬼", WatchStickerMotion.Swing),
    )

    private val index: Map<String, WatchSticker> = presets.associateBy(WatchSticker::id)

    private const val PREFIX = "[sticker:"
    private const val SUFFIX = "]"

    fun byId(id: String): WatchSticker? = index[id]

    /** What goes on the wire, and into the transcript, for [sticker]. */
    fun token(sticker: WatchSticker): String = "$PREFIX${sticker.id}$SUFFIX"

    /**
     * The sticker a chat message *is*, or null for an ordinary one.
     *
     * The whole message has to be the token — a token quoted inside a sentence is a sentence,
     * and someone typing the literal text deserves to see what they typed.
     */
    fun parse(text: String): WatchSticker? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX) || !trimmed.endsWith(SUFFIX)) return null
        val id = trimmed.substring(PREFIX.length, trimmed.length - SUFFIX.length)
        return byId(id)
    }
}

/** The sticker this message carries, or null when it is words. */
val WatchChatMessage.sticker: WatchSticker?
    get() = WatchStickers.parse(text)

/**
 * What to show when there is one line to show it on — a preview, a notification, a 弹幕 that
 * has to stay on one row. Falls back to the label so an unknown id still reads as something.
 */
val WatchChatMessage.oneLineText: String
    get() = sticker?.let { "${it.glyph} ${it.label}" } ?: text
