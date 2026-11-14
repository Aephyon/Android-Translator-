package app.chattranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LruCacheTest {
    @Test fun evictsLeastRecentlyUsed() {
        val cache = LruCache<String, Int>(2)
        cache["a"] = 1
        cache["b"] = 2
        cache["a"]            // touch a, so b is now the oldest
        cache["c"] = 3
        assertEquals(1, cache["a"])
        assertNull(cache["b"])
        assertEquals(3, cache["c"])
        assertEquals(2, cache.size)
    }
}
