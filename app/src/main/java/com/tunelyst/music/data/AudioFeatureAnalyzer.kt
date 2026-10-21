package com.tunelyst.music.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import org.json.JSONObject
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** On-device descriptors derived from a short decoded window; no audio leaves the device. */
data class LocalAudioFeatures(
    val tempoBpm: Float?,
    val tempoConfidence: Float,
    /** Relative RMS loudness mapped to 0..1 (a useful ranking feature, not a mood label). */
    val energy: Float,
    val analyzedDurationMs: Long
)

/** Persistent cache for derived audio descriptors. It is deliberately excluded from portable backups. */
class LocalAudioFeatureStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val entries = ConcurrentHashMap<Long, CachedFeatures>()

    /** Loads the small derived-feature cache on a worker thread before any ranking requests it. */
    fun loadCache() {
        preferences.all.forEach { (key, value) ->
            val songId = key.removePrefix(KEY_PREFIX).toLongOrNull()
                ?.takeIf { key.startsWith(KEY_PREFIX) && it >= 0L } ?: return@forEach
            val raw = value as? String ?: return@forEach
            val cached = runCatching {
                val json = JSONObject(raw)
                val tempo = if (json.isNull("tempo_bpm")) null else json.optDouble("tempo_bpm").toFloat()
                val energy = json.optDouble("energy", Double.NaN).toFloat()
                val confidence = json.optDouble("tempo_confidence", 0.0).toFloat()
                if (!energy.isFinite() || !confidence.isFinite()) return@runCatching null
                CachedFeatures(
                    signature = json.optString("signature"),
                    features = LocalAudioFeatures(
                        tempoBpm = tempo?.takeIf { it.isFinite() && it in MIN_TEMPO_BPM..MAX_TEMPO_BPM },
                        tempoConfidence = confidence.coerceIn(0f, 1f),
                        energy = energy.coerceIn(0f, 1f),
                        analyzedDurationMs = json.optLong("duration_ms", 0L).coerceAtLeast(0L)
                    )
                )
            }.getOrNull() ?: return@forEach
            entries[songId] = cached
        }
    }

    fun featuresFor(song: Song): LocalAudioFeatures? = entries[song.id]
        ?.takeIf { it.signature == signature(song) }
        ?.features

    /** Decode and analyze at most a short PCM window. Call from Dispatchers.IO. */
    fun analyze(song: Song): LocalAudioFeatures? {
        if (song.id < 0L) return null
        featuresFor(song)?.let { return it }
        val features = LocalAudioFeatureDecoder.analyze(appContext, song.uri, song.durationMs) ?: return null
        val cached = CachedFeatures(signature(song), features)
        entries[song.id] = cached
        preferences.edit().putString(cacheKey(song.id), cached.toJson().toString()).apply()
        return features
    }

    private fun signature(song: Song): String = listOf(
        song.id,
        song.uri.toString(),
        song.durationMs,
        song.sizeBytes,
        song.dateAddedSeconds,
        song.mimeType
    ).joinToString("|")

    private fun cacheKey(songId: Long) = KEY_PREFIX + songId

    private data class CachedFeatures(val signature: String, val features: LocalAudioFeatures) {
        fun toJson(): JSONObject = JSONObject()
            .put("signature", signature)
            .put("tempo_bpm", features.tempoBpm ?: JSONObject.NULL)
            .put("tempo_confidence", features.tempoConfidence)
            .put("energy", features.energy)
            .put("duration_ms", features.analyzedDurationMs)
    }

    private companion object {
        const val PREFERENCES_NAME = "tunelyst_audio_features"
        const val KEY_PREFIX = "track_"
        const val MIN_TEMPO_BPM = 45f
        const val MAX_TEMPO_BPM = 220f
    }
}

private object LocalAudioFeatureDecoder {
    private const val MAX_ANALYSIS_SECONDS = 18
    private const val MAX_TARGET_SAMPLE_RATE = 24_000
    private const val MAX_CODEC_WALL_TIME_MS = 8_000L
    private const val CODEC_TIMEOUT_US = 8_000L

