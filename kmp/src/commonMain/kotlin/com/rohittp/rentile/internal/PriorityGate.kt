package com.rohittp.rentile.internal

import com.rohittp.rentile.RenderPriority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which of a gate's two queues a waiter joins. A [DEFERRED] waiter is only ever handed a permit no
 * [FIRST] waiter wants.
 */
internal enum class GateLane { FIRST, DEFERRED }

/** Which lane one raw-resource exchange takes. Warming never runs on a slot acquisition wants. */
internal enum class ResourcePriority(val lane: GateLane) {
    ACQUISITION(GateLane.FIRST),
    WARM(GateLane.DEFERRED),
}

/** Which lane one output tile's draw takes, from the priority its caller asked for. */
internal val RenderPriority.lane: GateLane
    get() = when (this) {
        RenderPriority.URGENT -> GateLane.FIRST
        RenderPriority.NORMAL -> GateLane.DEFERRED
    }

/**
 * A permit gate with two FIFO queues, where a freed permit is offered to [GateLane.DEFERRED] only
 * when no [GateLane.FIRST] waiter wants it.
 *
 * Two resources in a rasterizer are scarce and contended by work of two very different urgencies.
 * Network exchanges: a read-ahead warming the cache shares the connection budget with the
 * acquisition a caller is waiting on. Metatile workers: a read-ahead render shares the same one or
 * two workers with the tile a caller is about to put on screen. Both fail the same way when the
 * queue is plain FIFO — work nobody is waiting for holds every slot — and it is not a small effect:
 * on a device, playback held for 6.8-10.5 s at a boundary while the two raster workers drew 35-65
 * read-ahead tiles ahead of the handful the resume needed.
 *
 * A plain `Semaphore` cannot express this, and the alternative — bounding how far ahead the
 * read-ahead may run — is a guess that is wrong at both ends: too small and the budget still idles,
 * too large and the read-ahead takes every freed slot from the work it is meant to help. That is the
 * failure mode the consumer's ADR 0017 recorded, where a read-ahead spent the contended budget on
 * tiles the run would not reach for minutes. Strict priority removes the parameter instead of tuning
 * it, so a read-ahead can safely cover a whole session.
 *
 * Deferred work is not preempted once it holds a permit: a [GateLane.FIRST] waiter may wait for one
 * in-flight exchange or one tile's draw to finish, which is bounded by a single unit of work rather
 * than being starvation. Cancelling either would throw away work already paid for — bytes on the
 * wire, or pixels already drawn.
 *
 * That bound is a permit-holding rule, and every holder must keep it: **a permit covers one exchange
 * or one tile draw and nothing else -- never a `Retry-After` wait, a backoff, or any other sleep,
 * and never a wait on a second permit.** A holder that waits under its permit converts "one unit of
 * work" into "one unit of work plus however long it chose to sleep", and a burst of such holders
 * parks the whole gate; prefetching ([warmRawResource]) therefore leaves the gate before it waits
 * and comes back for a fresh permit.
 */
internal class PriorityGate(
    private val permits: Int,
    /**
     * Receives every lane this gate is asked for, so a test can prove which lane a call site
     * requests. Null in production. Nothing else can observe it: a lane changes only *when* work
     * runs, never what it returns, so a caller that passed the wrong one would otherwise produce
     * identical output and merely lose the priority.
     */
    private val laneRecorderForTest: ((GateLane) -> Unit)? = null,
) {
    private val mutex = Mutex()
    private var available = permits
    private val firstWaiters = ArrayDeque<CompletableDeferred<Unit>>()
    private val deferredWaiters = ArrayDeque<CompletableDeferred<Unit>>()

    init {
        require(permits > 0) { "a gate needs at least one permit, got $permits" }
    }

    suspend fun <T> withPermit(lane: GateLane, block: suspend () -> T): T {
        acquire(lane)
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire(lane: GateLane) {
        laneRecorderForTest?.invoke(lane)
        val waiter = mutex.withLock {
            if (available > 0) {
                available--
                null
            } else {
                CompletableDeferred<Unit>().also { queued ->
                    when (lane) {
                        GateLane.FIRST -> firstWaiters.addLast(queued)
                        GateLane.DEFERRED -> deferredWaiters.addLast(queued)
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
                    val stillQueued = firstWaiters.remove(waiter) || deferredWaiters.remove(waiter)
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
        val next = firstWaiters.removeFirstOrNull() ?: deferredWaiters.removeFirstOrNull()
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
