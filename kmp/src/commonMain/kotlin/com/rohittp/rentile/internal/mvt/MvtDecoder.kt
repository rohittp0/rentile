package com.rohittp.rentile.internal.mvt

import com.rohittp.rentile.ResourceLimits
import com.rohittp.rentile.internal.style.FeatureGeometryType
import com.rohittp.rentile.internal.style.StyleValue
import kotlin.math.absoluteValue

internal data class DecodedVectorTile(
    val layers: List<DecodedVectorLayer>,
)

internal data class DecodedVectorLayer(
    val name: String,
    val extent: Int,
    val features: List<DecodedVectorFeature>,
)

internal data class DecodedVectorFeature(
    val id: ULong?,
    val geometryType: FeatureGeometryType,
    val properties: Map<String, StyleValue>,
    val geometry: DecodedVectorGeometry,
)

internal data class VectorCoordinate(
    val x: Int,
    val y: Int,
)

internal sealed interface DecodedVectorGeometry {
    data class Points(val points: List<VectorCoordinate>) : DecodedVectorGeometry

    data class Lines(val lines: List<List<VectorCoordinate>>) : DecodedVectorGeometry

    data class Polygons(val rings: List<VectorRing>) : DecodedVectorGeometry
}

internal data class VectorRing(
    val points: List<VectorCoordinate>,
    val signedAreaTwice: Double,
)

internal class MvtDecodingException(
    message: String,
    val limitName: String? = null,
    val limit: Long? = null,
    val observed: Long? = null,
) : IllegalArgumentException(message)

