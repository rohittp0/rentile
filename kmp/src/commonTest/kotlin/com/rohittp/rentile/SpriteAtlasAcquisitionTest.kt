package com.rohittp.rentile

import com.rohittp.rentile.internal.glyph.Glyph
import com.rohittp.rentile.internal.glyph.Glyphs
import com.rohittp.rentile.internal.mvt.Tile
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The public sprite atlas (ADR 0036): the sheet Rentile already acquires and parses, handed to a
 * host that draws icons itself, at the provider's `@2x` ratio when it asks for one.
 *
 * Three things are proved here that nothing else in the suite covers. The ratio-one atlas is the
 * very sheet the style was prepared with, so the `LabelIconRef` sizes a host reads were derived
 * from the same entries it draws. The ratio-two sheet comes from its own `@2x` URLs through the
 * same acquisition path, honours the access mode it is given, and never falls back to the 1x
 * sheet. And a sheet carrying `stretchX`/`stretchY`/`content`, which used to fail outright, now
 * parses - without changing a single Output Tile pixel, because nothing on the raster path resizes
 * an icon non-uniformly.
 */
class SpriteAtlasAcquisitionTest {
    @Test
    fun aStyleWithoutASpriteHasNoSpriteAtlas() = runTest {
        val transport = RecordingTransport { error("Unexpected request ${it.resourceClass}") }
        withRasterizer(transport) { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(BACKGROUND_STYLE_WITHOUT_SPRITE))

            assertNull(rasterizer.acquireSpriteAtlas(style))
            assertNull(rasterizer.acquireSpriteAtlas(style, pixelRatio = 2))
            assertTrue(transport.requests().isEmpty(), "a style with no sprite must fetch nothing")
        }
    }

    @Test
    fun ratioOneIsTheAtlasThePreparationResolvedAndCostsNoRequest() = runTest {
        val transport = sheetTransport()
        withRasterizer(transport) { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(labelStyle()))
            assertEquals(2, transport.requests().size, "preparation fetches the 1x JSON and PNG once each")
            transport.clear()

            val atlas = assertNotNull(rasterizer.acquireSpriteAtlas(style))

            assertTrue(transport.requests().isEmpty(), "the prepared atlas is handed over, not fetched again")
            assertEquals(1, atlas.pixelRatio)
            assertEquals(16, atlas.width)
            assertEquals(8, atlas.height)
            assertTrue(atlas.pngBytes.contentEquals(SHEET_1X_PNG), "the provider's PNG bytes travel verbatim")
            assertEquals(
                SpriteImageEntry(
                    name = "marker", x = 0, y = 0, width = 8, height = 8, pixelRatio = 1.0, sdf = true,
                    stretchX = null, stretchY = null, content = null,
                ),
                atlas.entries["marker"],
            )
            assertEquals(setOf("marker", "badge"), atlas.entries.keys)
            assertEquals(false, atlas.entries.getValue("badge").sdf)
        }
    }

    @Test
    fun ratioTwoFetchesTheAt2xSheetBesideTheBaseUrlAndKeepsItsQuery() = runTest {
        val transport = sheetTransport()
        withRasterizer(transport) { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(labelStyle()))
            transport.clear()

            val atlas = assertNotNull(rasterizer.acquireSpriteAtlas(style, pixelRatio = 2))

            assertEquals(
                listOf("$SPRITE_ORIGIN/icons@2x.json?key=$SPRITE_KEY"),
                transport.requests(ResourceClass.SPRITE_JSON).map { it.url },
            )
            assertEquals(
                listOf("$SPRITE_ORIGIN/icons@2x.png?key=$SPRITE_KEY"),
                transport.requests(ResourceClass.SPRITE_IMAGE).map { it.url },
            )
            assertEquals(2, atlas.pixelRatio)
            assertEquals(32, atlas.width)
            assertEquals(16, atlas.height)
            assertTrue(atlas.pngBytes.contentEquals(SHEET_2X_PNG))
            val marker = atlas.entries.getValue("marker")
            assertEquals(16, marker.width)
            assertEquals(2.0, marker.pixelRatio)

            // A second ask is served from the raw store the first one filled.
            transport.clear()
            assertEquals(atlas, rasterizer.acquireSpriteAtlas(style, pixelRatio = 2))
            assertTrue(transport.requests().isEmpty())
        }
    }

    @Test
    fun aMissingAt2xSheetFailsInsteadOfFallingBackToOneX() = runTest {
        val transport = sheetTransport(missing2x = true)
        withRasterizer(transport) { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(labelStyle()))

            val failure = assertFailsWith<ResourceAcquisitionException> {
                rasterizer.acquireSpriteAtlas(style, pixelRatio = 2)
            }

            assertEquals(404, failure.statusCode)
            // Ratio one still works, and is still the prepared sheet: the failure is the 2x
            // sheet's alone, and nothing quietly substituted the other one for it.
            assertEquals(1, rasterizer.acquireSpriteAtlas(style)?.pixelRatio)
        }
    }

    @Test
    fun ratioOneAcquiresASpriteThePreparationNeverNeeded() = runTest {
        val transport = sheetTransport()
        withRasterizer(transport) { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(BACKGROUND_STYLE_WITH_SPRITE))
            assertTrue(transport.requests().isEmpty(), "no layer draws an icon, so preparation fetched no sprite")

            val atlas = assertNotNull(rasterizer.acquireSpriteAtlas(style))

            assertEquals(1, atlas.pixelRatio)
            assertEquals(
                listOf("$SPRITE_ORIGIN/icons.json?key=$SPRITE_KEY", "$SPRITE_ORIGIN/icons.png?key=$SPRITE_KEY"),
                transport.requests().map { it.url }.sorted(),
            )
        }
    }

    @Test
    fun onlyRatiosOneAndTwoAreAccepted() = runTest {
        withRasterizer(sheetTransport()) { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(BACKGROUND_STYLE_WITH_SPRITE))

            assertFailsWith<IllegalArgumentException> { rasterizer.acquireSpriteAtlas(style, pixelRatio = 0) }
            assertFailsWith<IllegalArgumentException> { rasterizer.acquireSpriteAtlas(style, pixelRatio = 3) }
        }
    }

    @Test
    fun cacheOnlyNeverReachesTheTransportAndServesWhatIsStored() = runTest {
        val store = InMemoryRawResourceStore()
        val transport = sheetTransport()
        withRasterizer(transport, store) { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(BACKGROUND_STYLE_WITH_SPRITE))

            assertFailsWith<ResourceAcquisitionException> {
                rasterizer.acquireSpriteAtlas(style, pixelRatio = 2, resourceAccess = ResourceAccessMode.CACHE_ONLY)
            }
            assertTrue(transport.requests().isEmpty(), "cache-only must not touch the transport on a miss")

            rasterizer.acquireSpriteAtlas(style, pixelRatio = 2)
        }

        transport.clear()
        withRasterizer(transport, store) { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(BACKGROUND_STYLE_WITH_SPRITE))

            val atlas = rasterizer.acquireSpriteAtlas(style, pixelRatio = 2, resourceAccess = ResourceAccessMode.CACHE_ONLY)

            assertEquals(2, atlas?.pixelRatio)
            assertTrue(transport.requests().isEmpty(), "a stored sheet is served without a request or a refresh")
        }
    }

    @Test
    fun reloadFetchesAgainAndReplacesTheStoredSheet() = runTest {
        var revision = 1
        val transport = RecordingTransport { request ->
            val png = if (revision == 1) SHEET_2X_PNG else SHEET_2X_PNG_REVISED
            serveSheet(request, missing2x = false, png2x = png)
        }
        withRasterizer(transport) { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(BACKGROUND_STYLE_WITH_SPRITE))
            val first = assertNotNull(rasterizer.acquireSpriteAtlas(style, pixelRatio = 2))
            revision = 2
            transport.clear()

            val reloaded = assertNotNull(
                rasterizer.acquireSpriteAtlas(style, pixelRatio = 2, resourceAccess = ResourceAccessMode.RELOAD),
            )

            assertEquals(2, transport.requests().size, "reload fetches the JSON and the PNG again")
            assertTrue(reloaded.pngBytes.contentEquals(SHEET_2X_PNG_REVISED))
            assertTrue(first.contentKey != reloaded.contentKey, "different bytes are a different texture")

            transport.clear()
            val cached = rasterizer.acquireSpriteAtlas(style, pixelRatio = 2, resourceAccess = ResourceAccessMode.CACHE_ONLY)
            assertTrue(cached!!.pngBytes.contentEquals(SHEET_2X_PNG_REVISED), "reload replaced the stored entry")
        }
    }

    @Test
    fun aForeignStyleIsRejected() = runTest {
        withRasterizer(sheetTransport()) { owner ->
            val style = owner.prepare(StyleInput.InlineJson(BACKGROUND_STYLE_WITH_SPRITE))
            withRasterizer(sheetTransport()) { other ->
                assertFailsWith<ForeignPreparedStyleException> { other.acquireSpriteAtlas(style) }
            }
        }
    }

    @Test
    fun aSpriteThatCannotBeAcquiredIsReportedRatherThanCalledAbsent() = runTest {
        // Both shapes prepare, because no layer here needs the sprite, and neither may come back
        // as null: null means "this style declares no sprite", and these styles declare one.
        withRasterizer(sheetTransport()) { rasterizer ->
            val arrayForm = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":[{"id":"default","url":"$SPRITE_ORIGIN/icons"}],""" +
                        """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#123456"}}]}""",
                ),
            )
            assertFailsWith<StylePreparationException> { rasterizer.acquireSpriteAtlas(arrayForm) }

            val relative = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"icons",""" +
                        """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#123456"}}]}""",
                ),
            )
            assertFailsWith<StylePreparationException> { rasterizer.acquireSpriteAtlas(relative, pixelRatio = 2) }
        }
    }

    @Test
    fun stretchAndContentMetadataIsParsedIntoTheAtlas() = runTest {
        withRasterizer(sheetTransport(json1x = STRETCHABLE_SHEET_1X_JSON)) { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(BACKGROUND_STYLE_WITH_SPRITE))

            val shield = assertNotNull(rasterizer.acquireSpriteAtlas(style)).entries.getValue("shield")

            assertEquals(listOf(SpriteStretchRange(2.0, 4.0), SpriteStretchRange(5.0, 6.5)), shield.stretchX)
            assertEquals(listOf(SpriteStretchRange(1.0, 7.0)), shield.stretchY)
            assertEquals(SpriteContentBox(left = 2.0, top = 1.0, right = 6.0, bottom = 7.0), shield.content)
        }
    }

    @Test
    fun aSheetWithStretchMetadataDrawsTheSamePixelsAsTheSameSheetWithout() = runTest {
        // Before ADR 0036 any entry carrying stretchX, stretchY or content failed the whole sheet,
        // so this style - whose icon layer requires its sprite - did not even prepare. It must now
        // render, and render byte-identically to the same sheet without the metadata: the Output
        // Tile path draws every icon and pattern at its own aspect, where the style specification
        // gives stretch zones and a content box no effect.
        val plain = renderIconTile(sheetTransport(json1x = PLAIN_SHEET_1X_JSON))
        val stretchable = renderIconTile(sheetTransport(json1x = STRETCHABLE_SHEET_1X_JSON))
        val blank = withRasterizer(sheetTransport()) { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(BACKGROUND_ONLY_STYLE))
            rasterizer.render(style, listOf(ICON_TILE), RenderOptions(256)).tiles.single().pngBytes
        }

        assertTrue(stretchable.contentEquals(plain), "stretch metadata changed an Output Tile pixel")
        assertTrue(!plain.contentEquals(blank), "the icon drew nothing, so the comparison proves nothing")
    }

    @Test
    fun malformedStretchOrContentMetadataStillFailsTheSheet() = runTest {
        // These failed before ADR 0036 because any stretch or content key did; they still fail,
        // now because the value is wrong rather than because the key exists. Accepting a bad
        // value would hand a host a stretch zone outside its own image.
        val malformed = listOf(
            """"stretchX":[[4,2]]""",
            """"stretchX":[[0,9]]""",
            """"stretchX":[[3,5],[2,6]]""",
            """"stretchX":"wide"""",
            """"stretchY":[[1]]""",
            """"stretchY":[[-1,3]]""",
            """"content":[0,0,9,8]""",
            """"content":[0,0,4]""",
            """"content":[5,0,4,8]""",
            """"content":[0,"top",4,8]""",
        )
        for (field in malformed) {
            val json = """{"shield":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,$field}}"""
            withRasterizer(sheetTransport(json1x = json)) { rasterizer ->
                val style = rasterizer.prepare(StyleInput.InlineJson(BACKGROUND_STYLE_WITH_SPRITE))
                assertFailsWith<ResourceDecodeException>("$field must fail the sheet") {
                    rasterizer.acquireSpriteAtlas(style)
                }
                assertFailsWith<ResourceDecodeException>("$field must still fail a required sprite") {
                    rasterizer.prepare(StyleInput.InlineJson(ICON_STYLE))
                }
            }
        }
    }

    @Test
    fun aStretchableLabelIconIsEmittedAndItsGeometryStaysAtRatioOne() = runTest {
        // A label layer only *desires* its sprite, so a sheet with stretch metadata used to leave
        // it unresolved and every paired icon skipped. Now the candidate carries the icon, sized
        // from the 1x sheet in style pixels, and the host draws it from whichever sheet it likes.
        val transport = sheetTransport(json1x = STRETCHABLE_SHEET_1X_JSON, json2x = STRETCHABLE_SHEET_2X_JSON)
        withRasterizer(transport) { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(labelStyle(iconImage = "shield", iconSize = 2.0)))
            val requestKey = rasterizer.labelCandidateRequestKey(style, listOf(LABEL_TILE))

            val batch = rasterizer.acquireLabelCandidates(style, listOf(LABEL_TILE))
            val icon = assertNotNull(batch.candidates.single().icon)
            val sheet = assertNotNull(rasterizer.acquireSpriteAtlas(style, pixelRatio = 2))
            val entry = sheet.entries.getValue("shield")

            assertTrue(batch.diagnostics.none { it.code == DiagnosticCode.ICON_FEATURE_SKIPPED })
            assertEquals(16.0, icon.width, "8 px at ratio 1, icon-size 2")
            assertEquals(entry.width / entry.pixelRatio * 2.0, icon.width, "the 2x entry covers the same style pixels")
            assertEquals(listOf(SpriteStretchRange(4.0, 8.0), SpriteStretchRange(10.0, 13.0)), entry.stretchX)
            assertEquals(requestKey, rasterizer.labelCandidateRequestKey(style, listOf(LABEL_TILE)))
        }
    }

    private suspend fun renderIconTile(transport: RecordingTransport): ByteArray =
        withRasterizer(transport) { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(ICON_STYLE))
            rasterizer.render(style, listOf(ICON_TILE), RenderOptions(256)).tiles.single().pngBytes
        }

    private suspend fun <T> withRasterizer(
        transport: ResourceTransport,
        store: RawResourceStore = InMemoryRawResourceStore(),
        block: suspend (BasemapRasterizer) -> T,
    ): T {
        val rasterizer = Rentile.create(RentileConfiguration(transport = transport, rawResourceStore = store))
        try {
            return block(rasterizer)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    private fun sheetTransport(
        missing2x: Boolean = false,
        json1x: String = SHEET_1X_JSON,
        json2x: String = SHEET_2X_JSON,
    ): RecordingTransport = RecordingTransport { request ->
        serveSheet(request, missing2x, json1x = json1x, json2x = json2x)
    }

    private fun serveSheet(
        request: TransportRequest,
        missing2x: Boolean,
        json1x: String = SHEET_1X_JSON,
        json2x: String = SHEET_2X_JSON,
        png2x: ByteArray = SHEET_2X_PNG,
    ): TransportResponse = when (request.url) {
        "$SPRITE_ORIGIN/icons.json?key=$SPRITE_KEY" -> TransportResponse(200, json1x.encodeToByteArray())
        "$SPRITE_ORIGIN/icons.png?key=$SPRITE_KEY" -> TransportResponse(200, SHEET_1X_PNG)
        "$SPRITE_ORIGIN/icons@2x.json?key=$SPRITE_KEY" ->
            if (missing2x) TransportResponse(404, ByteArray(0)) else TransportResponse(200, json2x.encodeToByteArray())
        "$SPRITE_ORIGIN/icons@2x.png?key=$SPRITE_KEY" ->
            if (missing2x) TransportResponse(404, ByteArray(0)) else TransportResponse(200, png2x)
        else -> when (request.resourceClass) {
            ResourceClass.GLYPH_RANGE -> TransportResponse(200, GLYPH_RANGE)
            ResourceClass.VECTOR_TILE -> TransportResponse(200, POINT_TILE)
            else -> TransportResponse(404, ByteArray(0))
        }
    }

    private companion object {
        const val SPRITE_ORIGIN = "https://sprite.example.test"
        const val SPRITE_KEY = "sprite-secret"
        val LABEL_TILE = TileId(2, 1, 1)
        val ICON_TILE = TileId(2, 1, 1)

        const val SHEET_1X_JSON =
            """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true},""" +
                """"badge":{"x":8,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":false}}"""
        const val SHEET_2X_JSON =
            """{"marker":{"x":0,"y":0,"width":16,"height":16,"pixelRatio":2,"sdf":true},""" +
                """"badge":{"x":16,"y":0,"width":16,"height":16,"pixelRatio":2,"sdf":false}}"""
        const val PLAIN_SHEET_1X_JSON =
            """{"shield":{"x":8,"y":0,"width":8,"height":8,"pixelRatio":1}}"""
        const val STRETCHABLE_SHEET_1X_JSON =
            """{"shield":{"x":8,"y":0,"width":8,"height":8,"pixelRatio":1,""" +
                """"stretchX":[[2,4],[5,6.5]],"stretchY":[[1,7]],"content":[2,1,6,7]}}"""
        const val STRETCHABLE_SHEET_2X_JSON =
            """{"shield":{"x":16,"y":0,"width":16,"height":16,"pixelRatio":2,""" +
                """"stretchX":[[4,8],[10,13]],"stretchY":[[2,14]],"content":[4,2,12,14]}}"""

        val SHEET_1X_PNG: ByteArray = sheetPng(16, 8, Color.makeARGB(255, 220, 40, 40), Color.makeARGB(255, 40, 80, 230))
        val SHEET_2X_PNG: ByteArray = sheetPng(32, 16, Color.makeARGB(255, 220, 40, 40), Color.makeARGB(255, 40, 80, 230))
        val SHEET_2X_PNG_REVISED: ByteArray =
            sheetPng(32, 16, Color.makeARGB(255, 30, 160, 60), Color.makeARGB(255, 240, 210, 30))

        const val BACKGROUND_STYLE_WITHOUT_SPRITE =
            """{"version":8,"layers":[{"id":"bg","type":"background","paint":{"background-color":"#123456"}}]}"""
        const val BACKGROUND_ONLY_STYLE =
            """{"version":8,"layers":[{"id":"bg","type":"background","paint":{"background-color":"#101010"}}]}"""
        const val BACKGROUND_STYLE_WITH_SPRITE =
            """{"version":8,"sprite":"$SPRITE_ORIGIN/icons?key=$SPRITE_KEY",""" +
                """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#123456"}}]}"""

        /** An author-declared icon layer: it cannot draw without its sprite, so it requires one. */
        const val ICON_STYLE =
            """{"version":8,"sprite":"$SPRITE_ORIGIN/icons?key=$SPRITE_KEY",""" +
                """"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"],"maxzoom":14}},""" +
                """"layers":[""" +
                """{"id":"bg","type":"background","paint":{"background-color":"#101010"}},""" +
                """{"id":"poi","type":"symbol","source":"v","source-layer":"place",""" +
                """"layout":{"icon-image":"shield","icon-size":3}}]}"""

        /** A text-bearing symbol layer with a paired icon: it only desires its sprite. */
        fun labelStyle(iconImage: String = "marker", iconSize: Double = 1.0): String =
            """{"version":8,"sprite":"$SPRITE_ORIGIN/icons?key=$SPRITE_KEY",""" +
                """"glyphs":"https://glyphs.example.test/{fontstack}/{range}.pbf",""" +
                """"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"],"maxzoom":14}},""" +
                """"layers":[{"id":"places","type":"symbol","source":"v","source-layer":"place",""" +
                """"layout":{"text-field":["get","name"],"text-font":["Open Sans Regular"],"text-size":14,""" +
                """"icon-image":"$iconImage","icon-size":$iconSize,"icon-text-fit":"both"}}]}"""

        /** Two differently coloured squares, so a sheet read at the wrong offset changes the tile. */
        fun sheetPng(width: Int, height: Int, left: Int, right: Int): ByteArray {
            val surface = Surface.makeRasterN32Premul(width, height)
            val paint = Paint()
            try {
                paint.color = left
                surface.canvas.drawRect(Rect.makeXYWH(0f, 0f, width / 2f, height.toFloat()), paint)
                paint.color = right
                surface.canvas.drawRect(Rect.makeXYWH(width / 2f, 0f, width / 2f, height.toFloat()), paint)
                val image = surface.makeImageSnapshot()
                try {
                    val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("Skia could not encode the sheet")
                    try {
                        return data.bytes
                    } finally {
                        data.close()
                    }
                } finally {
                    image.close()
                }
            } finally {
                paint.close()
                surface.close()
            }
        }

        /** The printable ASCII block, each glyph an 8x10 body, with a bitmap-less space. */
        val GLYPH_RANGE: ByteArray = Glyphs(
            stacks = listOf(
                Glyphs.Fontstack(
                    name = "Open Sans Regular",
                    range = "0-255",
                    glyphs = (32..126).map { codepoint ->
                        if (codepoint == ' '.code) {
                            Glyph(id = codepoint, width = 0, height = 0, left = 0, top = 0, advance = 6)
                        } else {
                            Glyph(
                                id = codepoint, width = 8, height = 10, left = 0, top = -10, advance = 10,
                                bitmap = ByteArray(14 * 16) { 1 }.toByteString(),
                            )
                        }
                    },
                ),
            ),
        ).encode()

        /** One named point feature in the `place` layer, at the tile's centre. */
        val POINT_TILE: ByteArray = Tile.ADAPTER.encode(
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
                        ),
                        keys = listOf("name"),
                        values = listOf(Tile.Value(string_value = "Tokyo")),
                        extent = 4096,
                    ),
                ),
            ),
        )
    }
}
