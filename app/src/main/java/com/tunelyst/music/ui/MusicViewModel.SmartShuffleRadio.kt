@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.tunelyst.music.ui

import androidx.media3.common.C
import androidx.media3.common.Player
import com.tunelyst.music.data.LocalAudioFeatures
import com.tunelyst.music.data.RepeatAvoidanceLevel
import com.tunelyst.music.data.Song
import com.tunelyst.music.core.logic.normalizeTasteKey
import com.tunelyst.music.core.logic.normalizedGenreKeys
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

internal fun MusicViewModel.playSmartShuffleImpl(songs: List<Song>) {
    clearSmartShuffleCompletionPrompt()
    radioActive = false
    playNextIndicatorIds.clear()
    queuedIndicatorIds.clear()
    val catalog = songs.ifEmpty { _uiState.value.songs }
    if (catalog.isEmpty()) return
    val activePlayer = player
    val currentIndex = activePlayer?.currentMediaItemIndex ?: C.INDEX_UNSET
    val hasCurrentTrack = activePlayer != null &&
        currentIndex in 0 until activePlayer.mediaItemCount &&
        activePlayer.playbackState != Player.STATE_ENDED
    if (hasCurrentTrack) {
        enableSmartShuffleForCurrentTrack(catalog)
    } else {
        startSmartShuffleNow(catalog)
    }
}

internal fun MusicViewModel.toggleSmartShuffleImpl(songs: List<Song>) {
    if (smartShuffleActive || player?.shuffleModeEnabled == true) toggleShuffle()
    else playSmartShuffle(songs)
}

internal fun MusicViewModel.startSmartShuffleNowImpl(songs: List<Song>) {
    val ordered = buildSmartShuffleQueue(songs)
    if (ordered.isEmpty()) {
        stopShuffleContinuation(player)
        return
    }
    playFrom(ordered, 0, shuffle = false, autoShuffleAtEnd = true, preserveOrder = true, smartShuffle = true)
}

internal fun MusicViewModel.repeatAvoidanceCandidatesImpl(
    songs: List<Song>,
    excludedIds: Set<Long> = emptySet()
): List<Song> {
    val available = songs.distinctBy(Song::id).filterNot { it.id in excludedIds }
    if (_appSettings.value.repeatAvoidanceLevel != RepeatAvoidanceLevel.VERY_FEW) return available
    val recentIds = _uiState.value.recentlyPlayedIds.take(VERY_FEW_RECENT_COOLDOWN_SIZE).toSet()
    val notRecentlyPlayed = available.filterNot { it.id in recentIds }
    return notRecentlyPlayed.ifEmpty { available }
}

