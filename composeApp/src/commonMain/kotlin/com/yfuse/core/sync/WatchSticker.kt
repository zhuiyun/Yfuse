package com.yfuse.core.sync

/**
 * How a sticker moves once it lands.
 *
 * A 动图 here is a glyph plus a canned motion rather than a decoded image file. The room
 * protocol still carries only a short sticker id, so adding presets never increases relay
 * payload size and old clients keep showing unknown ids as text instead of losing messages.
 */
enum class WatchStickerMotion(
    /** One full cycle, in milliseconds. */
    val periodMs: Int,
) {
    Still(0),
    Bounce(920),
    Shake(620),
    Spin(1_500),
    Pulse(1_100),
    Swing(1_250),
    Wobble(840),

    /** Slow vertical drift with a tiny tilt; calmer than [Bounce]. */
    Float(1_900),

    /** Soft squash-and-stretch, useful for playful reactions. */
    Jelly(980),

    /** A continuous card-like turn around the Y axis. */
    Flip(1_800),

    /** Quick scale-up accent followed by a long settle. */
    Pop(1_260),

    /** Two compact beats per cycle. */
    Heartbeat(1_180),

    /** Small circular travel while turning slightly. */
    Orbit(1_700),

    /** Gentle side travel and tilt, less frantic than [Shake]. */
    Sway(1_560),
}

/** The five shelves in the tray. Keeping 64 presets in one endless row is not usable. */
enum class WatchStickerCategory(val label: String) {
    Reaction("反应"),
    Mood("情绪"),
    Cheer("互动"),
    Movie("观影"),
    Fun("趣味"),
}

/**
 * One preset from the tray.
 *
 * @param id what travels over the wire. Never rename an existing id: room history can outlive
 *   the build that created it.
 * @param glyph what is drawn.
 * @param label what a screen reader says and what compact previews show.
 */
data class WatchSticker(
    val id: String,
    val glyph: String,
    val label: String,
    val motion: WatchStickerMotion = WatchStickerMotion.Still,
    val category: WatchStickerCategory = WatchStickerCategory.Reaction,
)

/** Sticker catalogue plus the wire format that carries it. */
object WatchStickers {

