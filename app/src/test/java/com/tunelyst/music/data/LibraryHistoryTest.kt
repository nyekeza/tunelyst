package com.tunelyst.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryHistoryTest {
    @Test
    fun newSearchMovesToFrontDeduplicatesAndCapsHistory() {
        assertEquals(
            listOf(4L, 1L, 2L),
            prependBoundedUniqueId(values = listOf(1L, 2L, 3L, 2L), id = 4L, limit = 3)
        )
        assertEquals(
            listOf(2L, 1L, 3L),
            prependBoundedUniqueId(values = listOf(1L, 2L, 3L), id = 2L, limit = 3)
        )
    }

    @Test
    fun nonPositiveHistoryLimitProducesNoIds() {
        assertEquals(emptyList<Long>(), prependBoundedUniqueId(listOf(1L), 2L, 0))
    }

    @Test
    fun catalogPruningDropsMissingAndNegativeIdsWithoutChangingNewestFirstOrder() {
        assertEquals(
            listOf(8L, 2L),
            boundedUniqueIds(listOf(8L, -4L, 3L, 2L, 8L), limit = 100, validIds = setOf(2L, 8L))
        )
    }

    @Test
    fun prependingNeverKeepsNegativeIdsFromLegacyHistory() {
        assertEquals(listOf(3L, 1L), prependBoundedUniqueId(listOf(-2L, 1L, 3L), 3L, 5))
    }
}
