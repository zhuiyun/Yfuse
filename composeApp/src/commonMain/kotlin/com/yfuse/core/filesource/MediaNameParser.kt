package com.yfuse.core.filesource

/** Whether a file reads as a film or as one episode of a show. */
enum class ParsedMediaKind { Movie, Episode }

/** What a file's name and the folders above it say it is, before anything is looked up. */
data class ParsedMediaName(
    /** Title candidates, most telling first: `流浪地球2.The.Wandering.Earth.II` yields both. */
    val titles: List<String>,
    val year: Int? = null,
    val kind: ParsedMediaKind = ParsedMediaKind.Movie,
    val season: Int? = null,
    val episode: Int? = null,
    /** A `{tmdb-872585}` tag some libraries put in a folder or file name; exact when present. */
    val tmdbId: Int? = null,
) {
    val title: String get() = titles.first()
}

/**
 * Reads [path] — the folders below a share's root, then the file's name — the way a person
 * reading the listing would: the file names the title when it can, and the folders fill in what
 * it leaves out, which for `狂飙/第01集.mp4` is the title itself and for `Breaking Bad (2008)/
 * Season 5/Breaking.Bad.S05E14.mkv` is the year.
 *
 * Null for what is not a title of its own: samples, trailers and extras, and the insides of a
 * Blu-ray or DVD folder, which only play as the disc they belong to.
 */
fun parseMediaPath(path: List<String>): ParsedMediaName? {
    val fileName = path.lastOrNull()?.takeIf(String::isNotBlank) ?: return null
    val folders = path.dropLast(1)
    if (folders.any { it.isDiscStructureFolder() || it.isExtrasFolder() }) return null
    val base = fileBaseName(fileName)
    if (base.isExtraFileName()) return null
    val read = readName(base)
    // Nearest first: the season folder, then the show or film folder, then whatever holds those.
    val above = folders.asReversed().map { it to readName(it) }
    val seasonFolder = above.firstOrNull { (_, facts) -> facts.season != null && facts.titles.isEmpty() }?.second
    val titled = above.firstOrNull { (name, facts) -> facts.titles.isNotEmpty() && !name.isGenericFolder() }?.second
    val file = read.withTrailingEpisode(titled, seasonFolder != null)
    val leading =
        base.leadingEpisodeNumber()?.takeIf {
            file.episode == null &&
                (file.titles.isEmpty() || seasonFolder != null)
        }
    // `01 - Pilot.mkv`: what follows the number is the episode's own name, not the show's.
    val episodeFile = if (leading != null) file.copy(titles = emptyList(), episode = leading) else file
    return if (episodeFile.episode != null) {
        episodeName(episodeFile, titled, seasonFolder)
    } else {
        movieName(file, titled, folders.lastOrNull())
    }
}

private fun episodeName(
    file: NameFacts,
    show: NameFacts?,
    seasonFolder: NameFacts?,
): ParsedMediaName? {
    val titles = (file.titles + show?.titles.orEmpty()).distinctTitles()
    if (titles.isEmpty()) return null
    return ParsedMediaName(
        titles = titles.withCountryVariants(),
        year = file.year ?: show?.year,
        kind = ParsedMediaKind.Episode,
        season = file.season ?: seasonFolder?.season ?: show?.season,
        episode = file.episode,
        tmdbId = file.tmdbId ?: show?.tmdbId,
    )
}

private fun movieName(
    file: NameFacts,
    titledFolder: NameFacts?,
    parentName: String?,
): ParsedMediaName? {
    val fileTitles = file.titles.filterNot(String::isGenericTitle)
    // The parent folder is this film's own when it says so — a year, a TMDB tag, or the same
    // title. A folder of many films (`诺兰作品集`) must not become a guess of its own.
    val parent = parentName?.takeUnless(String::isGenericFolder)?.let(::readName)
    val ownFolder =
        parent?.takeIf { facts ->
            facts.titles.isNotEmpty() &&
                (
                    fileTitles.isEmpty() ||
                        facts.year != null ||
                        facts.tmdbId != null ||
                        facts.titles.any { title -> fileTitles.any { it.sameTitleAs(title) } }
                )
        }
    val titles = (fileTitles + ownFolder?.titles.orEmpty()).distinctTitles()
    if (titles.isEmpty()) {
        val folder = titledFolder?.takeIf { it.titles.isNotEmpty() } ?: return null
        return ParsedMediaName(folder.titles, folder.year, ParsedMediaKind.Movie, tmdbId = folder.tmdbId)
    }
    return ParsedMediaName(
        titles = titles,
        year = file.year ?: ownFolder?.year,
        kind = ParsedMediaKind.Movie,
        tmdbId = file.tmdbId ?: ownFolder?.tmdbId,
    )
}

