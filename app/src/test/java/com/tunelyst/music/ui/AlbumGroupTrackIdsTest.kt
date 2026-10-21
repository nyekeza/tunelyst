package com.tunelyst.music.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumGroupTrackIdsTest {
    @Test
    fun groupsAlbumArtistTagsIgnoringCaseAndWhitespace() {
        val tracks = listOf(
            track(1, album = "Blue Room", artist = "Singer A", albumArtist = "The Band"),
            track(2, album = "blue room", artist = "Singer B", albumArtist = "  THE   BAND "),
            track(3, album = "Blue Room", artist = "Other Artist", albumArtist = "Other Artist")
        )

        assertEquals(setOf(1L, 2L), albumGroupTrackIds(tracks, songId = 1L))
    }

    @Test
    fun fallsBackToTrackArtistWhenAlbumArtistTagsAreInconsistent() {
        val tracks = listOf(
            track(1, album = "Blue Room", artist = "The Band", albumArtist = "The Band - Remastered"),
            track(2, album = "Blue Room", artist = "The Band", albumArtist = "The Band"),
            track(3, album = "Blue Room", artist = "Guest Artist", albumArtist = "Guest Artist")
        )

        assertEquals(setOf(1L, 2L), albumGroupTrackIds(tracks, songId = 1L))
    }

    @Test
    fun includesAllTracksForACompilation() {
        val tracks = listOf(
            track(1, album = "Summer Collection", artist = "Singer A", albumArtist = "Various Artists", compilation = true),
            track(2, album = "Summer Collection", artist = "Singer B", albumArtist = "Various Artists", compilation = true),
            track(3, album = "Summer Collection", artist = "Singer C", albumArtist = "Various Artists", compilation = true)
        )

        assertEquals(setOf(1L, 2L, 3L), albumGroupTrackIds(tracks, songId = 2L))
    }

    @Test
    fun doesNotMergeAnIdenticallyNamedAlbumByAnotherArtist() {
        val tracks = listOf(
            track(1, album = "Greatest Hits", artist = "Artist A", albumArtist = "Artist A"),
            track(2, album = "Greatest Hits", artist = "Artist B", albumArtist = "Artist B")
        )

        assertEquals(setOf(1L), albumGroupTrackIds(tracks, songId = 1L))
    }

    private fun track(
        id: Long,
        album: String,
        artist: String,
        albumArtist: String,
        compilation: Boolean = false
    ) = AlbumTrackMetadata(id, album, artist, albumArtist, compilation)
}
