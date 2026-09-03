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
    // Both gates are priority-aware, not just the per-origin one: with a plain global semaphore an
    // acquisition that won its origin slot would still queue behind warming at the second gate.
    private val exchangePermits = PriorityGate(policy.maxConcurrentExchanges)
    private val decodePermits = Semaphore(policy.maxConcurrentDecodes)
    private val maxConcurrentExchangesPerOrigin = policy.maxConcurrentExchangesPerOrigin
    private val originMutex = Mutex()
    private val originPermits = mutableMapOf<String, PriorityGate>()

    suspend fun <T> exchange(
        url: String,
        priority: ResourcePriority = ResourcePriority.ACQUISITION,
        block: suspend () -> T,
    ): T =
        permitsFor(url).withPermit(priority) {
            exchangePermits.withPermit(priority) { block() }
        }

    suspend fun <T> decode(block: suspend () -> T): T = decodePermits.withPermit { block() }

    private suspend fun permitsFor(url: String): PriorityGate {
        val origin = originOf(url)
        return originMutex.withLock {
            originPermits.getOrPut(origin) { PriorityGate(maxConcurrentExchangesPerOrigin) }
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
