package com.rohittp.rentile

import com.rohittp.rentile.internal.glyph.Glyph
import com.rohittp.rentile.internal.renderSyntheticPng
import com.rohittp.rentile.internal.glyph.Glyphs
import com.rohittp.rentile.internal.mvt.Tile
import com.rohittp.rentile.internal.sha256Hex
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a Label Candidate Batch's glyph atlas is packed, and that the default packs exactly what
 * every earlier release packed.
 *
 * The default packs every glyph of every acquired 256-codepoint range, which for a dense CJK
 * viewport is an 8192-pixel-wide atlas of well over a hundred megabytes decoded. A host with a GPU
 * texture ceiling and a tight heap opts into packing only the glyphs its candidates draw, and into
 * a dimension cap of its own, without either changing what any other host receives.
 */
class LabelGlyphAtlasPolicyTest {
    @Test
    fun theDefaultPacksEveryAcquiredGlyphExactlyAsBefore() = runTest {
        // Pinned against 0.11.4, before packing became a policy. The atlas key and its decoded
        // pixels are independent of the label-candidate marker, so this pin survives a marker
        // bump; the PNG bytes themselves are not pinned, because encoders differ by platform
        // (ADR 0010) while the pixels they encode do not.
        val batch = acquire(configuration())

        assertEquals(PINNED_ATLAS_KEY, batch.atlas.contentKey)
        assertEquals(PINNED_ATLAS_SIZE, batch.atlas.width to batch.atlas.height)
        assertEquals(94, batch.atlas.entries.size)
        assertEquals(PINNED_ATLAS_PIXELS, decodeRgba(batch.atlas.pngBytes).sha256Hex())
    }

    @Test
    fun referencedPackingKeepsOnlyTheGlyphsTheCandidatesDraw() = runTest {
        val all = acquire(configuration())
        val referenced = acquire(configuration(LabelGlyphAtlasPolicy(packing = LabelGlyphPacking.REFERENCED_GLYPHS)))

        val drawn = "TokyoOsaka".map { it.code }.toSet()
        assertEquals(drawn, referenced.atlas.entries.map { it.codepoint }.toSet())
        assertEquals(drawn.size, referenced.atlas.entries.size, "one entry per distinct glyph")
        assertTrue(
            referenced.atlas.width.toLong() * referenced.atlas.height < all.atlas.width.toLong() * all.atlas.height,
            "a referenced-only atlas must be smaller than one holding all 94 glyphs",
        )

        // Layout is untouched: the same candidates, quads at the same places and scales, and each
        // quad naming the same glyph - only the index into a shorter entry list differs.
        assertEquals(all.layerStyles, referenced.layerStyles)
        assertEquals(all.diagnostics, referenced.diagnostics)
        assertEquals(all.candidates.size, referenced.candidates.size)
        for ((before, after) in all.candidates.zip(referenced.candidates)) {
            assertEquals(before.copy(glyphs = emptyList()), after.copy(glyphs = emptyList()))
            assertEquals(before.glyphs.map { Triple(it.x, it.y, it.scale) }, after.glyphs.map { Triple(it.x, it.y, it.scale) })
            assertEquals(
                before.glyphs.map { all.atlas.entries[it.entryIndex].copy(x = 0, y = 0) },
                after.glyphs.map { referenced.atlas.entries[it.entryIndex].copy(x = 0, y = 0) },
            )
        }

        // And each cell carries that glyph's own pixels, so a quad samples the right texels.
        val allPixels = decodeRgba(all.atlas.pngBytes)
        val referencedPixels = decodeRgba(referenced.atlas.pngBytes)
        for (entry in referenced.atlas.entries) {
            val source = all.atlas.entries.single { it.fontStackDigest == entry.fontStackDigest && it.codepoint == entry.codepoint }
            assertContentEquals(
                cell(allPixels, all.atlas.width, source),
                cell(referencedPixels, referenced.atlas.width, entry),
                "cell for codepoint ${entry.codepoint}",
            )
        }
    }

