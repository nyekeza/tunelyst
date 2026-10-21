package com.tunelyst.music.data

private val AUDIO_FILE_EXTENSION = Regex("(?i)\\.(?:aac|aif|aiff|alac|ape|dff|dsf|flac|m4a|m4b|m4p|mka|mp3|oga|ogg|opus|wav|wma|wv)$")
private val REPEATED_WHITESPACE = Regex("\\s+")
private val TRACK_NUMBER_PREFIX = Regex("^\\s*\\d{1,3}[\\s._-]+")

/** Convert MediaStore titles or fallback filenames into a readable, bounded track label. */
fun cleanTrackDisplayName(rawTitle: String, fileName: String): String {
    val title = rawTitle.trim().takeUnless {
        it.isBlank() || it.equals("<unknown>", ignoreCase = true) ||
            it.startsWith("content://", ignoreCase = true) || it.startsWith("file://", ignoreCase = true)
    }
    val usingFileName = title == null
    val source = title ?: fileName
    var cleaned = source
        .replace('\\', '/')
        .substringAfterLast('/')
        .trim()
        .replace(AUDIO_FILE_EXTENSION, "")
        .replace('_', ' ')
        .replace(REPEATED_WHITESPACE, " ")
        .trim(' ', '.', '-', '_')

    if (usingFileName) cleaned = TRACK_NUMBER_PREFIX.replaceFirst(cleaned, "").trim()
    return cleaned.take(MAX_DISPLAY_TITLE_LENGTH).trimEnd().ifBlank { "Untitled track" }
}

private const val MAX_DISPLAY_TITLE_LENGTH = 120
