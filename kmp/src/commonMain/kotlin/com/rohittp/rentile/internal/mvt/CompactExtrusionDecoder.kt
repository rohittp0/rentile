package com.rohittp.rentile.internal.mvt

import com.rohittp.rentile.ExtrusionGeometry
import com.rohittp.rentile.ExtrusionLimits
import com.rohittp.rentile.ResourceLimits
import com.rohittp.rentile.SceneryGeometry
import com.rohittp.rentile.SceneryGeometryType
import com.rohittp.rentile.SceneryLayerQuery
import com.rohittp.rentile.internal.style.StyleValue
import kotlin.math.abs

/** Limits are charged before allocation, including dictionary/string and object overhead estimates. */
internal class ExtrusionBudget(val limits: ExtrusionLimits) {
    var retained: Long = 0L
        private set
    fun restore(checkpoint: Long) { retained = checkpoint }
    fun retain(bytes: Long) {
        checkedLimit("maxRetainedBytes", limits.maxRetainedBytes, retained + bytes)
        retained += bytes
    }
}

internal class CompactPolygonFeature(
    val layer: String, val extent: Int, val index: Int, val id: Long?,
    val properties: Map<String, StyleValue>, val geometry: ExtrusionGeometry,
)

internal class CompactSceneryFeature(
    val layer: String, val extent: Int, val index: Int, val id: Long?,
    val properties: Map<String, StyleValue>, val geometry: SceneryGeometry,
)

/**
 * Selective, two-pass protobuf reader. It never constructs Wire's Tile graph, boxed geometry
 * commands, VectorCoordinates, or lists of ring points. Count/validate first, allocate exact
 * primitive arrays second. Field order, packed/unpacked fields and unknown fields are supported.
 */
