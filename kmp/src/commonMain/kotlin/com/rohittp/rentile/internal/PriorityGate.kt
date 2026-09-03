package com.rohittp.rentile.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Which queue a waiter joins. [WARM] only ever runs on a slot [ACQUISITION] does not want. */
internal enum class ResourcePriority { ACQUISITION, WARM }

/**
 * A permit gate with two FIFO queues, where a freed permit is offered to [ResourcePriority.WARM]
 * only when no [ResourcePriority.ACQUISITION] waiter wants it.
 *
 * A plain `Semaphore` cannot express this, and the alternative — bounding how far ahead prefetching
 * may run — is a guess that is wrong at both ends: too small and the connection budget still idles,
 * too large and prefetching takes every freed slot from the acquisition it is meant to help. That is
 * the failure mode ADR 0017 recorded, where a read-ahead spent the contended budget on tiles the run
 * would not reach for minutes. Strict priority removes the parameter instead of tuning it, so
 * prefetching can safely cover a whole session.
 *
 * Prefetching is not preempted once its request is in flight: acquisition may wait for one in-flight
 * exchange to finish, which is bounded by a single request's latency rather than being starvation.
 * Cancelling an in-flight fetch would throw away bytes already paid for.
 */
internal class PriorityGate(private val permits: Int) {
    private val mutex = Mutex()
    private var available = permits
    private val acquisitionWaiters = ArrayDeque<CompletableDeferred<Unit>>()
    private val warmWaiters = ArrayDeque<CompletableDeferred<Unit>>()

    init {
        require(permits > 0) { "a gate needs at least one permit, got $permits" }
    }

    suspend fun <T> withPermit(priority: ResourcePriority, block: suspend () -> T): T {
        acquire(priority)
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire(priority: ResourcePriority) {
        val waiter = mutex.withLock {
            if (available > 0) {
                available--
                null
            } else {
                CompletableDeferred<Unit>().also { queued ->
                    when (priority) {
                        ResourcePriority.ACQUISITION -> acquisitionWaiters.addLast(queued)
                        ResourcePriority.WARM -> warmWaiters.addLast(queued)
                    }
                }
            }
        }
        if (waiter == null) return
        try {
            waiter.await()
        } catch (cancelled: CancellationException) {
            // Either we are still queued and must leave, or a permit was handed to us between the
            // cancellation and now and must be passed on. Dropping it would leak a permit for the
            // life of the rasterizer.
            mutex.withLock {
                val stillQueued = acquisitionWaiters.remove(waiter) || warmWaiters.remove(waiter)
                if (!stillQueued && waiter.isCompleted) releaseLocked()
            }
            throw cancelled
        }
    }

    private suspend fun release() = mutex.withLock { releaseLocked() }

    private fun releaseLocked() {
        val next = acquisitionWaiters.removeFirstOrNull() ?: warmWaiters.removeFirstOrNull()
        if (next == null) available++ else next.complete(Unit)
    }

    internal suspend fun availableForTest(): Int = mutex.withLock { available }
}
