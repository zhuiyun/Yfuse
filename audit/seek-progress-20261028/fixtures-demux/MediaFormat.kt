package android.media

/** Only the metadata surface used by fake extractors; no Android codec is exercised. */
class MediaFormat {
    fun getString(key: String): String? = null
    companion object { const val KEY_MIME = "mime" }
}

class MediaExtractor { companion object { const val SAMPLE_FLAG_SYNC = 1 } }
