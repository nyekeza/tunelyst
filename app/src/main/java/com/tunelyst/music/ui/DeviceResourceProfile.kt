package com.tunelyst.music.ui

internal const val LOW_MEMORY_HEAP_LIMIT_BYTES = 192L * 1024L * 1024L
internal const val LOW_MEMORY_CLASS_LIMIT_MB = 192

/** Shared low-memory classification used by UI rendering and optional background analysis. */
internal fun isTunelystLowMemoryDevice(
    platformLowRam: Boolean,
    memoryClassMb: Int,
    maxHeapBytes: Long
): Boolean = platformLowRam ||
    memoryClassMb in 1..LOW_MEMORY_CLASS_LIMIT_MB ||
    maxHeapBytes in 1L..LOW_MEMORY_HEAP_LIMIT_BYTES