/**
 * `山海情/山海情 01.mp4`: a number after the show's own name is an episode. Only taken when the
 * folder says so — a matching title above, or a season folder — so `Apollo 13` stays a film.
 */
private fun NameFacts.withTrailingEpisode(
    show: NameFacts?,
    inSeasonFolder: Boolean,
): NameFacts {
    if (episode != null || titles.size != 1) return this
    val match = TRAILING_NUMBER.matchEntire(titles.single()) ?: return this
    val prefix = match.groupValues[1].cleanTitle()
    val named = show?.titles.orEmpty().any { it.sameTitleAs(prefix) }
    if (!named && !inSeasonFolder) return this
    return copy(titles = listOf(prefix), episode = match.groupValues[2].toInt())
}

/** Everything one name — a file's or a folder's — says on its own. */
private data class NameFacts(
    val titles: List<String>,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val tmdbId: Int?,
)

private data class Bracket(
    val content: String,
    val book: Boolean,
) {
    val year: Int? get() =
        YEAR_ONLY
            .matchEntire(content)
            ?.groupValues
            ?.get(1)
            ?.toInt()

    /** `[05]`, `[05v2]`, `[第05话]`, `[EP05]`; not `[1080]` and not `[2021]`. */
    val episode: Int?
        get() =
            BRACKET_EPISODE
                .matchEntire(content)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.takeIf { it < MIN_YEAR && it !in RESOLUTIONS }
                ?: findEpisode(content)?.episode
}

private data class Token(
    val start: Int,
    val value: String,
)

private fun readName(raw: String): NameFacts {
    var text = raw.normalizeWidth()
    val tmdbId =
        TMDB_TAG
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    text = TMDB_TAG.replace(text, " ")
    text = TAG_REWRITES.fold(text) { current, (pattern, replacement) -> pattern.replace(current, replacement) }
    text = NOISE.fold(text) { current, pattern -> pattern.replace(current, " ") }
    val brackets = mutableListOf<Bracket>()
    val free = TRAILING_GROUP.replace(extractBrackets(text, brackets).replace('_', ' ').separateDots(), " ")
    val bracketYear = brackets.firstNotNullOfOrNull(Bracket::year)
    val episodeMark = findEpisode(free)
    val seasonMark = findSeason(free)
    val words = tokens(free)
    // `BD1080P 让子弹飞`: tags ahead of the title are skipped, not taken as its end.
    val textStart = words.firstOrNull { !it.value.isStrongTech() && it.value.junkOffset() != 0 }?.start ?: free.length
    val techStart =
        words
            .filter { it.start > textStart }
            .firstNotNullOfOrNull { word ->
                if (word.value.isStrongTech()) word.start else word.value.junkOffset()?.let { word.start + it }
            } ?: free.length
    val cut = minOf(techStart, episodeMark?.start ?: free.length, seasonMark?.start ?: free.length)
    // `Blade.Runner.2049.2017`: the last year before the tags is the year, the rest the title.
    val titleYear =
        if (bracketYear != null) {
            null
        } else {
            words.lastOrNull { it.start in (textStart + 1) until cut && YEAR.matches(it.value) }
        }
    val laterYear = words.firstOrNull { it.start >= cut && YEAR.matches(it.value) }?.value?.toInt()
    val titleEnd = titleYear?.start ?: cut
    val titleText = if (textStart < titleEnd) free.substring(textStart, titleEnd) else ""
    val year = bracketYear ?: titleYear?.value?.toInt() ?: laterYear
    val episodeBracket = brackets.indexOfFirst { it.episode != null }.takeIf { it >= 0 && episodeMark == null }
    // An edition word is dropped from the end only when a release tag ended the title; before a
    // year it is the title's own (`Charlottes.Web.2006`, `Step.Up.3D.2010`).
    val endedByTag = titleYear == null && cut == techStart && techStart < free.length
    // `【高清】《狂怒》.Fury.2014`: a title in 《》 is named as one, so it leads.
    val book = brackets.firstOrNull { it.book }?.let { titleCandidates(it.content, stripSoftTags = false) }.orEmpty()
    val titles =
        (book + titleCandidates(titleText, stripSoftTags = endedByTag))
            // A bare `01` is an episode number, not a title — unless a year makes it `300 (2006)`.
            .filterNot { title -> year == null && title.length <= MAX_EPISODE_DIGITS && title.all(Char::isDigit) }
            .distinctTitles()
            .ifEmpty { bracketTitles(brackets, episodeBracket) }
    return NameFacts(
        titles = titles,
        year = year,
        season =
            episodeMark?.season ?: seasonMark?.season
                ?: brackets.firstNotNullOfOrNull { findSeason(it.content)?.season },
        episode = episodeMark?.episode ?: episodeBracket?.let { brackets[it].episode },
        tmdbId = tmdbId,
    )
}