internal class MvtDecoder(
    limits: ResourceLimits,
) {
    private val limits = MvtDecodeLimits(
        maxLayers = limits.maxMvtLayers,
        maxFeatures = limits.maxMvtFeatures,
        maxTags = limits.maxMvtTags,
        maxCommands = limits.maxMvtCommands,
        maxCoordinates = limits.maxMvtCoordinates,
        maxExtent = limits.maxMvtExtent,
    )

    fun decode(bytes: ByteArray): DecodedVectorTile {
        val tile = try {
            Tile.ADAPTER.decode(bytes)
        } catch (_: Throwable) {
            throw MvtDecodingException("MVT protobuf is malformed or truncated")
        }
        if (tile.layers.size > limits.maxLayers) {
            limit("maxMvtLayers", limits.maxLayers.toLong(), tile.layers.size.toLong(), "MVT layer count exceeds its limit")
        }
        val budget = DecodeBudget()
        val layerNames = mutableSetOf<String>()
        val layers = tile.layers.map { layer ->
            if (layer.version != 2) fail("MVT layer version must be 2")
            if (layer.name.isEmpty() || !layerNames.add(layer.name)) fail("MVT layer names must be non-empty and unique")
            val extent = layer.extent ?: Tile.Layer.DEFAULT_EXTENT
            if (extent <= 0 || extent > limits.maxExtent) {
                limit("maxMvtExtent", limits.maxExtent.toLong(), extent.toLong(), "MVT layer extent is outside its limit")
            }
            if (layer.keys.size != layer.keys.toSet().size) fail("MVT layer keys must be unique")
            val values = layer.values.map(::decodeValue)
            val features = layer.features.map { feature ->
                budget.features += 1
                if (budget.features > limits.maxFeatures) {
                    limit("maxMvtFeatures", limits.maxFeatures.toLong(), budget.features.toLong(), "MVT feature count exceeds its limit")
                }
                decodeFeature(feature, layer.keys, values, extent, budget)
            }
            DecodedVectorLayer(layer.name, extent, features)
        }
        return DecodedVectorTile(layers)
    }

    private fun decodeValue(value: Tile.Value): StyleValue {
        val present = listOfNotNull(
            value.string_value?.let { StyleValue.StringValue(it) },
            value.float_value?.let { StyleValue.NumberValue(it.toDouble()) },
            value.double_value?.let { StyleValue.NumberValue(it) },
            value.int_value?.let { StyleValue.NumberValue(it.toDouble()) },
            value.uint_value?.let { StyleValue.NumberValue(it.toULong().toDouble()) },
            value.sint_value?.let { StyleValue.NumberValue(it.toDouble()) },
            value.bool_value?.let { StyleValue.BooleanValue(it) },
        )
        if (present.size != 1) fail("Every MVT value must contain exactly one scalar")
        val scalar = present.single()
        if (scalar is StyleValue.NumberValue && !scalar.value.isFinite()) fail("MVT numeric values must be finite")
        return scalar
    }

    private fun decodeFeature(
        feature: Tile.Feature,
        keys: List<String>,
        values: List<StyleValue>,
        extent: Int,
        budget: DecodeBudget,
    ): DecodedVectorFeature {
        if (feature.tags.size % 2 != 0) fail("MVT feature tags must be key/value pairs")
        budget.tags += feature.tags.size
        if (budget.tags > limits.maxTags) {
            limit("maxMvtTags", limits.maxTags.toLong(), budget.tags.toLong(), "MVT tag count exceeds its limit")
        }
        val properties = LinkedHashMap<String, StyleValue>(feature.tags.size / 2)
        feature.tags.chunked(2).forEach { pair ->
            val keyIndex = pair[0].toUInt().toLong()
            val valueIndex = pair[1].toUInt().toLong()
            if (keyIndex >= keys.size || valueIndex >= values.size) fail("MVT feature tag index is invalid")
            val key = keys[keyIndex.toInt()]
            if (properties.put(key, values[valueIndex.toInt()]) != null) fail("MVT feature contains a duplicate property key")
        }

        val geometryType = when (feature.type) {
            Tile.GeomType.POINT -> FeatureGeometryType.POINT
            Tile.GeomType.LINESTRING -> FeatureGeometryType.LINE_STRING
            Tile.GeomType.POLYGON -> FeatureGeometryType.POLYGON
            else -> fail("MVT feature geometry type is missing or unknown")
        }
        if (feature.geometry.isEmpty()) fail("MVT feature geometry command stream is empty")
        budget.commands += feature.geometry.size
        if (budget.commands > limits.maxCommands) {
            limit("maxMvtCommands", limits.maxCommands.toLong(), budget.commands.toLong(), "MVT command count exceeds its limit")
        }
        val geometry = GeometryDecoder(feature.geometry, limits, budget, extent).decode(geometryType)
        return DecodedVectorFeature(
            id = feature.id?.toULong(),
            geometryType = geometryType,
            properties = properties,
            geometry = geometry,
        )
    }

    private fun limit(name: String, limit: Long, observed: Long, message: String): Nothing =
        throw MvtDecodingException(message, name, limit, observed)

    private fun fail(message: String): Nothing = throw MvtDecodingException(message)
}

