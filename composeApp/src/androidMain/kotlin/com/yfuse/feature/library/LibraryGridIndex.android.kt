package com.yfuse.feature.library

import android.icu.text.AlphabeticIndex
import java.util.Locale

/**
 * The pinyin initial through ICU's [AlphabeticIndex] (API 24, so on every supported device): built
 * for Simplified Chinese, its buckets are A–Z cut at the Chinese collation's pinyin boundaries — the
 * same buckets the system contact list files names under — and a Latin title lands under its own
 * first letter. Digits, kana and punctuation fall outside the letters and go under #.
 * `Transliterator` would spell the pinyin out, but only from API 29.
 */
internal actual fun nameIndexLetter(title: String): String {
    val start = title.trimStart { !it.isLetterOrDigit() }
    if (start.isEmpty()) return "#"
    val index = PinyinIndex.index ?: return asciiIndexLetter(start)
    return runCatching {
        val bucket = index.getBucket(index.getBucketIndex(start))
        if (bucket.labelType == AlphabeticIndex.Bucket.LabelType.NORMAL) {
            bucket.label.uppercase(Locale.ROOT).take(1)
        } else {
            "#"
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: asciiIndexLetter(start)
}

/** Built once, off the main thread in practice: the index's first user is a background labelling pass. */
private object PinyinIndex {
    val index: AlphabeticIndex.ImmutableIndex<Any>? by lazy {
        runCatching {
            AlphabeticIndex<Any>(Locale.SIMPLIFIED_CHINESE)
                .addLabels(Locale.ENGLISH)
                .buildImmutableIndex()
        }.getOrNull()
    }
}