/** Pulls every bracketed run out of [text] into [into], leaving a space where each stood. */
private fun extractBrackets(
    text: String,
    into: MutableList<Bracket>,
): String {
    val out = StringBuilder()
    var index = 0
    while (index < text.length) {
        val close = BRACKETS[text[index]]
        val end = if (close != null) text.indexOf(close, index + 1) else -1
        if (close != null && end > index) {
            into += Bracket(text.substring(index + 1, end).trim(), book = text[index] == '《')
            out.append(' ')
            index = end + 1
        } else {
            out.append(text[index])
            index++
        }
    }
    return out.toString()
}

/**
 * The title when nothing is left outside the brackets — the fansub form
 * `[Group][Title][05][1080p]`: the run of brackets just before the episode number
 * (`[完美世界][Perfect World][2021][151]` gives both), or, with no number, the first bracket that
 * is not the release group.
 */
private fun bracketTitles(
    brackets: List<Bracket>,
    episodeBracket: Int?,
): List<String> {
    val beforeNumber =
        episodeBracket
            ?.let { index -> brackets.subList(0, index).asReversed() }
            ?.dropWhile { it.year != null }
            ?.takeWhile { it.year != null || it.content.isTitleLike() }
            ?.filter { it.year == null }
            ?.asReversed()
            .orEmpty()
    val chosen =
        beforeNumber.ifEmpty {
            val titled = brackets.filter { it.content.isTitleLike() }
            listOfNotNull(titled.firstOrNull())
        }
    // `[Yuru Camp Season 2]`: the season is read separately, so the title stops where it starts.
    return chosen
        .map { bracket ->
            val content = bracket.content
            val end =
                listOfNotNull(
                    findSeason(content)?.start,
                    findEpisode(content)?.start,
                ).filter { it > 0 }.minOrNull()
            if (end != null) content.substring(0, end) else content
        }.flatMap { titleCandidates(it, stripSoftTags = false) }
        .distinctTitles()
}

private data class Mark(
    val start: Int,
    val season: Int?,
    val episode: Int?,
)

private fun findEpisode(text: String): Mark? =
    EPISODE_PATTERNS
        .mapNotNull { (pattern, read) ->
            pattern.findAll(text).firstNotNullOfOrNull { match ->
                val (season, episode) = read(match)
                episode?.let { Mark(match.range.first, season, it) }
            }
        }.minByOrNull { it.start }

private fun findSeason(text: String): Mark? =
    SEASON_PATTERNS
        .mapNotNull { (pattern, read) ->
            pattern.find(text)?.let { match -> read(match)?.let { Mark(match.range.first, it, null) } }
        }.minByOrNull { it.start }

/**
 * The title part of a name as its candidates. A name that carries a Chinese title and a Latin
 * one side by side — `流浪地球2 The Wandering Earth II`, `繁花 Blossoms Shanghai` — gives each
 * on its own, in the order written; `X战警` and `F1` stay whole, since a two-letter run is part
 * of the title rather than a second one.
 */
private fun titleCandidates(
    text: String,
    stripSoftTags: Boolean,
): List<String> {
    val cleaned = text.replace('_', ' ').cleanTitle()
    if (cleaned.isEmpty()) return emptyList()
    return cleaned
        .split(ALTERNATIVE_SEPARATOR)
        .map { it.cleanTitle().let { title -> if (stripSoftTags) title.withoutTrailingSoftTags() else title } }
        .filter(String::isNotEmpty)
        .flatMap(::splitScripts)
        .distinctTitles()
        .filter { it.any(Char::isLetterOrDigit) }
}

