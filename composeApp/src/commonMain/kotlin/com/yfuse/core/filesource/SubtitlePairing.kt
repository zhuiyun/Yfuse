package com.yfuse.core.filesource

/** A sidecar found next to a video, before it is fetched. */
data class PairedSubtitle(
    val entry: FileSourceEntry,
    /**
     * What the track picker shows — `简体中文`, `简英双语`, `英语` — or null when the name says
     * nothing about a language. Display names rather than ISO codes, because YCore labels an
     * external track with exactly this string, and `chi` twice in a picker helps nobody choose.
     */
    val language: String?,
    val forced: Boolean,
    /** At most one per video: the track playback starts with. */
    val default: Boolean,
) {
    val codec: String get() = entry.extension
}

/**
 * The subtitles in [folder] that belong to [video].
 *
 * 同名字幕: a subtitle belongs to a video when its name is the video's name with only language
 * or flag tags added — `Dune.2024.mkv` takes `Dune.2024.srt`, `Dune.2024.chs.ass` and
 * `Dune.2024.zh-CN.forced.srt`, but `Dune.2024.Extended.mkv`'s subtitle does not stray to it.
 * When the folder holds a single video every subtitle in it is taken, since a film folder's
 * `简体.srt` belongs to the film whatever it is called.
 *
 * Chinese is preferred as the default — simplified, then bilingual, then traditional — and
 * among equals an exact-name match, then ASS over SRT for its styling. [MAX_PAIRED_SUBTITLES]
 * bounds how many are fetched for one video.
 */
fun pairSubtitles(
    video: FileSourceEntry,
    folder: List<FileSourceEntry>,
): List<PairedSubtitle> {
    val base = fileBaseName(video.name)
    val videoBases = folder.filter { it.isVideo }.map { fileBaseName(it.name) }
    val candidates =
        folder
            .filter { it.isSubtitle && !isHiddenFileSourceName(it.name) }
            .mapNotNull { subtitle ->
                val subtitleBase = fileBaseName(subtitle.name)
                // The most specific video wins: `Dune.2024.Extended.chs.srt` is the Extended cut's,
                // even though it also starts with the theatrical cut's name.
                val owner = videoBases.filter { subtitleBase.belongsToVideo(it) }.maxByOrNull { it.length }
                val tags =
                    when {
                        owner != null && owner.equals(base, ignoreCase = true) -> subtitleBase.substring(base.length)
                        owner == null && videoBases.size <= 1 -> subtitleBase
                        else -> return@mapNotNull null
                    }
                Candidate(subtitle, subtitleTags(tags), exactName = subtitleBase.equals(base, ignoreCase = true))
            }
    val ranked =
        candidates
            .sortedWith(
                compareBy<Candidate> { it.tags.rank }
                    .thenByDescending { it.exactName }
                    .thenBy { if (it.entry.extension == "ass" || it.entry.extension == "ssa") 0 else 1 }
                    .thenComparator { left, right -> compareNatural(left.entry.name, right.entry.name) },
            ).take(MAX_PAIRED_SUBTITLES)
    val defaultEntry =
        ranked
            .firstOrNull { it.tags.rank <= RANK_CHINESE_TRADITIONAL || it.tags.language == null }
            ?.entry
    return ranked
        .map { candidate ->
            PairedSubtitle(
                entry = candidate.entry,
                language = candidate.tags.language,
                forced = candidate.tags.forced,
                default = candidate.entry == defaultEntry,
            )
        }.withDistinctLanguages()
}

/** What the tags after a video's name say about a subtitle. */
internal data class SubtitleTags(
    val language: String?,
    val forced: Boolean,
    val rank: Int,
)

/**
 * Reads `chs`, `zh-CN`, `简体`, `chs&eng`, `eng.forced` and the like. Only whole tokens count, so
 * a title word that happens to contain `en` is not taken for English.
 */
