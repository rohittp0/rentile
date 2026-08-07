package com.rohittp.rentile.internal.mvt

import com.rohittp.rentile.ResourceLimits
import com.rohittp.rentile.internal.style.FeatureGeometryType
import com.rohittp.rentile.internal.style.StyleValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MvtDecoderTest {
    @Test
    fun decodesEveryGeometryKindAndScalarTag() {
        val values = listOf(
            Tile.Value(string_value = "primary"),
            Tile.Value(float_value = 1.5f),
            Tile.Value(double_value = 2.5),
            Tile.Value(int_value = -3),
            Tile.Value(uint_value = 4),
            Tile.Value(sint_value = -5),
            Tile.Value(bool_value = true),
        )
        val tags = values.indices.flatMap { listOf(it, it) }
        val tile = Tile(
            layers = listOf(
                Tile.Layer(
                    version = 2,
                    name = "transportation",
                    keys = listOf("class", "float", "double", "int", "uint", "sint", "bool"),
                    values = values,
                    extent = 4096,
                    features = listOf(
                        Tile.Feature(
                            id = 7,
                            tags = tags,
                            type = Tile.GeomType.POINT,
                            geometry = listOf(command(1, 2), zigZag(1), zigZag(2), zigZag(3), zigZag(4)),
                        ),
                        Tile.Feature(
                            type = Tile.GeomType.LINESTRING,
                            geometry = listOf(command(1, 1), zigZag(1), zigZag(1), command(2, 2), zigZag(4), zigZag(0), zigZag(0), zigZag(4)),
                        ),
                        Tile.Feature(
                            type = Tile.GeomType.POLYGON,
                            geometry = listOf(
                                command(1, 1), zigZag(1), zigZag(1),
                                command(2, 3), zigZag(10), zigZag(0), zigZag(0), zigZag(10), zigZag(-10), zigZag(0),
                                command(7, 1),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val decoded = decoder().decode(Tile.ADAPTER.encode(tile))
        val layer = decoded.layers.single()
        assertEquals("transportation", layer.name)
        assertEquals(4096, layer.extent)
        assertEquals(7uL, layer.features[0].id)
        assertEquals(FeatureGeometryType.POINT, layer.features[0].geometryType)
        assertEquals(StyleValue.StringValue("primary"), layer.features[0].properties["class"])
        assertEquals(StyleValue.NumberValue(1.5), layer.features[0].properties["float"])
        assertEquals(StyleValue.NumberValue(-5.0), layer.features[0].properties["sint"])
        assertEquals(StyleValue.BooleanValue(true), layer.features[0].properties["bool"])

        val points = assertIs<DecodedVectorGeometry.Points>(layer.features[0].geometry)
        assertEquals(listOf(VectorCoordinate(1, 2), VectorCoordinate(4, 6)), points.points)
        val lines = assertIs<DecodedVectorGeometry.Lines>(layer.features[1].geometry)
        assertEquals(listOf(VectorCoordinate(1, 1), VectorCoordinate(5, 1), VectorCoordinate(5, 5)), lines.lines.single())
        val polygons = assertIs<DecodedVectorGeometry.Polygons>(layer.features[2].geometry)
        assertEquals(
            listOf(VectorCoordinate(1, 1), VectorCoordinate(11, 1), VectorCoordinate(11, 11), VectorCoordinate(1, 11)),
            polygons.rings.single().points,
        )
        assertEquals(100.0, polygons.rings.single().signedAreaTwice / 2.0)
    }

    @Test
    fun rejectsMalformedTagsValuesAndGeometryCommands() {
        assertFailsWith<MvtDecodingException> {
            decodeFeature(Tile.Feature(tags = listOf(0), type = Tile.GeomType.POINT, geometry = listOf(9, 0, 0)))
        }
        assertFailsWith<MvtDecodingException> {
            val invalidValue = Tile.Value(string_value = "x", bool_value = true)
            decodeFeature(
                Tile.Feature(tags = listOf(0, 0), type = Tile.GeomType.POINT, geometry = listOf(9, 0, 0)),
                keys = listOf("key"),
                values = listOf(invalidValue),
            )
        }
        assertFailsWith<MvtDecodingException> {
            decodeFeature(Tile.Feature(type = Tile.GeomType.LINESTRING, geometry = listOf(command(1, 1), 0, 0)))
        }
        assertFailsWith<MvtDecodingException> {
            decodeFeature(
                Tile.Feature(
                    type = Tile.GeomType.POLYGON,
                    geometry = listOf(command(1, 1), 0, 0, command(2, 1), 2, 2, command(7, 1)),
                ),
            )
        }
    }

    @Test
    fun enforcesConfiguredFeatureAndExtentLimits() {
        val limits = ResourceLimits(maxMvtFeatures = 1, maxMvtExtent = 4096)
        val feature = Tile.Feature(type = Tile.GeomType.POINT, geometry = listOf(9, 0, 0))
        val tooMany = tile(features = listOf(feature, feature))
        val featureError = assertFailsWith<MvtDecodingException> {
            MvtDecoder(limits).decode(Tile.ADAPTER.encode(tooMany))
        }
        assertEquals("maxMvtFeatures", featureError.limitName)
        assertEquals(1L, featureError.limit)
        assertEquals(2L, featureError.observed)

        val extentError = assertFailsWith<MvtDecodingException> {
            MvtDecoder(limits).decode(Tile.ADAPTER.encode(tile(features = listOf(feature), extent = 8192)))
        }
        assertEquals("maxMvtExtent", extentError.limitName)
        assertEquals(4096L, extentError.limit)
        assertEquals(8192L, extentError.observed)
    }

    @Test
    fun rejectsTruncatedProtobufWithoutLeakingWireFailure() {
        assertFailsWith<MvtDecodingException> {
            decoder().decode(byteArrayOf(0x1a, 0x7f))
        }
    }

    private fun decodeFeature(
        feature: Tile.Feature,
        keys: List<String> = emptyList(),
        values: List<Tile.Value> = emptyList(),
    ): DecodedVectorTile = decoder().decode(
        Tile.ADAPTER.encode(tile(features = listOf(feature), keys = keys, values = values)),
    )

    private fun tile(
        features: List<Tile.Feature>,
        keys: List<String> = emptyList(),
        values: List<Tile.Value> = emptyList(),
        extent: Int = 4096,
    ): Tile = Tile(
        layers = listOf(
            Tile.Layer(
                version = 2,
                name = "layer",
                features = features,
                keys = keys,
                values = values,
                extent = extent,
            ),
        ),
    )

    private fun decoder(): MvtDecoder = MvtDecoder(ResourceLimits())

    private fun command(id: Int, count: Int): Int = (count shl 3) or id

    private fun zigZag(value: Int): Int = (value shl 1) xor (value shr 31)
}
