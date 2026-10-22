package com.tunelyst.music

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingAudioIntentParserInstrumentedTest {
    @Test
    fun sendIntentCombinesStreamDataAndClipDataWithoutDuplicates() {
        val streamUri = Uri.parse("content://example.test/audio/1")
        val dataUri = Uri.parse("content://example.test/audio/2")
        val clipUri = Uri.parse("content://example.test/audio/3")
        val clipData = ClipData(
            "shared audio",
            arrayOf("audio/mpeg"),
            ClipData.Item(streamUri)
        ).apply {
            addItem(ClipData.Item(clipUri))
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, streamUri)
            data = dataUri
            this.clipData = clipData
        }

        assertEquals(
            listOf(streamUri, dataUri, clipUri),
            IncomingAudioIntentParser.collect(intent)
        )
    }

    @Test
    fun sendMultipleKeepsReadableUrisAndDropsUnsupportedSchemes() {
        val contentUri = Uri.parse("content://example.test/audio/4")
        val fileUri = Uri.parse("file:///storage/emulated/0/Music/test.mp3")
        val webUri = Uri.parse("https://example.test/audio.mp3")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(contentUri, fileUri, webUri, contentUri))
        }

        assertEquals(
            listOf(contentUri, fileUri),
            IncomingAudioIntentParser.collect(intent)
        )
    }

    @Test
    fun unrelatedActionsAreIgnored() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://example.test/audio/5"))
        }

        assertTrue(IncomingAudioIntentParser.collect(intent).isEmpty())
    }
}
