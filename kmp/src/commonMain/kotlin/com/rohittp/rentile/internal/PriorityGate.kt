package com.rohittp.rentile.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
 *
 * That bound is a permit-holding rule, and every holder must keep it: **a permit covers one
 * exchange and nothing else -- never a `Retry-After` wait, a backoff, or any other sleep.** A holder
 * that waits under its permit converts "one request's latency" into "one request's latency plus
 * however long it chose to sleep", and a burst of such holders parks the whole gate; prefetching
 * ([warmRawResource]) therefore leaves the gate before it waits and comes back for a fresh permit.
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
            withContext(NonCancellable) {
                mutex.withLock {
                    val stillQueued = acquisitionWaiters.remove(waiter) || warmWaiters.remove(waiter)
                    if (!stillQueued && waiter.isCompleted) releaseLocked()
                }
            }
            throw cancelled
        }
    }

    /**
     * NonCancellable is load-bearing, not defensive.
     *
     * Returning a permit takes the lock, and `Mutex.lock()` is cancellable whenever it has to
     * suspend -- which it does exactly when another coroutine holds the lock. So a holder cancelled
     * while the lock was contended threw out of its own release and never returned its permit. The
     * uncontended path hides this completely, because acquiring a free mutex does not suspend and so
     * never checks cancellation, which is why it took a contended test to see it.
     *
     * These gates belong to a process-wide rasterizer, so a leak outlives the session that caused
     * it: Preview teardown cancelling warm fetches could empty a gate and wedge a later export in
     * the same process, with every worker parked and no network traffic at all. The plain Semaphore
     * this class replaced was immune because its `release()` never suspends.
     */
    private suspend fun release() {
        withContext(NonCancellable) { mutex.withLock { releaseLocked() } }
    }

    private fun releaseLocked() {
        val next = acquisitionWaiters.removeFirstOrNull() ?: warmWaiters.removeFirstOrNull()
        if (next == null) available++ else next.complete(Unit)
    }

    internal suspend fun availableForTest(): Int = mutex.withLock { available }

    /**
     * Holds the internal lock until [until] completes, so a test can contend it deliberately.
     *
     * The contended case is the only one that matters and the only one a test cannot otherwise
     * reach: `Mutex.lock()` acquires without suspending when free, and a non-suspending path never
     * checks cancellation, so an uncontended release succeeds even from a cancelled coroutine.
     */
    internal suspend fun holdLockForTest(until: Deferred<Unit>) {
        mutex.withLock { until.await() }
    }
}
