package com.rohittp.rentile.internal

import com.rohittp.rentile.TransportResponse
import com.rohittp.rentile.TransportResponseMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class TileRequestRetryTest {

    @Test
    fun transientServerFailureIsRetriedOnce() = runTest {
        var exchanges = 0

        val response = executeTileRequestWithRetry {
            exchanges += 1
            TransportResponse(if (exchanges == 1) 503 else 200, byteArrayOf(1))
        }

        assertEquals(200, response.statusCode)
        assertEquals(2, exchanges)
    }

    @Test
    fun permanentClientFailureIsNotRetried() = runTest {
        var exchanges = 0

        val response = executeTileRequestWithRetry {
            exchanges += 1
            TransportResponse(404, byteArrayOf())
        }

        assertEquals(404, response.statusCode)
        assertEquals(1, exchanges)
    }

    @Test
    fun transportFailureIsRetriedOnce() = runTest {
        var exchanges = 0

        val response = executeTileRequestWithRetry {
            exchanges += 1
            if (exchanges == 1) error("redacted transport failure")
            TransportResponse(200, byteArrayOf(1))
        }

        assertEquals(200, response.statusCode)
        assertEquals(2, exchanges)
    }

    @Test
    fun retryAfterBeyondFiveSecondsIsCappedAndRetriedOnce() = runTest {
        var exchanges = 0

        val response = executeTileRequestWithRetry {
            exchanges += 1
            if (exchanges == 1) {
                TransportResponse(
                    statusCode = 429,
                    body = byteArrayOf(),
                    metadata = TransportResponseMetadata(retryAfterMillis = MAX_TILE_RETRY_DELAY_MILLIS * 2L),
                )
            } else {
                TransportResponse(200, byteArrayOf(1))
            }
        }

        assertEquals(200, response.statusCode)
        assertEquals(2, exchanges)
        assertEquals(MAX_TILE_RETRY_DELAY_MILLIS, testScheduler.currentTime)
    }

    @Test
    fun retryAfterAtFiveSecondsIsHonored() = runTest {
        var exchanges = 0

        val response = executeTileRequestWithRetry {
            exchanges += 1
            if (exchanges == 1) {
                TransportResponse(
                    statusCode = 429,
                    body = byteArrayOf(),
                    metadata = TransportResponseMetadata(retryAfterMillis = MAX_TILE_RETRY_DELAY_MILLIS),
                )
            } else {
                TransportResponse(200, byteArrayOf(1))
            }
        }

        assertEquals(200, response.statusCode)
        assertEquals(2, exchanges)
        assertEquals(MAX_TILE_RETRY_DELAY_MILLIS, testScheduler.currentTime)
    }

    @Test
    fun cancellationIsNeverRetried() = runTest {
        var exchanges = 0

        assertFailsWith<CancellationException> {
            executeTileRequestWithRetry {
                exchanges += 1
                throw CancellationException("cancelled")
            }
        }

        assertEquals(1, exchanges)
    }
}