internal class CompactExtrusionDecoder(
    private val resourceLimits: ResourceLimits,
    private val budget: ExtrusionBudget,
    private val checkCancellation: () -> Unit,
) {
    private var sceneryQuery: Map<String, SceneryLayerQuery>? = null
    private var emitScenery: ((CompactSceneryFeature) -> Unit)? = null

    fun decodeScenery(bytes: ByteArray, query: List<SceneryLayerQuery>, emit: (CompactSceneryFeature) -> Unit) {
        check(sceneryQuery == null)
        sceneryQuery = query.associateBy { it.sourceLayer }
        emitScenery = emit
        try { decode(bytes, sceneryQuery!!.keys) {} }
        finally { sceneryQuery = null; emitScenery = null }
    }

    private var features = 0L
    private var tags = 0L
    private var commands = 0L
    private var coordinates = 0L
    private var working = 0L
    private var steps = 0

    fun decode(bytes: ByteArray, selectedLayers: Set<String>, emit: (CompactPolygonFeature) -> Unit) {
        checkedLimit("maxEncodedTileBytes", budget.limits.maxEncodedTileBytes, bytes.size.toLong())
        working = 0
        val root = Cursor(bytes)
        val names = mutableSetOf<String>()
        var layers = 0L
        while (!root.done) {
            tick()
            val tag = root.tag()
            if (tag == 26) {
                checkedLimit("maxMvtLayers", resourceLimits.maxMvtLayers.toLong(), ++layers)
                val layer = root.message()
                var name: String? = null
                var version: Long? = null
                var extent = 4096L
                val header = layer.cursor()
                while (!header.done) {
                    tick()
                    when (val field = header.tag()) {
                        10 -> { if (name != null) malformed(); work(96); name = string(header.message()) }
                        40 -> extent = header.uint()
                        120 -> version = header.uint()
                        18 -> { checkedLimit("maxMvtFeatures", resourceLimits.maxMvtFeatures.toLong(), ++features); header.message() }
                        else -> header.skip(field)
                    }
                }
                if (version != 2L || name.isNullOrEmpty() || !names.add(name)) malformed()
                checkedLimit("maxMvtExtent", resourceLimits.maxMvtExtent.toLong(), extent)
                if (extent <= 0) malformed()
                if (name in selectedLayers) decodeLayer(layer, name, extent.toInt(), emit)
            } else root.skip(tag)
        }
        checkCancellation()
    }

    private fun decodeLayer(layer: Slice, name: String, extent: Int, emit: (CompactPolygonFeature) -> Unit) {
        // Dictionaries are needed before features, regardless of protobuf field order.
        val keys = ArrayList<String>()
        val uniqueKeys = HashSet<String>()
        val values = ArrayList<StyleValue>()
        val scan = layer.cursor()
        val beforeDictionary = working
        while (!scan.done) {
            tick()
            when (val field = scan.tag()) {
                26 -> {
                    work(96)
                    val key = string(scan.message())
                    if (!uniqueKeys.add(key)) malformed()
                    keys.add(key)
                }
                34 -> { work(48); values.add(value(scan.message())) }
                else -> scan.skip(field)
            }
        }
        val featureScan = layer.cursor()
        var index = 0
        while (!featureScan.done) {
            tick()
            val field = featureScan.tag()
            if (field == 18) decodeFeature(featureScan.message(), name, extent, index++, keys, values, emit)
            else featureScan.skip(field)
        }
        working = beforeDictionary
    }

    private fun decodeFeature(
        feature: Slice, layer: String, extent: Int, index: Int,
        keys: List<String>, values: List<StyleValue>, emit: (CompactPolygonFeature) -> Unit,
    ) {
        var id: Long? = null
        var type = 0L
        val scan = feature.cursor()
        while (!scan.done) {
            tick()
            when (val field = scan.tag()) {
                8 -> id = scan.uint()
                24 -> type = scan.uint()
                else -> scan.skip(field)
            }
        }
        val selected = sceneryQuery?.get(layer)
        val expectedType = selected?.geometryType?.let { it.ordinal + 1 } ?: 3
        if (type != expectedType.toLong()) return
        val counts = if (type == 3L) polygon(feature, extent, null, null, null, countBudget = true)
            else pointsOrLines(feature, extent, type == 1L, null, null, countBudget = true)
        val arrayBytes = (counts.vertices.toLong() * 2 + counts.rings + 1L + counts.polygons + 1L) * 4L
        budget.retain(arrayBytes + 128)
        // Count tags before allocating their map; charge a conservative map entry estimate.
        val tagCounts = UIntFields(feature, 2, ::tick)
        var tagCount = 0L
        while (tagCounts.next() != null) {
            tick()
            checkedLimit("maxMvtTags", resourceLimits.maxMvtTags.toLong(), tags + ++tagCount)
        }
        if (tagCount % 2 != 0L) malformed()
        tags += tagCount
        val propertyBytes = 96L + tagCount / 2 * 128L
        budget.retain(propertyBytes)
        val properties = LinkedHashMap<String, StyleValue>()
        val tagReader = UIntFields(feature, 2, ::tick)
        while (true) {
            tick()
            val key = tagReader.next() ?: break
            val value = tagReader.next() ?: malformed()
            if (key !in keys.indices.toLongRange() || value !in values.indices.toLongRange()) malformed()
            val k = keys[key.toInt()]
            val v = values[value.toInt()]
            if (selected != null && k !in selected.properties) continue
            // Retained properties reference dictionary objects after this layer's working set dies.
            budget.retain(k.length.toLong() * 2 + 48 + (if (v is StyleValue.StringValue) v.value.length.toLong() * 2 + 48 else 32))
            if (properties.put(k, v) != null) malformed()
        }
        val xy = IntArray(counts.vertices * 2)
        val rings = IntArray(counts.rings + 1)
        val polygons = IntArray(counts.polygons + 1)
        if (type == 3L) polygon(feature, extent, xy, rings, polygons, countBudget = false)
        else pointsOrLines(feature, extent, type == 1L, xy, rings, countBudget = false)
        if (selected != null) {
            emitScenery!!(CompactSceneryFeature(layer, extent, index, id, properties,
                SceneryGeometry(selected.geometryType, xy, rings, polygons)))
        } else emit(CompactPolygonFeature(layer, extent, index, id, properties, ExtrusionGeometry(xy, rings, polygons)))
    }

    private data class Counts(val vertices: Int, val rings: Int, val polygons: Int)

    private fun polygon(feature: Slice, extent: Int, xy: IntArray?, rings: IntArray?, polygons: IntArray?, countBudget: Boolean): Counts {
        val words = UIntFields(feature, 4, ::tick)
        fun word(): Long? = words.next().also {
            if (it != null && countBudget) checkedLimit("maxMvtCommands", resourceLimits.maxMvtCommands.toLong(), ++commands)
        }
        var x = 0L; var y = 0L
        var firstX = 0L; var firstY = 0L
        var area = 0.0
        var open = false
        var ringVertices = 0
        var vertices = 0; var ringCount = 0; var polygonCount = 0
        val coordinateBound = maxOf(1L shl 28, extent.toLong() * 16)
        while (true) {
            tick()
            val command = word() ?: break
            val kind = command and 7
            val count = command ushr 3
            if (count <= 0) malformed()
            when (kind) {
                1L, 2L -> {
                    if (kind == 1L) {
                        if (open || count != 1L) malformed()
                        open = true; area = 0.0; ringVertices = 0
                        rings?.set(ringCount, vertices)
                    } else if (!open) malformed()
                    // Reject impossible counts before looping or allocating anything.
                    if (countBudget) {
                        checkedLimit("maxMvtCoordinates", resourceLimits.maxMvtCoordinates.toLong(), coordinates + count)
                        coordinates += count
                    }
                    if (count > Int.MAX_VALUE || vertices.toLong() + count > Int.MAX_VALUE / 2) malformed()
                    repeat(count.toInt()) {
                        tick()
                        val dx = zigzag(word() ?: malformed())
                        val dy = zigzag(word() ?: malformed())
                        val nextX = x + dx; val nextY = y + dy
                        if (abs(nextX) > coordinateBound || abs(nextY) > coordinateBound) malformed()
                        if (nextX !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
                            nextY !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) malformed()
                        if (ringVertices == 0) { firstX = nextX; firstY = nextY }
                        else area += x.toDouble() * nextY - nextX.toDouble() * y
                        x = nextX; y = nextY
                        xy?.set(vertices * 2, x.toInt()); xy?.set(vertices * 2 + 1, y.toInt())
                        vertices++; ringVertices++
                    }
                }
                7L -> {
                    if (!open || count != 1L || ringVertices < 3) malformed()
                    area += x.toDouble() * firstY - firstX.toDouble() * y
                    if (area == 0.0 || (polygonCount == 0 && area < 0.0)) malformed()
                    if (area > 0.0) { polygons?.set(polygonCount, ringCount); polygonCount++ }
                    ringCount++; open = false
                }
                else -> malformed()
            }
        }
        if (open || ringCount == 0) malformed()
        rings?.set(ringCount, vertices); polygons?.set(polygonCount, ringCount)
        return Counts(vertices, ringCount, polygonCount)
    }

    private fun pointsOrLines(
        feature: Slice, extent: Int, points: Boolean, xy: IntArray?, parts: IntArray?, countBudget: Boolean,
    ): Counts {
        val words = UIntFields(feature, 4, ::tick)
        fun word(): Long? = words.next().also {
            if (it != null && countBudget) checkedLimit("maxMvtCommands", resourceLimits.maxMvtCommands.toLong(), ++commands)
        }
        var x = 0L; var y = 0L
        var vertices = 0; var partCount = 0; var partVertices = 0
        val bound = maxOf(1L shl 28, extent.toLong() * 16)
        while (true) {
            tick()
            val command = word() ?: break
            val kind = command and 7
            val count = command ushr 3
            if (count <= 0 || kind !in 1L..2L || (points && kind != 1L)) malformed()
            if (!points) {
                if (kind == 1L) {
                    if (count != 1L || (partCount > 0 && partVertices < 2)) malformed()
                    parts?.set(partCount, vertices); partCount++; partVertices = 0
                } else if (partCount == 0) malformed()
            }
            if (countBudget) {
                checkedLimit("maxMvtCoordinates", resourceLimits.maxMvtCoordinates.toLong(), coordinates + count)
                coordinates += count
            }
            if (count > Int.MAX_VALUE || vertices.toLong() + count > Int.MAX_VALUE / 2) malformed()
            repeat(count.toInt()) {
                tick()
                x += zigzag(word() ?: malformed()); y += zigzag(word() ?: malformed())
                if (abs(x) > bound || abs(y) > bound || x !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
                    y !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) malformed()
                xy?.set(vertices * 2, x.toInt()); xy?.set(vertices * 2 + 1, y.toInt())
                vertices++; partVertices++
            }
        }
        if (vertices == 0 || (!points && partVertices < 2)) malformed()
        if (points) { parts?.set(0, 0); partCount = 1 }
        parts?.set(partCount, vertices)
        return Counts(vertices, partCount, 0)
    }

    private fun value(slice: Slice): StyleValue {
        val scan = slice.cursor()
        var result: StyleValue? = null
        while (!scan.done) {
            tick()
            val field = scan.tag()
            val v = when (field) {
                10 -> StyleValue.StringValue(string(scan.message()))
                21 -> StyleValue.NumberValue(Float.fromBits(scan.fixed(4).toInt()).toDouble())
                25 -> StyleValue.NumberValue(Double.fromBits(scan.fixed(8)))
                32 -> StyleValue.NumberValue(scan.uint().toDouble())
                40 -> StyleValue.NumberValue(scan.uint().toULong().toDouble())
                48 -> { val n = scan.uint(); StyleValue.NumberValue(((n ushr 1) xor -(n and 1)).toDouble()) }
                56 -> { val n = scan.uint(); if (n !in 0L..1L) malformed(); StyleValue.BooleanValue(n == 1L) }
                else -> { scan.skip(field); null }
            }
            if (v != null) { if (result != null) malformed(); result = v }
        }
        if (result is StyleValue.NumberValue && !result.value.isFinite()) malformed()
        return result ?: malformed()
    }

    private fun string(slice: Slice): String {
        work(48L + (slice.end - slice.start).toLong() * 4)
        return try { slice.bytes.decodeToString(slice.start, slice.end, throwOnInvalidSequence = true) }
        catch (_: CharacterCodingException) { malformed() }
    }

    private fun work(bytes: Long) {
        checkedLimit("maxWorkingBytes", budget.limits.maxWorkingBytes, working + bytes)
        working += bytes
    }

    private fun tick() { if (++steps and 255 == 0) checkCancellation() }
}