    fun analyze(context: Context, uri: Uri, durationMs: Long): LocalAudioFeatures? =
        runCatching { decodeAndAnalyze(context, uri, durationMs) }.getOrNull()

    private fun decodeAndAnalyze(context: Context, uri: Uri, durationMs: Long): LocalAudioFeatures? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        try {
            extractor.setDataSource(context, uri, null)
            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                runCatching { extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    .getOrDefault(false)
            } ?: return null
            val inputFormat = extractor.getTrackFormat(audioTrack)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val inputSampleRate = inputFormat.integerOrNull(MediaFormat.KEY_SAMPLE_RATE)?.takeIf { it > 0 } ?: return null
            val inputChannels = inputFormat.integerOrNull(MediaFormat.KEY_CHANNEL_COUNT)?.takeIf { it > 0 } ?: return null
            extractor.selectTrack(audioTrack)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            codecStarted = true

            var sampleRate = inputSampleRate
            var channelCount = inputChannels.coerceIn(1, 8)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var downsampleStride = downsampleStride(sampleRate)
            var effectiveSampleRate = (sampleRate / downsampleStride).coerceAtLeast(1)
            val pcm = FloatArray(MAX_TARGET_SAMPLE_RATE * MAX_ANALYSIS_SECONDS)
            var pcmCount = 0
            var sourceFrameIndex = 0L
            var firstSampleTimeUs: Long? = null
            var inputEnded = false
            var outputEnded = false
            var steps = 0
            val deadlineMs = SystemClock.elapsedRealtime() + MAX_CODEC_WALL_TIME_MS
            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputEnded && steps++ < MAX_DECODE_STEPS && SystemClock.elapsedRealtime() < deadlineMs) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer == null) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            inputBuffer.clear()
                            val sampleTimeUs = extractor.sampleTime
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            val firstTime = firstSampleTimeUs ?: sampleTimeUs.takeIf { it >= 0L }
                            if (firstSampleTimeUs == null && firstTime != null) firstSampleTimeUs = firstTime
                            val elapsedUs = if (sampleTimeUs >= 0L && firstTime != null) sampleTimeUs - firstTime else -1L
                            val requestedDurationUs = minOf(durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE, MAX_ANALYSIS_SECONDS * 1_000L) * 1_000L
                            if (sampleSize < 0 || sampleTimeUs < 0L || elapsedUs >= requestedDurationUs) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    sampleTimeUs.coerceAtLeast(0L),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        val newRate = outputFormat.integerOrNull(MediaFormat.KEY_SAMPLE_RATE)?.takeIf { it > 0 } ?: sampleRate
                        val newChannels = outputFormat.integerOrNull(MediaFormat.KEY_CHANNEL_COUNT)?.takeIf { it > 0 } ?: channelCount
                        val newEncoding = outputFormat.integerOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
                        if (pcmCount == 0) {
                            sampleRate = newRate
                            channelCount = newChannels.coerceIn(1, 8)
                            pcmEncoding = newEncoding
                            downsampleStride = downsampleStride(sampleRate)
                            effectiveSampleRate = (sampleRate / downsampleStride).coerceAtLeast(1)
                        }
                    }
                    outputIndex >= 0 -> {
                        if (bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null) {
                                outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                                val end = (bufferInfo.offset + bufferInfo.size).coerceAtMost(outputBuffer.capacity())
                                outputBuffer.position(bufferInfo.offset.coerceAtLeast(0).coerceAtMost(end))
                                outputBuffer.limit(end)
                                val bytesPerSample = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                val bytesPerFrame = bytesPerSample * channelCount
                                if (bytesPerFrame > 0) {
                                    val frameCount = outputBuffer.remaining() / bytesPerFrame
                                    repeat(frameCount) {
                                        var mono = 0f
                                        repeat(channelCount) {
                                            val value = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                                outputBuffer.float.coerceIn(-1f, 1f)
                                            } else {
                                                outputBuffer.short.toFloat() / 32768f
                                            }
                                            mono += value
                                        }
                                        if (sourceFrameIndex % downsampleStride == 0L && pcmCount < pcm.size) {
                                            pcm[pcmCount++] = mono / channelCount
                                        }
                                        sourceFrameIndex++
                                    }
                                }
                            }
                        }
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
                if (pcmCount >= pcm.size) outputEnded = true
            }
            if (pcmCount < effectiveSampleRate) return null
            return AudioFeatureMath.analyzePcm(pcm.copyOf(pcmCount), effectiveSampleRate)
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun downsampleStride(sampleRate: Int): Int =
        ceil(sampleRate.toDouble() / MAX_TARGET_SAMPLE_RATE.toDouble()).toInt().coerceAtLeast(1)

    private fun MediaFormat.integerOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private const val MAX_DECODE_STEPS = 10_000
}

