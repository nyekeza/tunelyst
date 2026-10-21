package com.tunelyst.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCleanupAnalyzerTest {
    @Test
    fun flagsOnlyCloseTitleArtistAndDurationMatchesAsLikelyDuplicates() {
        val analysis = analyzeLibraryCleanupMetadata(
            listOf(
                track(1, "♪ 01 - Midnight City [Official Audio] ♪", "M83", durationMs = 240_000L),
                track(2, "Midnight City", "M83", durationMs = 241_200L),
                track(3, "Midnight City", "M83", durationMs = 250_000L),
                track(4, "Midnight City (Live)", "M83", durationMs = 240_500L)
            )
        )

        assertEquals(1, analysis.likelyDuplicates.size)
        assertEquals(setOf(1L, 2L), analysis.likelyDuplicates.single().songIds.toSet())
        assertTrue(analysis.suggestions.any { it.songId == 1L && it.suggestedTitle == "Midnight City" })
    }

    @Test
    fun suggestsTitleArtistSwapOnlyWhenAnAlbumShowsARepeatedFieldPattern() {
        val analysis = analyzeLibraryCleanupMetadata(
            listOf(
                track(1, "The Weeknd", "Blinding Lights", album = "After Hours", albumArtist = "The Weeknd"),
                track(2, "The Weeknd", "Save Your Tears", album = "After Hours", albumArtist = "The Weeknd"),
                track(3, "The Weeknd", "In Your Eyes", album = "After Hours", albumArtist = "The Weeknd")
            )
        )

        assertEquals(3, analysis.suggestions.size)
        analysis.suggestions.forEach { suggestion ->
            assertEquals(LibraryCleanupSuggestionKind.POSSIBLE_TITLE_ARTIST_SWAP, suggestion.kind)
            assertEquals("The Weeknd", suggestion.suggestedArtist)
            assertEquals(suggestion.originalArtist, suggestion.suggestedTitle)
        }
    }

    @Test
    fun doesNotGuessAFieldSwapFromOneTrackOrGenericCompilationArtist() {
        val single = analyzeLibraryCleanupMetadata(
            listOf(track(1, "The Weeknd", "Blinding Lights", album = "After Hours", albumArtist = "The Weeknd"))
        )
        val compilation = analyzeLibraryCleanupMetadata(
            listOf(
                track(2, "Various Artists", "Song One", album = "Sampler", albumArtist = "Various Artists"),
                track(3, "Various Artists", "Song Two", album = "Sampler", albumArtist = "Various Artists")
            )
        )

        assertTrue(single.suggestions.isEmpty())
        assertTrue(compilation.suggestions.isEmpty())
    }

    @Test
    fun preservesLiveOrRemixVersionNamesWhenComparingDuplicates() {
        val analysis = analyzeLibraryCleanupMetadata(
            listOf(
                track(1, "Song Name", "Artist", durationMs = 180_000L),
                track(2, "Song Name (Live)", "Artist", durationMs = 180_400L),
                track(3, "Song Name - Remix", "Artist", durationMs = 180_500L)
            )
        )

        assertTrue(analysis.likelyDuplicates.isEmpty())
    }

    @Test
    fun duplicateBatchDeletionMustLeaveOneCopyPerGroupAndAtLeastOneDeviceFile() {
        val groups = listOf(
            LikelyDuplicateGroup(listOf(1L, 2L), "Song One", "Artist", "Possible copy"),
            LikelyDuplicateGroup(listOf(3L, 4L), "Song Two", "Artist", "Possible copy")
        )

        assertFalse(canDeleteSelectedDuplicateTracks(groups, setOf(1L, 2L), setOf(1L, 2L)))
        assertFalse(canDeleteSelectedDuplicateTracks(groups, setOf(1L, 3L), emptySet()))
        assertTrue(canDeleteSelectedDuplicateTracks(groups, setOf(1L, 3L), setOf(1L, 3L)))
    }

    @Test
    fun disappearedSnapshotMemberCannotCountAsTheUncheckedCopy() {
        val analyzedGroup = LikelyDuplicateGroup(listOf(1L, 2L, 3L), "Song", "Artist", "Possible copy")
        val currentGroups = resolveCurrentDuplicateGroups(listOf(analyzedGroup), setOf(1L, 2L))

        assertEquals(listOf(1L, 2L), currentGroups.single().songIds)
        assertFalse(canDeleteSelectedDuplicateTracks(currentGroups, setOf(1L, 2L), setOf(1L, 2L)))
        assertTrue(resolveCurrentDuplicateGroups(listOf(analyzedGroup), setOf(1L)).isEmpty())
    }

    private fun track(
        id: Long,
        title: String,
        artist: String,
        album: String = "Album",
        albumArtist: String = artist,
        durationMs: Long = 180_000L
    ) = CleanupTrackMetadata(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        durationMs = durationMs,
        sizeBytes = 4_096L + id
    )
}
