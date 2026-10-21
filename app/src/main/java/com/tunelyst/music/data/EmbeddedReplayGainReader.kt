package com.tunelyst.music.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Reads common, read-only embedded ReplayGain tags without loading audio payloads. */
internal object EmbeddedReplayGainReader {
    private const val MAX_HEAD_BYTES = 256 * 1024
    private const val MAX_TAIL_BYTES = 128 * 1024
    private const val MAX_TAG_BYTES = 8 * 1024 * 1024
    private val gainPattern = Regex("([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*(?:dB)?", RegexOption.IGNORE_CASE)
    private val rawCommentPattern = Regex(
        "(?is)REPLAYGAIN_(TRACK|ALBUM)_GAIN[=:\\s]+([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*(?:dB)?"
    )

    private data class Gains(val track: Float? = null, val album: Float? = null) {
        fun preferred(): Float? = track ?: album
        fun merge(other: Gains) = Gains(track ?: other.track, album ?: other.album)
    }

    fun read(context: Context, uri: Uri): Float? = runCatching {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val channel = (input as? FileInputStream)?.channel ?: return null
            val size = channel.size()
            if (size <= 0L) return null
            val head = readAt(channel, 0L, minOf(size, MAX_HEAD_BYTES.toLong()).toInt())
            val tailLength = minOf(size, MAX_TAIL_BYTES.toLong()).toInt()
            val tail = readAt(channel, (size - tailLength).coerceAtLeast(0L), tailLength)
            val headGains = parseId3(head)
                .merge(parseFlacComments(head))
                .merge(parseRawComments(head))
            val tailGains = parseApeItems(tail)
                .merge(parseRawComments(tail))
            (headGains.merge(tailGains)).preferred()?.coerceIn(-24f, 24f)
        }
    }.getOrNull()

    private fun readAt(channel: java.nio.channels.FileChannel, position: Long, requested: Int): ByteArray {
        if (requested <= 0) return ByteArray(0)
        val buffer = ByteBuffer.allocate(requested)
        var offset = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, offset)
            if (read <= 0) break
            offset += read
        }
        return buffer.array().copyOf(buffer.position())
    }

    private fun parseId3(bytes: ByteArray): Gains {
        if (bytes.size < 10 || bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) return Gains()
        val version = bytes[3].toInt() and 0xff
        if (version !in 2..4) return Gains()
        val tagSize = if (version == 2) synchsafe(bytes, 6, 3) else synchsafe(bytes, 6, 4)
        if (tagSize <= 0 || tagSize > MAX_TAG_BYTES) return Gains()
        val end = minOf(bytes.size, 10 + tagSize)
        var offset = 10
        if ((bytes[5].toInt() and 0x40) != 0) {
            if (version == 3 && offset + 4 <= end) offset += 4 + uint32be(bytes, offset).coerceAtLeast(0)
            else if (version == 4 && offset + 4 <= end) offset += synchsafe(bytes, offset, 4).coerceAtLeast(0)
        }
        var track: Float? = null
        var album: Float? = null
        while (offset < end) {
            val headerSize = if (version == 2) 6 else 10
            if (offset + headerSize > end) break
            val idLength = if (version == 2) 3 else 4
            val frameId = ascii(bytes, offset, idLength)
            if (frameId.isBlank() || frameId.all { it == '\u0000' }) break
            val frameSize = when (version) {
                2 -> uint24be(bytes, offset + 3)
                3 -> uint32be(bytes, offset + 4)
                else -> synchsafe(bytes, offset + 4, 4)
            }
            if (frameSize <= 0 || frameSize > end - offset - headerSize) break
            val userTextFrame = if (version == 2) frameId == "TXX" else frameId == "TXXX"
            if (userTextFrame) {
                val parsed = parseUserText(bytes, offset + headerSize, frameSize)
                when (parsed.first) {
                    "replaygain_track_gain" -> track = parseGain(parsed.second) ?: track
                    "replaygain_album_gain" -> album = parseGain(parsed.second) ?: album
                }
            }
            offset += headerSize + frameSize
        }
        return Gains(track, album)
    }

    private fun parseUserText(bytes: ByteArray, offset: Int, length: Int): Pair<String, String> {
        if (length <= 1 || offset + length > bytes.size) return "" to ""
        val encoding = bytes[offset].toInt() and 0xff
        val charset = when (encoding) {
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> StandardCharsets.ISO_8859_1
        }
        val wide = encoding == 1 || encoding == 2
        val contentStart = offset + 1
        val end = offset + length
        var terminator = contentStart
        while (terminator < end) {
            if (bytes[terminator] == 0.toByte() && (!wide || terminator + 1 < end && bytes[terminator + 1] == 0.toByte())) break
            terminator += if (wide) 2 else 1
        }
        val description = decode(bytes, contentStart, (terminator - contentStart).coerceAtLeast(0), charset)
            .replace("\u0000", "").trim().lowercase(Locale.ROOT)
        val valueStart = (terminator + if (wide) 2 else 1).coerceAtMost(end)
        val value = decode(bytes, valueStart, end - valueStart, charset).replace("\u0000", "").trim()
        return description to value
    }

    private fun parseFlacComments(bytes: ByteArray): Gains {
        if (bytes.size < 8 || ascii(bytes, 0, 4) != "fLaC") return Gains()
        var offset = 4
        while (offset + 4 <= bytes.size) {
            val header = bytes[offset].toInt() and 0xff
            val blockType = header and 0x7f
            val length = uint24be(bytes, offset + 1)
            val dataStart = offset + 4
            if (length < 0 || dataStart + length > bytes.size) return Gains()
            if (blockType == 4) return parseVorbisComments(bytes, dataStart, length)
            offset = dataStart + length
            if ((header and 0x80) != 0) break
        }
        return Gains()
    }

    private fun parseVorbisComments(bytes: ByteArray, offset: Int, length: Int): Gains {
        val end = offset + length
        var cursor = offset
        if (cursor + 4 > end) return Gains()
        val vendorLength = uint32le(bytes, cursor)
        cursor += 4 + vendorLength
        if (vendorLength < 0 || cursor + 4 > end) return Gains()
        val count = uint32le(bytes, cursor).coerceIn(0, 50_000)
        cursor += 4
        var track: Float? = null
        var album: Float? = null
        for (index in 0 until count) {
            if (cursor + 4 > end) break
            val itemLength = uint32le(bytes, cursor)
            cursor += 4
            if (itemLength < 0 || cursor + itemLength > end) break
            val item = decode(bytes, cursor, itemLength, StandardCharsets.UTF_8)
            val separator = item.indexOf('=')
            if (separator > 0) {
                when (item.substring(0, separator).trim().lowercase(Locale.ROOT)) {
                    "replaygain_track_gain" -> track = parseGain(item.substring(separator + 1)) ?: track
                    "replaygain_album_gain" -> album = parseGain(item.substring(separator + 1)) ?: album
                }
            }
            cursor += itemLength
        }
        return Gains(track, album)
    }

    private fun parseRawComments(bytes: ByteArray): Gains {
        if (bytes.isEmpty()) return Gains()
        val text = String(bytes, StandardCharsets.ISO_8859_1).replace('\u0000', ' ')
        var track: Float? = null
        var album: Float? = null
        rawCommentPattern.findAll(text).forEach { match ->
            val value = parseGain(match.groupValues.getOrNull(2).orEmpty()) ?: return@forEach
            when (match.groupValues[1].uppercase(Locale.ROOT)) {
                "TRACK" -> if (track == null) track = value
                "ALBUM" -> if (album == null) album = value
            }
        }
        return Gains(track, album)
    }

    private fun parseApeItems(bytes: ByteArray): Gains {
        if (bytes.isEmpty()) return Gains()
        val marker = "APETAGEX".toByteArray(StandardCharsets.US_ASCII)
        var footer = -1
        for (index in bytes.size - marker.size downTo 0) {
            if (matches(bytes, index, marker)) {
                footer = index
                break
            }
        }
        val end = if (footer >= 0 && footer + 32 <= bytes.size) footer else bytes.size
        var track: Float? = null
        var album: Float? = null
        val trackKey = "replaygain_track_gain".toByteArray(StandardCharsets.US_ASCII)
        val albumKey = "replaygain_album_gain".toByteArray(StandardCharsets.US_ASCII)
        for (index in 8 until end) {
            val keyLength = when {
                matchesIgnoreCase(bytes, index, trackKey) -> trackKey.size
                matchesIgnoreCase(bytes, index, albumKey) -> albumKey.size
                else -> continue
            }
            val valueStart = index + keyLength + 1
            if (valueStart > end) continue
            val valueEnd = bytes.indexOfZero(valueStart, end)
            val value = decode(bytes, valueStart, valueEnd - valueStart, StandardCharsets.ISO_8859_1)
            val gain = parseGain(value) ?: continue
            if (keyLength == trackKey.size && matchesIgnoreCase(bytes, index, trackKey)) track = track ?: gain
            else album = album ?: gain
        }
        return Gains(track, album)
    }

    private fun parseGain(text: String): Float? = gainPattern.find(text)?.groupValues?.getOrNull(1)
        ?.toFloatOrNull()?.takeIf(Float::isFinite)?.coerceIn(-24f, 24f)

    private fun synchsafe(bytes: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || offset + length > bytes.size) return -1
        var value = 0
        repeat(length) { index ->
            val part = bytes[offset + index].toInt() and 0x7f
            value = (value shl 7) or part
        }
        return value
    }

    private fun uint24be(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 3 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0xff) shl 16) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or (bytes[offset + 2].toInt() and 0xff)
    }

    private fun uint32be(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) return -1
        val value = ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or (bytes[offset + 3].toLong() and 0xffL)
        return value.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: -1
    }

    private fun uint32le(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) return -1
        val value = (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)
        return value.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: -1
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        if (offset < 0 || length < 0 || offset + length > bytes.size) "" else
            String(bytes, offset, length, StandardCharsets.ISO_8859_1)

    private fun decode(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String =
        if (offset < 0 || length <= 0 || offset + length > bytes.size) "" else
            String(bytes, offset, length, charset)

    private fun matches(bytes: ByteArray, offset: Int, value: ByteArray): Boolean =
        offset >= 0 && offset + value.size <= bytes.size && value.indices.all { bytes[offset + it] == value[it] }

    private fun matchesIgnoreCase(bytes: ByteArray, offset: Int, value: ByteArray): Boolean =
        offset >= 0 && offset + value.size <= bytes.size && value.indices.all { index ->
            (bytes[offset + index].toInt() and 0xff).toChar().lowercaseChar() ==
                (value[index].toInt() and 0xff).toChar().lowercaseChar()
        }

    private fun ByteArray.indexOfZero(from: Int, until: Int): Int {
        for (index in from until until) if (this[index] == 0.toByte()) return index
        return until
    }
}
