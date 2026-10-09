package com.rohittp.rentile

import com.rohittp.rentile.internal.style.StyleValue

/** Geometry supplied by the source, not a placement or mesh invented by Rentile. */
public enum class SceneryGeometryType { POINT, LINE, POLYGON }
public enum class SceneryPropertyKind { STRING, NUMBER, BOOLEAN }

/** Explicit layer/type/property whitelist. Query lists and sets are snapshotted before suspension. */
public data class SceneryLayerQuery(
    public val sourceLayer: String,
    public val geometryType: SceneryGeometryType,
    public val properties: Set<String> = emptySet(),
) {
    init {
        require(sourceLayer.isNotBlank() && sourceLayer.length <= 128)
        require(properties.size <= 32 && properties.all { it.isNotBlank() && it.length <= 128 })
    }
}

/** Canonical source-tile coordinates; point parts contain all multipoints, line parts are open. */
public class SceneryGeometry internal constructor(
    public val type: SceneryGeometryType,
    private val coordinates: IntArray,
    private val parts: IntArray,
    private val polygons: IntArray,
) {
    public val vertexCount: Int get() = coordinates.size / 2
    public val partCount: Int get() = parts.size - 1
    public val polygonCount: Int get() = polygons.size - 1
    public val primitiveByteCount: Long get() = (coordinates.size.toLong() + parts.size + polygons.size) * 4L
    public fun x(vertex: Int): Int { require(vertex in 0 until vertexCount); return coordinates[vertex * 2] }
    public fun y(vertex: Int): Int { require(vertex in 0 until vertexCount); return coordinates[vertex * 2 + 1] }
    public fun partStart(part: Int): Int { require(part in 0 until partCount); return parts[part] }
    public fun partEnd(part: Int): Int { require(part in 0 until partCount); return parts[part + 1] }
    public fun polygonStart(polygon: Int): Int { require(polygon in 0 until polygonCount); return polygons[polygon] }
    public fun polygonEnd(polygon: Int): Int { require(polygon in 0 until polygonCount); return polygons[polygon + 1] }
}

/** Whitelisted scalars only. Missing and wrong-type values remain distinguishable. IDs may be null. */
public class SceneryCandidate internal constructor(
    public val sourceTile: TileId,
    public val sourceId: String,
    public val sourceLayer: String,
    public val extent: Int,
    public val featureIndex: Int,
    public val featureId: Long?,
    public val geometry: SceneryGeometry,
    private val properties: Map<String, StyleValue>,
) {
    public fun propertyKind(name: String): SceneryPropertyKind? = when (properties[name]) {
        is StyleValue.StringValue -> SceneryPropertyKind.STRING
        is StyleValue.NumberValue -> SceneryPropertyKind.NUMBER
        is StyleValue.BooleanValue -> SceneryPropertyKind.BOOLEAN
        else -> null
    }
    public fun stringProperty(name: String): String? = (properties[name] as? StyleValue.StringValue)?.value
    public fun numberProperty(name: String): Double? = (properties[name] as? StyleValue.NumberValue)?.value
    public fun booleanProperty(name: String): Boolean? = (properties[name] as? StyleValue.BooleanValue)?.value
}

/** Per-operation limits, not a process RSS guarantee. Hosts budget concurrent and GPU copies. */
public data class SceneryLimits(
    public val maxEncodedTileBytes: Long = 4L * 1024 * 1024,
    public val maxWorkingBytes: Long = 8L * 1024 * 1024,
    public val maxRetainedBytes: Long = 8L * 1024 * 1024,
    public val maxRequestedTiles: Int = 64,
) {
    init {
        require(maxEncodedTileBytes > 0 && maxWorkingBytes > 0 && maxRetainedBytes > 0 && maxRequestedTiles > 0)
    }
    internal fun decodingLimits(): ExtrusionLimits = ExtrusionLimits(
        maxEncodedTileBytes, maxWorkingBytes, maxRetainedBytes, maxRequestedTiles,
    )
}

/** Caller-owned immutable compact payload; content identity includes query and resource revisions. */
public class SceneryCandidateBatch internal constructor(
    public val candidates: List<SceneryCandidate>,
    public val contentKey: String,
    public val estimatedRetainedBytes: Long,
)
