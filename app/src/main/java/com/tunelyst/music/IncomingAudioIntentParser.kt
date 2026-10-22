package com.tunelyst.music

import android.content.Intent
import android.net.Uri

/** Collects audio locations shared through Android's standard intent fields. */
internal object IncomingAudioIntentParser {
    private val audioIntentActions = setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW)

    fun collect(intent: Intent): List<Uri> {
        if (intent.action !in audioIntentActions) return emptyList()

        val sharedUris = linkedSetOf<Uri>()

        fun addSharedValue(value: Any?) {
            when (value) {
                is Uri -> sharedUris += value
                is String -> runCatching { Uri.parse(value) }.getOrNull()?.let(sharedUris::add)
                is Iterable<*> -> value.forEach(::addSharedValue)
                is Array<*> -> value.forEach(::addSharedValue)
            }
        }

        // EXTRA_STREAM is standard, but some file managers only populate data or ClipData.
        runCatching { intent.extras?.get(Intent.EXTRA_STREAM) }
            .getOrNull()
            ?.let(::addSharedValue)
        intent.data?.let(sharedUris::add)
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(sharedUris::add)
        }

        return sharedUris.filter { it.scheme == "content" || it.scheme == "file" }
    }
}
