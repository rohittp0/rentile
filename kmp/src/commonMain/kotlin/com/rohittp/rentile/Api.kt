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

/** Host-owned allowance for degraded output tiles during initial preparation. */
public data class TileSubstitutionPolicy(
    public val maximumSubstitutedTiles: Int = 0,
) {
    init {
        require(maximumSubstitutedTiles >= 0)
    }

    public companion object {
        public val Disabled: TileSubstitutionPolicy = TileSubstitutionPolicy()
    }
}

/** How one unavailable source resource was replaced for an output tile. */
public enum class TileSubstitutionStrategy {
    IMMEDIATE_CHILDREN,
    ANCESTOR,
}

/** Credential-free provenance for one substituted source beneath an output tile. */
public data class ResourceSubstitution(
    public val resourceClass: ResourceClass,
    public val sanitizedSourceId: String,
    public val strategy: TileSubstitutionStrategy,
    public val sourceTiles: List<TileId>,
    public val ancestorZoomDistance: Int? = null,
)

/** Successful exact-only recovery performed against an existing prepared batch. */
public data class ExactRecoveryResult(
    public val upgradedTiles: Set<TileId>,
    public val remainingSubstitutedTiles: Set<TileId>,
    public val diagnostics: List<RenderDiagnostic> = emptyList(),
)

/** Sanitized style-layer input for a host-owned geographic-label renderer. */
public data class LabelLayerDescriptor(
    public val id: String,
    public val sourceId: String,
    public val sourceLayer: String,
    public val sourceMinimumZoom: Int,
    public val sourceMaximumZoom: Int,
    public val layerJson: String,
)

/** Validated encoded MVT bytes acquired through Rentile's transport and raw cache. */
public data class ValidatedMvtTile(
    public val requestedTile: TileId,
    public val sourceTile: TileId,
    public val sourceId: String,
    public val bytes: ByteArray,
    public val contentDigest: String,
)

/**
 * One glyph in the batch atlas: where its cell sits in the texture, and the metrics that position
 * it.
 *
 * Every value is in atlas pixels at the signed-distance-field em of **24 pixels**, which is the
 * size the provider rendered these glyphs at. [LabelGlyphQuad.scale] carries the ratio from that
 * em to the label's own `text-size`, so a consumer multiplies by it rather than assuming any
 * particular text size here.
 *
 * The buffered-versus-unbuffered distinction matters and is the one thing easy to get wrong:
 *
 * - [x], [y], [width] and [height] describe the glyph's **cell** in the texture — the region to
 *   sample. Signed-distance-field glyphs carry a **3-pixel** buffer on all four sides so the
 *   distance field stays continuous past the ink, and that buffer is inside the cell: the cell is
 *   6 pixels wider and 6 taller than the glyph body it contains. [x] and [y] are the cell's
 *   top-left corner, measured from the texture's top-left, x rightwards and y downwards.
 * - [left] and [top] are the provider's bearings for the glyph **body**, not the cell, forwarded
 *   verbatim from the glyph range. [left] is the horizontal distance from the pen position to the
 *   body's left edge, positive rightwards. [top] is negative, and `-top` is the distance from the
 *   **line's ascender** down to the body's top edge — measured from the top of the line, not from
 *   its baseline. A line's baseline therefore sits `-top` below the line top for a glyph whose
 *   `height` is zero, which is how the ascender can be recovered from a range's own space glyph;
 *   every other glyph's body bottom is `-top + height` below the line top, so a cap-height letter
 *   lands exactly on the baseline and a descender passes below it.
 *
 * A consumer drawing this itself therefore places the cell's corner 3 pixels up and left of the
 * bearing, which is what [LabelGlyphQuad.x] and [LabelGlyphQuad.y] already have applied — they
 * are cell corners, so nothing further is needed unless the quads are being recomputed.
 *
 * [advance] is how far the pen moves after this glyph, positive rightwards. [fontStackDigest]
 * identifies the font stack this glyph came from without naming it, and [codepoint] is its
 * Unicode code point.
 */
public data class LabelGlyphEntry(
    public val fontStackDigest: String,
    public val codepoint: Int,
    public val x: Int, public val y: Int,
    public val width: Int, public val height: Int,
    public val left: Int, public val top: Int,
    public val advance: Int,
)