/** Pure PCM feature extraction, kept separate so the ranking signals can be unit-tested off-device. */
internal object AudioFeatureMath {
    private const val FFT_SIZE = 1_024
    private const val HOP_SIZE = 512
    private const val MIN_TEMPO_BPM = 55f
    private const val MAX_TEMPO_BPM = 180f

    fun analyzePcm(samples: FloatArray, sampleRateHz: Int): LocalAudioFeatures? {
        if (sampleRateHz <= 0 || samples.isEmpty()) return null
        var squareSum = 0.0
        samples.forEach { sample ->
            val safe = sample.takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
            squareSum += safe.toDouble() * safe.toDouble()
        }
        val rms = sqrt(squareSum / samples.size.toDouble()).toFloat()
        val dbfs = 20f * (ln(rms.coerceAtLeast(0.000001f).toDouble()) / ln(10.0)).toFloat()
        val energy = ((dbfs + 45f) / 35f).coerceIn(0f, 1f)
        val durationMs = (samples.size.toLong() * 1_000L / sampleRateHz).coerceAtLeast(0L)
        val (tempo, confidence) = estimateTempo(samples, sampleRateHz)
        return LocalAudioFeatures(tempo, confidence, energy, durationMs)
    }

    private fun estimateTempo(samples: FloatArray, sampleRateHz: Int): Pair<Float?, Float> {
        if (samples.size < sampleRateHz * 6 || sampleRateHz < 8_000) return null to 0f
        val frameCount = 1 + (samples.size - FFT_SIZE).coerceAtLeast(0) / HOP_SIZE
        if (frameCount < 100) return null to 0f
        val window = FloatArray(FFT_SIZE) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / (FFT_SIZE - 1))).toFloat()
        }
        val real = FloatArray(FFT_SIZE)
        val imaginary = FloatArray(FFT_SIZE)
        val previousMagnitude = FloatArray(FFT_SIZE / 2 + 1)
        val onset = FloatArray(frameCount)
        var previousSum = 0f

        for (frame in 0 until frameCount) {
            val offset = frame * HOP_SIZE
            for (index in 0 until FFT_SIZE) {
                real[index] = samples[offset + index] * window[index]
                imaginary[index] = 0f
            }
            fft(real, imaginary)
            var positiveFlux = 0f
            var magnitudeSum = 0f
            for (bin in 1..FFT_SIZE / 2) {
                val magnitude = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
                if (frame > 0) positiveFlux += (magnitude - previousMagnitude[bin]).coerceAtLeast(0f)
                magnitudeSum += magnitude
                previousMagnitude[bin] = magnitude
            }
            if (frame > 0) {
                val normalizedFlux = positiveFlux / previousSum.coerceAtLeast(0.0001f)
                val energyRise = (magnitudeSum - previousSum).coerceAtLeast(0f) / previousSum.coerceAtLeast(0.0001f)
                onset[frame] = (normalizedFlux * 0.78f + energyRise * 0.22f).coerceAtMost(10f)
            }
            previousSum = magnitudeSum
        }

        val mean = onset.average().toFloat()
        val centered = FloatArray(onset.size) { index -> (onset[index] - mean * 0.72f).coerceAtLeast(0f) }
        val totalEnergy = centered.sumOf { it.toDouble() }
        if (totalEnergy < 0.01) return null to 0f
        val framesPerSecond = sampleRateHz.toDouble() / HOP_SIZE.toDouble()
        val minLag = floor(framesPerSecond * 60.0 / MAX_TEMPO_BPM).toInt().coerceAtLeast(1)
        val maxLag = ceil(framesPerSecond * 60.0 / MIN_TEMPO_BPM).toInt().coerceAtMost(centered.size / 2)
        var bestTempo: Float? = null
        var bestCorrelation = 0f
        var bestPreferredRange = false
        var preferredTempo: Float? = null
        var preferredCorrelation = 0f
        val lagSubdivisions = 4
        for (lagStep in minLag * lagSubdivisions..maxLag * lagSubdivisions) {
            val lag = lagStep.toDouble() / lagSubdivisions
            var dot = 0.0
            var leftEnergy = 0.0
            var rightEnergy = 0.0
            for (index in ceil(lag).toInt() until centered.size) {
                val left = centered[index].toDouble()
                val shiftedIndex = index - lag
                val lowerIndex = shiftedIndex.toInt()
                val fraction = shiftedIndex - lowerIndex
                val right = centered[lowerIndex] * (1.0 - fraction) + centered[lowerIndex + 1] * fraction
                dot += left * right
                leftEnergy += left * left
                rightEnergy += right * right
            }
            val denominator = sqrt(leftEnergy * rightEnergy)
            if (denominator <= 0.0000001) continue
            val correlation = (dot / denominator).toFloat().coerceIn(0f, 1f)
            val bpm = (60.0 * framesPerSecond / lag).toFloat()
            val preferredRange = bpm in 90f..140f
            if (preferredRange && correlation > preferredCorrelation) {
                preferredTempo = bpm
                preferredCorrelation = correlation
            }
            if (correlation > bestCorrelation + 0.0001f ||
                (abs(correlation - bestCorrelation) <= 0.0001f && preferredRange && !bestPreferredRange)
            ) {
                bestCorrelation = correlation
                bestTempo = bpm
                bestPreferredRange = preferredRange
            }
        }
        // Autocorrelation can favor a bar-level half-tempo peak; prefer a plausible beat-range peak when its signal remains substantial.
        val preferMidrangeTempo = preferredTempo != null && preferredCorrelation >= bestCorrelation * 0.65f
        val selectedTempo = if (preferMidrangeTempo) preferredTempo else bestTempo
        val selectedConfidence = if (preferMidrangeTempo) preferredCorrelation else bestCorrelation
        if (selectedConfidence < 0.08f) return null to selectedConfidence
        return selectedTempo to selectedConfidence
    }

    private fun fft(real: FloatArray, imaginary: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val realSwap = real[i]
                real[i] = real[j]
                real[j] = realSwap
                val imaginarySwap = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = imaginarySwap
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length.toDouble()
            val stepReal = cos(angle).toFloat()
            val stepImaginary = sin(angle).toFloat()
            val half = length / 2
            var start = 0
            while (start < n) {
                var twiddleReal = 1f
                var twiddleImaginary = 0f
                for (offset in 0 until half) {
                    val even = start + offset
                    val odd = even + half
                    val oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary
                    val oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal
                    val evenReal = real[even]
                    val evenImaginary = imaginary[even]
                    real[even] = evenReal + oddReal
                    imaginary[even] = evenImaginary + oddImaginary
                    real[odd] = evenReal - oddReal
                    imaginary[odd] = evenImaginary - oddImaginary
                    val nextTwiddleReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary
                    twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
                    twiddleReal = nextTwiddleReal
                }
                start += length
            }
            length = length shl 1
        }
    }
}
