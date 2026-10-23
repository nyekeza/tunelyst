package com.tunelyst.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunelyst.music.data.Song

@Composable
internal fun FullPlayerArtworkAndMetadata(
    song: Song,
    artworkSize: Dp,
    layoutGap: Dp,
    lowMemoryDevice: Boolean,
    accent: Color,
    artworkSwipeThresholdPx: Float,
    motionEnabled: Boolean,
    isFavorite: Boolean,
    favoriteInteraction: MutableInteractionSource,
    onTogglePlayback: () -> Unit,
    onShowSongInformation: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (Song) -> Unit,
    onToggleFavorite: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(layoutGap)
    ) {
        Box(
            modifier = Modifier
                .width(artworkSize)
                .aspectRatio(1f)
                .combinedClickable(
                    onClick = { /* Single tap is intentionally reserved for artwork gestures. */ },
                    onDoubleClick = onTogglePlayback,
                    onLongClick = { onShowSongInformation() }
                )
                .semantics {
                    contentDescription = "Artwork for ${song.title}. Double tap to pause or play, swipe to change songs, or press and hold for song information."
                }
                .pointerInput(song.id, artworkSwipeThresholdPx, onPrevious, onNext) {
                    var horizontalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { horizontalDrag = 0f },
                        onHorizontalDrag = { change, amount ->
                            horizontalDrag += amount
                            change.consume()
                        },
                        onDragEnd = {
                            when {
                                horizontalDrag >= artworkSwipeThresholdPx -> onPrevious()
                                horizontalDrag <= -artworkSwipeThresholdPx -> onNext()
                            }
                        },
                        onDragCancel = { horizontalDrag = 0f }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(20.dp)
                    .then(if (lowMemoryDevice) Modifier else Modifier.blur(24.dp))
                    .background(accent.copy(alpha = 0.43f), RoundedCornerShape(36.dp))
            )
            AlbumArtwork(
                artworkUri = song.artworkUri,
                title = song.title,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(28.dp),
                crossfade = true
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    color = TunelystColors.Text,
                    fontSize = 23.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = if (motionEnabled) Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE) else Modifier.fillMaxWidth()
                )
                val albumTitle = song.album.takeUnless { it.isBlank() || it.equals("Unknown album", ignoreCase = true) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val artistWidth = song.artist.length.coerceIn(8, 36).toFloat()
                    Text(
                        text = song.artist,
                        color = TunelystColors.Muted,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(if (albumTitle == null) 1f else artistWidth, fill = false)
                            .springyClickable(role = androidx.compose.ui.semantics.Role.Button) {
                                onOpenArtist(song.artist)
                            }
                            .semantics { contentDescription = "Open artist ${song.artist}" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    albumTitle?.let { album ->
                        Text("•", color = TunelystColors.Muted, fontSize = 17.sp, fontWeight = FontWeight.Black)
                        Text(
                            text = album,
                            color = TunelystColors.Muted,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(album.length.coerceIn(8, 36).toFloat(), fill = false)
                                .springyClickable(role = androidx.compose.ui.semantics.Role.Button) {
                                    onOpenAlbum(song)
                                }
                                .semantics { contentDescription = "Open album $album" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(48.dp).clip(CircleShape).background(TunelystColors.SurfaceRaised.copy(alpha = 0.72f)).springyPress(favoriteInteraction),
                interactionSource = favoriteInteraction
            ) {
                ExpressiveToggleIcon(
                    selected = isFavorite,
                    selectedIcon = Icons.Rounded.Favorite,
                    unselectedIcon = Icons.Rounded.FavoriteBorder,
                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (isFavorite) TunelystColors.Rose else TunelystColors.Text,
                    size = 22.dp,
                    label = "now-playing-favorite-icon"
                )
            }
        }
    }
}