/** One texture the consumer uploads once per distinct [contentKey]. */
public data class LabelGlyphAtlas(
    public val pngBytes: ByteArray,
    public val width: Int,
    public val height: Int,
    public val contentKey: String,
    public val entries: List<LabelGlyphEntry>,
) {
    override fun equals(other: Any?): Boolean =
        other is LabelGlyphAtlas &&
            pngBytes.contentEquals(other.pngBytes) &&
            width == other.width &&
            height == other.height &&
            contentKey == other.contentKey &&
            entries == other.entries

    override fun hashCode(): Int {
        var result = pngBytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + contentKey.hashCode()
        result = 31 * result + entries.hashCode()
        return result
    }

    override fun toString(): String =
        "LabelGlyphAtlas(byteCount=${pngBytes.size}, width=$width, height=$height, " +
            "contentKey=$contentKey, entryCount=${entries.size})"
}

/**
 * One glyph positioned within its label, referencing [LabelGlyphAtlas.entries] by [entryIndex].
 *
 * [x] and [y] are the **top-left corner of the glyph's cell** in label-local coordinates: x
 * rightwards, y downwards, and the origin is the label's own anchor point — the position
 * [LabelCandidate.longitude] and [LabelCandidate.latitude] name — after `text-anchor` and
 * `text-offset` have been applied. Nothing here is in screen coordinates and nothing is projected.
 *
 * Each line of a multi-line label occupies one `text-line-height` row and is positioned from that
 * row's top edge, which is what [LabelGlyphEntry.top] is measured against; no baseline is involved
 * and none needs to be reconstructed to draw these.
 *
 * The buffer is already compensated for: these are cell corners, not bearings, so the glyph body
 * lands on the provider's bearing without further adjustment.
 *
 * [scale] is `text-size` divided by the 24-pixel signed-distance-field em, and it is the factor
 * relating this label to the atlas. The quad occupies
 * `x .. x + entry.width * scale` by `y .. y + entry.height * scale`, using the cell extent from
 * [LabelGlyphEntry]; [x] and [y] already have it applied, so it is needed only for the extent.
 */
public data class LabelGlyphQuad(
    public val entryIndex: Int,
    public val x: Double, public val y: Double,
    public val scale: Double,
)

/**
 * Label-local bounds, before any projection, for the consumer's screen-space collision.
 *
 * In the same coordinates and the same units as [LabelGlyphQuad]: the origin is the label's anchor
 * point, x runs rightwards and y downwards, and the values are at the label's own `text-size`
 * rather than at the atlas em. So [top] is above [bottom] numerically — [top] is the smaller value
 * — and both are typically negative for a single-line label centred on its anchor.
 *
 * The box is the union of every quad's cell extent, expanded on all sides by the layer's
 * `text-padding`. It therefore includes each glyph's 3-pixel signed-distance-field buffer, which
 * makes it very slightly larger than the ink it contains.
 */
public data class LabelBox(
    public val left: Double, public val top: Double,
    public val right: Double, public val bottom: Double,
)

/**
 * Paint resolved for one layer at one requested output zoom with no feature context.
 * One entry per (layer, zoom) pair present in the batch; [LabelCandidate.layerStyleIndex]
 * selects the entry matching that candidate's own `requestedTile.z`.
 *
 * [priority] is the layer's position in the style's own layer list, ascending, so a larger value
 * means the style declared the layer later and it should win a placement conflict against a
 * smaller one. It exists so that two consumers resolve ties the same way; the absolute values are
 * not contiguous and carry no meaning beyond their order.
 *
 * [color] and [haloColor] are packed `0xAARRGGBB` integers. Both are resolved with no feature
 * context because no place-name layer in the rolling corpus makes either feature-driven, unlike
 * `text-opacity` and the halo widths, which sit on [LabelCandidate] for exactly that reason.
 */
public data class LabelLayerStyle(
    public val layerId: String,
    public val zoom: Int,
    public val priority: Int,
    public val color: Int,
    public val haloColor: Int,
)

