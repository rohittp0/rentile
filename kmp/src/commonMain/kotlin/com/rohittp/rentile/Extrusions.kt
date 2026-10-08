package com.rohittp.rentile

/** A host-owned extrusion layer, in authored style order. No provider URLs are exposed. */
public data class ExtrusionLayerDescriptor(
    public val id: String,
    public val styleLayerIndex: Int,
    public val sourceId: String,
    public val sourceLayer: String,
    public val minimumZoom: Double,
    public val maximumZoom: Double,
    public val sourceMinimumZoom: Int,
    public val sourceMaximumZoom: Int,
    public val verticalGradient: Boolean,
)

/** Metres above ground. Color is opaque ARGB; fill-extrusion-color's alpha is ignored. */
public data class ExtrusionPaint(
    public val baseMetres: Double,
    public val heightMetres: Double,
    public val color: Int,
)

/** Layer opacity is separate from feature color and is evaluated at fractional camera zoom. */
public class ExtrusionLayerStyle internal constructor(
    public val descriptor: ExtrusionLayerDescriptor,
    private val evaluateOpacity: (Double) -> Double,
) {
    public fun opacityAtZoom(zoom: Double): Double {
        require(zoom.isFinite())
        return evaluateOpacity(zoom)
    }
}

/**
 * Immutable, compact MVT coordinates in the canonical source tile's extent. Buffered coordinates
 * outside the extent are preserved. Rings are implicitly closed; the first ring of each polygon
 * is its clockwise exterior (in Y-down tile coordinates), followed by counter-clockwise holes.
 * This is geometry, not a mesh: projection, triangulation, clipping and tile-edge walls belong to
 * the host. Accessors avoid copying; copy methods deliberately allocate caller-owned arrays.
 */
public class ExtrusionGeometry internal constructor(
    private val coordinates: IntArray,
    private val rings: IntArray,
    private val polygons: IntArray,
) {
    public val vertexCount: Int get() = coordinates.size / 2
    public val ringCount: Int get() = rings.size - 1
    public val polygonCount: Int get() = polygons.size - 1
    public val primitiveByteCount: Long get() = (coordinates.size.toLong() + rings.size + polygons.size) * 4L
    public fun x(vertex: Int): Int { require(vertex in 0 until vertexCount); return coordinates[vertex * 2] }
    public fun y(vertex: Int): Int { require(vertex in 0 until vertexCount); return coordinates[vertex * 2 + 1] }
    public fun ringStart(ring: Int): Int { require(ring in 0 until ringCount); return rings[ring] }
    public fun ringEnd(ring: Int): Int { require(ring in 0 until ringCount); return rings[ring + 1] }
    public fun polygonStart(polygon: Int): Int { require(polygon in 0 until polygonCount); return polygons[polygon] }
    public fun polygonEnd(polygon: Int): Int { require(polygon in 0 until polygonCount); return polygons[polygon + 1] }
    public fun copyCoordinates(): IntArray = coordinates.copyOf()
    public fun copyRingOffsets(): IntArray = rings.copyOf()
    public fun copyPolygonOffsets(): IntArray = polygons.copyOf()
}

/**
 * One feature/layer pairing. A source feature without an MVT ID keeps a null ID; featureIndex is
 * its index in that source layer and is stable only for this resource revision. uint64 IDs keep
 * their bit pattern in a signed Long, as LabelCandidate does.
 *
 * paintAtZoom uses fractional zoom for paint and floor(zoom) for filters. Null means inactive,
 * or filtered; missing or unusable properties use their style defaults (height/base zero, color
 * black). Legacy explicit defaults are respected. A missing height never fabricates a building.
 * Negative heights/bases are clamped to zero; base greater than height is clamped to height.
 * Constant paint is cached. Zoom-dependent paint retains only its feature properties/program,
 * never the encoded tile or a graph of coordinate objects.
 */
public class ExtrusionCandidate internal constructor(
    public val layerStyleIndex: Int,
    public val sourceTile: TileId,
    public val sourceId: String,
    public val extent: Int,
    public val featureIndex: Int,
    public val featureId: Long?,
    public val geometry: ExtrusionGeometry,
    public val paintIsZoomDependent: Boolean,
    private val evaluatePaint: (Double) -> ExtrusionPaint?,
) {
    public fun paintAtZoom(zoom: Double): ExtrusionPaint? {
        require(zoom.isFinite())
        return evaluatePaint(zoom)
    }
}

/**
 * Per-operation bounds, checked before allocation. Retained accounting is a conservative estimate
 * of geometry, candidates and retained feature properties, not a process RSS guarantee. Hosts
 * must separately budget concurrent operations, retained batches, triangulation and GPU copies.
 * Raw transport buffers remain bounded by ResourceLimits.maxTileBytes; the encoded extrusion
 * cap is checked before selective decoding. Tile decoding is sequential within a batch. Ask for bounded viewport/prepared windows, not an
 * entire route. Exceeding a limit throws SafetyLimitException, never silently drops geometry.
 */
public data class ExtrusionLimits(
    public val maxEncodedTileBytes: Long = 4L * 1024 * 1024,
    public val maxWorkingBytes: Long = 8L * 1024 * 1024,
    public val maxRetainedBytes: Long = 16L * 1024 * 1024,
    public val maxRequestedTiles: Int = 256,
) {
    init {
        require(maxEncodedTileBytes > 0 && maxWorkingBytes > 0 && maxRetainedBytes > 0)
        require(maxRequestedTiles > 0)
    }
}

/** Caller-owned data. Geometry is shared across style layers and overzoomed output children. */
public class ExtrusionCandidateBatch internal constructor(
    public val candidates: List<ExtrusionCandidate>,
    public val layerStyles: List<ExtrusionLayerStyle>,
    public val contentKey: String,
    public val estimatedRetainedBytes: Long,
)
