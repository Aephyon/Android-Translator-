package app.chattranslator

/** Small least-recently-used map so long chats don't grow memory without bound. */
class LruCache<K, V>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > maxSize
    }

    operator fun get(key: K): V? = map[key]
    operator fun set(key: K, value: V) { map[key] = value }
    fun remove(key: K) { map.remove(key) }
    fun clear() = map.clear()
    val size get() = map.size
}