internal fun MusicViewModel.buildSmartShuffleQueueImpl(
    songs: List<Song>,
    excludedIds: Set<Long> = emptySet(),
    contextSeed: Song? = null
): List<Song> {
    if (songs.isEmpty()) return emptyList()
    val state = _uiState.value
    val candidateSongs = repeatAvoidanceCandidates(songs, excludedIds)
    if (candidateSongs.isEmpty()) return emptyList()
    val songsById = candidateSongs.associateBy(Song::id)
    val tasteSongsById = (state.songs + songs + listOfNotNull(contextSeed)).associateBy(Song::id)
    val recentRanks = state.recentlyPlayedIds.withIndex().associate { it.value to it.index }
    val favoriteSongs = candidateSongs.filter { it.id in state.favorites }
    // Favorites can inform taste, but must not occupy most of Smart Shuffle's candidate pool.
    // A small rotating sample leaves room for discovery from the full local library.
    val favoriteSeedSongs = reservoirSample(
        favoriteSongs,
        excludedIds,
        minOf(MAX_FAVORITE_SHUFFLE_SEEDS, favoriteSongs.size)
    )
    val recentSeedSongs = state.recentlyPlayedIds.take(24).mapNotNull(songsById::get)
    val seedSongs = (listOfNotNull(contextSeed?.takeIf { it.id in songsById }) + recentSeedSongs + favoriteSeedSongs)
        .filterNot { it.id in excludedIds }
        .distinctBy(Song::id)
    val discoveryPool = reservoirSample(
        candidateSongs,
        excludedIds + seedSongs.map(Song::id).toSet(),
        (MAX_DISCOVERY_CANDIDATES - seedSongs.size.coerceAtMost(MAX_DISCOVERY_CANDIDATES)).coerceAtLeast(0)
    )
    val candidates = (seedSongs.take(MAX_DISCOVERY_CANDIDATES) + discoveryPool)
        .distinctBy(Song::id)
        .take(MAX_DISCOVERY_CANDIDATES)
    enqueueShuffleFeatureWarmup(listOfNotNull(contextSeed) + seedSongs, discoveryPool)
    val featuresById = candidates.mapNotNull { song ->
        audioFeatureStore.featuresFor(song)?.let { song.id to it }
    }.toMap()
    val audioProfile = localAudioTasteProfile(state, tasteSongsById)
    val contextProfile = contextSeed?.let(audioFeatureStore::featuresFor)?.let {
        LocalAudioTasteProfile(it.tempoBpm, it.tempoConfidence, it.energy)
    }
    val contextArtistKey = contextSeed?.let { normalizeTasteKey(it.artist) }
    val contextGenres = contextSeed?.let { normalizedGenreKeys(it.genre).toSet() }.orEmpty()
    val artistAffinity = HashMap<String, Float>()
    val genreAffinity = HashMap<String, Float>()
    state.recentlyPlayedIds.take(40).forEachIndexed { index, id ->
        val seed = tasteSongsById[id] ?: return@forEachIndexed
        val weight = 5.5f / (1f + index * 0.24f)
        val artistKey = normalizeTasteKey(seed.artist)
        artistAffinity[artistKey] = (artistAffinity[artistKey] ?: 0f) + weight
        normalizedGenreKeys(seed.genre).forEach { genre ->
            genreAffinity[genre] = (genreAffinity[genre] ?: 0f) + weight * 0.65f
        }
    }
    favoriteSeedSongs.forEach { seed ->
        val artistKey = normalizeTasteKey(seed.artist)
        artistAffinity[artistKey] = (artistAffinity[artistKey] ?: 0f) + 0.45f
        normalizedGenreKeys(seed.genre).forEach { genre ->
            genreAffinity[genre] = (genreAffinity[genre] ?: 0f) + 0.28f
        }
    }
    return selectDiverseCandidates(candidates, SMART_SHUFFLE_QUEUE_SIZE) { song ->
        val artistKey = normalizeTasteKey(song.artist)
        val songGenres = normalizedGenreKeys(song.genre)
        val artistAffinityScore = artistAffinity[artistKey] ?: 0f
        val genreAffinityScore = songGenres.sumOf { (genreAffinity[it] ?: 0f).toDouble() }.toFloat()
        val acousticMatch = featuresById[song.id]?.let { audioFeatureSimilarity(it, audioProfile) } ?: 0f
        val contextAcousticMatch = contextProfile?.let { profile ->
            featuresById[song.id]?.let { audioFeatureSimilarity(it, profile) } ?: 0f
        } ?: 0f
        val contextArtistMatch = if (contextArtistKey != null && artistKey == contextArtistKey) 2.2f else 0f
        val contextGenreMatch = songGenres.count { it in contextGenres } * 0.8f
        (if (song.id in state.favorites) 0.75f else 0f) +
            contextArtistMatch + contextGenreMatch + contextAcousticMatch * 2.2f +
            artistAffinityScore * 0.42f + genreAffinityScore * 0.30f + acousticMatch * 2.8f -
            styleAwareRecentPenalty(recentRanks[song.id]) +
            lifetimePlayPenalty(song) + localTastePenalty(song) +
            kotlin.random.Random.nextFloat() * 1.5f
    }
}

