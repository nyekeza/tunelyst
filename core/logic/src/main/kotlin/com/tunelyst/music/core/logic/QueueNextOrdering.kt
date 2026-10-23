package com.tunelyst.music.core.logic

/**
 * Returns the insertion point after the current song and any already queued-next tracks.
 * Appending to this contiguous block preserves the order in which tracks were added.
 */
fun queueNextInsertionIndex(
    currentIndex: Int,
    queueSongIds: List<Long>,
    playNextSongIds: Set<Long>
): Int {
    var insertAt = (currentIndex + 1).coerceIn(0, queueSongIds.size)
    while (insertAt < queueSongIds.size && queueSongIds[insertAt] in playNextSongIds) {
        insertAt++
    }
    return insertAt
}