    @Test
    fun referencedPackingMovesBothLabelKeysAndTheDefaultMovesNeither() = runTest {
        val implicit = keyed(configuration())
        val explicit = keyed(configuration(LabelGlyphAtlasPolicy()))
        val referenced = keyed(configuration(LabelGlyphAtlasPolicy(packing = LabelGlyphPacking.REFERENCED_GLYPHS)))
        val capped = keyed(configuration(LabelGlyphAtlasPolicy(maxDimensionPx = 256)))

        assertEquals(implicit, explicit)
        // Candidates index a different entry list under referenced packing, so a cache keyed under
        // one packing must not serve the other.
        assertNotEquals(implicit.first, referenced.first, "request key")
        assertNotEquals(implicit.second, referenced.second, "content key")
        assertNotEquals(implicit.third, referenced.third, "atlas key")
        // A cap moves cells, never indices: the candidates are identical, so their keys are too,
        // and only the atlas key - which covers the atlas dimensions - moves with the texture.
        assertEquals(implicit.first, capped.first)
        assertEquals(implicit.second, capped.second)
        assertNotEquals(implicit.third, capped.third)
    }

    @Test
    fun aDimensionCapBoundsTheGlyphAtlasWithoutLoweringTheRasterLimit() = runTest {
        val capped = acquire(configuration(LabelGlyphAtlasPolicy(maxDimensionPx = 256)), STYLE_WITH_SPRITE)
        val rasterLimited = acquire(
            configuration(resourceLimits = ResourceLimits(maxRasterDimensionPx = 256)),
            STYLE_WITH_SPRITE,
        )

        assertTrue(capped.atlas.width <= 256 && capped.atlas.height <= 256, "${capped.atlas.width}x${capped.atlas.height}")
        // The cap lays the glyph atlas out exactly as lowering maxRasterDimensionPx would...
        assertEquals(rasterLimited.atlas, capped.atlas)
        // ...but leaves the 512 px sprite sheet admissible, which that lowered limit does not:
        // under it the sheet fails its dimension check and every paired icon is skipped.
        assertNotNull(capped.candidates.first().icon)
        assertNull(rasterLimited.candidates.first().icon)
        assertTrue(rasterLimited.diagnostics.any { it.code == DiagnosticCode.ICON_FEATURE_SKIPPED })
    }

    @Test
    fun aGlyphSetTooTallForTheCapFailsNamingTheCap() = runTest {
        val failure = assertFailsWith<SafetyLimitException> {
            acquire(configuration(LabelGlyphAtlasPolicy(maxDimensionPx = 16)))
        }

        assertEquals("labelGlyphAtlas.maxDimensionPx", failure.limitName)
        assertEquals(16L, failure.limit)
    }

    private fun configuration(
        policy: LabelGlyphAtlasPolicy? = null,
        resourceLimits: ResourceLimits = ResourceLimits(),
    ): RentileConfiguration {
        val base = RentileConfiguration(
            transport = transport(),
            rawResourceStore = InMemoryRawResourceStore(),
            resourceLimits = resourceLimits,
        )
        return if (policy == null) base else base.copy(labelGlyphAtlas = policy)
    }

