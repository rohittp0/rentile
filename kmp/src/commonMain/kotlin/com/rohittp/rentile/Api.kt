package com.rohittp.rentile

import com.rohittp.rentile.internal.createBasemapRasterizer

/** North-up XYZ output-tile identity. */
public data class TileId(
    public val z: Int,
    public val x: Int,
    public val y: Int,
)

/** Input forms accepted by style preparation. */
public sealed interface StyleInput {
    /** Inline style JSON with an optional base URI for relative references. */
    public class InlineJson(
        public val json: String,
        public val baseUri: String? = null,
    ) : StyleInput {
        override fun toString(): String =
            "StyleInput.InlineJson(byteCount=${json.encodeToByteArray().size}, hasBaseUri=${baseUri != null})"
    }

    /** Style document acquired through the configured [ResourceTransport]. */
    public class Remote(
        public val url: String,
    ) : StyleInput {
        override fun toString(): String = "StyleInput.Remote(url=<redacted>)"
    }

    /** Already acquired style bytes with a caller-provided canonical identity. */
    public class Prefetched(
        bytes: ByteArray,
        public val canonicalIdentity: String,
        public val baseUri: String? = null,
    ) : StyleInput {
        internal val bytes: ByteArray = bytes.copyOf()

        override fun toString(): String =
            "StyleInput.Prefetched(canonicalIdentity=<redacted>, byteCount=${bytes.size})"
    }
}

/** Closed set of rendering profiles understood by this library version. */
public class CompatibilityPolicy private constructor(
    public val id: String,
    public val minimumOutputZoom: Int,
    public val maximumOutputZoom: Int,
) {
    public companion object {
        public val RentileV1: CompatibilityPolicy = CompatibilityPolicy(
            id = "rentile-v1",
            minimumOutputZoom = 0,
            maximumOutputZoom = 22,
        )
        public val Default: CompatibilityPolicy = RentileV1
    }

    override fun equals(other: Any?): Boolean = other is CompatibilityPolicy && id == other.id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = id
}

/** Controls how an operation may access the injected raw-resource store and transport. */
public enum class ResourceAccessMode {
    NORMAL,
    CACHE_ONLY,
    RELOAD,
}

/** Content-affecting controls for output-tile creation. */
public data class RenderOptions(
    public val outputSizePx: Int = DEFAULT_OUTPUT_SIZE_PX,
) {
    init {
        require(outputSizePx in SUPPORTED_OUTPUT_SIZES) {
            "outputSizePx must be one of ${SUPPORTED_OUTPUT_SIZES.sorted()}"
        }
    }

    public companion object {
        public const val DEFAULT_OUTPUT_SIZE_PX: Int = 512
        public val SUPPORTED_OUTPUT_SIZES: Set<Int> = setOf(256, 512)
    }
}

/** Operational limits owned by one long-lived [BasemapRasterizer]. */
public data class ExecutionPolicy(
    public val maxConcurrentExchanges: Int = 8,
    public val maxConcurrentExchangesPerOrigin: Int = 6,
    public val maxConcurrentDecodes: Int = 2,
    public val maxResidentDecodedBytes: Long = 128L * 1024L * 1024L,
    public val maxConcurrentMetatileWorkers: Int = 1,
) {
    init {
        require(maxConcurrentExchanges > 0)
        require(maxConcurrentExchangesPerOrigin in 1..maxConcurrentExchanges)
        require(maxConcurrentDecodes > 0)
        require(maxResidentDecodedBytes > 0)
        require(maxConcurrentMetatileWorkers > 0)
    }
}

