package com.rohittp.rentile.internal

import com.rohittp.rentile.ExecutionPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Process-local limits shared by every resource class owned by one rasterizer. */
internal class ResourceWorkCoordinator(
    policy: ExecutionPolicy,
) {
    private val exchangePermits = Semaphore(policy.maxConcurrentExchanges)
    private val decodePermits = Semaphore(policy.maxConcurrentDecodes)
    private val maxConcurrentExchangesPerOrigin = policy.maxConcurrentExchangesPerOrigin
    private val originMutex = Mutex()
    private val originPermits = mutableMapOf<String, Semaphore>()

    suspend fun <T> exchange(url: String, block: suspend () -> T): T =
        permitsFor(url).withPermit {
            exchangePermits.withPermit { block() }
        }

    suspend fun <T> decode(block: suspend () -> T): T = decodePermits.withPermit { block() }

    private suspend fun permitsFor(url: String): Semaphore {
        val origin = originOf(url)
        return originMutex.withLock {
            originPermits.getOrPut(origin) { Semaphore(maxConcurrentExchangesPerOrigin) }
        }
    }

    private fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return "<invalid>"
        val authorityStart = schemeEnd + 3
        val authorityEnd = url.indexOf('/', authorityStart).let { if (it < 0) url.length else it }
        return url.substring(0, authorityEnd).substringBefore('?').lowercase()
    }
}