private fun splitScripts(text: String): List<String> {
    val groups = mutableListOf<Pair<Boolean, MutableList<Token>>>()
    tokens(text).forEach { token ->
        val cjk = token.value.any { it.isCjk() }
        val latin = token.value.any { it.isLatinLetter() }
        val last = groups.lastOrNull()
        when {
            last == null -> groups += cjk to mutableListOf(token)
            // Digits and signs go with what they follow: `唐人街探案3`, `Detective Chinatown 3`.
            !cjk && !latin -> last.second += token
            last.first == cjk -> last.second += token
            else -> groups += cjk to mutableListOf(token)
        }
    }
    val latinLetters =
        groups.filter { !it.first }.sumOf { (_, group) ->
            group.sumOf { token -> token.value.count { it.isLatinLetter() } }
        }
    if (groups.size < 2 || groups.none { it.first } || latinLetters < MIN_LATIN_TITLE_LETTERS) return listOf(text)
    // Cut from the text itself rather than rejoined, so `Spider-Man` keeps its hyphen.
    return groups
        .map { (_, group) ->
            text.substring(group.first().start, group.last().let { it.start + it.value.length }).cleanTitle()
        }.filter(String::isNotEmpty)
}

/** Words with their offsets: split at spaces, hyphens, `+`, `&` and `_`, and where Latin meets CJK. */
private fun tokens(text: String): List<Token> {
    val out = mutableListOf<Token>()
    var start = -1
    for (index in 0..text.length) {
        val char = text.getOrNull(index)
        val separator = char == null || char.isWhitespace() || char in TOKEN_SEPARATORS
        val boundary = !separator && start >= 0 && char != null && scriptBoundary(text[index - 1], char)
        if ((separator || boundary) && start >= 0) {
            out += Token(start, text.substring(start, index))
            start = -1
        }
        if (!separator && start < 0) start = index
    }
    return out
}

private fun scriptBoundary(
    previous: Char,
    next: Char,
): Boolean = (previous.isCjk() && next.isLatinLetter()) || (previous.isLatinLetter() && next.isCjk())

/** `The Office US` also as `The Office`: TMDB names that show without its country. */
private fun List<String>.withCountryVariants(): List<String> =
    flatMap { title ->
        val stripped = COUNTRY_SUFFIX.replace(title, "").trim()
        if (stripped != title && stripped.isNotEmpty()) listOf(title, stripped) else listOf(title)
    }.distinctTitles()

private fun List<String>.distinctTitles(): List<String> =
    fold(mutableListOf()) { kept, title ->
        if (title.isNotBlank() && kept.none { it.sameTitleAs(title) }) kept += title
        kept
    }

/** Case, width, spacing and punctuation aside, the same title. */
fun String.sameTitleAs(other: String): Boolean {
    val normalized = normalizedTitle()
    return normalized.isNotEmpty() && normalized == other.normalizedTitle()
}

/** Letters and digits only, lower-cased: the form titles are compared in. */
fun String.normalizedTitle(): String =
    normalizeWidth()
        .lowercase()
        .filter(Char::isLetterOrDigit)

private fun String.cleanTitle(): String =
    replace(SPACES, " ")
        .trim { it.isWhitespace() || it in TITLE_EDGE }
        .replace(TRAILING_DASH, "")
        .trim()

/** `Movie EXTENDED` → `Movie`: an edition word ends a title only when nothing follows it. */
private fun String.withoutTrailingSoftTags(): String {
    var words = split(' ')
    while (words.size > 1 && words.last().lowercase() in SOFT_TECH_WORDS) words = words.dropLast(1)
    return words.joinToString(" ")
}

/**
 * Dots become spaces, except in a one-digit decimal such as `5.1` or `2.0`: `Mission.Impossible.
 * 7.2023` has to come apart at the `7.`, and `DDP5.1` has already been joined by then.
 */
private fun String.separateDots(): String =
    buildString(length) {
        val text = this@separateDots
        text.forEachIndexed { index, char ->
            val decimal =
                char == '.' &&
                    text.getOrNull(index - 1)?.isDigit() == true &&
                    text.getOrNull(index - 2)?.isDigit() != true &&
                    text.getOrNull(index + 1)?.isDigit() == true &&
                    text.getOrNull(index + 2)?.isDigit() != true
            append(if ((char == '.' && !decimal) || char == '。' || char == '、') ' ' else char)
        }
    }

/** `01.mp4`, `01 - Pilot.mkv`: a name that opens on a bare episode number. */
private fun String.leadingEpisodeNumber(): Int? =
    LEADING_NUMBER
        .find(normalizeWidth().trim())
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

private fun String.isStrongTech(): Boolean {
    val word = lowercase().trim('.', '-')
    return word in TECH_WORDS || TECH_PATTERNS.any { it.matches(word) }
}