/**
 * The sprite the style pairs with this label, indexing the atlas Rentile already builds.
 *
 * The sprite's centre sits at the label's anchor displaced by
 * `anchorOffset + offset + translate` — the sum of all three, which is exactly how Rentile's own
 * icon pass positions the same sprite. Applying a subset puts the marker somewhere Rentile does
 * not: [anchorOffsetX] and [anchorOffsetY] carry `icon-anchor`'s shift in logical pixels for the
 * resolved [width] and [height], [offsetX] and [offsetY] carry `icon-offset` scaled by
 * `icon-size`, and [translateX] and [translateY] carry `icon-translate`, which the style
 * specification does not scale.
 */
public data class LabelIconRef(
    public val imageName: String,
    public val width: Double, public val height: Double,
    public val offsetX: Double, public val offsetY: Double,
    public val anchorOffsetX: Double, public val anchorOffsetY: Double,
    public val translateX: Double, public val translateY: Double,
)

/**
 * One Label decoded, evaluated and laid out, but not positioned on screen and not
 * resolved against any other Label. Nothing here is in screen coordinates.
 */
public data class LabelCandidate(
    public val layerStyleIndex: Int,
    public val requestedTile: TileId,
    public val sourceTile: TileId,
    public val longitude: Double,
    public val latitude: Double,
    public val glyphs: List<LabelGlyphQuad>,
    public val boundingBox: LabelBox,
    public val icon: LabelIconRef?,
    public val allowOverlap: Boolean,
    public val ignorePlacement: Boolean,
    public val padding: Double,
    public val sortKey: Double,
    public val opacity: Double,
    public val haloWidth: Double,
    public val haloBlur: Double,
)

/** The immutable result of one Label acquisition. Not a Prepared Batch; see CONTEXT.md. */
public data class LabelCandidateBatch(
    public val candidates: List<LabelCandidate>,
    public val layerStyles: List<LabelLayerStyle>,
    public val atlas: LabelGlyphAtlas,
    public val contentKey: String,
    public val diagnostics: List<RenderDiagnostic>,
)

public enum class TerrainDemEncoding {
    MAPBOX,
    TERRARIUM,
}

/** Sanitized planning metadata for the style-selected terrain source. */
public data class TerrainSourceDescriptor(
    public val sourceId: String,
    public val encoding: TerrainDemEncoding,
    public val minimumZoom: Int,
    public val maximumZoom: Int,
    public val tileSizePx: Int,
)

/** Evaluated literal Mapbox ambient-plus-directional ground-light radiance. */
public data class GroundRadianceDescriptor(
    public val red: Double,
    public val green: Double,
    public val blue: Double,
)

