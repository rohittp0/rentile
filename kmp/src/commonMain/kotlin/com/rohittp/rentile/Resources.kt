package com.rohittp.rentile

/** Classes used for planning, limits, metrics, and diagnostics. */
public enum class ResourceClass {
    STYLE,
    TILE_JSON,
    VECTOR_TILE,
    RASTER_TILE,
    DEM_TILE,
    SPRITE_JSON,
    SPRITE_IMAGE,
    GEO_JSON,
    GLYPH_RANGE,
}

/** Typed request metadata; arbitrary headers do not cross the public transport boundary. */
public data class TransportRequestMetadata(
    public val ifNoneMatch: String? = null,
    public val ifModifiedSince: String? = null,
    public val accept: String? = null,
)

/** One bounded exchange. The URL may be credential-bearing and is intentionally redacted from [toString]. */
public class TransportRequest(
    public val url: String,
    public val resourceClass: ResourceClass,
    public val maxResponseBytes: Long,
    public val metadata: TransportRequestMetadata = TransportRequestMetadata(),
) {
    init {
        require(maxResponseBytes > 0)
    }

    override fun toString(): String =
        "TransportRequest(resourceClass=$resourceClass, maxResponseBytes=$maxResponseBytes, url=<redacted>)"
}

/** Allowlisted response facts retained for freshness, redirects, and local metrics. */
public class TransportResponseMetadata(
    public val contentType: String? = null,
    public val etag: String? = null,
    public val lastModified: String? = null,
    public val cacheControl: String? = null,
    public val expiresAtEpochMillis: Long? = null,
    public val vary: List<String> = emptyList(),
    public val retryAfterMillis: Long? = null,
    public val redirectLocation: String? = null,
    public val wireByteCount: Long? = null,
) {
    override fun toString(): String =
        "TransportResponseMetadata(contentType=$contentType, hasEtag=${etag != null}, " +
            "hasLastModified=${lastModified != null}, hasRedirect=${redirectLocation != null}, " +
            "wireByteCount=$wireByteCount)"
}

/** Decompressed response body and allowlisted response facts from one exchange. */
public class TransportResponse(
    public val statusCode: Int,
    body: ByteArray,
    public val metadata: TransportResponseMetadata = TransportResponseMetadata(),
) {
    private val bodyBytes: ByteArray = body.copyOf()

    public val body: ByteArray
        get() = bodyBytes.copyOf()

    init {
        require(statusCode in 100..599)
    }

    override fun toString(): String =
        "TransportResponse(statusCode=$statusCode, byteCount=${bodyBytes.size}, metadata=$metadata)"
}

public fun interface ResourceTransport {
    public suspend fun execute(request: TransportRequest): TransportResponse
}

/** Sanitized, credential-free key for one persistent raw-resource entry. */
public data class RawResourceKey(
    public val stableId: String,
    public val resourceClass: ResourceClass,
)

/** Persisted allowlisted freshness and validation metadata. */
public data class RawResourceMetadata(
    public val contentType: String? = null,
    public val etag: String? = null,
    public val lastModified: String? = null,
    public val freshUntilEpochMillis: Long? = null,
    public val storedAtEpochMillis: Long,
) {
    override fun toString(): String =
        "RawResourceMetadata(contentType=$contentType, hasEtag=${etag != null}, " +
            "hasLastModified=${lastModified != null}, freshUntilEpochMillis=$freshUntilEpochMillis, " +
            "storedAtEpochMillis=$storedAtEpochMillis)"
}

/** Complete, validated encoded entry returned by [RawResourceStore]. */
public class StoredRawResource(
    bytes: ByteArray,
    public val contentDigest: String,
    public val metadata: RawResourceMetadata,
) {
    private val storedBytes: ByteArray = bytes.copyOf()

    public val bytes: ByteArray
        get() = storedBytes.copyOf()

    override fun toString(): String =
        "StoredRawResource(contentDigest=$contentDigest, byteCount=${storedBytes.size}, metadata=$metadata)"
}

/** Atomic, corruption-safe, cross-process persistent raw-resource boundary. */
public interface RawResourceStore {
    public suspend fun read(key: RawResourceKey): StoredRawResource?
    public suspend fun write(key: RawResourceKey, resource: StoredRawResource)
    public suspend fun remove(key: RawResourceKey)
}

/** Provider session supplied by the host without defining billing semantics. */
public data class MapSession(
    public val value: String,
    public val expiresAtEpochMillis: Long,
) {
    override fun toString(): String =
        "MapSession(value=<redacted>, expiresAtEpochMillis=$expiresAtEpochMillis)"
}

public fun interface MapSessionProvider {
    public suspend fun sessionFor(origin: String): MapSession?

    public companion object {
        public val None: MapSessionProvider = MapSessionProvider { null }
    }
}

/** Optional credential for an exact HTTPS origin and query-parameter name. */
public data class ProviderCredential(
    public val origin: String,
    public val queryParameterName: String,
    public val value: String,
) {
    override fun toString(): String =
        "ProviderCredential(origin=<redacted>, queryParameterName=$queryParameterName, value=<redacted>)"
}

public fun interface CredentialProvider {
    public suspend fun credentialFor(origin: String, queryParameterName: String): ProviderCredential?

    public companion object {
        public val None: CredentialProvider = CredentialProvider { _, _ -> null }
    }
}

public fun interface RentileClock {
    public fun nowEpochMillis(): Long

    public companion object {
        public val System: RentileClock = RentileClock { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    }
}
