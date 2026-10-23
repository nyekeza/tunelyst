package com.tunelyst.music.core.logic

import java.util.Locale

private val TRACK_NUMBER_PREFIX = Regex("""^\s*(?:\d{1,3}\s*[-._]\s*|\d{2,3}\s+)""")
private val AUDIO_EXTENSION_SUFFIX = Regex("""(?i)\.(?:aac|aif|aiff|alac|ape|dff|dsf|flac|m4a|m4b|m4p|mka|mp3|oga|ogg|opus|wav|wma|wv)$""")
private val TRAILING_BRACKET_GROUP = Regex("""\s*[\(\[\{]\s*([^\)\]\}]+?)\s*[\)\]\}]\s*$""")
private val TRAILING_PROMO_SUFFIX = Regex("""(?i)\s+[-–—|:]\s+(?:official\s+(?:music\s+)?video|music\s+video|lyrics?(?:\s+video)?|visuali[sz]er|youtube|vevo)\s*$""")
private val TRAILING_QUALITY_SUFFIX = Regex("""(?i)\s+(?:(?:mp3|flac|wav|aac)\s+)?(?:\d{2,4}\s?k?bps|(?:2160|1440|1080|720|480)p|4k|8k|uhd|hd|hq)\s*$""")
private val REPEATED_SPACES = Regex("\\s+")

private val NOISY_BRACKET_TAGS = setOf(
    "audio", "video", "official", "official audio", "official video", "official music video",
    "official lyrics", "official lyric video", "official lyrics video", "music video",
    "lyrics", "lyric", "lyrics video", "lyric video", "visualizer", "audio only",
    "full album", "free download", "download", "hd", "hq", "uhd", "4k", "8k"
)

/** Return a conservative, on-device title suggestion for common filename and upload clutter. */
fun suggestCleanTrackTitle(title: String, artist: String = ""): String? {
    val original = title.trim()
    if (original.isBlank()) return null

    val artistName = artist.trim().takeIf { it.length >= 2 }
    val artistPrefix = artistName?.let { Regex("""(?i)^${Regex.escape(it)}\s*[-–—:|]\s*""") }
    val artistSuffix = artistName?.let { Regex("""(?i)\s*[-–—:|]\s*${Regex.escape(it)}$""") }
    var candidate = stripDecorativeTitleEdges(original)
    var pass = 0
    while (pass++ < 8) {
        val previous = candidate
        candidate = candidate
            .replace(AUDIO_EXTENSION_SUFFIX, "")
            .replace(TRACK_NUMBER_PREFIX, "")
            .replace(TRAILING_PROMO_SUFFIX, "")
            .replace(TRAILING_QUALITY_SUFFIX, "")
            .trim()

        val bracketMatch = TRAILING_BRACKET_GROUP.find(candidate)
        if (bracketMatch != null && isNoisyTitleTag(bracketMatch.groupValues[1])) {
            candidate = candidate.substring(0, bracketMatch.range.first).trimEnd()
        }
        if (artistPrefix != null && artistSuffix != null) {
            candidate = candidate.replace(artistPrefix, "").replace(artistSuffix, "").trim()
        }

        candidate = candidate.replace(REPEATED_SPACES, " ")
            .trim(' ', '.', '-', '–', '—', '|', ':', '_')
            .let(::stripDecorativeTitleEdges)
        if (candidate == previous) break
    }

    if (candidate.length < 2 || candidate.none(Char::isLetterOrDigit)) return null
    return candidate.takeUnless { it.equals(original, ignoreCase = true) }
}

private val DECORATIVE_TITLE_EDGE_SYMBOLS = setOf(
    '♪', '♫', '♬', '★', '☆', '✦', '✧', '✩', '✪', '❖', '♥', '♡', '•', '●', '◆', '◇',
    '▪', '▫', '✓', '✔', '➤', '▶', '►', '*'
)

private fun stripDecorativeTitleEdges(value: String): String = value.trim().trim {
    it.isWhitespace() || it in DECORATIVE_TITLE_EDGE_SYMBOLS
}

private fun isNoisyTitleTag(rawTag: String): Boolean {
    val tag = rawTag.trim().lowercase(Locale.ROOT).replace(REPEATED_SPACES, " ")
    return tag in NOISY_BRACKET_TAGS ||
        tag.matches(Regex("""(?:\d{2,4}\s?k?bps|(?:2160|1440|1080|720|480)p|4k|8k|uhd|hd|hq)""")) ||
        tag.matches(Regex("""(?:mp3|flac|wav|aac)(?:\s+\d{2,4}\s?k?bps)?"""))
}
