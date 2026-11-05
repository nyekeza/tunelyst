package com.tunelyst.music

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.tunelyst.music.core.logic.isTunelystLowMemoryDevice

/** One process-wide Coil loader with an explicit, device-aware artwork memory budget. */
class TunelystApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lowMemoryDevice = isTunelystLowMemoryDevice(
            platformLowRam = activityManager?.isLowRamDevice == true,
            memoryClassMb = activityManager?.memoryClass ?: Int.MAX_VALUE,
            maxHeapBytes = Runtime.getRuntime().maxMemory()
        )
        val memoryCacheBytes = if (lowMemoryDevice) LOW_MEMORY_IMAGE_CACHE_BYTES else STANDARD_IMAGE_CACHE_BYTES

        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(memoryCacheBytes)
                    .build()
            }
            .build()
    }

    private companion object {
        const val LOW_MEMORY_IMAGE_CACHE_BYTES = 4 * 1024 * 1024
        const val STANDARD_IMAGE_CACHE_BYTES = 12 * 1024 * 1024
    }
}