internal fun MusicViewModel.startRadioFromLocalSongsImpl(seed: Song, scopedSongs: List<Song>?) {
    val librarySongs = _uiState.value.songs
    val stationPool = scopedSongs.orEmpty().distinctBy(Song::id)
    val localSongs = if (stationPool.size >= 2 && stationPool.any { it.id == seed.id }) stationPool else librarySongs
    val radioSeed = localSongs.firstOrNull { it.id == seed.id } ?: seed
    val sameArtist = localSongs.asSequence()
        .filter { it.id != radioSeed.id && it.artist.equals(radioSeed.artist, ignoreCase = true) }
        .take(MAX_RADIO_ARTIST_CANDIDATES)
        .toList()
    val includedIds = (sameArtist.map(Song::id) + radioSeed.id).toSet()
    val sampled = reservoirSample(localSongs, includedIds, MAX_DISCOVERY_CANDIDATES - sameArtist.size)
    val candidates = repeatAvoidanceCandidates((sameArtist + sampled).distinctBy(Song::id))
    val recentRanks = _uiState.value.recentlyPlayedIds.withIndex().associate { it.value to it.index }
    val favorites = _uiState.value.favorites
    enqueueShuffleFeatureWarmup(listOf(radioSeed), candidates)
    val seedFeatures = audioFeatureStore.featuresFor(radioSeed)
    val candidateFeatures = candidates.mapNotNull { song ->
        audioFeatureStore.featuresFor(song)?.let { song.id to it }
    }.toMap()
    val seedProfile = seedFeatures?.let { LocalAudioTasteProfile(it.tempoBpm, it.tempoConfidence, it.energy) }
    val artistKey = normalizeTasteKey(radioSeed.artist)
    val albumKey = normalizeTasteKey(radioSeed.album)
    val suggestions = selectDiverseCandidates(candidates, RADIO_QUEUE_SIZE - 1) { song ->
        val acousticMatch = if (seedProfile == null) 0f else {
            candidateFeatures[song.id]?.let { audioFeatureSimilarity(it, seedProfile) } ?: 0f
        }
        (if (normalizeTasteKey(song.artist) == artistKey) 8f else 0f) +
            (if (normalizeTasteKey(song.album) == albumKey) 4f else 0f) +
            acousticMatch * 3.2f +
            (if (song.id in favorites) 2.5f else 0f) -
            styleAwareRecentPenalty(recentRanks[song.id]) +
            lifetimePlayPenalty(song) + localTastePenalty(song) +
            kotlin.random.Random.nextFloat() * 1.25f
    }
    val radioQueue = listOf(radioSeed) + suggestions
    val activePlayer = player
    val currentIndex = activePlayer?.currentMediaItemIndex ?: C.INDEX_UNSET
    val isAlreadyPlayingSeed = activePlayer != null &&
        currentIndex in 0 until activePlayer.mediaItemCount &&
        activePlayer.currentMediaItem?.mediaId?.toLongOrNull() == radioSeed.id
    if (isAlreadyPlayingSeed) {
        replaceRadioQueueKeepingCurrent(activePlayer!!, suggestions)
    } else {
        playFrom(radioQueue, 0, shuffle = false, preserveOrder = true, radioSession = true)
    }
}

internal fun MusicViewModel.replaceRadioQueueKeepingCurrentImpl(activePlayer: Player, suggestions: List<Song>) {
    val currentIndex = activePlayer.currentMediaItemIndex
    if (currentIndex !in 0 until activePlayer.mediaItemCount) return
    clearSmartShuffleCompletionPrompt()
    radioActive = true
    smartShuffleActive = false
    preserveCurrentQueueOrder = true
    autoShuffleOnCompletion = false
    autoShuffleJob?.cancel()
    autoShuffleJob = null
    playbackGeneration++
    playNextIndicatorIds.clear()
    queuedIndicatorIds.clear()
    activePlayer.shuffleModeEnabled = false
    activePlayer.repeatMode = Player.REPEAT_MODE_OFF
    if (currentIndex > 0) activePlayer.removeMediaItems(0, currentIndex)
    val preservedIndex = activePlayer.currentMediaItemIndex
    if (preservedIndex !in 0 until activePlayer.mediaItemCount) return
    if (preservedIndex + 1 < activePlayer.mediaItemCount) {
        activePlayer.removeMediaItems(preservedIndex + 1, activePlayer.mediaItemCount)
    }
    if (suggestions.isNotEmpty()) {
        activePlayer.addMediaItems(preservedIndex + 1, suggestions.map(::toMediaItem))
    }
    activePlayer.play()
    syncPlayback(activePlayer)
    persistPlaybackSession(activePlayer, force = true)
    scheduleEmbeddedReplayGainForCurrentQueue()
}

internal fun MusicViewModel.stopRadioImpl() {
    radioActive = false
    val activePlayer = player
    if (activePlayer == null) {
        _playbackState.update { it.copy(radioActive = false) }
        return
    }
    val currentIndex = activePlayer.currentMediaItemIndex
    if (currentIndex in 0 until activePlayer.mediaItemCount) {
        val keepIds = playNextIndicatorIds + queuedIndicatorIds
        for (index in activePlayer.mediaItemCount - 1 downTo currentIndex + 1) {
            val songId = activePlayer.getMediaItemAt(index).mediaId.toLongOrNull()
            if (songId == null || songId !in keepIds) activePlayer.removeMediaItem(index)
        }
    }
    syncPlayback(activePlayer)
    persistPlaybackSession(activePlayer, force = true)
}