    /** The request key, the batch content key and the atlas key, in that order. */
    private suspend fun keyed(configuration: RentileConfiguration): Triple<String, String, String> {
        val rasterizer = Rentile.create(configuration)
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(STYLE))
            val batch = rasterizer.acquireLabelCandidates(style, listOf(TILE))
            return Triple(rasterizer.labelCandidateRequestKey(style, listOf(TILE)), batch.contentKey, batch.atlas.contentKey)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    private suspend fun acquire(configuration: RentileConfiguration, style: String = STYLE): LabelCandidateBatch {
        val rasterizer = Rentile.create(configuration)
        try {
            val prepared = rasterizer.prepare(StyleInput.InlineJson(style))
            return rasterizer.acquireLabelCandidates(prepared, listOf(TILE))
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    /** One entry's cell, row by row, out of a decoded RGBA atlas [width] pixels wide. */
    private fun cell(rgba: ByteArray, width: Int, entry: LabelGlyphEntry): ByteArray {
        val out = ByteArray(entry.width * entry.height * 4)
        for (row in 0 until entry.height) {
            rgba.copyInto(
                out,
                destinationOffset = row * entry.width * 4,
                startIndex = ((entry.y + row) * width + entry.x) * 4,
                endIndex = ((entry.y + row) * width + entry.x + entry.width) * 4,
            )
        }
        return out
    }

    private fun transport(): ResourceTransport = ResourceTransport { request ->
        when (request.resourceClass) {
            ResourceClass.GLYPH_RANGE -> TransportResponse(200, GLYPH_RANGE)
            ResourceClass.VECTOR_TILE -> TransportResponse(200, PLACE_TILE)
            ResourceClass.SPRITE_JSON -> TransportResponse(200, SPRITE_JSON.encodeToByteArray())
            ResourceClass.SPRITE_IMAGE -> TransportResponse(200, SPRITE_PNG)
            else -> TransportResponse(404, ByteArray(0))
        }
    }

    private companion object {
        const val PINNED_ATLAS_KEY = "31f2a69d23df7b1272cc059f046893f2e02f053a98b8dccb5bb6d5931d3bea62"
        val PINNED_ATLAS_SIZE = 1316 to 18
        const val PINNED_ATLAS_PIXELS = "a7a30b6bfd48d203f57856ff6007a39a82bb87d3a2889db2218ce0577c6a38da"

        val TILE = TileId(2, 1, 1)

        const val STYLE =
            """{"version":8,"glyphs":"https://glyphs.example.test/{fontstack}/{range}.pbf",""" +
                """"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"],"maxzoom":14}},""" +
                """"layers":[{"id":"places","type":"symbol","source":"v","source-layer":"place",""" +
                """"layout":{"text-field":["get","name"],"text-font":["Open Sans Regular"],"text-size":14}}]}"""

        /** [STYLE] with a paired icon from a sheet wider than 256 px. */
        const val STYLE_WITH_SPRITE =
            """{"version":8,"glyphs":"https://glyphs.example.test/{fontstack}/{range}.pbf",""" +
                """"sprite":"https://sprite.example.test/icons",""" +
                """"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"],"maxzoom":14}},""" +
                """"layers":[{"id":"places","type":"symbol","source":"v","source-layer":"place",""" +
                """"layout":{"text-field":["get","name"],"text-font":["Open Sans Regular"],"text-size":14,""" +
                """"icon-image":"dot"}}]}"""
        const val SPRITE_JSON = """{"dot":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1}}"""
        val SPRITE_PNG: ByteArray = renderSyntheticPng(512)

        /**
         * The printable ASCII block with a bitmap-less space. Every glyph's bitmap is distinct, so
         * a cell sampled for the wrong glyph changes the pixels rather than hiding in a flat fill.
         */
        val GLYPH_RANGE: ByteArray = Glyphs(
            stacks = listOf(
                Glyphs.Fontstack(
                    name = "Open Sans Regular",
                    range = "0-255",
                    glyphs = (32..126).map { codepoint ->
                        if (codepoint == ' '.code) {
                            Glyph(id = codepoint, width = 0, height = 0, left = 0, top = 0, advance = 6)
                        } else {
                            val width = 6 + codepoint % 5
                            val height = 10 + codepoint % 3
                            Glyph(
                                id = codepoint, width = width, height = height, left = 1, top = -height,
                                advance = width + 2,
                                bitmap = ByteArray((width + 6) * (height + 6)) { (codepoint * 7 + it).toByte() }
                                    .toByteString(),
                            )
                        }
                    },
                ),
            ),
        ).encode()

        /** Two named points in the `place` layer: "Tokyo" and "Osaka", eight distinct glyphs. */
        val PLACE_TILE: ByteArray = Tile.ADAPTER.encode(
            Tile(
                layers = listOf(
                    Tile.Layer(
                        version = 2,
                        name = "place",
                        features = listOf(
                            Tile.Feature(
                                tags = listOf(0, 0),
                                type = Tile.GeomType.POINT,
                                geometry = listOf((1 shl 3) or 1, 2048 shl 1, 2048 shl 1),
                            ),
                            Tile.Feature(
                                tags = listOf(0, 1),
                                type = Tile.GeomType.POINT,
                                geometry = listOf((1 shl 3) or 1, 1024 shl 1, 1024 shl 1),
                            ),
                        ),
                        keys = listOf("name"),
                        values = listOf(Tile.Value(string_value = "Tokyo"), Tile.Value(string_value = "Osaka")),
                        extent = 4096,
                    ),
                ),
            ),
        )

        /** Straight RGBA, rows top-down: what any decoder of the atlas PNG must produce. */
        fun decodeRgba(png: ByteArray): ByteArray {
            val image = Image.makeFromEncoded(png)
            try {
                val bitmap = Bitmap()
                try {
                    check(bitmap.allocPixels(ImageInfo(image.width, image.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)))
                    check(image.readPixels(bitmap))
                    return bitmap.readPixels() ?: error("Skia could not read the decoded atlas")
                } finally {
                    bitmap.close()
                }
            } finally {
                image.close()
            }
        }
    }
}
