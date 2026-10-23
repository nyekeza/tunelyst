package com.tunelyst.music.data

internal fun boundedUniqueIds(
    values: List<Long>,
    limit: Int,
    validIds: Set<Long>? = null
): List<Long> {
    if (limit <= 0) return emptyList()
    return values.asSequence()
        .filter { id -> id >= 0L && (validIds == null || id in validIds) }
        .distinct()
        .take(limit)
        .toList()
}

internal fun prependBoundedUniqueId(values: List<Long>, id: Long, limit: Int): List<Long> =
    boundedUniqueIds(listOf(id) + values.filterNot { it == id }, limit)

data class SavedPlaybackSession(
    val songIds: List<Long>,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: Int,
    val smartShuffleEnabled: Boolean = false,
    val preserveQueueOrder: Boolean = false
)
