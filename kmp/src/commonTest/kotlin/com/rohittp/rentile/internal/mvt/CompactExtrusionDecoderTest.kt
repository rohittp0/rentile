package com.rohittp.rentile.internal.mvt

import com.rohittp.rentile.ExtrusionFixtures
import com.rohittp.rentile.ExtrusionLimits
import com.rohittp.rentile.ResourceLimits
import com.rohittp.rentile.internal.style.StyleValue
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class CompactExtrusionDecoderTest {
    private fun decode(
        bytes: ByteArray, limits: ExtrusionLimits = ExtrusionLimits(), resources: ResourceLimits = ResourceLimits(),
        checkCancellation: () -> Unit = {},
    ): List<CompactPolygonFeature> = buildList {
        CompactExtrusionDecoder(resources, ExtrusionBudget(limits), checkCancellation).decode(bytes, setOf("building")) { add(it) }
    }

    @Test fun matchesTheExistingDecoderWithoutItsCoordinateGraph() {
        val bytes = ExtrusionFixtures.tile()
        val compact = decode(bytes).single()
        val old = MvtDecoder(ResourceLimits()).decode(bytes).layers.single().features.single()
        assertEquals(old.properties, compact.properties)
        val oldRings = assertIs<DecodedVectorGeometry.Polygons>(old.geometry).rings
        assertEquals(oldRings.size, compact.geometry.ringCount)
        for ((r, ring) in oldRings.withIndex()) {
            assertEquals(ring.points.size, compact.geometry.ringEnd(r) - compact.geometry.ringStart(r))
            for ((i, p) in ring.points.withIndex()) {
                assertEquals(p.x, compact.geometry.x(compact.geometry.ringStart(r) + i))
                assertEquals(p.y, compact.geometry.y(compact.geometry.ringStart(r) + i))
            }
        }
    }

    @Test fun everyLimitIsCheckedBeforeTheCorrespondingArrayAllocation() {
        val bytes = ExtrusionFixtures.tile()
        assertEquals("maxEncodedTileBytes", assertFailsWith<MvtDecodingException> { decode(bytes, ExtrusionLimits(maxEncodedTileBytes = 1)) }.limitName)
        assertEquals("maxWorkingBytes", assertFailsWith<MvtDecodingException> { decode(bytes, ExtrusionLimits(maxWorkingBytes = 1)) }.limitName)
        assertEquals("maxRetainedBytes", assertFailsWith<MvtDecodingException> { decode(bytes, ExtrusionLimits(maxRetainedBytes = 1)) }.limitName)
        assertEquals("maxMvtFeatures", assertFailsWith<MvtDecodingException> { decode(ExtrusionFixtures.tile(featureCount = 2), resources = ResourceLimits(maxMvtFeatures = 1)) }.limitName)
        assertEquals("maxMvtCoordinates", assertFailsWith<MvtDecodingException> { decode(bytes, resources = ResourceLimits(maxMvtCoordinates = 3)) }.limitName)
        assertEquals("maxMvtCommands", assertFailsWith<MvtDecodingException> { decode(bytes, resources = ResourceLimits(maxMvtCommands = 1)) }.limitName)
        assertEquals("maxMvtTags", assertFailsWith<MvtDecodingException> { decode(bytes, resources = ResourceLimits(maxMvtTags = 1)) }.limitName)
        assertEquals("maxMvtExtent", assertFailsWith<MvtDecodingException> { decode(bytes, resources = ResourceLimits(maxMvtExtent = 1)) }.limitName)
    }

    @Test fun malformedTopologyAndTruncatedCommandsAreRejected() {
        for (rings in listOf(listOf(ExtrusionFixtures.hole), listOf(listOf(1 to 1, 2 to 2, 3 to 3)))) {
            assertFailsWith<MvtDecodingException> { decode(ExtrusionFixtures.tile(rings = rings)) }
        }
        for (bytes in listOf(byteArrayOf(26, 127), byteArrayOf(0), byteArrayOf(26, -1, -1, -1, -1, 15))) {
            assertFailsWith<MvtDecodingException> { decode(bytes) }
        }
        val f = Tile.Feature(type = Tile.GeomType.POLYGON, geometry = listOf(9, 0, 0, 26, 10))
        val bytes = Tile.ADAPTER.encode(Tile(layers = listOf(Tile.Layer(version = 2, name = "building", features = listOf(f)))))
        assertFailsWith<MvtDecodingException> { decode(bytes) }
    }

    @Test fun arbitraryFieldOrderSplitPackedFieldsAndUnpackedFieldsAreSupported() {
        fun uint(n: Int): ByteArray {
            var value = n.toLong() and 0xffffffffL
            return buildList<Byte> {
                while (value > 127) { add(((value and 127) or 128).toByte()); value = value ushr 7 }
                add(value.toByte())
            }.toByteArray()
        }
        fun message(field: Int, bytes: ByteArray): ByteArray = uint(field * 8 + 2) + uint(bytes.size) + bytes
        val words = ExtrusionFixtures.geometry(ExtrusionFixtures.outer)
        val feature = uint(24) + uint(3) +
            message(4, words.take(3).fold(ByteArray(0)) { bytes, n -> bytes + uint(n) }) +
            words.drop(3).fold(ByteArray(0)) { bytes, n -> bytes + uint(32) + uint(n) } +
            uint(16) + uint(0) + message(2, uint(0))
        val value = uint(32) + uint(50)
        // Feature precedes the dictionary, name, extent and version, with an unknown field too.
        val layer = message(2, feature) + message(4, value) + message(3, "height".encodeToByteArray()) +
            uint(160) + uint(123) + message(1, "building".encodeToByteArray()) + uint(40) + uint(4096) + uint(120) + uint(2)
        val decoded = decode(message(3, layer)).single()
        assertEquals(4, decoded.geometry.vertexCount)
        assertEquals(StyleValue.NumberValue(50.0), decoded.properties["height"])
    }

    @Test fun unselectedLayersAreSkippedWithoutAllocatingTheirDictionaryOrGeometry() {
        val unselected = Tile.Layer(version = 2, name = "roads", keys = listOf("x".repeat(100_000)),
            values = listOf(Tile.Value(string_value = "y".repeat(100_000))), features = listOf(Tile.Feature()))
        val building = Tile.ADAPTER.decode(ExtrusionFixtures.tile()).layers.single()
        assertEquals(1, decode(Tile.ADAPTER.encode(Tile(layers = listOf(unselected, building))), ExtrusionLimits(maxWorkingBytes = 1024)).size)
    }

    @Test fun dictionaryAndInvalidScalarTagsAreRejected() {
        val building = Tile.ADAPTER.decode(ExtrusionFixtures.tile()).layers.single()
        for (layer in listOf(
            building.copy(keys = listOf("height", "height")),
            building.copy(values = listOf(Tile.Value(string_value = "x", bool_value = true))),
            building.copy(values = listOf(Tile.Value(double_value = Double.NaN))),
            building.copy(features = listOf(building.features.single().copy(tags = listOf(0)))),
            building.copy(features = listOf(building.features.single().copy(tags = listOf(10, 0)))),
            building.copy(version = 1),
        )) assertFailsWith<MvtDecodingException> { decode(Tile.ADAPTER.encode(Tile(layers = listOf(layer)))) }
        assertFailsWith<MvtDecodingException> { decode(Tile.ADAPTER.encode(Tile(layers = listOf(building, building)))) }
    }

    @Test fun decodingChecksCancellationDuringLongLoops() {
        var calls = 0
        assertFailsWith<CancellationException> {
            decode(ExtrusionFixtures.tile(featureCount = 1000), checkCancellation = { if (++calls == 3) throw CancellationException("cancel") })
        }
        assertEquals(3, calls)
    }
}
