from pathlib import Path

root = Path(__file__).resolve().parents[1]

# Remove the duplicate EpisodeCoordinate introduced while extracting SeriesCatalog, then keep
# the retry constants that are also shared by the existing episode/season transient retry path.
path = root / "composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailStore.kt"
text = path.read_text(encoding="utf-8")
block = '''private data class EpisodeCoordinate(
    val seasonNumber: Int?,
    val episodeNumber: Int?,
)

'''
first = text.find(block)
second = text.find(block, first + len(block)) if first >= 0 else -1
if second >= 0:
    text = text[:second] + text[second + len(block):]
if 'private const val SOURCE_SELECTION_MAX_ATTEMPTS' not in text:
    anchor = 'private const val SOURCE_SELECTION_TIMEOUT_MS = 45_000L\n'
    if anchor not in text:
        raise SystemExit("detail retry constant anchor missing")
    text = text.replace(
        anchor,
        'private const val SOURCE_SELECTION_MAX_ATTEMPTS = 3\n'
        'private const val SOURCE_SELECTION_RETRY_BASE_DELAY_MS = 250L\n' + anchor,
        1,
    )
path.write_text(text, encoding="utf-8")

# SourceInfo is a technical comparison object rather than a MediaVersion; the unified panel
# labels the source by server here and lets the version rows below state the actual quality.
path = root / "composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailPlaybackPanel.kt"
text = path.read_text(encoding="utf-8")
text = text.replace(
    'label = listOfNotNull(source.serverName, source.source?.let { it.qualityLabel }).joinToString(" · "),',
    'label = source.serverName,',
)
path.write_text(text, encoding="utf-8")

# Search filters are navigation chips; give the dialog list a scroll container so a large
# server/library facet stays usable on compact landscape devices.
path = root / "composeApp/src/commonMain/kotlin/com/yfuse/feature/search/SearchFilters.kt"
text = path.read_text(encoding="utf-8")
if 'import androidx.compose.foundation.rememberScrollState' not in text:
    text = text.replace(
        'import androidx.compose.foundation.lazy.items\n',
        'import androidx.compose.foundation.lazy.items\nimport androidx.compose.foundation.rememberScrollState\nimport androidx.compose.foundation.verticalScroll\n',
    )
text = text.replace(
    'private fun optionList(values: List<SearchOption>, selected: String, onSelect: (String) -> Unit) {\n    Column {',
    'private fun optionList(values: List<SearchOption>, selected: String, onSelect: (String) -> Unit) {\n    Column(Modifier.verticalScroll(rememberScrollState())) {',
)
path.write_text(text, encoding="utf-8")

# The countdown window is consumed by PlayerControls while the drawing function lives in the
# extracted file, so it is package-visible. The new Canvas file also needs fillMaxSize.
path = root / "composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerNextUp.kt"
text = path.read_text(encoding="utf-8")
if 'import androidx.compose.foundation.layout.fillMaxSize\n' not in text:
    text = text.replace(
        'import androidx.compose.foundation.layout.Column\n',
        'import androidx.compose.foundation.layout.Column\nimport androidx.compose.foundation.layout.fillMaxSize\n',
        1,
    )
text = text.replace('private const val NEXT_UP_WINDOW_MS = 10_000L', 'internal const val NEXT_UP_WINDOW_MS = 10_000L')
path.write_text(text, encoding="utf-8")

print("phase2a fixup applied")
