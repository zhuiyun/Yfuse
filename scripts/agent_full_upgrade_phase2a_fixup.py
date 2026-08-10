from pathlib import Path

root = Path(__file__).resolve().parents[1]

# Remove the duplicate EpisodeCoordinate introduced while extracting SeriesCatalog.
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
path.write_text(text, encoding="utf-8")

# Search filters are navigation chips; give the dialog list a bounded scroll container so a
# large genre/year facet stays usable on compact landscape devices.
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

print("phase2a fixup applied")
