package com.rohittp.rentile.internal

import com.rohittp.rentile.RawResourceKey
import com.rohittp.rentile.RawResourceStore
import com.rohittp.rentile.ResourceStoreException
import com.rohittp.rentile.StoredRawResource
import kotlinx.coroutines.CancellationException

/**
 * Shared [RawResourceStore] access wrappers used by every raw-resource acquirer.
 *
 * Cancellation is always rethrown before any store failure is converted into a
 * [ResourceStoreException], so a caller waiting on a cancelled coroutine never observes an
 * acquisition failure in its place.
 */
internal suspend fun RawResourceStore.readStore(key: RawResourceKey, failureMessage: String): StoredRawResource? = try {
    read(key)
} catch (error: CancellationException) {
    throw error
} catch (_: Throwable) {
    throw ResourceStoreException(failureMessage)
}

internal suspend fun RawResourceStore.writeStore(key: RawResourceKey, resource: StoredRawResource, failureMessage: String) {
    try {
        write(key, resource)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        throw ResourceStoreException(failureMessage)
    }
}

internal suspend fun RawResourceStore.removeStore(key: RawResourceKey, failureMessage: String) {
    try {
        remove(key)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        throw ResourceStoreException(failureMessage)
    }
}
