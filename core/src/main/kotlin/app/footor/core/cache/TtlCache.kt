package app.footor.core.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Small in-memory cache that keeps provider quotas in check:
 *  - entries expire after a per-call TTL, so live data is never older than intended;
 *  - concurrent requests for the same key share one in-flight load instead of hitting the network twice.
 */
class TtlCache<K : Any, V : Any>(
    private val maxEntries: Int = 256,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private class Entry<V>(val value: V, val storedAt: TimeMark)

    private val mutex = Mutex()
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private val inFlight = HashMap<K, CompletableDeferred<V>>()

    suspend fun getOrLoad(key: K, ttl: Duration, load: suspend () -> V): V {
        val deferred: CompletableDeferred<V>
        val owner: Boolean
        mutex.withLock {
            entries[key]?.let { if (it.storedAt.elapsedNow() < ttl) return it.value }
            val existing = inFlight[key]
            if (existing != null) {
                deferred = existing
                owner = false
            } else {
                deferred = CompletableDeferred<V>().also { inFlight[key] = it }
                owner = true
            }
        }
        if (!owner) return deferred.await()

        try {
            val value = load()
            mutex.withLock {
                entries[key] = Entry(value, timeSource.markNow())
                while (entries.size > maxEntries) entries.remove(entries.keys.first())
                inFlight.remove(key)
            }
            deferred.complete(value)
            return value
        } catch (t: Throwable) {
            mutex.withLock { inFlight.remove(key) }
            deferred.completeExceptionally(t)
            throw t
        }
    }

    suspend fun invalidate(key: K) {
        mutex.withLock { entries.remove(key) }
    }

    suspend fun clear() {
        mutex.withLock { entries.clear() }
    }
}