private fun IntRange.toLongRange(): LongRange = first.toLong()..last.toLong()
private fun zigzag(n: Long): Long {
    if (n !in 0L..0xffffffffL) malformed()
    return (n ushr 1) xor -(n and 1)
}
internal fun checkedLimit(name: String, limit: Long, observed: Long) {
    if (observed > limit || observed < 0) throw MvtDecodingException("Extrusion safety limit exceeded", name, limit, observed)
}
private fun malformed(): Nothing = throw MvtDecodingException("Extrusion MVT is malformed")

private class Slice(val bytes: ByteArray, val start: Int, val end: Int) {
    fun cursor(): Cursor = Cursor(bytes, start, end)
}

private class Cursor(private val bytes: ByteArray, private var position: Int = 0, private val end: Int = bytes.size) {
    val done: Boolean get() = position == end
    fun uint(): Long {
        var result = 0L
        for (shift in 0..63 step 7) {
            if (position >= end) malformed()
            val b = bytes[position++].toInt() and 255
            if (shift == 63 && b > 1) malformed()
            result = result or ((b and 127).toLong() shl shift)
            if (b < 128) return result
        }
        malformed()
    }
    fun tag(): Int {
        val value = uint()
        if (value <= 0 || value > 0xffffffffL || value ushr 3 == 0L) malformed()
        return value.toInt()
    }
    fun message(): Slice {
        val count = uint()
        if (count < 0 || count > end - position) malformed()
        val start = position
        position += count.toInt()
        return Slice(bytes, start, position)
    }
    fun fixed(count: Int): Long {
        if (count > end - position) malformed()
        var value = 0L
        repeat(count) { value = value or ((bytes[position++].toLong() and 255) shl (it * 8)) }
        return value
    }
    fun skip(tag: Int) {
        when (tag and 7) {
            0 -> uint()
            1 -> fixed(8)
            2 -> message()
            5 -> fixed(4)
            else -> malformed()
        }
    }
}

/** A stream across any number of packed or unpacked occurrences of a repeated uint32 field. */
private class UIntFields(slice: Slice, private val field: Int, private val tick: () -> Unit) {
    private val scan = slice.cursor()
    private var packed: Cursor? = null
    fun next(): Long? {
        while (true) {
            tick()
            packed?.let { if (!it.done) return uint32(it.uint()); packed = null }
            if (scan.done) return null
            val tag = scan.tag()
            when (tag) {
                field * 8 -> return uint32(scan.uint())
                field * 8 + 2 -> packed = scan.message().cursor()
                else -> scan.skip(tag)
            }
        }
    }
    private fun uint32(n: Long): Long { if (n !in 0L..0xffffffffL) malformed(); return n }
}
