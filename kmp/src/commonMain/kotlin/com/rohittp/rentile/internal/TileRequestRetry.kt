package com.rohittp.rentile.internal

import com.rohittp.rentile.TransportResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal const val MAX_TILE_RETRY_DELAY_MILLIS: Long = 5_000L

internal suspend fun executeTileRequestWithRetry(
    exchange: suspend () -> TransportResponse,
): TransportResponse {
    var retryAvailable = true
    while (true) {
        val response = try {
            exchange()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!retryAvailable) throw error
            retryAvailable = false
            continue
        }

        if (!retryAvailable || !response.statusCode.isTransientTileFailure()) return response
        val retryDelayMillis = response.metadata.retryAfterMillis
            ?.coerceIn(0L, MAX_TILE_RETRY_DELAY_MILLIS)
            ?: 0L
        retryAvailable = false
        if (retryDelayMillis > 0L) delay(retryDelayMillis)
    }
}

private fun Int.isTransientTileFailure(): Boolean = this == 408 || this == 429 || this in 500..599
