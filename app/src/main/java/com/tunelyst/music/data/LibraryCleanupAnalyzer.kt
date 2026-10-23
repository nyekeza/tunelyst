package com.tunelyst.music.data

import java.text.Normalizer
import java.util.Locale
import com.tunelyst.music.core.logic.suggestCleanTrackTitle

/** A reviewable app-metadata correction. No library or device file is changed by analysis. */
enum class LibraryCleanupSuggestionKind {
    TITLE_NOISE,
    POSSIBLE_TITLE_ARTIST_SWAP
}

data class LibraryCleanupSuggestion(
    val songId: Long,
    val originalTitle: String,
    val originalArtist: String,
    val suggestedTitle: String,
    val suggestedArtist: String? = null,
    val kind: LibraryCleanupSuggestionKind,
    val reason: String
)

/** Tracks grouped by a conservative title/artist/duration match; this is a flag, not a delete action. */
data class LikelyDuplicateGroup(
    val songIds: List<Long>,
    val representativeTitle: String,
    val representativeArtist: String,
    val reason: String
)

data class LibraryCleanupAnalysis(
    val suggestions: List<LibraryCleanupSuggestion> = emptyList(),
    val likelyDuplicates: List<LikelyDuplicateGroup> = emptyList()
)

/** Resolve each analysis group against the current catalog before applying deletion safety rules. */
internal fun resolveCurrentDuplicateGroups(
    duplicateGroups: List<LikelyDuplicateGroup>,
    availableSongIds: Set<Long>
): List<LikelyDuplicateGroup> = duplicateGroups.mapNotNull { group ->
    val currentIds = group.songIds.filter { it in availableSongIds }.distinct()
    if (currentIds.size < 2) null else group.copy(songIds = currentIds)
}

/** Allow only deletable selections that leave one unchecked current copy in every flagged group. */
internal fun canDeleteSelectedDuplicateTracks(
    duplicateGroups: List<LikelyDuplicateGroup>,
    selectedSongIds: Set<Long>,
    deletableSongIds: Set<Long>
): Boolean {
    if (selectedSongIds.none { it in deletableSongIds }) return false
    return duplicateGroups.none { group ->
        group.songIds.isNotEmpty() && group.songIds.all { it in selectedSongIds }
    }
}

/** Metadata-only input lets the detector be tested on the JVM without Android Uri implementations. */
data class CleanupTrackMetadata(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val durationMs: Long,
    val fileName: String = "",
    val folderPath: String = "",
    val sizeBytes: Long = 0L
)

/** Analyze a local MediaStore snapshot. This function does not mutate tracks or access the network. */
fun analyzeLibraryCleanup(songs: List<Song>): LibraryCleanupAnalysis =
    analyzeLibraryCleanupMetadata(
        songs.map { song ->
            CleanupTrackMetadata(
                id = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                albumArtist = song.albumArtist,
                durationMs = song.durationMs,
                fileName = song.fileName,
                folderPath = song.folderPath,
                sizeBytes = song.sizeBytes
            )
        }
    )

/** Analyze metadata using conservative, local-only heuristics. Suggested edits always require review. */
fun analyzeLibraryCleanupMetadata(tracks: List<CleanupTrackMetadata>): LibraryCleanupAnalysis {
    val uniqueTracks = tracks.distinctBy(CleanupTrackMetadata::id)
    val swaps = findPossibleTitleArtistSwaps(uniqueTracks)
    val byId = swaps.associateBy(LibraryCleanupSuggestion::songId)
    val suggestions = uniqueTracks.mapNotNull { track ->
        byId[track.id] ?: suggestCleanTrackTitle(track.title, track.artist)?.let { cleanTitle ->
            LibraryCleanupSuggestion(
                songId = track.id,
                originalTitle = track.title,
                originalArtist = track.artist,
                suggestedTitle = cleanTitle,
                kind = LibraryCleanupSuggestionKind.TITLE_NOISE,
                reason = if (hasDecorativeEdgeSymbols(track.title)) {
                    "Removes decorative symbols around the title."
                } else {
                    "Removes likely filename, track-number, artist, promotional, or audio-quality clutter."
                }
            )
        }
    }.sortedWith(
        compareBy<LibraryCleanupSuggestion> { it.kind.ordinal }
            .thenBy { it.originalArtist.lowercase(Locale.ROOT) }
            .thenBy { it.originalTitle.lowercase(Locale.ROOT) }
            .thenBy(LibraryCleanupSuggestion::songId)
    )
    return LibraryCleanupAnalysis(
        suggestions = suggestions,
        likelyDuplicates = findLikelyDuplicates(uniqueTracks)
    )
}

