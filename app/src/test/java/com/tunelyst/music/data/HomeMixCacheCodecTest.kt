package com.tunelyst.music.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMixCacheCodecTest {
    @Test
    fun schemaFourRoundTripsBestOfArtistShelves() {
        val snapshot = HomeMixCacheSnapshot(
            catalogSavedAtMs = 1234L,
            genresLoaded = true,
            rotationDay = 456L,
            favoritesFingerprint = 1L,
            recentlyPlayedFingerprint = 2L,
            hiddenSongsFingerprint = 3L,
            playCountsFingerprint = 4L,
            lessLikeArtistsFingerprint = 5L,
            lessLikeGenresFingerprint = 6L,
            recentlyAddedSongIds = listOf(10L, 11L),
            mixes = listOf(CachedHomeMix("Made for you", "A mix", listOf(10L, 12L))),
            bestOfArtists = listOf(CachedHomeMix("Best of Aria", "Top tracks", listOf(11L, 13L)))
        )

        val restored = readSnapshot(writeSnapshot(snapshot))

        assertEquals(snapshot, restored)
        assertTrue(restored.hasBestOfArtists)
    }

    @Test
    fun schemaThreeRemainsReadableAndIsMarkedForRefresh() {
        val restored = readSnapshot(writeSchemaThreeSnapshot())

        assertEquals(1234L, restored.catalogSavedAtMs)
        assertEquals(listOf(10L, 11L), restored.recentlyAddedSongIds)
        assertEquals(listOf(CachedHomeMix("Made for you", "A mix", listOf(10L, 12L))), restored.mixes)
        assertTrue(restored.bestOfArtists.isEmpty())
        assertFalse(restored.hasBestOfArtists)

        // Rewriting a migrated snapshot preserves that the artist shelf still needs a refresh.
        val rewritten = readSnapshot(writeSnapshot(restored))
        assertFalse(rewritten.hasBestOfArtists)
        assertEquals(restored.mixes, rewritten.mixes)
        assertTrue(rewritten.bestOfArtists.isEmpty())
    }

    private fun writeSnapshot(snapshot: HomeMixCacheSnapshot): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { HomeMixCacheCodec.write(it, snapshot) }
        return bytes.toByteArray()
    }

    private fun readSnapshot(bytes: ByteArray): HomeMixCacheSnapshot =
        DataInputStream(ByteArrayInputStream(bytes)).use(HomeMixCacheCodec::read)

    /** Recreates the v3 layout, which ended after the Made for you shelf list. */
    private fun writeSchemaThreeSnapshot(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(0x4C4D4D58) // LMMX
            output.writeInt(3)
            output.writeLong(1234L)
            output.writeBoolean(true)
            output.writeLong(456L)
            output.writeLong(1L)
            output.writeLong(2L)
            output.writeLong(3L)
            output.writeLong(4L)
            output.writeLong(5L)
            output.writeLong(6L)
            output.writeInt(2)
            output.writeLong(10L)
            output.writeLong(11L)
            output.writeInt(1)
            output.writeUTF("Made for you")
            output.writeUTF("A mix")
            output.writeInt(2)
            output.writeLong(10L)
            output.writeLong(12L)
        }
        return bytes.toByteArray()
    }
}
