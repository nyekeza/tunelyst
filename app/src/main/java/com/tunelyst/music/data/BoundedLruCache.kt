package com.tunelyst.music.data

/** Small synchronized access-order cache for metadata that must not grow with the whole library. */
internal class BoundedLruCache<K, V>(private val maxEntries: Int) {
    private val entries = LinkedHashMap<K, V>(maxEntries + 1, 0.75f, true)

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    @Synchronized
    operator fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = value
        while (entries.size > maxEntries) {
            val eldestKey = entries.keys.iterator().next()
            entries.remove(eldestKey)
        }
    }

    @Synchronized
    fun clear() = entries.clear()

    /** Returns entries from least to most recently used, preserving order for persistence. */
    @Synchronized
    fun snapshot(): List<Pair<K, V>> = entries.entries.map { it.key to it.value }

    @Synchronized
    fun size(): Int = entries.size
}
