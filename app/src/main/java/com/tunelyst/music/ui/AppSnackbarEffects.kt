package com.tunelyst.music.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.tunelyst.music.data.Song

@Composable
internal fun AppSnackbarEffects(
    viewModel: MusicViewModel,
    embeddedTagWriteMessage: String?,
    playerConnectionError: String?,
    shouldPromptForSmartShuffle: Boolean,
    currentLibrarySongs: List<Song>,
    snackbarHostState: SnackbarHostState
) {
    LaunchedEffect(embeddedTagWriteMessage) {
        val message = embeddedTagWriteMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearEmbeddedTagWriteMessage(message)
    }
    LaunchedEffect(playerConnectionError) {
        val message = playerConnectionError ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Retry",
            withDismissAction = true,
            duration = androidx.compose.material3.SnackbarDuration.Long
        )
        viewModel.clearPlayerConnectionError()
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            viewModel.retryPlayerConnection()
        }
    }
    LaunchedEffect(shouldPromptForSmartShuffle) {
        if (!shouldPromptForSmartShuffle) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Mix finished · continue with Smart Shuffle?",
            actionLabel = "Continue",
            withDismissAction = true,
            duration = androidx.compose.material3.SnackbarDuration.Long
        )
        viewModel.clearSmartShuffleCompletionPrompt()
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            viewModel.playSmartShuffle(currentLibrarySongs)
        }
    }
}