private class GeometryDecoder(
    private val commands: List<Int>,
    private val limits: MvtDecodeLimits,
    private val budget: DecodeBudget,
    extent: Int,
) {
    private var index = 0
    private var cursorX = 0L
    private var cursorY = 0L
    private val maxCoordinateMagnitude = maxOf(1L shl 28, extent.toLong() * 16L)

    fun decode(type: FeatureGeometryType): DecodedVectorGeometry = when (type) {
        FeatureGeometryType.POINT -> decodePoints()
        FeatureGeometryType.LINE_STRING -> decodeLines()
        FeatureGeometryType.POLYGON -> decodePolygons()
    }

    private fun decodePoints(): DecodedVectorGeometry.Points {
        val points = mutableListOf<VectorCoordinate>()
        while (index < commands.size) {
            val command = readCommand(expectedId = MOVE_TO)
            repeat(command.count) { points += readCoordinate() }
        }
        if (points.isEmpty()) fail("MVT point geometry has no points")
        return DecodedVectorGeometry.Points(points)
    }

    private fun decodeLines(): DecodedVectorGeometry.Lines {
        val lines = mutableListOf<List<VectorCoordinate>>()
        while (index < commands.size) {
            val move = readCommand(expectedId = MOVE_TO)
            if (move.count != 1) fail("MVT line MoveTo count must be one")
            val line = mutableListOf(readCoordinate())
            val draw = readCommand(expectedId = LINE_TO)
            if (draw.count < 1) fail("MVT line LineTo count must be positive")
            repeat(draw.count) { line += readCoordinate() }
            lines += line
        }
        if (lines.isEmpty()) fail("MVT line geometry has no lines")
        return DecodedVectorGeometry.Lines(lines)
    }

    private fun decodePolygons(): DecodedVectorGeometry.Polygons {
        val rings = mutableListOf<VectorRing>()
        while (index < commands.size) {
            val move = readCommand(expectedId = MOVE_TO)
            if (move.count != 1) fail("MVT polygon MoveTo count must be one")
            val points = mutableListOf(readCoordinate())
            val draw = readCommand(expectedId = LINE_TO)
            if (draw.count < 2) fail("MVT polygon rings require at least three vertices")
            repeat(draw.count) { points += readCoordinate() }
            val close = readCommand(expectedId = CLOSE_PATH)
            if (close.count != 1) fail("MVT polygon ClosePath count must be one")
            val area = signedAreaTwice(points)
            if (area == 0.0) fail("MVT polygon ring area must be non-zero")
            rings += VectorRing(points, area)
        }
        if (rings.isEmpty()) fail("MVT polygon geometry has no rings")
        return DecodedVectorGeometry.Polygons(rings)
    }

    private fun readCommand(expectedId: Int): GeometryCommand {
        if (index >= commands.size) fail("MVT geometry command stream is truncated")
        val encoded = commands[index++].toUInt().toLong()
        val id = (encoded and 0x7L).toInt()
        val count = encoded ushr 3
        if (id != expectedId) fail("MVT geometry command sequence is invalid")
        if (count == 0L || count > Int.MAX_VALUE) fail("MVT geometry command count is invalid")
        val parameterCount = if (id == CLOSE_PATH) 0L else count * 2L
        if (parameterCount > commands.size.toLong() - index) fail("MVT geometry command parameters are truncated")
        return GeometryCommand(id, count.toInt())
    }

    private fun readCoordinate(): VectorCoordinate {
        if (index + 1 >= commands.size) fail("MVT coordinate pair is truncated")
        val dx = zigZagDecode(commands[index++])
        val dy = zigZagDecode(commands[index++])
        cursorX += dx
        cursorY += dy
        if (cursorX.absoluteValue > maxCoordinateMagnitude || cursorY.absoluteValue > maxCoordinateMagnitude) {
            limit(
                "maxMvtCoordinateMagnitude",
                maxCoordinateMagnitude,
                maxOf(cursorX.absoluteValue, cursorY.absoluteValue),
                "MVT coordinate magnitude exceeds its limit",
            )
        }
        budget.coordinates += 1
        if (budget.coordinates > limits.maxCoordinates) {
            limit(
                "maxMvtCoordinates",
                limits.maxCoordinates.toLong(),
                budget.coordinates.toLong(),
                "MVT coordinate count exceeds its limit",
            )
        }
        return VectorCoordinate(cursorX.toInt(), cursorY.toInt())
    }

    private fun zigZagDecode(encoded: Int): Long {
        val value = encoded.toUInt().toLong()
        return (value ushr 1) xor -(value and 1L)
    }

    private fun signedAreaTwice(points: List<VectorCoordinate>): Double {
        var area = 0.0
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            area += current.x.toDouble() * next.y - next.x.toDouble() * current.y
        }
        return area
    }

    private fun limit(name: String, limit: Long, observed: Long, message: String): Nothing =
        throw MvtDecodingException(message, name, limit, observed)

    private fun fail(message: String): Nothing = throw MvtDecodingException(message)

    private data class GeometryCommand(val id: Int, val count: Int)

    private companion object {
        const val MOVE_TO = 1
        const val LINE_TO = 2
        const val CLOSE_PATH = 7
    }
}

private data class MvtDecodeLimits(
    val maxLayers: Int,
    val maxFeatures: Int,
    val maxTags: Int,
    val maxCommands: Int,
    val maxCoordinates: Int,
    val maxExtent: Int,
)

private data class DecodeBudget(
    var features: Int = 0,
    var tags: Int = 0,
    var commands: Int = 0,
    var coordinates: Int = 0,
)