    /**
     * 64 presets. The original 32 ids stay unchanged for history compatibility; new presets
     * only append ids. Categories keep the picker shallow enough to scan with one thumb.
     */
    val presets: List<WatchSticker> = listOf(
        // ---------------------------------------------------------------- reaction
        WatchSticker("laugh", "😂", "笑哭", WatchStickerMotion.Wobble),
        WatchSticker("wow", "😮", "惊讶", WatchStickerMotion.Pop),
        WatchSticker("rofl", "🤣", "笑翻", WatchStickerMotion.Jelly),
        WatchSticker("shock", "😱", "吓到", WatchStickerMotion.Shake),
        WatchSticker("dead", "💀", "笑死", WatchStickerMotion.Shake),
        WatchSticker("sweat", "😅", "尴尬", WatchStickerMotion.Wobble),
        WatchSticker("eyes", "👀", "盯", WatchStickerMotion.Sway),
        WatchSticker("think", "🤔", "思考", WatchStickerMotion.Swing),
        WatchSticker("facepalm", "🤦", "捂脸", WatchStickerMotion.Swing),
        WatchSticker("shrug", "🤷", "摊手", WatchStickerMotion.Sway),
        WatchSticker("mindblown", "🤯", "脑壳炸了", WatchStickerMotion.Pop),
        WatchSticker("scream", "🫨", "震惊", WatchStickerMotion.Shake),
        WatchSticker("peek", "🫣", "偷看", WatchStickerMotion.Sway),

        // ---------------------------------------------------------------- mood
        WatchSticker("love", "😍", "喜欢", WatchStickerMotion.Heartbeat, WatchStickerCategory.Mood),
        WatchSticker("cry", "😭", "大哭", WatchStickerMotion.Shake, WatchStickerCategory.Mood),
        WatchSticker("heart", "❤️", "爱心", WatchStickerMotion.Heartbeat, WatchStickerCategory.Mood),
        WatchSticker("cool", "😎", "酷", WatchStickerMotion.Sway, WatchStickerCategory.Mood),
        WatchSticker("sleep", "😴", "困了", WatchStickerMotion.Float, WatchStickerCategory.Mood),
        WatchSticker("angry", "😡", "生气", WatchStickerMotion.Shake, WatchStickerCategory.Mood),
        WatchSticker("blush", "☺️", "害羞", WatchStickerMotion.Pulse, WatchStickerCategory.Mood),
        WatchSticker("kiss", "😘", "亲亲", WatchStickerMotion.Pop, WatchStickerCategory.Mood),
        WatchSticker("plead", "🥺", "拜托啦", WatchStickerMotion.Pulse, WatchStickerCategory.Mood),
        WatchSticker("relief", "😌", "舒服了", WatchStickerMotion.Float, WatchStickerCategory.Mood),
        WatchSticker("smirk", "😏", "懂的都懂", WatchStickerMotion.Sway, WatchStickerCategory.Mood),
        WatchSticker("melting", "🫠", "融化", WatchStickerMotion.Jelly, WatchStickerCategory.Mood),
        WatchSticker("salute", "🫡", "收到", WatchStickerMotion.Pop, WatchStickerCategory.Mood),

        // ---------------------------------------------------------------- cheer / room interaction
        WatchSticker("clap", "👏", "鼓掌", WatchStickerMotion.Bounce, WatchStickerCategory.Cheer),
        WatchSticker("thumb", "👍", "赞", WatchStickerMotion.Pop, WatchStickerCategory.Cheer),
        WatchSticker("pray", "🙏", "拜托", WatchStickerMotion.Bounce, WatchStickerCategory.Cheer),
        WatchSticker("wave", "👋", "招手", WatchStickerMotion.Swing, WatchStickerCategory.Cheer),
        WatchSticker("muscle", "💪", "加油", WatchStickerMotion.Bounce, WatchStickerCategory.Cheer),
        WatchSticker("party", "🎉", "撒花", WatchStickerMotion.Orbit, WatchStickerCategory.Cheer),
        WatchSticker("sparkle", "✨", "闪闪", WatchStickerMotion.Pulse, WatchStickerCategory.Cheer),
        WatchSticker("cheers", "🍻", "干杯", WatchStickerMotion.Swing, WatchStickerCategory.Cheer),
        WatchSticker("ok", "👌", "可以", WatchStickerMotion.Pop, WatchStickerCategory.Cheer),
        WatchSticker("victory", "✌️", "耶", WatchStickerMotion.Bounce, WatchStickerCategory.Cheer),
        WatchSticker("hundred", "💯", "满分", WatchStickerMotion.Pop, WatchStickerCategory.Cheer),
        WatchSticker("handheart", "🫶", "比心", WatchStickerMotion.Heartbeat, WatchStickerCategory.Cheer),
        WatchSticker("fist", "👊", "冲", WatchStickerMotion.Pop, WatchStickerCategory.Cheer),

        // ---------------------------------------------------------------- movie night
        WatchSticker("fire", "🔥", "燃", WatchStickerMotion.Wobble, WatchStickerCategory.Movie),
        WatchSticker("popcorn", "🍿", "爆米花", WatchStickerMotion.Jelly, WatchStickerCategory.Movie),
        WatchSticker("star", "⭐", "星星", WatchStickerMotion.Spin, WatchStickerCategory.Movie),
        WatchSticker("rocket", "🚀", "起飞", WatchStickerMotion.Bounce, WatchStickerCategory.Movie),
        WatchSticker("bomb", "💣", "炸了", WatchStickerMotion.Shake, WatchStickerCategory.Movie),
        WatchSticker("cinema", "🎬", "开场", WatchStickerMotion.Flip, WatchStickerCategory.Movie),
        WatchSticker("tv", "📺", "追剧", WatchStickerMotion.Pop, WatchStickerCategory.Movie),
        WatchSticker("music", "🎵", "音乐", WatchStickerMotion.Float, WatchStickerCategory.Movie),
        WatchSticker("sleepmovie", "🥱", "看困了", WatchStickerMotion.Swing, WatchStickerCategory.Movie),
        WatchSticker("rewind", "⏪", "倒回去", WatchStickerMotion.Sway, WatchStickerCategory.Movie),
        WatchSticker("fast", "⏩", "快进", WatchStickerMotion.Sway, WatchStickerCategory.Movie),
        WatchSticker("award", "🏆", "神作", WatchStickerMotion.Pop, WatchStickerCategory.Movie),

        // ---------------------------------------------------------------- fun
        WatchSticker("spin", "🌀", "晕", WatchStickerMotion.Spin, WatchStickerCategory.Fun),
        WatchSticker("snow", "❄️", "冷", WatchStickerMotion.Spin, WatchStickerCategory.Fun),
        WatchSticker("cat", "🐱", "猫", WatchStickerMotion.Bounce, WatchStickerCategory.Fun),
        WatchSticker("ghost", "👻", "鬼", WatchStickerMotion.Float, WatchStickerCategory.Fun),
        WatchSticker("dog", "🐶", "狗狗", WatchStickerMotion.Bounce, WatchStickerCategory.Fun),
        WatchSticker("monkey", "🙈", "不敢看", WatchStickerMotion.Swing, WatchStickerCategory.Fun),
        WatchSticker("alien", "👽", "外星人", WatchStickerMotion.Float, WatchStickerCategory.Fun),
        WatchSticker("robot", "🤖", "机器人", WatchStickerMotion.Flip, WatchStickerCategory.Fun),
        WatchSticker("unicorn", "🦄", "独角兽", WatchStickerMotion.Orbit, WatchStickerCategory.Fun),
        WatchSticker("frog", "🐸", "青蛙", WatchStickerMotion.Jelly, WatchStickerCategory.Fun),
        WatchSticker("duck", "🦆", "鸭鸭", WatchStickerMotion.Sway, WatchStickerCategory.Fun),
        WatchSticker("poop", "💩", "离谱", WatchStickerMotion.Wobble, WatchStickerCategory.Fun),
        WatchSticker("magic", "🪄", "魔法", WatchStickerMotion.Orbit, WatchStickerCategory.Fun),
    )

    private val index: Map<String, WatchSticker> = presets.associateBy(WatchSticker::id)
    private val grouped: Map<WatchStickerCategory, List<WatchSticker>> = presets.groupBy(WatchSticker::category)

    private const val PREFIX = "[sticker:"
    private const val SUFFIX = "]"

    fun byId(id: String): WatchSticker? = index[id]

    fun inCategory(category: WatchStickerCategory): List<WatchSticker> = grouped[category].orEmpty()

    /** What goes on the wire, and into the transcript, for [sticker]. */
    fun token(sticker: WatchSticker): String = "$PREFIX${sticker.id}$SUFFIX"

    /** The sticker a chat message *is*, or null for ordinary text / an unknown id. */
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

/** Compact preview text for transcript previews / notifications. */
val WatchChatMessage.oneLineText: String
    get() = sticker?.let { "${it.glyph} ${it.label}" } ?: text
