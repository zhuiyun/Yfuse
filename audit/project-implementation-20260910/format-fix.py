from pathlib import Path
p=Path('composeApp/src/commonMain/kotlin/com/yfuse/core/data/PlaybackBookmarks.kt');s=p.read_text(encoding='utf-8').replace('            settings.getStringOrNull(KEY)?.let { json.decodeFromString<List<PlaybackBookmark>>(it) }.orEmpty().also { records ->','''            val encoded = settings.getStringOrNull(KEY)
            encoded?.let { json.decodeFromString<List<PlaybackBookmark>>(it) }.orEmpty().also { records ->''');p.write_text(s,encoding='utf-8')
