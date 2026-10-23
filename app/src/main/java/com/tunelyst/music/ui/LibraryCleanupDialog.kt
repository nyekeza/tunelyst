package com.tunelyst.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunelyst.music.data.LibraryCleanupAnalysis
import com.tunelyst.music.data.LibraryCleanupSuggestion
import com.tunelyst.music.data.LibraryCleanupSuggestionKind
import com.tunelyst.music.data.canDeleteSelectedDuplicateTracks
import com.tunelyst.music.data.resolveCurrentDuplicateGroups
import com.tunelyst.music.data.Song
import com.tunelyst.music.data.formatTime

@Composable
internal fun LibraryCleanupDialog(
    analysis: LibraryCleanupAnalysis,
    songs: List<Song>,
    onApplyTitleFixes: (List<TitleFixRequest>) -> Unit,
    onDeleteDuplicates: (List<Song>) -> Unit,
    onDismiss: () -> Unit
) {
    val songsById = remember(songs) { songs.associateBy(Song::id) }
    val suggestions = remember(analysis, songsById) {
        analysis.suggestions.filter { it.songId in songsById }
    }
    val resolvedDuplicateAnalysisGroups = remember(analysis, songsById) {
        resolveCurrentDuplicateGroups(analysis.likelyDuplicates, songsById.keys)
    }
    val duplicateGroups = remember(resolvedDuplicateAnalysisGroups, songsById) {
        resolvedDuplicateAnalysisGroups.mapNotNull { group ->
            val groupSongs = group.songIds.mapNotNull(songsById::get)
            if (groupSongs.size >= 2) group to groupSongs else null
        }
    }
    val allDuplicateIds = remember(duplicateGroups) {
        duplicateGroups.flatMap { it.second }.map(Song::id).toSet()
    }
    var selectedIds by remember(analysis) { mutableStateOf(emptySet<Long>()) }
    var selectedDuplicateIds by remember(analysis, allDuplicateIds) { mutableStateOf(emptySet<Long>()) }
    var selectedTab by remember(analysis) {
        mutableStateOf(if (suggestions.isEmpty() && duplicateGroups.isNotEmpty()) 1 else 0)
    }
    var confirmingTitleFixes by remember(analysis) { mutableStateOf(false) }
    var confirmingDuplicateDelete by remember(analysis, allDuplicateIds) { mutableStateOf(false) }
    val selectedSuggestions = suggestions.filter { it.songId in selectedIds }
    val selectedFixes = remember(selectedSuggestions, songs) { planTitleFixes(selectedSuggestions, songs) }
    val selectedDuplicateSongs = remember(duplicateGroups, selectedDuplicateIds) {
        duplicateGroups.flatMap { it.second }
            .filter { it.id in selectedDuplicateIds }
            .distinctBy(Song::id)
    }
    val duplicateGroupWouldLoseEveryCopy = duplicateGroups.any { (_, groupSongs) ->
        groupSongs.isNotEmpty() && groupSongs.all { it.id in selectedDuplicateIds }
    }
    val deletableDuplicateIds = selectedDuplicateSongs.filter { song ->
        song.id >= 0L && song.uri.scheme == "content" && song.uri.authority == "media"
    }.map(Song::id).toSet()
    val deletableDuplicateCount = deletableDuplicateIds.size
    val skippedDuplicateCount = selectedDuplicateSongs.size - deletableDuplicateCount
    val canDeleteDuplicates = canDeleteSelectedDuplicateTracks(
        duplicateGroups = duplicateGroups.map { it.first },
        selectedSongIds = selectedDuplicateIds,
        deletableSongIds = deletableDuplicateIds
    )

    BackHandler(enabled = confirmingTitleFixes || confirmingDuplicateDelete) {
        confirmingTitleFixes = false
        confirmingDuplicateDelete = false
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Library cleanup", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 500.dp)) {
                when {
                    confirmingTitleFixes -> {
                        Text(
                            "Selected changes · ${selectedFixes.size}",
                            color = TunelystColors.Primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(7.dp))
                        LazyColumn(
                            modifier = Modifier.heightIn(min = 72.dp, max = 315.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(selectedFixes, key = TitleFixRequest::songId) { fix ->
                                Surface(
                                    color = TunelystColors.SurfaceRaised.copy(alpha = 0.38f),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                        Text(
                                            "Title: “${fix.currentTitle.ifBlank { "Current title" }}” → “${fix.suggestedTitle}”",
                                            color = TunelystColors.Text,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp
                                        )
                                        if (fix.suggestedArtist != null) {
                                            Text(
                                                "Artist: “${fix.currentArtist.ifBlank { "Current artist" }}” → “${fix.suggestedArtist}”",
                                                color = TunelystColors.Text,
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp,
                                                modifier = Modifier.padding(top = 3.dp)
                                            )
                                        }
                                        if (fix.currentFileName != fix.newFileName) {
                                            Text(
                                                "File: ${fix.currentFileName} → ${fix.newFileName}",
                                                color = TunelystColors.Muted,
                                                fontSize = 10.sp,
                                                lineHeight = 14.sp,
                                                modifier = Modifier.padding(top = 3.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        val hasDeviceFiles = selectedFixes.any { it.mediaUriString != null }
                        Text(
                            if (hasDeviceFiles) {
                                "The corrections will be saved in Tunelyst. Android may ask before device tags or filenames are changed."
                            } else {
                                "The corrections will be saved in Tunelyst."
                            },
                            color = TunelystColors.Muted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    confirmingDuplicateDelete -> {
                        Text(
                            "Delete ${selectedDuplicateSongs.size} selected ${if (selectedDuplicateSongs.size == 1) "track" else "tracks"}?",
                            color = TunelystColors.Rose,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            buildString {
                                append("This permanently removes the checked device audio. Leave at least one version unchecked in every group. Android approval is required.")
                                if (skippedDuplicateCount > 0) {
                                    append(" $skippedDuplicateCount shared or unsupported ${if (skippedDuplicateCount == 1) "track will" else "tracks will"} be skipped.")
                                }
                            },
                            color = TunelystColors.Muted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 5.dp, bottom = 8.dp)
                        )
                        LazyColumn(
                            modifier = Modifier.heightIn(min = 72.dp, max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            items(selectedDuplicateSongs, key = Song::id) { song ->
                                Surface(
                                    color = TunelystColors.SurfaceRaised.copy(alpha = 0.38f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(9.dp)) {
                                        Text(
                                            "${song.title} — ${song.artist}",
                                            color = TunelystColors.Text,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            song.fileName.ifBlank { song.folderPath.ifBlank { "Device audio" } },
                                            color = TunelystColors.Subtle,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                label = { Text("Suggested changes · ${suggestions.size}", fontSize = 12.sp) }
                            )
                            FilterChip(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                label = { Text("Possible duplicates · ${duplicateGroups.size}", fontSize = 12.sp) }
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        if (selectedTab == 0) {
                            if (suggestions.isEmpty()) {
                                Text("No title or metadata corrections are suggested.", color = TunelystColors.Muted, fontSize = 13.sp)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${selectedSuggestions.size} of ${suggestions.size} selected",
                                        color = TunelystColors.Muted,
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    val allSuggestionsSelected = selectedIds.containsAll(suggestions.map { it.songId })
                                    TextButton(onClick = {
                                        selectedIds = if (allSuggestionsSelected) emptySet() else suggestions.map { it.songId }.toSet()
                                    }) {
                                        Text(if (allSuggestionsSelected) "Clear" else "Select all", color = TunelystColors.Primary)
                                    }
                                }
                                LazyColumn(
                                    modifier = Modifier.heightIn(min = 96.dp, max = 340.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(suggestions, key = LibraryCleanupSuggestion::songId) { suggestion ->
                                        val checked = suggestion.songId in selectedIds
                                        Surface(
                                            color = TunelystColors.SurfaceRaised.copy(alpha = 0.38f),
                                            shape = RoundedCornerShape(15.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = checked,
                                                    onCheckedChange = { selected ->
                                                        selectedIds = if (selected) selectedIds + suggestion.songId else selectedIds - suggestion.songId
                                                    }
                                                )
                                                Column(Modifier.weight(1f).padding(start = 3.dp, end = 4.dp)) {
                                                    Text(
                                                        if (suggestion.kind == LibraryCleanupSuggestionKind.POSSIBLE_TITLE_ARTIST_SWAP) "Possible title/artist mix-up"
                                                        else "Clean up title",
                                                        color = TunelystColors.Primary,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        "Title: “${suggestion.originalTitle}” → “${suggestion.suggestedTitle}”",
                                                        color = TunelystColors.Text,
                                                        fontSize = 12.sp,
                                                        lineHeight = 16.sp
                                                    )
                                                    if (suggestion.suggestedArtist != null) {
                                                        Text(
                                                            "Artist: “${suggestion.originalArtist}” → “${suggestion.suggestedArtist}”",
                                                            color = TunelystColors.Text,
                                                            fontSize = 12.sp,
                                                            lineHeight = 16.sp
                                                        )
                                                    } else if (suggestion.originalArtist.isNotBlank()) {
                                                        Text(
                                                            suggestion.originalArtist,
                                                            color = TunelystColors.Muted,
                                                            fontSize = 11.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    Text(
                                                        suggestion.reason,
                                                        color = TunelystColors.Muted,
                                                        fontSize = 10.sp,
                                                        lineHeight = 14.sp,
                                                        modifier = Modifier.padding(top = 3.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (duplicateGroups.isEmpty()) {
                            Text("No likely duplicate groups were found.", color = TunelystColors.Muted, fontSize = 13.sp)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${selectedDuplicateIds.size} selected",
                                    color = TunelystColors.Muted,
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                val allDuplicatesSelected = allDuplicateIds.isNotEmpty() && selectedDuplicateIds.containsAll(allDuplicateIds)
                                TextButton(onClick = {
                                    selectedDuplicateIds = if (allDuplicatesSelected) emptySet() else allDuplicateIds
                                }) {
                                    Text(if (allDuplicatesSelected) "Clear" else "Select all", color = TunelystColors.Primary)
                                }
                            }
                            if (duplicateGroupWouldLoseEveryCopy) {
                                Text(
                                    "Keep at least one unchecked copy in each group.",
                                    color = TunelystColors.Rose,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            if (selectedDuplicateSongs.isNotEmpty() && skippedDuplicateCount > 0) {
                                Text(
                                    "$skippedDuplicateCount shared or unsupported ${if (skippedDuplicateCount == 1) "track" else "tracks"} cannot be deleted here.",
                                    color = TunelystColors.Rose,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            LazyColumn(
                                modifier = Modifier.heightIn(min = 96.dp, max = 330.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                items(duplicateGroups, key = { it.first.songIds.first() }) { (group, groupSongs) ->
                                    Surface(
                                        color = TunelystColors.SurfaceRaised.copy(alpha = 0.38f),
                                        shape = RoundedCornerShape(15.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(11.dp)) {
                                            Text(
                                                "Possible duplicate · ${groupSongs.size} tracks",
                                                color = TunelystColors.Primary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                "${group.representativeTitle} — ${group.representativeArtist}",
                                                color = TunelystColors.Text,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                            Text(
                                                group.reason,
                                                color = TunelystColors.Muted,
                                                fontSize = 10.sp,
                                                lineHeight = 14.sp,
                                                modifier = Modifier.padding(top = 3.dp, bottom = 4.dp)
                                            )
                                            groupSongs.forEach { song ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Checkbox(
                                                        checked = song.id in selectedDuplicateIds,
                                                        onCheckedChange = { checked ->
                                                            selectedDuplicateIds = if (checked) selectedDuplicateIds + song.id else selectedDuplicateIds - song.id
                                                        }
                                                    )
                                                    Column(Modifier.weight(1f).padding(start = 3.dp)) {
                                                        Text(
                                                            "${formatTime(song.durationMs)} · ${song.title} — ${song.artist}",
                                                            color = TunelystColors.Text,
                                                            fontSize = 11.sp,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            song.fileName.ifBlank { song.folderPath.ifBlank { "Device audio" } },
                                                            color = TunelystColors.Subtle,
                                                            fontSize = 10.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                confirmingTitleFixes -> TextButton(
                    enabled = selectedFixes.isNotEmpty(),
                    onClick = { onApplyTitleFixes(selectedFixes) }
                ) { Text("Apply corrections", color = TunelystColors.Primary) }
                confirmingDuplicateDelete -> TextButton(
                    enabled = canDeleteDuplicates,
                    onClick = { onDeleteDuplicates(selectedDuplicateSongs) }
                ) { Text("Continue to Android approval", color = TunelystColors.Rose) }
                selectedTab == 0 -> TextButton(
                    enabled = selectedFixes.isNotEmpty(),
                    onClick = { confirmingTitleFixes = true }
                ) { Text("Clean selected · ${selectedFixes.size}", color = TunelystColors.Primary) }
                else -> TextButton(
                    enabled = canDeleteDuplicates,
                    onClick = { confirmingDuplicateDelete = true }
                ) { Text("Delete selected · ${selectedDuplicateSongs.size}", color = TunelystColors.Rose) }
            }
        },
        dismissButton = {
            when {
                confirmingTitleFixes -> TextButton(onClick = { confirmingTitleFixes = false }) { Text("Back", color = TunelystColors.Muted) }
                confirmingDuplicateDelete -> TextButton(onClick = { confirmingDuplicateDelete = false }) { Text("Back", color = TunelystColors.Muted) }
                else -> TextButton(onClick = onDismiss) { Text("Done", color = TunelystColors.Muted) }
            }
        },
        containerColor = TunelystColors.Surface
    )
}