private fun findPossibleTitleArtistSwaps(
    tracks: List<CleanupTrackMetadata>
): List<LibraryCleanupSuggestion> {
    val albumGroups = tracks.asSequence()
        .filter { track ->
            normalizeMetadataText(track.album).isNotBlank() &&
                normalizeMetadataText(track.albumArtist) !in GENERIC_ARTIST_KEYS &&
                normalizeMetadataText(track.title).isNotBlank() &&
                normalizeMetadataText(track.artist).isNotBlank()
        }
        .groupBy { track ->
            normalizeMetadataText(track.albumArtist) + "\u0000" + normalizeMetadataText(track.album)
        }

    return albumGroups.values.flatMap { albumTracks ->
        val albumArtistKey = normalizeMetadataText(albumTracks.first().albumArtist)
        albumTracks
            .filter { normalizeMetadataText(it.title) == albumArtistKey }
            .groupBy { normalizeMetadataText(it.title) }
            .values
            .filter { repeated ->
                repeated.size >= MIN_TRACKS_FOR_SWAP_SIGNAL &&
                    repeated.map { normalizeMetadataText(it.artist) }.filter(String::isNotBlank).distinct().size >= 2 &&
                    repeated.none { normalizeMetadataText(it.artist) == albumArtistKey }
            }
            .flatten()
            .mapNotNull { track ->
                val proposedTitle = track.artist.trim()
                val proposedArtist = track.albumArtist.trim()
                if (!isPlausibleMetadataLabel(proposedTitle) || !isPlausibleMetadataLabel(proposedArtist)) {
                    null
                } else {
                    LibraryCleanupSuggestion(
                        songId = track.id,
                        originalTitle = track.title,
                        originalArtist = track.artist,
                        suggestedTitle = proposedTitle,
                        suggestedArtist = proposedArtist,
                        kind = LibraryCleanupSuggestionKind.POSSIBLE_TITLE_ARTIST_SWAP,
                        reason = "Several tracks on this album repeat the album artist as the title, while the track-artist values differ. This may be a title/artist field mix-up; verify each suggestion."
                    )
                }
            }
    }.distinctBy(LibraryCleanupSuggestion::songId)
}

private fun findLikelyDuplicates(tracks: List<CleanupTrackMetadata>): List<LikelyDuplicateGroup> {
    val candidates = tracks.mapNotNull { track ->
        val artistKey = normalizeMetadataText(track.artist.ifBlank { track.albumArtist })
        val cleanedTitle = suggestCleanTrackTitle(track.title, track.artist) ?: track.title
        val titleKey = normalizeMetadataText(cleanedTitle)
        if (track.durationMs <= 0L || artistKey.isBlank() || titleKey.length < 2 ||
            artistKey in GENERIC_ARTIST_KEYS || titleKey in GENERIC_TITLE_KEYS
        ) {
            null
        } else {
            DuplicateCandidate(track, titleKey + "\u0000" + artistKey)
        }
    }

    return candidates.groupBy(DuplicateCandidate::fingerprint)
        .values
        .flatMap { sameMetadata ->
            val ordered = sameMetadata.sortedWith(
                compareBy<DuplicateCandidate> { it.track.durationMs }.thenBy { it.track.id }
            )
            val groups = mutableListOf<List<CleanupTrackMetadata>>()
            var current = mutableListOf<CleanupTrackMetadata>()
            var anchorDuration = 0L
            ordered.forEach { candidate ->
                val song = candidate.track
                if (current.isEmpty()) {
                    current += song
                    anchorDuration = song.durationMs
                } else if (song.durationMs - anchorDuration <= DUPLICATE_DURATION_TOLERANCE_MS) {
                    current += song
                } else {
                    if (current.size >= 2) groups += current
                    current = mutableListOf(song)
                    anchorDuration = song.durationMs
                }
            }
            if (current.size >= 2) groups += current

            groups.map { duplicateTracks ->
                val representative = duplicateTracks.first()
                val identicalSizes = duplicateTracks.map(CleanupTrackMetadata::sizeBytes)
                    .filter { it > 0L }.distinct().size <= 1 && duplicateTracks.all { it.sizeBytes > 0L }
                LikelyDuplicateGroup(
                    songIds = duplicateTracks.map(CleanupTrackMetadata::id),
                    representativeTitle = representative.title,
                    representativeArtist = representative.artist.ifBlank { representative.albumArtist },
                    reason = if (identicalSizes) {
                        "Normalized title and artist match; duration and file size also match. These are likely copies, but versions still need your review."
                    } else {
                        "Normalized title and artist match, and track lengths are within 2.5 seconds. Different editions can look similar, so review before changing anything."
                    }
                )
            }
        }
        .sortedWith(
            compareBy<LikelyDuplicateGroup> { normalizeMetadataText(it.representativeArtist) }
                .thenBy { normalizeMetadataText(it.representativeTitle) }
        )
}

private data class DuplicateCandidate(
    val track: CleanupTrackMetadata,
    val fingerprint: String
)

private fun isPlausibleMetadataLabel(value: String): Boolean {
    val key = normalizeMetadataText(value)
    return value.length in 2..160 && key.length >= 2 && key !in GENERIC_TITLE_KEYS &&
        key !in GENERIC_ARTIST_KEYS && value.none(Char::isISOControl)
}

private fun normalizeMetadataText(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .filter { it.isLetterOrDigit() || it.isWhitespace() }
    .replace(WHITESPACE, " ")
    .trim()

private fun hasDecorativeEdgeSymbols(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.firstOrNull()?.let { it in DECORATIVE_EDGE_SYMBOLS } == true ||
        trimmed.lastOrNull()?.let { it in DECORATIVE_EDGE_SYMBOLS } == true
}

private val WHITESPACE = Regex("\\s+")
private val GENERIC_TITLE_KEYS = setOf("unknown", "unknown track", "untitled", "untitled track", "track")
private val GENERIC_ARTIST_KEYS = setOf("unknown", "unknown artist", "various artists", "n a", "unspecified")
private val DECORATIVE_EDGE_SYMBOLS = setOf(
    '♪', '♫', '♬', '★', '☆', '✦', '✧', '✩', '✪', '❖', '♥', '♡', '•', '●', '◆', '◇',
    '▪', '▫', '✓', '✔', '➤', '▶', '►', '*'
)
private const val MIN_TRACKS_FOR_SWAP_SIGNAL = 2
private const val DUPLICATE_DURATION_TOLERANCE_MS = 2_500L
