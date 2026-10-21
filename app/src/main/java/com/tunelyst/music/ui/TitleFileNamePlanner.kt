package com.tunelyst.music.ui

import com.tunelyst.music.data.LibraryCleanupSuggestion
import com.tunelyst.music.data.Song
import java.util.Locale

/** A reviewed metadata correction, embedded-tag write, and optional filename change. */
data class TitleFixRequest(
    val songId: Long,
    val suggestedTitle: String,
    val mediaUriString: String?,
    val currentFileName: String,
    val newFileName: String,
    val folderPath: String,
    val currentTitle: String = "",
    val currentArtist: String = "",
    val suggestedArtist: String? = null
)

/** Plan safe, extension-preserving, collision-free file names for selected cleanup suggestions. */
internal fun planTitleFixes(
    suggestions: List<LibraryCleanupSuggestion>,
    allSongs: List<Song>
): List<TitleFixRequest> {
    val songsById = allSongs.associateBy(Song::id)
    val distinctSuggestions = suggestions.distinctBy(LibraryCleanupSuggestion::songId)
    val reservedByFolder = allSongs
        .groupBy { normalizedFolder(it.folderPath) }
        .mapValues { (_, songs) -> songs.mapTo(mutableSetOf()) { it.fileName.lowercase(Locale.ROOT) } }
        .toMutableMap()

    return distinctSuggestions.mapNotNull { suggestion ->
        val song = songsById[suggestion.songId] ?: return@mapNotNull null
        val suggestedTitle = suggestion.suggestedTitle
        val folderKey = normalizedFolder(song.folderPath)
        val reservedNames = reservedByFolder.getOrPut(folderKey) { mutableSetOf() }
        val canRenameInMediaStore = song.id >= 0L &&
            song.uri.scheme == "content" &&
            song.uri.authority == "media"
        if (canRenameInMediaStore) reservedNames.remove(song.fileName.lowercase(Locale.ROOT))
        val requestedName = uniqueAudioDisplayName(suggestedTitle, song.fileName, reservedNames)
        reservedNames += requestedName.lowercase(Locale.ROOT)
        TitleFixRequest(
            songId = song.id,
            suggestedTitle = suggestedTitle,
            mediaUriString = song.uri.toString().takeIf { canRenameInMediaStore },
            currentFileName = song.fileName,
            newFileName = requestedName,
            folderPath = song.folderPath,
            currentTitle = suggestion.originalTitle,
            currentArtist = suggestion.originalArtist,
            suggestedArtist = suggestion.suggestedArtist
        )
    }
}

internal fun safeAudioDisplayName(title: String, currentFileName: String): String {
    val extension = audioExtension(currentFileName)
    val safeBase = safeFileStem(title).ifBlank { "Untitled track" }
    return safeBase.take(MAX_AUDIO_STEM_LENGTH) + extension
}

internal fun uniqueAudioDisplayName(
    title: String,
    currentFileName: String,
    reservedNamesLowercase: Set<String>
): String {
    val extension = audioExtension(currentFileName)
    val base = safeFileStem(title).ifBlank { "Untitled track" }.take(MAX_AUDIO_STEM_LENGTH)
    var candidate = base + extension
    var suffix = 2
    while (candidate.lowercase(Locale.ROOT) in reservedNamesLowercase) {
        val suffixText = " ($suffix)"
        candidate = base.take((MAX_AUDIO_STEM_LENGTH - suffixText.length).coerceAtLeast(1)) + suffixText + extension
        suffix++
    }
    return candidate
}

private fun safeFileStem(value: String): String = value
    .replace(UNSAFE_FILE_CHARS, " ")
    .replace(WHITESPACE, " ")
    .trim()
    .trimEnd('.')

private fun audioExtension(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    return if (dot > 0 && dot < fileName.lastIndex) fileName.substring(dot) else ""
}

private fun normalizedFolder(path: String): String = path.trim().ifBlank { "Device root" }.lowercase(Locale.ROOT)

private const val MAX_AUDIO_STEM_LENGTH = 180
private val UNSAFE_FILE_CHARS = Regex("[/\\\\:*?\"<>|\\p{Cntrl}]")
private val WHITESPACE = Regex("\\s+")