/** Where a Chinese release tag starts inside [this] — `国语中字`, `蓝光`, `未删减` — or null. */
private fun String.junkOffset(): Int? = CJK_JUNK.mapNotNull { indexOf(it).takeIf { index -> index >= 0 } }.minOrNull()

private fun String.isTitleLike(): Boolean {
    if (!any { it.isLetter() }) return false
    val words = tokens(this).map { it.value }
    val tagsOnly =
        words.all { word ->
            word.isStrongTech() ||
                word.lowercase() in SOFT_TECH_WORDS ||
                word.junkOffset() == 0 ||
                !word.any(Char::isLetter)
        }
    return words.isNotEmpty() && !tagsOnly && !isGroupLike()
}

/** `[SweetSub]`, `[Lilith-Raws]`, `【喵萌奶茶屋】`: who released it, not what it is. */
private fun String.isGroupLike(): Boolean {
    if (GROUP_MARKS.any { it in this }) return true
    return tokens(lowercase()).any { (_, word) ->
        word in GROUP_NAMES ||
            word.endsWith("sub") ||
            word.endsWith("subs") ||
            word.startsWith("subs") ||
            word.endsWith("raws")
    }
}

private fun String.isGenericTitle(): Boolean {
    val normalized = normalizedTitle()
    return normalized in GENERIC_TITLES || GENERIC_TITLE.matches(normalized)
}

private fun String.isGenericFolder(): Boolean {
    val normalized = normalizedTitle()
    return normalized.isEmpty() || normalized in GENERIC_FOLDERS || isGenericTitle()
}

private fun String.isDiscStructureFolder(): Boolean = lowercase() in DISC_FOLDERS

private fun String.isExtrasFolder(): Boolean = normalizedTitle() in EXTRAS_FOLDERS

private fun String.isExtraFileName(): Boolean {
    val text = normalizeWidth()
    return EXTRA_NAME.matches(text.trim()) || EXTRA_TAG.containsMatchIn(text) || EXTRA_CJK.containsMatchIn(text)
}

/** Full-width forms to ASCII and the ideographic space to a space, so `Ｓ０１Ｅ０２` reads as it looks. */
fun String.normalizeWidth(): String =
    buildString(length) {
        this@normalizeWidth.forEach { char ->
            append(
                when (char) {
                    in '\uFF01'..'\uFF5E' -> char - FULL_WIDTH_OFFSET
                    '\u3000' -> ' '
                    else -> char
                },
            )
        }
    }

private fun Char.isCjk(): Boolean =
    this in '\u3400'..'\u9FFF' ||
        this in '\uF900'..'\uFAFF' ||
        this in '\u3040'..'\u30FF' ||
        this in '\uAC00'..'\uD7AF' ||
        this == '\u3007'

private fun Char.isLatinLetter(): Boolean = isLetter() && !isCjk()

/** `12`, `十二`, `二十三`, `一百零五`, `两`. */
internal fun parseCountNumber(text: String): Int? {
    text.toIntOrNull()?.let { return it }
    if (text.isEmpty() || text.any { it !in CHINESE_DIGITS && it !in CHINESE_UNITS }) return null
    var total = 0
    var digit = 0
    text.forEach { char ->
        val value = CHINESE_DIGITS[char]
        if (value != null) {
            digit = value
        } else {
            total += (if (digit == 0) 1 else digit) * CHINESE_UNITS.getValue(char)
            digit = 0
        }
    }
    return (total + digit).takeIf { it > 0 }
}

private val CHINESE_DIGITS =
    mapOf(
        '零' to 0,
        '〇' to 0,
        '一' to 1,
        '二' to 2,
        '两' to 2,
        '三' to 3,
        '四' to 4,
        '五' to 5,
        '六' to 6,
        '七' to 7,
        '八' to 8,
        '九' to 9,
    )
private val CHINESE_UNITS = mapOf('十' to 10, '百' to 100, '千' to 1_000)
private const val CHINESE_COUNT = "[0-9]{1,4}|[零〇一二两三四五六七八九十百千]{1,6}"