/** Validated encoded DEM image bytes acquired through Rentile's transport and raw cache. */
public data class ValidatedDemTile(
    public val requestedTile: TileId,
    public val sourceTile: TileId,
    public val sourceId: String,
    public val encoding: TerrainDemEncoding,
    public val bytes: ByteArray,
    public val contentDigest: String,
)

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
    /**
     * Appended, not inserted, and every field added here in future must be too. These two arrived
     * with label candidates, and while they sat between `maxGeoJsonBytes` and
     * `maxRasterDimensionPx` a caller written against the previous version silently changed
     * meaning instead of failing to compile: six positional arguments ending in a `4096` intended
     * for `maxRasterDimensionPx` type-check as a `Long` and set `maxGlyphRangeBytes` to 4 KiB, so
     * every glyph range over 4 KiB raised [SafetyLimitException]. A source-compatible signature
     * that quietly reassigns a caller's arguments is worse than an incompatible one.
     */
    public val maxGlyphRangeBytes: Long = 1L * 1024L * 1024L,
    /**
     * Highest observed in the rolling corpus was 15 glyph ranges, at Tokyo z14, dense CJK
     * (measured 2026-08-19). That count is per tile, while this ceiling is per batch: a real
     * consumer acquires ten to thirty tiles in one [acquireLabelCandidates] call. Ranges dedupe
     * across the whole batch, so a large viewport over one dense area does not multiply 15 by
     * the tile count — but a style with several font stacks does multiply it by the stack count.
     * The failure mode is a hard [SafetyLimitException] that fails the entire acquisition, so
     * headroom is worth more than tightness here: 64 is about four times the observed
     * single-tile maximum, not the ~32 a naive "twice the observed maximum" would give.
     * Tightening this further needs a real multi-tile viewport measurement, not a re-derivation
     * from the single-tile number above.
     */
    public val maxGlyphRangesPerBatch: Int = 64,
) {
    init {
        require(maxStyleBytes > 0)
        require(maxMetadataBytes > 0)
        require(maxTileBytes > 0)
        require(maxSpriteImageBytes > 0)
        require(maxGeoJsonBytes > 0)
        require(maxGlyphRangeBytes > 0)
        require(maxGlyphRangesPerBatch > 0)
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
    public val substitutions: Map<TileId, List<ResourceSubstitution>>

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

    /**
     * Stable identity for a caller-owned rendered-output cache lookup.
     *
     * Unlike [PreparedBatch.contentKeys], this key is available before raw tile acquisition. It
     * includes every output-affecting request input known at this boundary and deliberately omits
     * credentials, sessions, validators, and acquired-resource digests. A caller must store the
     * eventual content key and substitution provenance beside the rendered bytes it indexes with
     * this value.
     */
    public fun outputRequestKey(
        style: PreparedStyle,
        tile: TileId,
        options: RenderOptions = RenderOptions(),
    ): String

    public suspend fun prepareBatch(
        style: PreparedStyle,
        tiles: List<TileId>,
        options: RenderOptions = RenderOptions(),
        resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
        substitutionPolicy: TileSubstitutionPolicy = TileSubstitutionPolicy.Disabled,
    ): PreparedBatch

    /**
     * Retries exact acquisition only for resources currently represented by substitutes.
     * Acquisition failures retain the existing substitutes and do not fail the recovery cycle.
     */
    public suspend fun retryExact(batch: PreparedBatch): ExactRecoveryResult

    /** Resolved visible place-name symbol layers in style order. URL templates remain private. */
    public fun labelLayerDescriptors(style: PreparedStyle): List<LabelLayerDescriptor>

    /** All-or-error validated MVT acquisition. Tile substitution is deliberately not applied. */
    public suspend fun acquireLabelTiles(
        style: PreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): List<ValidatedMvtTile>

    /**
     * Stable identity for a caller-owned label-candidate cache lookup, available before any
     * network.
     *
     * Covers style identity, tile identities and a label-semantics version. It deliberately omits
     * credentials, sessions, validators and the glyph closure: which Glyph Ranges a tile set needs
     * is not knowable until its features are decoded, so this key cannot depend on them. A caller
     * must store [LabelCandidateBatch.contentKey] beside whatever it indexes with this value.
     */
    public fun labelCandidateRequestKey(style: PreparedStyle, tiles: List<TileId>): String

    /**
     * All-or-error validated Label acquisition. Tile substitution is deliberately not applied.
     *
     * A style declaring no `glyphs` template yields an empty batch carrying
     * [DiagnosticCode.GLYPH_RANGE_UNAVAILABLE] rather than failing: label preparation is opt-in,
     * and a style without glyphs is legitimate.
     */
    public suspend fun acquireLabelCandidates(
        style: PreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): LabelCandidateBatch

    /** Returns null when the prepared style does not select a raster-dem terrain source. */
    public fun terrainSourceDescriptor(style: PreparedStyle): TerrainSourceDescriptor?

    /** Returns null when the prepared style has no complete supported ground-light pair. */
    public fun groundRadianceDescriptor(style: PreparedStyle): GroundRadianceDescriptor?

    /** All-or-error validated DEM acquisition. Tile substitution is deliberately not applied. */
    public suspend fun acquireTerrainTiles(
        style: PreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): List<ValidatedDemTile>

    public suspend fun render(
        batch: PreparedBatch,
        tiles: List<TileId> = batch.tiles,
    ): RenderBatch

    public suspend fun render(
        style: PreparedStyle,
        tiles: List<TileId>,
        options: RenderOptions = RenderOptions(),
        resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
        substitutionPolicy: TileSubstitutionPolicy = TileSubstitutionPolicy.Disabled,
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
