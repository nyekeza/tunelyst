package com.tunelyst.music.ui

import androidx.compose.runtime.saveable.Saver

internal enum class AppTab { HOME, SEARCH, LIBRARY, LIKED, LISTENING_STATS, SETTINGS }

internal data class PendingDeviceAudioDeletion(
    val description: String,
    val uriStrings: List<String>,
    val skippedCount: Int
)

internal val pendingDeviceDeletionSaver = Saver<PendingDeviceAudioDeletion?, String>(
    save = { request ->
        request?.let {
            org.json.JSONObject()
                .put("description", it.description)
                .put("uris", org.json.JSONArray(it.uriStrings))
                .put("skipped", it.skippedCount)
                .toString()
        } ?: ""
    },
    restore = { encoded ->
        if (encoded.isBlank()) null else runCatching {
            val json = org.json.JSONObject(encoded)
            val uriArray = json.optJSONArray("uris") ?: org.json.JSONArray()
            PendingDeviceAudioDeletion(
                description = json.optString("description"),
                uriStrings = List(uriArray.length()) { index -> uriArray.optString(index) }.filter(String::isNotBlank),
                skippedCount = json.optInt("skipped").coerceAtLeast(0)
            )
        }.getOrNull()
    }
)

internal val titleFixRequestsSaver = Saver<List<TitleFixRequest>, String>(
    save = { requests ->
        org.json.JSONArray().apply {
            requests.forEach { request ->
                put(
                    org.json.JSONObject()
                        .put("songId", request.songId)
                        .put("title", request.suggestedTitle)
                        .put("uri", request.mediaUriString.orEmpty())
                        .put("currentName", request.currentFileName)
                        .put("newName", request.newFileName)
                        .put("folderPath", request.folderPath)
                        .put("currentTitle", request.currentTitle)
                        .put("currentArtist", request.currentArtist)
                        .put("suggestedArtist", request.suggestedArtist.orEmpty())
                )
            }
        }.toString()
    },
    restore = { encoded ->
        runCatching {
            val json = org.json.JSONArray(encoded)
            List(json.length()) { index ->
                val item = json.getJSONObject(index)
                TitleFixRequest(
                    songId = item.optLong("songId"),
                    suggestedTitle = item.optString("title"),
                    mediaUriString = item.optString("uri").takeIf(String::isNotBlank),
                    currentFileName = item.optString("currentName"),
                    newFileName = item.optString("newName"),
                    folderPath = item.optString("folderPath"),
                    currentTitle = item.optString("currentTitle"),
                    currentArtist = item.optString("currentArtist"),
                    suggestedArtist = item.optString("suggestedArtist").takeIf(String::isNotBlank)
                )
            }
        }.getOrDefault(emptyList())
    }
)
