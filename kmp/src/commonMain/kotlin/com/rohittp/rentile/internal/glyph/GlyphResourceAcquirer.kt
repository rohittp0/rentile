package com.rohittp.rentile.internal.glyph

import com.rohittp.rentile.MetricName
import com.rohittp.rentile.PipelineStage
import com.rohittp.rentile.RawResourceKey
import com.rohittp.rentile.RawResourceMetadata
import com.rohittp.rentile.RentileConfiguration
import com.rohittp.rentile.RentileMetric
import com.rohittp.rentile.ResourceAcquisitionException
import com.rohittp.rentile.ResourceClass
import com.rohittp.rentile.SafetyLimitException
import com.rohittp.rentile.StoredRawResource
import com.rohittp.rentile.TransportRequest
import com.rohittp.rentile.TransportRequestMetadata
import com.rohittp.rentile.internal.ResourceWorkCoordinator
import com.rohittp.rentile.internal.SingleFlight
import com.rohittp.rentile.internal.readStore
import com.rohittp.rentile.internal.recordSafely
import com.rohittp.rentile.internal.removeStore
import com.rohittp.rentile.internal.sha256Hex
import com.rohittp.rentile.internal.withRedactedAuthenticationQuery
import com.rohittp.rentile.internal.writeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/** One decoded, digested signed-distance-field glyph range for a single font stack. */
internal data class AcquiredGlyphRange(
    val fontStack: String,
    val rangeStart: Int,
    val glyphs: List<DecodedGlyph>,
    val contentDigest: String,
)

/**
 * Acquires signed-distance-field glyph ranges through the host-provided transport and raw cache.
 *
 * This is [com.rohittp.rentile.internal.sprite.SpriteResourceAcquirer]'s sibling: single-flighted
 * on a redacted-URL digest, cache-read with digest verification, and identical cancellation and
 * error-mapping behavior in [acquireRaw].
 */
internal class GlyphResourceAcquirer(
    private val configuration: RentileConfiguration,
    scope: CoroutineScope,
    private val workCoordinator: ResourceWorkCoordinator,
) {
    private val singleFlight = SingleFlight<String, AcquiredGlyphRange>(scope)

    suspend fun acquire(template: String, fontStack: String, rangeStart: Int): AcquiredGlyphRange {
        val url = resolveUrl(template, fontStack, rangeStart)
        val sanitizedId = url.withRedactedAuthenticationQuery().sha256Hex()
        return singleFlight.run(sanitizedId) {
            val bytes = acquireRaw(url, sanitizedId)
            AcquiredGlyphRange(
                fontStack = fontStack,
                rangeStart = rangeStart,
                glyphs = GlyphRangeDecoder.decode(bytes, fontStack),
                contentDigest = bytes.sha256Hex(),
            )
        }
    }

    private suspend fun acquireRaw(url: String, sanitizedId: String): ByteArray {
        val limit = configuration.resourceLimits.maxGlyphRangeBytes
        val resourceClass = ResourceClass.GLYPH_RANGE
        val key = RawResourceKey(sanitizedId, resourceClass)
        val cached = configuration.rawResourceStore.readStore(key, "Raw glyph cache read failed")
        if (cached != null) {
            if (cached.bytes.size.toLong() <= limit && cached.bytes.sha256Hex() == cached.contentDigest) {
                configuration.metricsSink.recordSafely(RentileMetric(MetricName.RAW_CACHE_HIT, resourceClass = resourceClass))
                return cached.bytes
            }
            configuration.rawResourceStore.removeStore(key, "Corrupt glyph cache removal failed")
        }
        configuration.metricsSink.recordSafely(RentileMetric(MetricName.RAW_CACHE_MISS, resourceClass = resourceClass))
        val response = workCoordinator.exchange(url) {
            configuration.metricsSink.recordSafely(RentileMetric(MetricName.RESOURCE_REQUEST, resourceClass = resourceClass))
            try {
                configuration.transport.execute(
                    TransportRequest(
                        url = url,
                        resourceClass = resourceClass,
                        maxResponseBytes = limit,
                        metadata = TransportRequestMetadata(accept = "application/x-protobuf"),
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                throw ResourceAcquisitionException(
                    message = "Glyph transport failed",
                    resourceClass = resourceClass,
                    sanitizedResourceId = sanitizedId,
                )
            }
        }
        if (response.statusCode !in 200..299) {
            throw ResourceAcquisitionException(
                message = "Glyph transport returned a non-success status",
                resourceClass = resourceClass,
                sanitizedResourceId = sanitizedId,
                statusCode = response.statusCode,
                retryAfterMillis = response.metadata.retryAfterMillis,
            )
        }
        val bytes = response.body
        if (bytes.size.toLong() > limit) {
            throw SafetyLimitException(
                message = "Glyph range exceeds its configured byte limit",
                limitName = "maxGlyphRangeBytes",
                limit = limit,
                observed = bytes.size.toLong(),
                stage = PipelineStage.RESOURCE_ACQUISITION,
            )
        }
        val digest = bytes.sha256Hex()
        configuration.rawResourceStore.writeStore(
            key,
            StoredRawResource(
                bytes = bytes,
                contentDigest = digest,
                metadata = RawResourceMetadata(
                    contentType = response.metadata.contentType,
                    etag = response.metadata.etag,
                    lastModified = response.metadata.lastModified,
                    freshUntilEpochMillis = response.metadata.expiresAtEpochMillis,
                    storedAtEpochMillis = configuration.clock.nowEpochMillis(),
                ),
            ),
            "Raw glyph cache write failed",
        )
        configuration.metricsSink.recordSafely(
            RentileMetric(
                MetricName.RESOURCE_WIRE_BYTES,
                value = response.metadata.wireByteCount ?: bytes.size.toLong(),
                resourceClass = resourceClass,
            ),
        )
        return bytes
    }

    companion object {
        private const val RANGE_SIZE = 256

        /** Buckets a codepoint into the 256-wide range its glyph endpoint serves it from. */
        fun rangeStartFor(codepoint: Int): Int = codepoint / RANGE_SIZE * RANGE_SIZE

        /**
         * Substitutes `{fontstack}` and `{range}` into a glyph URL template. Spaces in font names
         * are percent-encoded; the commas separating stack members are left literal, matching what
         * glyph endpoints expect.
         */
        fun resolveUrl(template: String, fontStack: String, rangeStart: Int): String = template
            .replace("{fontstack}", fontStack.replace(" ", "%20"))
            .replace("{range}", "$rangeStart-${rangeStart + RANGE_SIZE - 1}")
    }
}
