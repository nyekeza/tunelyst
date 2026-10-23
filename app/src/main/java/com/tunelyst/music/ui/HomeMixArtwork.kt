package com.tunelyst.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

import com.tunelyst.music.data.Song

@Composable
internal fun MixArtworkCollage(
    songs: List<Song>,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(19.dp)
) {
    val covers = songs.take(4)
    val tileShape = RoundedCornerShape(3.dp)
    Box(
        modifier = modifier.clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF155C69), Color(0xFF242A43), Color(0xFF382444))))
    ) {
        when (covers.size) {
            1 -> AlbumArtwork(covers[0].artworkUri, covers[0].title, Modifier.fillMaxSize(), shape)
            2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                covers.forEach { song ->
                    AlbumArtwork(song.artworkUri, song.title, Modifier.weight(1f).fillMaxHeight(), tileShape)
                }
            }
            3 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                AlbumArtwork(covers[0].artworkUri, covers[0].title, Modifier.weight(1f).fillMaxHeight(), tileShape)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AlbumArtwork(covers[1].artworkUri, covers[1].title, Modifier.weight(1f).fillMaxWidth(), tileShape)
                    AlbumArtwork(covers[2].artworkUri, covers[2].title, Modifier.weight(1f).fillMaxWidth(), tileShape)
                }
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    AlbumArtwork(covers[0].artworkUri, covers[0].title, Modifier.weight(1f).fillMaxHeight(), tileShape)
                    AlbumArtwork(covers[1].artworkUri, covers[1].title, Modifier.weight(1f).fillMaxHeight(), tileShape)
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    AlbumArtwork(covers[2].artworkUri, covers[2].title, Modifier.weight(1f).fillMaxHeight(), tileShape)
                    AlbumArtwork(covers[3].artworkUri, covers[3].title, Modifier.weight(1f).fillMaxHeight(), tileShape)
                }
            }
        }
    }
}
