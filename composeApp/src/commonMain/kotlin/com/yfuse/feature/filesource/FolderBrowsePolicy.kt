package com.yfuse.feature.filesource

import com.yfuse.core.filesource.compareNatural

internal enum class FolderSort(
    val label: String,
) {
    Name("名称"),
    Modified("修改时间"),
    Size("大小"),
}

/** Filtering only changes the view; the original listing still owns subtitle pairing and episodes. */
internal fun browseFolderRows(
    rows: List<FolderRow>,
    query: String,
    sort: FolderSort,
    descending: Boolean,
): List<FolderRow> {
    val words = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    val order = if (descending) -1 else 1
    return rows
        .filter { row -> words.all { row.entry.name.contains(it, ignoreCase = true) } }
        .sortedWith { left, right ->
            val a = left.entry
            val b = right.entry
            when {
                a.directory != b.directory -> if (a.directory) -1 else 1
                sort == FolderSort.Name || a.directory -> compareNatural(a.name, b.name) * order
                else -> {
                    val av = (if (sort == FolderSort.Size) a.sizeBytes else a.modifiedEpochMs)?.takeIf { it >= 0L }
                    val bv = (if (sort == FolderSort.Size) b.sizeBytes else b.modifiedEpochMs)?.takeIf { it >= 0L }
                    when {
                        av == null && bv != null -> 1
                        av != null && bv == null -> -1
                        else -> {
                            val byValue = if (av != null && bv != null) av.compareTo(bv) * order else 0
                            if (byValue != 0) byValue else compareNatural(a.name, b.name)
                        }
                    }
                }
            }
        }
}
