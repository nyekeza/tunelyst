package com.tunelyst.music.core.logic

const val LOW_MEMORY_HEAP_LIMIT_BYTES = 192L * 1024L * 1024L
const val LOW_MEMORY_CLASS_LIMIT_MB = 192

/** Shared low-memory classification used by UI rendering and optional background analysis. */
fun isTunelystLowMemoryDevice(
    platformLowRam: Boolean,
    memoryClassMb: Int,
    maxHeapBytes: Long
): Boolean = platformLowRam ||
    memoryClassMb in 1..LOW_MEMORY_CLASS_LIMIT_MB ||
    maxHeapBytes in 1L..LOW_MEMORY_HEAP_LIMIT_BYTES