internal fun subtitleTags(tags: String): SubtitleTags {
    val tokens =
        tags
            .lowercase()
            .split('.', '-', '_', ' ', '[', ']', '(', ')', '（', '）', '&', '+', ',', '，', '、', '@')
            .filter(String::isNotEmpty)
    val chinese = tokens.any { it in CHINESE_TOKENS }
    val simplified =
        tokens.any { it in SIMPLIFIED_TOKENS } ||
            chinese &&
            tokens.any { it in SIMPLIFIED_REGION_TOKENS }
    val traditional =
        tokens.any { it in TRADITIONAL_TOKENS } ||
            chinese &&
            tokens.any { it in TRADITIONAL_REGION_TOKENS }
    val combined = tokens.firstNotNullOfOrNull { COMBINED_TOKENS[it] }
    val english = tokens.any { it in ENGLISH_TOKENS }
    val japanese = tokens.any { it in JAPANESE_TOKENS }
    val korean = tokens.any { it in KOREAN_TOKENS }
    val forced = tokens.any { it == "forced" || it == "强制" || it == "forcé" }
    val language =
        when {
            combined != null -> combined
            (simplified || chinese && !traditional) && english -> "简英双语"
            traditional && english -> "繁英双语"
            (simplified || chinese) && japanese -> "简日双语"
            simplified -> "简体中文"
            traditional -> "繁体中文"
            chinese -> "中文"
            english -> "英语"
            japanese -> "日语"
            korean -> "韩语"
            else -> null
        }
    val rank =
        when (language) {
            "简体中文", "简英双语", "简日双语", "中文", "双语" -> RANK_CHINESE_SIMPLIFIED
            "繁体中文", "繁英双语" -> RANK_CHINESE_TRADITIONAL
            null -> RANK_UNKNOWN
            else -> RANK_OTHER
        }
    return SubtitleTags(language, forced, rank)
}

/**
 * A subtitle's name or label marks traditional Chinese: `Film.cht.srt`, `zh-TW`, a picker label
 * such as `繁英双语`, or a server's `Chinese Traditional`.
 */
fun subtitleLabelSuggestsTraditional(label: String?): Boolean {
    if (label.isNullOrBlank()) return false
    return '繁' in label ||
        label.contains("traditional", ignoreCase = true) ||
        subtitleTags(label).rank == RANK_CHINESE_TRADITIONAL
}

private data class Candidate(
    val entry: FileSourceEntry,
    val tags: SubtitleTags,
    val exactName: Boolean,
)

/** `Dune.2024.chs` belongs to `Dune.2024`; `Dune.20245` does not. */
private fun String.belongsToVideo(videoBase: String): Boolean =
    equals(videoBase, ignoreCase = true) ||
        length > videoBase.length &&
        startsWith(videoBase, ignoreCase = true) &&
        this[videoBase.length] in TAG_SEPARATORS

/**
 * YCore names an external track by its language alone, so two `简体中文` files — an SRT and an
 * ASS of the same release — would read the same in the picker. The later ones are told apart by
 * their format, and by a number if even that is shared.
 */
private fun List<PairedSubtitle>.withDistinctLanguages(): List<PairedSubtitle> {
    val seen = mutableMapOf<String, Int>()
    return map { subtitle ->
        val label = subtitle.language ?: return@map subtitle
        val count = (seen[label] ?: 0) + 1
        seen[label] = count
        if (count == 1) return@map subtitle
        val withFormat = "$label（${subtitle.codec.uppercase()}）"
        val formatCount = (seen[withFormat] ?: 0) + 1
        seen[withFormat] = formatCount
        subtitle.copy(language = if (formatCount == 1) withFormat else "$label $count")
    }
}

private const val TAG_SEPARATORS = ".-_ ["
private const val MAX_PAIRED_SUBTITLES = 8
private const val RANK_CHINESE_SIMPLIFIED = 0
private const val RANK_CHINESE_TRADITIONAL = 1
private const val RANK_UNKNOWN = 2
private const val RANK_OTHER = 3

private val CHINESE_TOKENS = setOf("zh", "zho", "chi", "chinese", "中文", "中字", "国语", "中")
private val SIMPLIFIED_TOKENS = setOf("chs", "sc", "gb", "gbk", "hans", "简体", "简中", "简", "简体中文")
private val TRADITIONAL_TOKENS = setOf("cht", "tc", "big5", "hant", "繁体", "繁體", "繁中", "繁", "繁体中文")
private val SIMPLIFIED_REGION_TOKENS = setOf("cn", "sg", "hans")
private val TRADITIONAL_REGION_TOKENS = setOf("tw", "hk", "mo", "hant")
private val ENGLISH_TOKENS = setOf("en", "eng", "english", "英", "英文", "英语")
private val JAPANESE_TOKENS = setOf("ja", "jp", "jpn", "japanese", "日", "日文", "日语")
private val KOREAN_TOKENS = setOf("ko", "kor", "korean", "韩", "韩文", "韩语")
private val COMBINED_TOKENS =
    mapOf(
        "简英" to "简英双语",
        "中英" to "简英双语",
        "简英双语" to "简英双语",
        "繁英" to "繁英双语",
        "简日" to "简日双语",
        "中日" to "简日双语",
        "双语" to "双语",
        "双字" to "双语",
    )
