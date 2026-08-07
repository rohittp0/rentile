package com.rohittp.rentile.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Process-local shared work with cancellation only after the final waiter detaches. */
internal class SingleFlight<K : Any, V : Any>(
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val entries = mutableMapOf<K, Entry<V>>()

    suspend fun run(
        key: K,
        onJoin: () -> Unit = {},
        block: suspend () -> V,
    ): V {
        var created = false
        var joined = false
        val entry = mutex.withLock {
            entries[key]?.also {
                it.waiters += 1
                joined = true
            } ?: Entry(
                work = scope.async(start = CoroutineStart.LAZY) { block() },
                waiters = 1,
            ).also {
                entries[key] = it
                created = true
            }
        }
        if (joined) onJoin()
        if (created) entry.work.start()
        return try {
            entry.work.await()
        } finally {
            withContext(NonCancellable) { detach(key, entry) }
        }
    }

    private suspend fun detach(key: K, entry: Entry<V>) {
        var cancel = false
        mutex.withLock {
            entry.waiters -= 1
            check(entry.waiters >= 0) { "Single-flight waiter count underflow" }
            if (entries[key] === entry && (entry.work.isCompleted || entry.waiters == 0)) {
                entries.remove(key)
            }
            if (!entry.work.isCompleted && entry.waiters == 0) cancel = true
        }
        if (cancel) entry.work.cancel(CancellationException("Single-flight work has no remaining waiters"))
    }

    private class Entry<V : Any>(
        val work: Deferred<V>,
        var waiters: Int,
    )
}