private val EPISODE_PATTERNS: List<Pair<Regex, (MatchResult) -> Pair<Int?, Int?>>> =
    listOf(
        Regex("(?i)(?<![a-z0-9])s(\\d{1,2})\\s?[-. ]?\\s?e(\\d{1,4})(?![0-9])") to { match ->
            match.groupValues[1].toInt() to match.groupValues[2].toInt()
        },
        Regex("(?i)(?<![a-z0-9])(\\d{1,2})x(\\d{2,3})(?![0-9a-z])") to { match ->
            match.groupValues[1].toInt() to match.groupValues[2].toInt()
        },
        Regex("第\\s*($CHINESE_COUNT)\\s*[集话話回]") to { match -> null to parseCountNumber(match.groupValues[1]) },
        Regex("(?<![全共0-9])(\\d{1,4})\\s*[集话話](?![a-zA-Z])") to { match -> null to match.groupValues[1].toInt() },
        // `EP 01`, `Ep.5` and `E01` — but not `WALL-E 2008`, where the E ends a title.
        Regex("(?i)(?<![a-z0-9])ep\\s?\\.?\\s?(\\d{1,4})(?![0-9a-z])") to { match ->
            null to match.groupValues[1].toInt().takeIf { it < MIN_YEAR }
        },
        Regex("(?i)(?<![a-z0-9])e(\\d{2,4})(?![0-9a-z])") to { match ->
            null to match.groupValues[1].toInt().takeIf { it < MIN_YEAR }
        },
        Regex("(?<=\\s)-\\s?(\\d{1,4})(?:v\\d)?(?=\\s|$)") to { match ->
            null to match.groupValues[1].toInt().takeIf { it < MIN_YEAR }
        },
    )

private val SEASON_PATTERNS: List<Pair<Regex, (MatchResult) -> Int?>> =
    listOf(
        Regex("(?i)(?<![a-z0-9])season\\s?(\\d{1,2})(?![0-9])") to { match -> match.groupValues[1].toInt() },
        Regex("第\\s*($CHINESE_COUNT)\\s*季") to { match -> parseCountNumber(match.groupValues[1]) },
        Regex("(?i)(?<![a-z0-9])s(\\d{1,2})(?![0-9a-z])") to { match -> match.groupValues[1].toInt() },
        Regex("(?i)(?<![a-z0-9])(\\d{1,2})(?:st|nd|rd|th)\\s?season") to { match -> match.groupValues[1].toInt() },
        Regex("(?i)^\\s*(?:specials?|sps?)\\s*$|特别篇") to { 0 },
    )

private val TMDB_TAG = Regex("(?i)[\\[{(]\\s*tmdb(?:id)?\\s*[-=:]\\s*(\\d{1,9})\\s*[\\]})]")

/** Tags written across a separator are joined first, so `WEB-DL` is one word and not `WEB`, `DL`. */
private val TAG_REWRITES: List<Pair<Regex, String>> =
    listOf(
        Regex("(?i)web[-. ]?dl") to "WEBDL",
        Regex("(?i)web[-. ]?rip") to "WEBRip",
        Regex("(?i)blu[-. ]?ray") to "BluRay",
        Regex("(?i)(?<![a-z0-9])([hx])[. ]?(26[45])") to "$1$2",
        Regex("(?i)dts[-. ]?hd(?:[-. ]?ma)?") to "DTSHD",
        Regex("(?i)(?<![a-z])dts[-. ]x(?![a-z])") to "DTSX",
        Regex("(?i)(?<![a-z])e[-. ]?ac[-. ]?3") to "EAC3",
        Regex("(?i)(?<![a-z])ac[-. ]3") to "AC3",
        Regex("(?i)dd(?:p|\\+)a?\\s?(\\d)[. ](\\d)") to "DDP$1$2",
        Regex("(?i)(?<![a-z])(aac|dd|flac|truehd|lpcm|opus)\\s?(\\d)[. ](\\d)") to "$1$2$3",
        Regex("(?i)hdr10\\+") to "HDR10P",
        Regex("(?i)dolby[-. ]?vision") to "DolbyVision",
        Regex("(?i)director'?s[-. ]cut") to "DirectorsCut",
        Regex("(?i)(?<![a-z0-9])(\\d{1,2})[-. ]bit(?![a-z])") to "$1bit",
        Regex("(?i)open[-. ]matte") to "OpenMatte",
    )

