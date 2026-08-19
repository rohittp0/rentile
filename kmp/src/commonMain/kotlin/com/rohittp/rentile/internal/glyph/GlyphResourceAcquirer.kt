package com.rohittp.rentile.internal.glyph

import com.rohittp.rentile.MetricName
import com.rohittp.rentile.PipelineStage
import com.rohittp.rentile.RawResourceKey
import com.rohittp.rentile.RawResourceMetadata
import com.rohittp.rentile.RentileConfiguration
import com.rohittp.rentile.RentileMetric
import com.rohittp.rentile.ResourceAccessMode
import com.rohittp.rentile.ResourceAcquisitionException
import com.rohittp.rentile.ResourceClass
import com.rohittp.rentile.ResourceDecodeException
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

    /**
     * [accessMode] is honoured the way [com.rohittp.rentile.internal.mvt.VectorResourceAcquirer]
     * honours it, not the way the sprite acquirer ignores it. The difference is not inconsistency:
     * a sprite resolves during `prepare()`, which takes no access mode at all, whereas a Glyph
     * Range is acquired by `acquireLabelCandidates`, whose public signature accepts one. A
     * documented parameter that silently did not apply would break offline export - a batch asked
     * for in `CACHE_ONLY` would still reach the network for its glyphs.
     */
    suspend fun acquire(
        template: String,
        fontStack: String,
        rangeStart: Int,
        accessMode: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): AcquiredGlyphRange {
        val url = resolveUrl(template, fontStack, rangeStart)
        val sanitizedId = url.withRedactedAuthenticationQuery().sha256Hex()
        return singleFlight.run(sanitizedId) {
            val bytes = acquireRaw(url, sanitizedId, accessMode)
            AcquiredGlyphRange(
                fontStack = fontStack,
                rangeStart = rangeStart,
                glyphs = decode(bytes, rangeLabelFor(rangeStart), sanitizedId),
                contentDigest = bytes.sha256Hex(),
            )
        }
    }

    /**
     * [GlyphRangeDecoder.decode] throws a bare `IllegalArgumentException` from its `require`
     * checks, and Wire's adapter throws its own codec exceptions on malformed protobuf; neither is
     * a [com.rohittp.rentile.RentileException]. Wrap both the same way
     * [com.rohittp.rentile.internal.sprite.SpriteResourceAcquirer.compile] wraps its JSON/PNG
     * decode failures, so a malformed or truncated glyph payload never escapes untyped. The
     * original exception's message is never forwarded since it may echo provider-controlled bytes.
     */
    private fun decode(bytes: ByteArray, expectedRange: String, sanitizedId: String): List<DecodedGlyph> = try {
        GlyphRangeDecoder.decode(bytes, expectedRange)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        throw ResourceDecodeException(
            message = "Glyph range payload is malformed",
            resourceClass = ResourceClass.GLYPH_RANGE,
            sanitizedResourceId = sanitizedId,
        )
    }

    private suspend fun acquireRaw(
        url: String,
        sanitizedId: String,
        accessMode: ResourceAccessMode,
    ): ByteArray {
        val limit = configuration.resourceLimits.maxGlyphRangeBytes
        val resourceClass = ResourceClass.GLYPH_RANGE
        val key = RawResourceKey(sanitizedId, resourceClass)
        if (accessMode != ResourceAccessMode.RELOAD) {
            val cached = configuration.rawResourceStore.readStore(key, "Raw glyph cache read failed")
            if (cached != null) {
                if (cached.bytes.size.toLong() <= limit && cached.bytes.sha256Hex() == cached.contentDigest) {
                    configuration.metricsSink.recordSafely(RentileMetric(MetricName.RAW_CACHE_HIT, resourceClass = resourceClass))
                    return cached.bytes
                }
                configuration.rawResourceStore.removeStore(key, "Corrupt glyph cache removal failed")
            }
            configuration.metricsSink.recordSafely(RentileMetric(MetricName.RAW_CACHE_MISS, resourceClass = resourceClass))
            if (accessMode == ResourceAccessMode.CACHE_ONLY) {
                // Nothing beyond this point may touch the transport: the whole contract of
                // cache-only is that it does not.
                throw ResourceAcquisitionException(
                    message = "Glyph range is unavailable in cache-only mode",
                    resourceClass = resourceClass,
                    sanitizedResourceId = sanitizedId,
                )
            }
        }
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
         * The `{range}` token for a block, e.g. `"0-255"`. The URL is built from this and the
         * served payload is checked against it, so both must come from here - a second copy of the
         * formatting would make the check compare one spelling of the block against another.
         */
        fun rangeLabelFor(rangeStart: Int): String = "$rangeStart-${rangeStart + RANGE_SIZE - 1}"

        /**
         * Substitutes `{fontstack}` and `{range}` into a glyph URL template. Spaces in font names
         * are percent-encoded; the commas separating stack members are left literal, matching what
         * glyph endpoints expect.
         */
        fun resolveUrl(template: String, fontStack: String, rangeStart: Int): String = template
            .replace("{fontstack}", fontStack.replace(" ", "%20"))
            .replace("{range}", rangeLabelFor(rangeStart))
    }
}
