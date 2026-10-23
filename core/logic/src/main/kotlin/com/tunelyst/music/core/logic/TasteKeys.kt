package com.tunelyst.music.core.logic

import java.text.Normalizer
import java.util.Locale

/** Stable comparison key shared by local taste controls and recommendation scoring. */
fun normalizeTasteKey(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .replace(Regex("\\s+"), " ")

/** Split common multi-tag genre delimiters so one preference matches each individual genre. */
fun normalizedGenreKeys(value: String): List<String> = value.split(',', ';', '/', '|')
    .map(::normalizeTasteKey)
    .filter { it.isNotBlank() && it !in IGNORED_TASTE_GENRES }
    .distinct()

fun matchesGenreRule(songGenre: String, ruleValue: String): Boolean {
    val wantedGenres = normalizedGenreKeys(ruleValue).toSet()
    return wantedGenres.isNotEmpty() && normalizedGenreKeys(songGenre).any(wantedGenres::contains)
}

private val IGNORED_TASTE_GENRES = setOf("n/a", "unknown", "unknown genre", "unspecified")
