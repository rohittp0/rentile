package com.rohittp.rentile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One exchange as the library asked for it, kept so a test can assert what it sent. */
internal class RecordedRequest(
    val resourceClass: ResourceClass,
    val url: String,
    val metadata: TransportRequestMetadata,
)

/**
 * Records the class, URL and request metadata of every exchange, in order.
 *
 * Shared by the revalidation suites: what those tests assert is not only which bytes came back but
 * which validators went out, and a transport lambda that only counts calls cannot show that.
 */
internal class RecordingTransport(
    private val respond: suspend (TransportRequest) -> TransportResponse,
) : ResourceTransport {
    private val mutex = Mutex()
    private val recorded = mutableListOf<RecordedRequest>()

    override suspend fun execute(request: TransportRequest): TransportResponse {
        mutex.withLock { recorded += RecordedRequest(request.resourceClass, request.url, request.metadata) }
        return respond(request)
    }

    fun requests(): List<RecordedRequest> = recorded.toList()

    fun requests(resourceClass: ResourceClass): List<RecordedRequest> =
        recorded.filter { it.resourceClass == resourceClass }

    fun clear() {
        recorded.clear()
    }
}

/** Records which resource classes were written, and whether a rewrite changed the bytes. */
internal class WriteRecordingRawResourceStore : RawResourceStore {
    private val mutex = Mutex()
    private val entries = mutableMapOf<RawResourceKey, StoredRawResource>()
    private val written = mutableListOf<ResourceClass>()
    private var nextWrite = CompletableDeferred<Unit>()
    var everyWriteKeptItsBytes: Boolean = true
        private set

    override suspend fun read(key: RawResourceKey): StoredRawResource? = mutex.withLock { entries[key] }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        val signal = mutex.withLock {
            val previous = entries[key]
            if (previous != null && !previous.bytes.contentEquals(resource.bytes)) {
                everyWriteKeptItsBytes = false
            }
            entries[key] = resource
            written += key.resourceClass
            // A fresh latch for the next waiter, so waiting for the second of two writes is not a
            // wait on an already-completed deferred.
            nextWrite.also { nextWrite = CompletableDeferred() }
        }
        signal.complete(Unit)
    }

    /**
     * Suspends until at least [count] writes have landed since the last [clearWrites].
     *
     * A background revalidation runs in the rasterizer's own scope, so nothing the test holds can
     * be joined; what it can observe is the store the refresh writes through. The count is checked
     * under the same lock the write takes, so a write that lands between two waits cannot be missed.
     */
    suspend fun awaitWrites(count: Int) {
        while (true) {
            val signal = mutex.withLock {
                if (written.size >= count) return
                nextWrite
            }
            signal.await()
        }
    }

    override suspend fun remove(key: RawResourceKey) {
        mutex.withLock { entries.remove(key) }
    }

    suspend fun writes(): List<ResourceClass> = mutex.withLock { written.toList() }

    suspend fun clearWrites() {
        mutex.withLock {
            written.clear()
            nextWrite = CompletableDeferred()
        }
    }
}
