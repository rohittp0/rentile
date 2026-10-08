package com.rohittp.rentile

import com.rohittp.rentile.internal.glyph.Glyph
import com.rohittp.rentile.internal.glyph.Glyphs
import com.rohittp.rentile.internal.mvt.Tile
import com.rohittp.rentile.internal.renderSyntheticPng
import okio.ByteString.Companion.toByteString

/**
 * Fixtures shared by the host-owned-symbol, candidate-identity, symbol-size and text-field-override
 * suites. Every resource is synthetic and every URL is under `example.test`.
 */
internal object LabelFixtures {
    const val GLYPHS = "https://glyphs.example.test/{fontstack}/{range}.pbf"
    const val SPRITE = "https://sprite.example.test/icons"
    const val TILES = "https://tiles.example.test/{z}/{x}/{y}.pbf"

    /** One feature of a fixture vector layer. Positions are in a 4096 extent. */
    data class Feature(
        val properties: Map<String, Any> = emptyMap(),
        val points: List<Pair<Int, Int>> = listOf(2048 to 2048),
        val line: Boolean = false,
        val id: Long? = null,
    )

    /** A vector tile with one layer per entry in [layers], features encoded in order. */
    fun vectorTile(layers: Map<String, List<Feature>>): ByteArray = Tile.ADAPTER.encode(
        Tile(
            layers = layers.map { (name, features) ->
                val keys = features.flatMap { it.properties.keys }.distinct()
                val values = features.flatMap { it.properties.values }.distinct()
                Tile.Layer(
                    version = 2,
                    name = name,
                    features = features.map { feature ->
                        Tile.Feature(
                            id = feature.id,
                            tags = feature.properties.flatMap { (key, value) ->
                                listOf(keys.indexOf(key), values.indexOf(value))
                            },
                            type = if (feature.line) Tile.GeomType.LINESTRING else Tile.GeomType.POINT,
                            geometry = geometry(feature),
                        )
                    },
                    keys = keys,
                    values = values.map { value ->
                        when (value) {
                            is String -> Tile.Value(string_value = value)
                            is Double -> Tile.Value(double_value = value)
                            is Int -> Tile.Value(sint_value = value.toLong())
                            is Boolean -> Tile.Value(bool_value = value)
                            else -> error("Unsupported fixture value $value")
                        }
                    },
                    extent = 4096,
                )
            },
        ),
    )

    private fun geometry(feature: Feature): List<Int> {
        if (!feature.line) {
            return listOf(command(1, feature.points.size)) + run {
                var cursorX = 0
                var cursorY = 0
                feature.points.flatMap { (x, y) ->
                    listOf(zigZag(x - cursorX), zigZag(y - cursorY)).also {
                        cursorX = x
                        cursorY = y
                    }
                }
            }
        }
        require(feature.points.size >= 2)
        val first = feature.points.first()
        var cursorX = first.first
        var cursorY = first.second
        return listOf(command(1, 1), zigZag(first.first), zigZag(first.second), command(2, feature.points.size - 1)) +
            feature.points.drop(1).flatMap { (x, y) ->
                listOf(zigZag(x - cursorX), zigZag(y - cursorY)).also {
                    cursorX = x
                    cursorY = y
                }
            }
    }

    /** The 95 printable ASCII glyphs of one range, with a bitmap-less space as real endpoints serve it. */
    fun glyphRange(fontStack: String = "Open Sans Regular", rangeStart: Int = 0): ByteArray {
        val glyphs = (32..126).map { offset ->
            val codepoint = rangeStart + offset
            if (offset == ' '.code) {
                Glyph(id = codepoint, width = 0, height = 0, left = 0, top = 0, advance = 6)
            } else {
                Glyph(
                    id = codepoint,
                    width = 8,
                    height = 10,
                    left = 0,
                    top = -10,
                    advance = 10,
                    bitmap = ByteArray((8 + 6) * (10 + 6)) { 1 }.toByteString(),
                )
            }
        }
        return Glyphs(
            stacks = listOf(
                Glyphs.Fontstack(name = fontStack, range = "$rangeStart-${rangeStart + 255}", glyphs = glyphs),
            ),
        ).encode()
    }

    /** One 8x8 SDF sprite named `marker`. */
    const val SPRITE_JSON: String = """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}"""

    /**
     * Serves sprite, glyph and vector resources by class, recording every URL so a test can prove
     * what was and was not fetched.
     */
    class Transport(
        private val vectorTile: ByteArray,
        private val spriteJson: String? = SPRITE_JSON,
        private val glyphs: ByteArray = glyphRange(),
    ) : ResourceTransport {
        private val spritePng = renderSyntheticPng(8)
        val requested: MutableList<Pair<ResourceClass, String>> = mutableListOf()

        override suspend fun execute(request: TransportRequest): TransportResponse {
            requested += request.resourceClass to request.url
            return when (request.resourceClass) {
                ResourceClass.SPRITE_JSON ->
                    spriteJson?.let { TransportResponse(200, it.encodeToByteArray()) } ?: TransportResponse(404, ByteArray(0))
                ResourceClass.SPRITE_IMAGE ->
                    if (spriteJson == null) TransportResponse(404, ByteArray(0)) else TransportResponse(200, spritePng)
                ResourceClass.GLYPH_RANGE -> TransportResponse(200, glyphs)
                else -> TransportResponse(200, vectorTile)
            }
        }

        fun requestedClasses(): List<ResourceClass> = requested.map { it.first }
    }

    fun rasterizer(
        transport: ResourceTransport,
        diagnosticSink: DiagnosticSink = DiagnosticSink.None,
    ): BasemapRasterizer = Rentile.create(
        RentileConfiguration(
            transport = transport,
            rawResourceStore = InMemoryRawResourceStore(),
            diagnosticSink = diagnosticSink,
        ),
    )

    /**
     * A style JSON with one vector source `v` and the given layers, and the glyphs and sprite keys
     * present unless switched off.
     */
    fun style(
        vararg layers: String,
        glyphs: Boolean = true,
        sprite: Boolean = true,
        extraSources: String = "",
    ): String = buildString {
        append("""{"version":8""")
        if (glyphs) append(""","glyphs":"$GLYPHS"""")
        if (sprite) append(""","sprite":"$SPRITE"""")
        append(""","sources":{"v":{"type":"vector","tiles":["$TILES"],"maxzoom":14}$extraSources}""")
        append(""","layers":[${layers.joinToString(",")}]}""")
    }

    private fun command(id: Int, count: Int): Int = (count shl 3) or id

    private fun zigZag(value: Int): Int = (value shl 1) xor (value shr 31)
}