/** Decorations that are never part of a title: `★10月新番★`, domains, the sites that stamp them. */
private val NOISE: List<Regex> =
    listOf(
        Regex("★[^★]*★"),
        Regex("[★☆♪◆◇■□●○]"),
        Regex("(\\d{4}年)?(\\d{1,2}月)?新番"),
        // Only what is plainly an address — `www.`, a scheme, or a digit in the name as in
        // `ygdy8.com` — since `La.La.Land`, `Call.Me.by.Your.Name` and `The.Breakfast.Club` are not.
        Regex(
            "(?i)(?:(?:https?://)?www\\.[a-z0-9-]{1,40}(?:\\.[a-z0-9-]{1,40})*|https?://[a-z0-9-]{1,40}" +
                "(?:\\.[a-z0-9-]{1,40})*|(?<![a-z0-9])[a-z0-9-]{0,40}\\d[a-z0-9-]{0,40})" +
                "\\.(?:com|net|org|cc|cn|tv|me|xyz|top|vip|la|io|info|biz|co|ws|club|live|app)(?![a-z0-9])@?",
        ),
        Regex(
            "阳光电影|电影天堂|高清影视之家|影视之家|人人影视|飘花电影|飘花资源网|迅雷下载|BT天堂|6v电影|龙部落|" +
                "高清MP4吧|圣城家园|片源网|大师兄影视|吾爱影视|天天美剧|美剧天堂|最新电影|电影港|BD影视分享|影视分享",
        ),
    )

private val BRACKETS = mapOf('[' to ']', '【' to '】', '(' to ')', '{' to '}', '《' to '》', '「' to '」', '〔' to '〕')
private val TOKEN_SEPARATORS = setOf('-', '+', '&', '_')
private val SPACES = Regex("\\s+")
private val TITLE_EDGE =
    setOf('-', ':', '·', '•', ',', '|', '~', '+', '&', '/', '_', '，', '：', '。', '、', '!', '?', '#', '=', '\'', '.')
private val TRAILING_DASH = Regex("\\s[-–—]\\s?$")
private val TRAILING_GROUP = Regex("\\s-(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]+\\s*$")
private val ALTERNATIVE_SEPARATOR = Regex("\\s[/|]\\s|\\s?/\\s?")
private val YEAR = Regex("19[0-9]{2}|20[0-4][0-9]")
private val YEAR_ONLY = Regex("(19[0-9]{2}|20[0-4][0-9])(?:\\s?-\\s?(?:19|20)[0-9]{2})?")
private val BRACKET_EPISODE = Regex("(?i)(?:ep?)?(\\d{1,4})(?:v\\d)?(?:end|完)?")
private val LEADING_NUMBER = Regex("^(\\d{1,3})(?:$|\\s*[-._ ]\\s*(?![0-9]))")
private val TRAILING_NUMBER = Regex("(?i)(.*\\S)\\s+(?:ep?)?(\\d{1,3})")
private val COUNTRY_SUFFIX = Regex("(?i)\\s(us|uk|au|nz|ca)$")

/** `title_t00`, `00001`, `VTS_01_1`: a ripper's or a disc's file name. `1917` and `2012` are films. */
private val GENERIC_TITLE =
    Regex("title_?t?\\d+|t\\d{2}|0\\d{2,5}|\\d{5,6}|vts\\d+|clip\\d*|track\\d+|video\\d*|movie\\d*")
private val EXTRA_NAME = Regex("(?i)sample|trailer|teaser|featurette|menu|preview")
private val EXTRA_TAG =
    Regex(
        "(?i)[\\s._\\-\\[(](sample|trailer|teaser|featurette|ncop\\d*|nced\\d*|menu|preview|" +
            "behind[\\s._-]the[\\s._-]scenes|deleted[\\s._-]scenes?|making[\\s._-]of)(?:$|[\\s._\\-\\])])",
    )
private val EXTRA_CJK = Regex("花絮|预告|片花|幕后")

private const val MIN_LATIN_TITLE_LETTERS = 3
private const val MAX_EPISODE_DIGITS = 3
private const val MIN_YEAR = 1900
private const val FULL_WIDTH_OFFSET = 0xFEE0
private val RESOLUTIONS = setOf(480, 540, 576, 720, 1080, 1440, 2160)
private val DISC_FOLDERS = setOf("bdmv", "video_ts", "audio_ts", "certificate", "hvdvd_ts")

