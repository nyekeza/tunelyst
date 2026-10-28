package com.tunelyst.music.playback

/** Pure policy for bounded native-heap PSS monitoring while the background playback service is active. */
internal object PlaybackMemoryGuardPolicy {
    const val SAMPLE_INTERVAL_MS = 15_000L
    const val REQUIRED_HIGH_SAMPLES = 3
    const val COOLDOWN_MS = 120_000L

    private const val MIB = 1024L * 1024L
    private const val MIN_THRESHOLD_BYTES = 320L * MIB
    private const val MAX_THRESHOLD_BYTES = 1_024L * MIB
    private const val MIN_GROWTH_AFTER_RECOVERY_BYTES = 192L * MIB
    private const val STANDARD_DEVICE_FRACTION = 0.10
    private const val LOW_RAM_DEVICE_FRACTION = 0.08

    fun thresholdBytes(totalDeviceMemoryBytes: Long, lowRamDevice: Boolean): Long {
        if (totalDeviceMemoryBytes <= 0L) return MIN_THRESHOLD_BYTES
        val fraction = if (lowRamDevice) LOW_RAM_DEVICE_FRACTION else STANDARD_DEVICE_FRACTION
        return (totalDeviceMemoryBytes.toDouble() * fraction)
            .toLong()
            .coerceIn(MIN_THRESHOLD_BYTES, MAX_THRESHOLD_BYTES)
    }

    /**
     * The first recovery is based on the absolute limit, so a high startup baseline cannot mask
     * pressure. After a full player replacement, require meaningful renewed growth before trying
     * again; repeatedly rebuilding a player against a flat allocator high-water mark only causes
     * interruptions and cannot reclaim memory the player no longer owns.
     */
    fun nextHighSampleCount(
        current: Int,
        pressureBytes: Long,
        thresholdBytes: Long,
        recoveryBaselineBytes: Long,
        hasRecoveredBefore: Boolean
    ): Int {
        if (thresholdBytes <= 0L || pressureBytes < thresholdBytes) return 0
        if (hasRecoveredBefore &&
            (pressureBytes - recoveryBaselineBytes).coerceAtLeast(0L) < MIN_GROWTH_AFTER_RECOVERY_BYTES
        ) return 0
        return current + 1
    }

    fun shouldRecycle(
        highSamples: Int,
        nowElapsedMs: Long,
        lastAttemptElapsedMs: Long,
        requiredSamples: Int = REQUIRED_HIGH_SAMPLES,
        cooldownMs: Long = COOLDOWN_MS
    ): Boolean {
        if (highSamples < requiredSamples) return false
        if (lastAttemptElapsedMs <= 0L) return true
        return nowElapsedMs >= lastAttemptElapsedMs && nowElapsedMs - lastAttemptElapsedMs >= cooldownMs
    }
}
