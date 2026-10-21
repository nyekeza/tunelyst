package com.tunelyst.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueNextOrderingTest {
    @Test
    fun consecutiveAddsStayInFirstAddedFirstPlayedOrder() {
        val queue = mutableListOf(10L, 20L, 21L)
        val playNext = mutableSetOf<Long>()
        listOf(30L, 31L, 32L).forEach { songId ->
            queue.add(queueNextInsertionIndex(0, queue, playNext), songId)
            playNext += songId
        }
        assertEquals(listOf(10L, 30L, 31L, 32L, 20L, 21L), queue)
    }

    @Test
    fun newPlayNextTracksFollowExistingPlayNextBlockOnly() {
        val queue = listOf(10L, 20L, 30L, 40L)
        val playNext = setOf(20L, 40L)
        assertEquals(2, queueNextInsertionIndex(0, queue, playNext))
    }
}