/** Release tags that end a title wherever they appear after its first word. */
private val TECH_WORDS =
    words(
        """
        2160p 1080p 1080i 720p 576p 540p 480p 1440p 4320p 4k 8k uhd fhd hd 2k hq hdr hdr10 hdr10p hlg sdr dv dovi
        dolbyvision hfr hsbs sbs hou bluray bdrip brrip bdremux remux bd bdmv webdl webrip hdtv hdtvrip pdtv dvdrip dvd5
        dvd9 dvdscr hdrip tvrip hdcam hdts telesync telecine screener amzn nf dsnp hmax atvp pcok pmtp itunes iqiyi youku
        wetv viu bilibili baha mytvsuper abema uhdbd hddvd x264 x265 h264 h265 hevc avc av1 vp9 xvid divx hi10p ma10p
        mpeg2 vc1 aac ac3 eac3 ddp ddpa dts dtshd dtsx truehd atmos flac lpcm pcm mp3 opus 2ch 6ch 8ch chs cht chi chn gb
        big5 eng jpn jp kor multisub vostfr cd1 cd2 cd3 disc1 disc2 disk1 disk2 part1 part2 mkv mp4 avi 10bit 8bit 12bit
        60fps 120fps 50fps
        """,
    )

/**
 * Tags that are also ordinary words — `Uncut Gems`, `Charlotte's Web`, `Step Up 3D`. They never
 * end a title; they are only dropped from its end, `Movie EXTENDED` → `Movie`.
 */
private val SOFT_TECH_WORDS =
    words(
        """
        extended unrated uncut remastered directorscut theatrical imax proper repack internal limited openmatte complete
        dual multi dubbed subbed korean japanese chinese french german spanish italian russian hindi thai nordic mandarin
        cantonese criterion web cam 3d sd dd dvd hulu subs
        """,
    )

private val TECH_PATTERNS =
    listOf(
        Regex("(bd|hd|web|dvd|uhd)\\d{3,4}[pi]"),
        Regex("\\d{3,4}x\\d{3,4}"),
        Regex("(dd|ddp|ddpa|aac|dts|ac3|eac3|truehd|flac|lpcm|opus)\\d{1,2}"),
        Regex("\\d{1,3}(fps|bit|audio|audios)"),
        Regex("\\d+(\\.\\d+)?(gb|mb)"),
        Regex("(cd|disc|disk)\\d"),
    )

/** Chinese release tags, found anywhere inside a run of Han characters. */
private val CJK_JUNK =
    words(
        """
        国语 粤语 国粤 中字 双字 中英 英中 双语 简繁 简体 繁体 简中 繁中 简日 繁日 中日 内封 内嵌 外挂 特效 字幕 高清 超清 蓝光
        原盘 未删减 无删减 完整版 加长版 导演剪辑 剪辑版 修复版 重制版 杜比 视界 全景声 帧率 全集 合集 完结 更新至 无水印 收藏版
        典藏版 珍藏版 国配 台配 日语 韩语 英语 音轨 首发 发布 高码 版本 招募 翻译 中文 官方 精校 无广告 去广告 抢先版 枪版 正式版
        国漫 最终季 最終季
        """,
    )

private val GROUP_MARKS =
    words("字幕组 字幕社 字幕組 汉化 漢化 压制 壓制 喵萌 桜都 诸神 動漫國 动漫国 幻樱 澄空 极影 北宇治 千夏 悠哈璃羽 爱恋 愛戀 天使动漫")

private val GROUP_NAMES =
    words(
        """
        ani vcb dbd studio team raws raw lolihouse nekomoe kissaten kamigami moozzi2 sakurato erai ohys judas dmhy airota
        sumisora haruhana mabors popgo orion snow skymoon lilith subsplease horriblesubs
        """,
    )

private val GENERIC_TITLES = words("movie film video main feature mainfeature index bdmv stream title untitled 新建文件夹")

/** Folders that sort a library rather than name a title; compared in [normalizedTitle] form. */
private val GENERIC_FOLDERS =
    words(
        """
        movies movie films film 电影 影片 tv tvshows tvseries shows series 剧集 电视剧 连续剧 美剧 英剧 日剧 韩剧 国产剧 国剧 港剧
        台剧 泰剧 动漫 动画 番剧 新番 anime animation cartoon cartoons 纪录片 documentary documentaries 综艺 video videos 视频
        media download downloads 下载 complete 4k 1080p 2160p uhd remux bluray 未分类 其他 其它 collection collections 影视
        影视剧 媒体库 阿里云盘 夸克网盘 百度网盘 115 115网盘 天翼云盘 onedrive googledrive pikpak 123云盘 移动云盘 迅雷云盘 mnt
        volume1 share public dav webdav data home chinesetv
        """,
    )

private val EXTRAS_FOLDERS =
    words(
        """
        extras extra featurettes behindthescenes deletedscenes interviews trailers samples sample shorts scenes other
        others sps cds scans fonts menus bonus 花絮 预告片 预告 特典 映像特典
        """,
    )

private fun words(text: String): Set<String> = text.trim().split(SPACES).toSet()