internal fun MusicViewModel.recentPlayPenaltyImpl(rank: Int?): Float {
    val recentRank = rank ?: return 0f
    return if (recentRank < RECENT_TRACK_PENALTY_LIMIT) {
        (RECENT_TRACK_PENALTY_LIMIT - recentRank) * RECENT_TRACK_PENALTY_STEP
    } else 0f
}

internal fun MusicViewModel.styleAwareRecentPenaltyImpl(rank: Int?): Float {
    val recentRank = rank ?: return 0f
    return when (_appSettings.value.repeatAvoidanceLevel) {
        RepeatAvoidanceLevel.BALANCED -> recentPlayPenalty(recentRank) * 0.38f
        RepeatAvoidanceLevel.FEWER -> recentPlayPenalty(recentRank)
        RepeatAvoidanceLevel.VERY_FEW -> if (recentRank < VERY_FEW_RECENT_PENALTY_LIMIT) {
            (VERY_FEW_RECENT_PENALTY_LIMIT - recentRank) * VERY_FEW_RECENT_PENALTY_STEP
        } else {
            0f
        }
    }
}

internal fun MusicViewModel.localAudioTasteProfileImpl(
    state: MusicUiState,
    songsById: Map<Long, Song>
): LocalAudioTasteProfile {
    val weighted = ArrayList<Pair<LocalAudioFeatures, Float>>()
    state.recentlyPlayedIds.take(24).forEachIndexed { index, songId ->
        val song = songsById[songId] ?: return@forEachIndexed
        val features = audioFeatureStore.featuresFor(song) ?: return@forEachIndexed
        weighted += features to (1f / (1f + index * 0.24f))
    }
    state.favorites.forEach { songId ->
        val song = songsById[songId] ?: return@forEach
        val features = audioFeatureStore.featuresFor(song) ?: return@forEach
        weighted += features to 0.12f
    }
    if (weighted.isEmpty()) return LocalAudioTasteProfile(null, 0f, null)
    val weightSum = weighted.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(0.0001f)
    val energy = weighted.sumOf { (features, weight) -> features.energy * weight.toDouble() }.toFloat() / weightSum
    val tempoItems = weighted.filter { (features, _) ->
        features.tempoBpm != null && features.tempoConfidence >= MIN_AUDIO_TEMPO_CONFIDENCE
    }
    val tempoWeight = tempoItems.sumOf { (features, weight) ->
        weight * features.tempoConfidence.toDouble()
    }.toFloat()
    val tempo = if (tempoWeight > 0f) {
        tempoItems.sumOf { (features, weight) ->
            features.tempoBpm!!.toDouble() * weight * features.tempoConfidence
        }.toFloat() / tempoWeight
    } else null
    val confidence = if (tempoWeight > 0f) {
        tempoItems.sumOf { (features, weight) ->
            (weight * features.tempoConfidence * features.tempoConfidence).toDouble()
        }.toFloat() / tempoWeight
    } else 0f
    return LocalAudioTasteProfile(tempo, confidence.coerceIn(0f, 1f), energy.coerceIn(0f, 1f))
}

internal fun MusicViewModel.audioFeatureSimilarityImpl(
    candidate: LocalAudioFeatures,
    profile: LocalAudioTasteProfile
): Float {
    var weightedScore = 0f
    var totalWeight = 0f
    val targetEnergy = profile.energy
    if (targetEnergy != null) {
        val energySimilarity = 1f - abs(candidate.energy - targetEnergy).coerceIn(0f, 1f)
        weightedScore += energySimilarity * 0.42f
        totalWeight += 0.42f
    }
    val targetTempo = profile.tempoBpm
    val candidateTempo = candidate.tempoBpm
    if (targetTempo != null && targetTempo > 0f && candidateTempo != null && candidateTempo > 0f &&
        candidate.tempoConfidence >= MIN_AUDIO_TEMPO_CONFIDENCE
    ) {
        val ratio = candidateTempo / targetTempo
        val octaveAdjustedDistance = minOf(
            abs(ln(ratio)),
            abs(ln(ratio * 2f)),
            abs(ln(ratio * 0.5f))
        )
        val tempoSimilarity = exp(-octaveAdjustedDistance / ln(1.5f)).coerceIn(0f, 1f)
        val confidenceWeight = 0.58f * candidate.tempoConfidence * profile.tempoConfidence
        weightedScore += tempoSimilarity * confidenceWeight
        totalWeight += confidenceWeight
    }
    return if (totalWeight > 0f) (weightedScore / totalWeight).coerceIn(0f, 1f) else 0f
}

