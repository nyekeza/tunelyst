package com.tunelyst.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedLruCacheTest {
    @Test
    fun evictsLeastRecentlyUsedEntryAndNeverExceedsLimit() {
        val cache = BoundedLruCache<Int, String>(maxEntries = 2)
        cache.put(1, "one")
        cache.put(2, "two")
        assertEquals("one", cache[1]) // Touch key 1; key 2 is now least recently used.
        cache.put(3, "three")

        assertEquals(2, cache.size())
        assertNull(cache[2])
        assertEquals("one", cache[1])
        assertEquals("three", cache[3])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnUnboundedOrEmptyCacheConfiguration() {
        BoundedLruCache<Int, String>(maxEntries = 0)
    }
}