/** Hard safety ceilings for untrusted encoded and decoded resources. */
public data class ResourceLimits(
    public val maxStyleBytes: Long = 8L * 1024L * 1024L,
    public val maxMetadataBytes: Long = 4L * 1024L * 1024L,
    public val maxTileBytes: Long = 32L * 1024L * 1024L,
    public val maxSpriteImageBytes: Long = 32L * 1024L * 1024L,
    public val maxGeoJsonBytes: Long = 64L * 1024L * 1024L,
    public val maxRasterDimensionPx: Int = 8192,
    public val maxDecodedRasterBytes: Long = 256L * 1024L * 1024L,
    public val maxMvtLayers: Int = 512,
    public val maxMvtFeatures: Int = 500_000,
    public val maxMvtTags: Int = 4_000_000,
    public val maxMvtCommands: Int = 8_000_000,
    public val maxMvtCoordinates: Int = 8_000_000,
    public val maxMvtExtent: Int = 65_536,
    public val maxRedirects: Int = 5,
) {
    init {
        require(maxStyleBytes > 0)
        require(maxMetadataBytes > 0)
        require(maxTileBytes > 0)
        require(maxSpriteImageBytes > 0)
        require(maxGeoJsonBytes > 0)
        require(maxRasterDimensionPx > 0)
        require(maxDecodedRasterBytes > 0)
        require(maxMvtLayers > 0)
        require(maxMvtFeatures > 0)
        require(maxMvtTags > 0)
        require(maxMvtCommands > 0)
        require(maxMvtCoordinates > 0)
        require(maxMvtExtent > 0)
        require(maxRedirects >= 0)
    }
}

/** Dependencies and non-content policy owned by one rasterizer instance. */
public data class RentileConfiguration(
    public val transport: ResourceTransport,
    public val rawResourceStore: RawResourceStore,
    public val sessionProvider: MapSessionProvider = MapSessionProvider.None,
    public val credentialProvider: CredentialProvider = CredentialProvider.None,
    public val clock: RentileClock = RentileClock.System,
    public val metricsSink: MetricsSink = MetricsSink.None,
    public val diagnosticSink: DiagnosticSink = DiagnosticSink.None,
    public val executionPolicy: ExecutionPolicy = ExecutionPolicy(),
    public val resourceLimits: ResourceLimits = ResourceLimits(),
)

/** Immutable style program owned by the rasterizer instance that prepared it. */
public interface PreparedStyle {
    public val digest: String
    public val policy: CompatibilityPolicy
    public val diagnostics: List<RenderDiagnostic>
}

/** Network-free rendering input with content keys available before drawing. */
public interface PreparedBatch : AutoCloseable {
    public val tiles: List<TileId>
    public val contentKeys: Map<TileId, String>
    public val diagnostics: List<RenderDiagnostic>

    /** Idempotent, non-blocking, and non-throwing. */
    override fun close()
}

/** A successful, caller-owned encoded output tile. */
public data class RenderedTile(
    public val id: TileId,
    public val pngBytes: ByteArray,
    public val contentKey: String,
    public val diagnostics: List<RenderDiagnostic> = emptyList(),
)

/** All-or-error result for a caller-defined render operation. */
public data class RenderBatch(
    public val tiles: List<RenderedTile>,
    public val diagnostics: List<RenderDiagnostic> = emptyList(),
)

/** Public renderer boundary. Implementations are process-local resource owners. */
public interface BasemapRasterizer : AutoCloseable {
    public suspend fun prepare(
        style: StyleInput,
        policy: CompatibilityPolicy = CompatibilityPolicy.Default,
    ): PreparedStyle

    public suspend fun prepareBatch(
        style: PreparedStyle,
        tiles: List<TileId>,
        options: RenderOptions = RenderOptions(),
        resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): PreparedBatch

    public suspend fun render(
        batch: PreparedBatch,
        tiles: List<TileId> = batch.tiles,
    ): RenderBatch

    public suspend fun render(
        style: PreparedStyle,
        tiles: List<TileId>,
        options: RenderOptions = RenderOptions(),
        resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): RenderBatch

    /** Idempotent, non-blocking, and non-throwing. */
    override fun close()

    /** Suspends until workers, leases, native objects, and secret state are released. */
    public suspend fun awaitClosed()
}

/** Factory for the process-local deep rendering module. */
public object Rentile {
    public fun create(configuration: RentileConfiguration): BasemapRasterizer =
        createBasemapRasterizer(configuration)
}