internal fun MusicViewModel.lifetimePlayPenaltyImpl(song: Song): Float {
    val plays = (_uiState.value.playCounts[song.id] ?: 0L).coerceAtLeast(0L)
    val strength = when (_appSettings.value.repeatAvoidanceLevel) {
        RepeatAvoidanceLevel.BALANCED -> 0.38f
        RepeatAvoidanceLevel.FEWER -> 0.9f
        RepeatAvoidanceLevel.VERY_FEW -> 1.5f
    }
    return -ln(1.0 + plays).toFloat() * strength
}

internal fun MusicViewModel.localTastePenaltyImpl(song: Song): Float {
    val state = _uiState.value
    val artistKey = normalizeTasteKey(song.artist)
    val genreKeys = normalizedGenreKeys(song.genre)
    val dislikesArtist = state.lessLikeArtistNames.any { normalizeTasteKey(it) == artistKey }
    val dislikedGenreCount = genreKeys.count { genre ->
        state.lessLikeGenreNames.any { normalizeTasteKey(it) == genre }
    }
    return (if (dislikesArtist) -32f else 0f) - dislikedGenreCount * 18f
}

internal fun MusicViewModel.reservoirSampleImpl(songs: List<Song>, excludedIds: Set<Long>, limit: Int): List<Song> {
    if (limit <= 0) return emptyList()
    val sample = ArrayList<Song>(minOf(limit, songs.size))
    var seen = 0
    songs.forEach { song ->
        if (song.id in excludedIds) return@forEach
        seen++
        if (sample.size < limit) sample += song
        else {
            val replacement = kotlin.random.Random.nextInt(seen)
            if (replacement < limit) sample[replacement] = song
        }
    }
    return sample
}

internal fun MusicViewModel.selectDiverseCandidatesImpl(
    candidates: List<Song>,
    limit: Int,
    score: (Song) -> Float
): List<Song> {
    data class RankedSong(val song: Song, val score: Float)
    val remaining = ArrayList<RankedSong>(minOf(candidates.size, MAX_DISCOVERY_CANDIDATES))
    val seen = HashSet<Long>()
    candidates.forEach { song ->
        if (seen.add(song.id)) remaining += RankedSong(song, score(song))
    }
    remaining.sortByDescending(RankedSong::score)
    val chosen = ArrayList<Song>(minOf(limit, remaining.size))
    val artistCounts = HashMap<String, Int>()
    val albumCounts = HashMap<String, Int>()
    val genreCounts = HashMap<String, Int>()
    val repeatBias = when (_appSettings.value.repeatAvoidanceLevel) {
        RepeatAvoidanceLevel.BALANCED -> 0.55f
        RepeatAvoidanceLevel.FEWER -> 1.65f
        RepeatAvoidanceLevel.VERY_FEW -> 3.1f
    }
    while (chosen.size < limit && remaining.isNotEmpty()) {
        val windowSize = minOf(SMART_SHUFFLE_LOOKAHEAD, remaining.size)
        var bestIndex = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (index in 0 until windowSize) {
            val candidate = remaining[index]
            val artist = normalizeTasteKey(candidate.song.artist)
            val album = normalizeTasteKey(candidate.song.album)
            val genreRepeatCount = normalizedGenreKeys(candidate.song.genre).sumOf { genreCounts[it] ?: 0 }
            val diversityPenalty = (artistCounts[artist] ?: 0) * 2.2f * repeatBias +
                (albumCounts[album] ?: 0) * 1.0f * repeatBias + genreRepeatCount * 1.2f * repeatBias
            val adjustedScore = candidate.score - diversityPenalty
            if (adjustedScore > bestScore) {
                bestScore = adjustedScore
                bestIndex = index
            }
        }
        val next = remaining.removeAt(bestIndex).song
        chosen += next
        val artist = normalizeTasteKey(next.artist)
        val album = normalizeTasteKey(next.album)
        artistCounts[artist] = (artistCounts[artist] ?: 0) + 1
        albumCounts[album] = (albumCounts[album] ?: 0) + 1
        normalizedGenreKeys(next.genre).forEach { genre -> genreCounts[genre] = (genreCounts[genre] ?: 0) + 1 }
    }
    return chosen
}
