package com.rohittp.rentile.internal

import com.rohittp.rentile.RenderPriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deferred work must never take a slot first-lane work wants — a connection an acquisition needs,
 * or a metatile worker the tile on screen needs.
 *
 * The alternative was bounding how far ahead a read-ahead may run, which is wrong at both ends: too
 * small and the budget idles, too large and the read-ahead starves the work it exists to help — the
 * failure the consumer's ADR 0017 recorded.
 */
class PriorityGateTest {
    @Test
    fun aFreedPermitGoesToTheFirstLaneEvenWhenDeferredWorkQueuedFirst() = runTest {
        val gate = PriorityGate(permits = 1)
        val order = mutableListOf<String>()
        val holderMayFinish = CompletableDeferred<Unit>()

        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(GateLane.FIRST) { holderMayFinish.await() }
        }
        // The deferred waiter queues first, so FIFO alone would serve it first.
        val deferred = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(GateLane.DEFERRED) { order += "deferred" }
        }
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(GateLane.FIRST) { order += "first" }
        }

        holderMayFinish.complete(Unit)
        holder.join()
        first.join()
        deferred.join()

        assertEquals(listOf("first", "deferred"), order)
    }

    @Test
    fun deferredWorkRunsWhenNothingElseWantsTheSlot() = runTest {
        val gate = PriorityGate(permits = 1)
        var ran = false

        gate.withPermit(GateLane.DEFERRED) { ran = true }

        assertTrue(ran, "an idle slot must be usable, or read-ahead never happens at all")
        assertEquals(1, gate.availableForTest())
    }

    @Test
    fun everyQueuedFirstLaneWaiterIsServedBeforeTheFirstDeferredOne() = runTest {
        val gate = PriorityGate(permits = 1)
        val order = mutableListOf<String>()
        val holderMayFinish = CompletableDeferred<Unit>()

        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(GateLane.FIRST) { holderMayFinish.await() }
        }
        val deferred = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(GateLane.DEFERRED) { order += "deferred" }
        }
        val firsts = (1..3).map { index ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                gate.withPermit(GateLane.FIRST) { order += "first$index" }
            }
        }

        holderMayFinish.complete(Unit)
        holder.join()
        firsts.forEach { it.join() }
        deferred.join()

        // The deferred queue is touched only once the first-lane queue is empty.
        assertEquals(listOf("first1", "first2", "first3", "deferred"), order)
    }

    @Test
    fun aFirstLaneWaiterArrivingAfterAFullDeferredBacklogTakesTheNextFreedPermit() = runTest {
        // The measured case, in miniature: the read-ahead has already filled the queue when the
        // work that keeps playback running finally asks. Strict priority is what makes the wait one
        // tile long instead of the whole backlog.
        val gate = PriorityGate(permits = 1)
        val order = mutableListOf<String>()
        val holderMayFinish = CompletableDeferred<Unit>()

        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(GateLane.DEFERRED) { holderMayFinish.await() }
        }
        val backlog = (1..6).map { index ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                gate.withPermit(GateLane.DEFERRED) { order += "deferred$index" }
            }
        }
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(GateLane.FIRST) { order += "first" }
        }

        holderMayFinish.complete(Unit)
        holder.join()
        first.join()
        backlog.forEach { it.join() }

        assertEquals("first", order.first(), "a backlog must not outrank the work someone waits on")
        // And the backlog is not dropped, only overtaken: every deferred waiter still runs, in the
        // order it queued.
        assertEquals(
            listOf("first") + (1..6).map { "deferred$it" },
            order,
        )
    }

    @Test
    fun cancellingAQueuedWaiterLeaksNoPermit() = runTest {
        val gate = PriorityGate(permits = 1)
        val holderMayFinish = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(GateLane.FIRST) { holderMayFinish.await() }
        }
        val queued = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(GateLane.FIRST) { error("must never run") }
        }

        queued.cancel()
        queued.join()
        holderMayFinish.complete(Unit)
        holder.join()
        yield()

        // A leaked permit would silently shrink the budget for the rasterizer's life.
        assertEquals(1, gate.availableForTest())
    }

    @Test
    fun cancellingAQueuedFirstLaneWaiterLeavesTheDeferredQueueRunnable() = runTest {
        // A cancelled first-lane waiter must take nothing with it: it never runs, it returns
        // nothing, and the permit it was about to get goes to whoever is next rather than to
        // nobody. A gate that lost the permit here would stall a session that merely changed its
        // mind about a tile.
        val gate = PriorityGate(permits = 1)
        val order = mutableListOf<String>()
        val holderMayFinish = CompletableDeferred<Unit>()

        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(GateLane.FIRST) { holderMayFinish.await() }
        }
        val abandoned = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(GateLane.FIRST) { order += "abandoned" }
        }
        val deferred = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(GateLane.DEFERRED) { order += "deferred" }
        }

        abandoned.cancel()
        abandoned.join()
        holderMayFinish.complete(Unit)
        holder.join()
        deferred.join()
        yield()

        assertEquals(listOf("deferred"), order)
        assertEquals(1, gate.availableForTest())
    }

    @Test
    fun cancellingAPermitHolderReturnsItsPermitEvenWhenTheLockIsContended() = runTest {
        // The interleaving that hung an export. Releasing takes the internal lock, and Mutex.lock()
        // is cancellable when it has to suspend, so a holder cancelled while the lock is held
        // elsewhere threw out of its own release and leaked the permit. These gates live on a
        // process-wide rasterizer, so a leak outlives the session that caused it: Preview teardown
        // cancelling warm fetches could empty the gate and wedge a later export with every worker
        // parked and no network traffic at all.
        val gate = PriorityGate(permits = 1)
        val holding = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(GateLane.FIRST) {
                holding.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        holding.await()

        val lockHolder = launch(start = CoroutineStart.UNDISPATCHED) { gate.holdLockForTest(releaseLock) }
        holder.cancel()
        // The lock must be handed back before joining: once the release is non-cancellable it waits
        // here, so the holder cannot finish until it gets the lock. Joining first deadlocks the test.
        releaseLock.complete(Unit)
        lockHolder.join()
        holder.join()
        yield()

        assertEquals(
            1,
            gate.availableForTest(),
            "a holder cancelled while the lock was contended must still return its permit",
        )
    }

    @Test
    fun cancellingAPermitHolderReturnsItsPermit() = runTest {
        // The interleaving the other cancellation tests miss: they cancel *waiters*, which never
        // held a permit. This cancels a *holder*, so the release runs in an already-cancelled
        // coroutine -- and Mutex.lock() is cancellable, so a release that takes the lock throws
        // before returning the permit and leaks it for the life of the rasterizer.
        val gate = PriorityGate(permits = 1)
        val holding = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(GateLane.FIRST) {
                holding.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }

        holding.await()
        holder.cancel()
        holder.join()

        assertEquals(1, gate.availableForTest(), "a cancelled holder must return its permit")
    }

    @Test
    fun aBurstOfCancellationsUnderContentionLeaksNoPermits() = runTest {
        // The invariant, rather than one interleaving: whatever order grants and cancellations land
        // in, every permit must come back. A leak silently shrinks the budget for the rasterizer's
        // life, which would look like the network getting slower over a long export.
        //
        // The gate's `!stillQueued && isCompleted` branch covers a cancellation arriving between a
        // grant and the waiter resuming. That interleaving is not constructible with the test
        // scheduler -- a grant resumes the waiter synchronously -- so it is asserted through this
        // property rather than by a test claiming to reproduce it directly.
        val gate = PriorityGate(permits = 2)
        val holdersMayFinish = CompletableDeferred<Unit>()
        val holders = (1..2).map {
            launch(start = CoroutineStart.UNDISPATCHED) {
                gate.withPermit(GateLane.FIRST) { holdersMayFinish.await() }
            }
        }
        val waiters = (1..8).map { index ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                val lane = if (index % 2 == 0) GateLane.DEFERRED else GateLane.FIRST
                gate.withPermit(lane) { yield() }
            }
        }

        waiters.filterIndexed { index, _ -> index % 3 == 0 }.forEach { it.cancel() }
        holdersMayFinish.complete(Unit)
        holders.forEach { it.join() }
        waiters.forEach { it.join() }

        assertEquals(2, gate.availableForTest())
    }

    @Test
    fun eachCallerMapsOntoTheGatesTwoLanes() {
        // One gate, two callers. Getting either mapping backwards would leave the gate working
        // perfectly and the priority inverted, which nothing else can observe.
        assertEquals(GateLane.FIRST, ResourcePriority.ACQUISITION.lane)
        assertEquals(GateLane.DEFERRED, ResourcePriority.WARM.lane)
        assertEquals(GateLane.FIRST, RenderPriority.URGENT.lane)
        assertEquals(GateLane.DEFERRED, RenderPriority.NORMAL.lane)
    }
}
