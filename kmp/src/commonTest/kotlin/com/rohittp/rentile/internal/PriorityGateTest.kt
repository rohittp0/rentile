package com.rohittp.rentile.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Warming must never take a connection slot an acquisition wants.
 *
 * The alternative was bounding how far ahead prefetching may run, which is wrong at both ends: too
 * small and the connection budget idles, too large and prefetching starves the acquisition it exists
 * to help — the failure ADR 0017 recorded.
 */
class PriorityGateTest {
    @Test
    fun aFreedPermitGoesToAcquisitionEvenWhenWarmingQueuedFirst() = runTest {
        val gate = PriorityGate(permits = 1)
        val order = mutableListOf<String>()
        val holderMayFinish = CompletableDeferred<Unit>()

        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(ResourcePriority.ACQUISITION) { holderMayFinish.await() }
        }
        // Warming queues first, so FIFO alone would serve it first.
        val warm = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(ResourcePriority.WARM) { order += "warm" }
        }
        val acquisition = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(ResourcePriority.ACQUISITION) { order += "acquisition" }
        }

        holderMayFinish.complete(Unit)
        holder.join()
        acquisition.join()
        warm.join()

        assertEquals(listOf("acquisition", "warm"), order)
    }

    @Test
    fun warmingRunsWhenNothingElseWantsTheSlot() = runTest {
        val gate = PriorityGate(permits = 1)
        var warmed = false

        gate.withPermit(ResourcePriority.WARM) { warmed = true }

        assertTrue(warmed, "an idle slot must be usable, or prefetching never happens at all")
        assertEquals(1, gate.availableForTest())
    }

    @Test
    fun everyQueuedAcquisitionIsServedBeforeTheFirstWarm() = runTest {
        val gate = PriorityGate(permits = 1)
        val order = mutableListOf<String>()
        val holderMayFinish = CompletableDeferred<Unit>()

        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(ResourcePriority.ACQUISITION) { holderMayFinish.await() }
        }
        val warm = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(ResourcePriority.WARM) { order += "warm" }
        }
        val acquisitions = (1..3).map { index ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                gate.withPermit(ResourcePriority.ACQUISITION) { order += "acquisition$index" }
            }
        }

        holderMayFinish.complete(Unit)
        holder.join()
        acquisitions.forEach { it.join() }
        warm.join()

        // The warm queue is touched only once the acquisition queue is empty.
        assertEquals(listOf("acquisition1", "acquisition2", "acquisition3", "warm"), order)
    }

    @Test
    fun cancellingAQueuedWaiterLeaksNoPermit() = runTest {
        val gate = PriorityGate(permits = 1)
        val holderMayFinish = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(ResourcePriority.ACQUISITION) { holderMayFinish.await() }
        }
        val queued = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(ResourcePriority.ACQUISITION) { error("must never run") }
        }

        queued.cancel()
        queued.join()
        holderMayFinish.complete(Unit)
        holder.join()
        yield()

        // A leaked permit would silently shrink the connection budget for the rasterizer's life.
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
            gate.withPermit(ResourcePriority.ACQUISITION) {
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
            gate.withPermit(ResourcePriority.ACQUISITION) {
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
        // in, every permit must come back. A leak silently shrinks the connection budget for the
        // rasterizer's life, which would look like the network getting slower over a long export.
        //
        // The gate's `!stillQueued && isCompleted` branch covers a cancellation arriving between a
        // grant and the waiter resuming. That interleaving is not constructible with the test
        // scheduler -- a grant resumes the waiter synchronously -- so it is asserted through this
        // property rather than by a test claiming to reproduce it directly.
        val gate = PriorityGate(permits = 2)
        val holdersMayFinish = CompletableDeferred<Unit>()
        val holders = (1..2).map {
            launch(start = CoroutineStart.UNDISPATCHED) {
                gate.withPermit(ResourcePriority.ACQUISITION) { holdersMayFinish.await() }
            }
        }
        val waiters = (1..8).map { index ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                val priority = if (index % 2 == 0) ResourcePriority.WARM else ResourcePriority.ACQUISITION
                gate.withPermit(priority) { yield() }
            }
        }

        waiters.filterIndexed { index, _ -> index % 3 == 0 }.forEach { it.cancel() }
        holdersMayFinish.complete(Unit)
        holders.forEach { it.join() }
        waiters.forEach { it.join() }

        assertEquals(2, gate.availableForTest())
    }
}
