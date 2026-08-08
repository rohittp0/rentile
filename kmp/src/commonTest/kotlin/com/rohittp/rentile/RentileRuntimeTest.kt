package com.rohittp.rentile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import com.rohittp.rentile.internal.renderSyntheticPng
import com.rohittp.rentile.internal.mvt.Tile
import com.rohittp.rentile.internal.style.CompiledPreparedStyle
import com.rohittp.rentile.internal.style.RasterDrawLayer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RentileRuntimeTest {
    @Test
    fun rentileV1AcceptsZ22AndRejectsZ23() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"layers":[{"id":"base","type":"background"}]}""",
                ),
            )
            val accepted = rasterizer.prepareBatch(style, listOf(TileId(22, 0, 0)))
            accepted.close()

            val error = assertFailsWith<InvalidTileIdException> {
                rasterizer.prepareBatch(style, listOf(TileId(23, 0, 0)))
            }
            assertEquals(RentileErrorCode.INVALID_TILE_ID, error.code)
            assertEquals(0, CompatibilityPolicy.RentileV1.minimumOutputZoom)
            assertEquals(22, CompatibilityPolicy.RentileV1.maximumOutputZoom)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun backgroundStyleRendersDeterministicPngThroughPublicInterface() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"layers":[{"id":"base","type":"background","paint":{"background-color":"hsl(120, 100%, 25%)"}}]}""",
                ),
            )
            val tile = TileId(2, 1, 3)
            val first = rasterizer.render(style, listOf(tile), RenderOptions(256)).tiles.single()
            val second = rasterizer.render(style, listOf(tile), RenderOptions(256)).tiles.single()

            assertTrue(first.pngBytes.startsWithPngSignature())
            assertEquals(256, first.pngBytes.pngWidth())
            assertEquals(256, first.pngBytes.pngHeight())
            assertEquals(first.contentKey, second.contentKey)
            assertTrue(first.pngBytes.contentEquals(second.pngBytes))
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun backgroundExpressionsEvaluateAtRequestedOutputZoom() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"layers":[{"id":"base","type":"background","paint":{"background-color":["interpolate",["linear"],["zoom"],0,"#000000",10,"#ffffff"],"background-opacity":["step",["zoom"],0.5,10,1]}}]}""",
                ),
            )
            val lowZoom = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()
            val highZoom = rasterizer.render(style, listOf(TileId(10, 0, 0)), RenderOptions(256)).tiles.single()

            assertTrue(lowZoom.pngBytes.startsWithPngSignature())
            assertTrue(highZoom.pngBytes.startsWithPngSignature())
            assertFalse(lowZoom.pngBytes.contentEquals(highZoom.pngBytes))
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun backgroundPatternLoadsAndCachesTheSpriteAtlas() = runTest {
        val spritePng = renderSyntheticPng(8)
        val requested = mutableListOf<Pair<ResourceClass, String>>()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    requested += request.resourceClass to request.url
                    when (request.resourceClass) {
                        ResourceClass.SPRITE_JSON -> TransportResponse(
                            200,
                            """{"pattern":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":2,"sdf":false}}""".encodeToByteArray(),
                        )
                        ResourceClass.SPRITE_IMAGE -> TransportResponse(200, spritePng)
                        else -> error("Unexpected resource class ${request.resourceClass}")
                    }
                },
                rawResourceStore = InMemoryRawResourceStore(),
            ),
        )
        try {
            val input = StyleInput.InlineJson(
                """{"version":8,"sprite":"https://sprite.example.test/atlas?key=private","layers":[{"id":"base","type":"background","paint":{"background-pattern":"pattern","background-opacity":0.75}}]}""",
            )
            val firstStyle = rasterizer.prepare(input)
            val secondStyle = rasterizer.prepare(input)
            val output = rasterizer.render(firstStyle, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()

            assertEquals(firstStyle.digest, secondStyle.digest)
            assertTrue(output.pngBytes.startsWithPngSignature())
            assertEquals(
                setOf(
                    ResourceClass.SPRITE_JSON to "https://sprite.example.test/atlas.json?key=private",
                    ResourceClass.SPRITE_IMAGE to "https://sprite.example.test/atlas.png?key=private",
                ),
                requested.toSet(),
            )
            assertEquals(2, requested.size)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun unsupportedReachableBackgroundExpressionFailsDuringPreparation() = runTest {
        val rasterizer = testRasterizer()
        try {
            val error = assertFailsWith<StylePreparationException> {
                rasterizer.prepare(
                    StyleInput.InlineJson(
                        """{"version":8,"layers":[{"id":"base","type":"background","paint":{"background-color":["concat","#fff"]}}]}""",
                    ),
                )
            }

            assertEquals(RentileErrorCode.STYLE_PREPARATION_FAILED, error.code)
            assertTrue(error.diagnostics.any { it.code == DiagnosticCode.UNSUPPORTED_RETAINED_CONSTRUCT })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun outputIdentityChangesWithTileAndSize() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson("""{"version":8,"layers":[]}"""))
            val tile = TileId(1, 0, 0)
            val size256 = rasterizer.prepareBatch(style, listOf(tile), RenderOptions(256))
            val size512 = rasterizer.prepareBatch(style, listOf(tile), RenderOptions(512))
            val otherTile = rasterizer.prepareBatch(style, listOf(TileId(1, 1, 0)), RenderOptions(256))
            try {
                assertNotEquals(size256.contentKeys.getValue(tile), size512.contentKeys.getValue(tile))
                assertNotEquals(size256.contentKeys.getValue(tile), otherTile.contentKeys.values.single())
            } finally {
                size256.close()
                size512.close()
                otherTile.close()
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun closedPreparedBatchFailsWithTypedLifecycleError() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson("""{"version":8,"layers":[]}"""))
            val batch = rasterizer.prepareBatch(style, listOf(TileId(0, 0, 0)))
            batch.close()

            val error = assertFailsWith<PreparedBatchClosedException> { rasterizer.render(batch) }
            assertEquals(RentileErrorCode.PREPARED_BATCH_CLOSED, error.code)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun unsupportedReachableLayerFailsAtPreparationWithFullDiagnostic() = runTest {
        val rasterizer = testRasterizer()
        try {
            val error = assertFailsWith<StylePreparationException> {
                rasterizer.prepare(
                    StyleInput.InlineJson(
                        """{"version":8,"layers":[{"id":"roads","type":"line","source":"v","source-layer":"road"}]}""",
                    ),
                )
            }

            assertEquals(RentileErrorCode.STYLE_PREPARATION_FAILED, error.code)
            assertEquals(DiagnosticCode.UNSUPPORTED_RETAINED_CONSTRUCT, error.diagnostics.single().code)
            assertEquals("0", error.diagnostics.single().details["layerIndex"])
            assertTrue(error.diagnostics.single().details.getValue("layerIdDigest").length == 64)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun preparationCollectsAllSafeLayerDiagnosticsBeforeFailing() = runTest {
        val rasterizer = testRasterizer()
        try {
            val error = assertFailsWith<StylePreparationException> {
                rasterizer.prepare(
                    StyleInput.InlineJson(
                        """{"version":8,"layers":[{"id":"labels","type":"symbol","layout":{"text-field":"name"}},{"id":"land","type":"fill"},{"id":"roads","type":"line"}]}""",
                    ),
                )
            }

            assertEquals(3, error.diagnostics.size)
            assertEquals(2, error.diagnostics.count { it.severity == DiagnosticSeverity.ERROR })
            assertTrue(error.diagnostics.any { it.code == DiagnosticCode.TEXT_ONLY_LAYER_EXCLUDED })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun textOnlyLayerIsExcludedBeforeTextPropertyValidation() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"layers":[{"id":"labels","type":"symbol","layout":{"text-field":["future-unsupported-op",1]}}]}""",
                ),
            )

            assertEquals(DiagnosticCode.TEXT_ONLY_LAYER_EXCLUDED, style.diagnostics.single().code)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun hiddenLayerDoesNotMakeItsSourceSyntaxReachable() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sources":{"hidden":{"type":"raster","url":"provider://unsupported"}},"layers":[{"id":"hidden","type":"raster","source":"hidden","layout":{"visibility":"none"}}]}""",
                ),
            )

            assertEquals(DiagnosticCode.HIDDEN_LAYER_NO_DRAW, style.diagnostics.single().code)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun callerCancellationCancelsInFlightTransport() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val rasterizer = testRasterizer(
            transport = ResourceTransport {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
        )
        try {
            val request = launch { rasterizer.prepare(StyleInput.Remote("https://example.test/style?key=secret")) }
            started.await()
            request.cancelAndJoin()
            cancelled.await()
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun closeCancelsInFlightWorkAndAwaitClosedJoinsIt() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val rasterizer = testRasterizer(
            transport = ResourceTransport {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
        )
        val request = launch { rasterizer.prepare(StyleInput.Remote("https://example.test/style")) }
        started.await()

        rasterizer.close()
        rasterizer.awaitClosed()
        request.join()
        assertTrue(cancelled.isCompleted)
        assertFailsWith<RasterizerClosedException> {
            rasterizer.prepare(StyleInput.InlineJson("""{"version":8,"layers":[]}"""))
        }
    }

    @Test
    fun rasterOnlyPngUsesPassThroughAndWarmRawCache() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val store = InMemoryRawResourceStore()
        var requests = 0
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    requests += 1
                    assertTrue(request.url.contains("/0/0/0.png"))
                    TransportResponse(
                        statusCode = 200,
                        body = sourcePng,
                        metadata = TransportResponseMetadata(contentType = "image/png"),
                    )
                },
                rawResourceStore = store,
            ),
        )
        try {
            val style = rasterizer.prepare(rasterStyle("first-secret"))
            val tile = TileId(0, 0, 0)
            val firstBatch = rasterizer.prepareBatch(style, listOf(tile), RenderOptions(256))
            val first = try {
                rasterizer.render(firstBatch).tiles.single()
            } finally {
                firstBatch.close()
            }
            val warmBatch = rasterizer.prepareBatch(style, listOf(tile), RenderOptions(256))
            val warm = try {
                rasterizer.render(warmBatch).tiles.single()
            } finally {
                warmBatch.close()
            }

            assertEquals(1, requests)
            assertTrue(first.pngBytes.contentEquals(sourcePng))
            assertTrue(warm.pngBytes.contentEquals(sourcePng))
            assertEquals(first.contentKey, warm.contentKey)
            assertTrue(first.diagnostics.any { it.code == DiagnosticCode.RASTER_PASSTHROUGH_USED })
            assertEquals(1, store.size())

            val reloadBatch = rasterizer.prepareBatch(
                style,
                listOf(tile),
                RenderOptions(256),
                ResourceAccessMode.RELOAD,
            )
            reloadBatch.close()
            assertEquals(2, requests)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun rasterApiKeyDoesNotChangeStyleOrOutputIdentity() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val rasterizer = testRasterizer(
            transport = ResourceTransport {
                TransportResponse(200, sourcePng, TransportResponseMetadata(contentType = "image/png"))
            },
        )
        try {
            val first = rasterizer.prepare(rasterStyle("first-secret"))
            val second = rasterizer.prepare(rasterStyle("second-secret"))
            assertEquals(first.digest, second.digest)

            val tile = TileId(0, 0, 0)
            val firstBatch = rasterizer.prepareBatch(first, listOf(tile), RenderOptions(256))
            val secondBatch = rasterizer.prepareBatch(second, listOf(tile), RenderOptions(256))
            try {
                assertEquals(firstBatch.contentKeys, secondBatch.contentKeys)
            } finally {
                firstBatch.close()
                secondBatch.close()
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun inlineVectorFillAndLineRenderFromOverzoomedSourceTileAndReuseRawCache() = runTest {
        val mvt = overzoomVectorTile()
        val store = InMemoryRawResourceStore()
        val requestedUrls = mutableListOf<String>()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    assertEquals(ResourceClass.VECTOR_TILE, request.resourceClass)
                    requestedUrls += request.url
                    TransportResponse(200, mvt, TransportResponseMetadata(contentType = "application/vnd.mapbox-vector-tile"))
                },
                rawResourceStore = store,
            ),
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf?key=private"],"maxzoom":15}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"land","type":"fill","source":"v","source-layer":"land","filter":["==","kind","land"],"paint":{"fill-color":["step",["zoom"],"#ff0000",22,"#00ff00"],"fill-opacity":0.75,"fill-outline-color":"#000000","fill-translate":[1,-1],"fill-translate-anchor":"viewport"}},{"id":"building","type":"fill-extrusion","source":"v","source-layer":"land","filter":["==","kind","land"],"paint":{"fill-extrusion-base":{"property":"height","type":"identity"},"fill-extrusion-color":"#808080","fill-extrusion-height":["interpolate",["linear"],["zoom"],0,0,22,100],"fill-extrusion-opacity":0.5,"fill-extrusion-vertical-gradient":true}},{"id":"road","type":"line","source":"v","source-layer":"roads","layout":{"line-cap":"round","line-join":"round","line-miter-limit":{"stops":[[0,2],[22,3]]},"line-round-limit":-1},"paint":{"line-blur":0.5,"line-color":"#0000ff","line-dasharray":[2,1],"line-gap-width":2,"line-offset":1,"line-opacity":0.9,"line-translate":["interpolate",["linear"],["zoom"],0,["literal",[0,0]],22,["literal",[1,-1]]],"line-width":8}}]}""",
                ),
            )
            val outputTile = TileId(22, 1_234_919, 1_576_977)
            val first = rasterizer.render(style, listOf(outputTile), RenderOptions(512)).tiles.single()
            val warm = rasterizer.render(style, listOf(outputTile), RenderOptions(512)).tiles.single()
            val blankStyle = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}}]}""",
                ),
            )
            val blank = rasterizer.render(blankStyle, listOf(outputTile), RenderOptions(512)).tiles.single()

            assertEquals(listOf("https://tiles.example.test/15/9647/12320.pbf?key=private"), requestedUrls)
            assertEquals(1, store.size())
            assertTrue(first.pngBytes.startsWithPngSignature())
            assertTrue(first.pngBytes.contentEquals(warm.pngBytes))
            assertFalse(first.pngBytes.contentEquals(blank.pngBytes))
            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.EXTRUSION_FLATTENED })
            assertTrue(warm.diagnostics.any { it.code == DiagnosticCode.RESOURCE_CACHE_HIT })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun retainedOptionalTextIconUsesSpriteExpressionAndRendersWithoutGlyphs() = runTest {
        val mvt = overzoomVectorTile()
        val spritePng = renderSyntheticPng(8)
        val requestedClasses = mutableListOf<ResourceClass>()
        val requestedClassesMutex = Mutex()
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                requestedClassesMutex.withLock { requestedClasses += request.resourceClass }
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(
                        200,
                        """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}""".encodeToByteArray(),
                    )
                    ResourceClass.SPRITE_IMAGE -> TransportResponse(200, spritePng)
                    ResourceClass.VECTOR_TILE -> TransportResponse(200, mvt)
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"empty-pattern","type":"fill","source":"v","source-layer":"land","paint":{"fill-color":"#00ff00","fill-pattern":["match",["get","unused"],"pattern","marker",""]}},{"id":"missing-pattern","type":"fill","source":"v","source-layer":"land","paint":{"fill-pattern":"not-in-atlas"}},{"id":"zero-sized-icon","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","icon-size":0}},{"id":"poi","type":"symbol","source":"v","source-layer":"poi","filter":["==","kind","poi"],"layout":{"icon-image":["coalesce",["image",["get","icon"]],["image","fallback"]],"icon-size":2,"icon-padding":3,"icon-rotate":["get","missing-rotation"],"text-field":"Name","text-optional":true,"symbol-sort-key":["get","missing-rank"]},"paint":{"icon-color":"#ff0000","icon-halo-color":"#ffffff","icon-halo-width":1,"icon-opacity":0.8}}]}""",
                ),
            )
            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()

            assertTrue(output.pngBytes.startsWithPngSignature())
            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.TEXT_COMPONENT_REMOVED_ICON_RETAINED })
            assertEquals(
                setOf(ResourceClass.SPRITE_JSON, ResourceClass.SPRITE_IMAGE, ResourceClass.VECTOR_TILE),
                requestedClassesMutex.withLock { requestedClasses.toSet() },
            )
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun remoteGeoJsonLineIsCachedProjectedAndRenderedWithoutSourceLayer() = runTest {
        val store = InMemoryRawResourceStore()
        val requested = mutableListOf<Pair<ResourceClass, String>>()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    requested += request.resourceClass to request.url
                    assertEquals(ResourceClass.GEO_JSON, request.resourceClass)
                    TransportResponse(
                        200,
                        """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"type":"equator","degree":0},"geometry":{"type":"LineString","coordinates":[[-180,0],[180,0]]}}]}""".encodeToByteArray(),
                    )
                },
                rawResourceStore = store,
            ),
        )
        try {
            val input = StyleInput.InlineJson(
                """{"version":8,"sources":{"grid":{"type":"geojson","data":"https://data.example.test/grid.json?key=private"}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"grid","type":"line","source":"grid","filter":["==",["get","type"],"equator"],"paint":{"line-color":"#ff0000","line-width":8}}]}""",
            )
            val firstStyle = rasterizer.prepare(input)
            val secondStyle = rasterizer.prepare(input)
            val tile = TileId(0, 0, 0)
            val rendered = rasterizer.render(firstStyle, listOf(tile), RenderOptions(256)).tiles.single()
            val blankStyle = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}}]}""",
                ),
            )
            val blank = rasterizer.render(blankStyle, listOf(tile), RenderOptions(256)).tiles.single()

            assertEquals(firstStyle.digest, secondStyle.digest)
            assertEquals(
                listOf(ResourceClass.GEO_JSON to "https://data.example.test/grid.json?key=private"),
                requested,
            )
            assertEquals(1, store.size())
            assertTrue(rendered.pngBytes.startsWithPngSignature())
            assertFalse(rendered.pngBytes.contentEquals(blank.pngBytes))
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun hillshadeFetchesDemNeighborsAndRendersTerrainRgb() = runTest {
        val demPng = renderTerrainDemPng(64)
        val requestedClasses = mutableListOf<ResourceClass>()
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                requestedClasses += request.resourceClass
                assertEquals(ResourceClass.DEM_TILE, request.resourceClass)
                TransportResponse(200, demPng)
            },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sources":{"dem":{"type":"raster-dem","tiles":["https://dem.example.test/{z}/{x}/{y}.png"],"tileSize":64,"maxzoom":0}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"terrain","type":"hillshade","source":"dem","paint":{"hillshade-accent-color":"#202020","hillshade-exaggeration":["interpolate",["linear"],["zoom"],0,1,22,2],"hillshade-highlight-color":"#ffff00","hillshade-shadow-color":"#0000ff"}}]}""",
                ),
            )
            val tile = TileId(0, 0, 0)
            val rendered = rasterizer.render(style, listOf(tile), RenderOptions(256)).tiles.single()
            val warm = rasterizer.render(style, listOf(tile), RenderOptions(256)).tiles.single()
            val blankStyle = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}}]}""",
                ),
            )
            val blank = rasterizer.render(blankStyle, listOf(tile), RenderOptions(256)).tiles.single()

            assertEquals(listOf(ResourceClass.DEM_TILE), requestedClasses)
            assertTrue(rendered.pngBytes.startsWithPngSignature())
            assertTrue(rendered.pngBytes.contentEquals(warm.pngBytes))
            assertFalse(rendered.pngBytes.contentEquals(blank.pngBytes))
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun relativeVectorTileJsonResolvesDuringPreparationAndIsCached() = runTest {
        val mvt = overzoomVectorTile()
        val store = InMemoryRawResourceStore()
        val requested = mutableListOf<Pair<ResourceClass, String>>()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    requested += request.resourceClass to request.url
                    when (request.resourceClass) {
                        ResourceClass.TILE_JSON -> TransportResponse(
                            200,
                            """{"tilejson":"3.0.0","tiles":["../tiles/{z}/{x}/{y}.pbf?token=inside"],"minzoom":0,"maxzoom":15,"scheme":"xyz"}""".encodeToByteArray(),
                        )
                        ResourceClass.VECTOR_TILE -> TransportResponse(200, mvt)
                        else -> error("Unexpected resource class ${request.resourceClass}")
                    }
                },
                rawResourceStore = store,
            ),
        )
        try {
            val input = StyleInput.InlineJson(
                """{"version":8,"sources":{"v":{"type":"vector","url":"metadata/tiles.json?key=inside"}},"layers":[{"id":"land","type":"fill","source":"v","source-layer":"land","paint":{"fill-color":"#00ff00"}}]}""",
                baseUri = "https://style.example.test/styles/basic/style.json?key=style-secret",
            )
            val firstStyle = rasterizer.prepare(input)
            val secondStyle = rasterizer.prepare(input)
            assertEquals(firstStyle.digest, secondStyle.digest)

            val tile = TileId(22, 1_234_919, 1_576_977)
            val output = rasterizer.render(firstStyle, listOf(tile), RenderOptions(512)).tiles.single()

            assertTrue(output.pngBytes.startsWithPngSignature())
            assertEquals(
                listOf(
                    ResourceClass.TILE_JSON to "https://style.example.test/styles/basic/metadata/tiles.json?key=inside",
                    ResourceClass.VECTOR_TILE to "https://style.example.test/styles/basic/tiles/15/9647/12320.pbf?token=inside",
                ),
                requested,
            )
            assertEquals(2, store.size())
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun tileJsonAuthenticationValuesDoNotChangePreparedStyleIdentity() = runTest {
        suspend fun prepareDigest(apiKey: String): String {
            val rasterizer = testRasterizer(
                transport = ResourceTransport { request ->
                    assertEquals(ResourceClass.TILE_JSON, request.resourceClass)
                    TransportResponse(
                        200,
                        """{"tilejson":"3.0.0","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf?key=$apiKey"],"maxzoom":15}""".encodeToByteArray(),
                    )
                },
            )
            return try {
                rasterizer.prepare(
                    StyleInput.InlineJson(
                        """{"version":8,"sources":{"v":{"type":"vector","url":"https://metadata.example.test/tiles.json"}},"layers":[{"id":"land","type":"fill","source":"v","source-layer":"land"}]}""",
                    ),
                ).digest
            } finally {
                rasterizer.close()
                rasterizer.awaitClosed()
            }
        }

        assertEquals(prepareDigest("first-secret"), prepareDigest("second-secret"))
    }

    @Test
    fun closeClearsCredentialBearingPreparedStyleUrls() = runTest {
        val rasterizer = testRasterizer()
        val protectedUrl = try {
            val style = rasterizer.prepare(rasterStyle("ephemeral-secret")) as CompiledPreparedStyle
            style.drawLayers.filterIsInstance<RasterDrawLayer>()
                .single()
                .source
                .tileTemplates
                .single()
                .also {
                    assertTrue(it.resolve().contains("ephemeral-secret"))
                    assertTrue(!it.toString().contains("ephemeral-secret"))
                }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }

        assertFailsWith<RasterizerClosedException> { protectedUrl.resolve() }
    }

    @Test
    fun rasterIsCompositedAndEncodedWhenOutputSizeDiffers() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { TransportResponse(200, sourcePng) },
        )
        try {
            val style = rasterizer.prepare(rasterStyle("secret"))
            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(512)).tiles.single()

            assertTrue(output.pngBytes.startsWithPngSignature())
            assertEquals(512, output.pngBytes.pngWidth())
            assertEquals(512, output.pngBytes.pngHeight())
            assertTrue(output.diagnostics.none { it.code == DiagnosticCode.RASTER_PASSTHROUGH_USED })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun rasterPaintExpressionsPrepareAndDisablePngPassThrough() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { TransportResponse(200, sourcePng) },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sources":{"tiles":{"type":"raster","tiles":["https://tiles.example.test/{z}/{x}/{y}.png"],"tileSize":256}},"layers":[{"id":"raster","type":"raster","source":"tiles","paint":{"raster-brightness-min":["interpolate",["linear"],["zoom"],0,0.05,22,0.1],"raster-brightness-max":0.98,"raster-contrast":{"stops":[[0,0.05],[22,0.1]]}}}]}""",
                ),
            )
            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()

            assertTrue(output.pngBytes.startsWithPngSignature())
            assertTrue(output.diagnostics.none { it.code == DiagnosticCode.RASTER_PASSTHROUGH_USED })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun terrainZoomZeroRasterPaintPreservesIllumination() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { TransportResponse(200, sourcePng) },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sources":{"imagery":{"type":"raster","tiles":["https://tiles.example.test/{z}/{x}/{y}.png"],"tileSize":256}},"layers":[{"id":"imagery","type":"raster","source":"imagery","paint":{"raster-contrast":["interpolate",["linear"],["zoom"],0,0.1,6,0],"raster-saturation":["interpolate",["linear"],["zoom"],0,0.15,6,0],"raster-brightness-min":["interpolate",["linear"],["zoom"],0,0.05,6,0]}}]}""",
                ),
            )

            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()

            assertColorClose(
                expected = Color.makeARGB(255, 17, 84, 64),
                actual = output.pngBytes.centerPixelColor(),
                tolerance = 1,
            )
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun tileJsonMaximumZoomCapsAnOverstatedRasterSourceMaximum() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val requested = mutableListOf<Pair<ResourceClass, String>>()
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                requested += request.resourceClass to request.url
                when (request.resourceClass) {
                    ResourceClass.TILE_JSON -> TransportResponse(
                        200,
                        """{"tilejson":"3.0.0","tiles":["https://tiles.example.test/{z}/{x}/{y}.png"],"maxzoom":18} """.encodeToByteArray(),
                    )
                    ResourceClass.RASTER_TILE -> TransportResponse(200, sourcePng)
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sources":{"imagery":{"type":"raster","url":"https://tiles.example.test/metadata.json","maxzoom":20,"tileSize":256}},"layers":[{"id":"imagery","type":"raster","source":"imagery"}]}""",
                ),
            )
            rasterizer.render(style, listOf(TileId(20, 4, 8)), RenderOptions(256))

            assertEquals(
                listOf(
                    ResourceClass.TILE_JSON to "https://tiles.example.test/metadata.json",
                    ResourceClass.RASTER_TILE to "https://tiles.example.test/18/1/2.png",
                ),
                requested,
            )
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun failureOnOneTileKeepsCompletedRawCacheEntries() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val store = InMemoryRawResourceStore()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    if (request.url.contains("/1/0/0.png")) {
                        TransportResponse(200, sourcePng)
                    } else {
                        store.firstWrite.await()
                        TransportResponse(503, ByteArray(0), TransportResponseMetadata(retryAfterMillis = 1_000))
                    }
                },
                rawResourceStore = store,
            ),
        )
        try {
            val style = rasterizer.prepare(rasterStyle("secret"))
            val error = assertFailsWith<ResourceAcquisitionException> {
                rasterizer.prepareBatch(style, listOf(TileId(1, 0, 0), TileId(1, 1, 0)), RenderOptions(256))
            }

            assertEquals(503, error.statusCode)
            assertEquals(1, store.size())
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun rasterFetchesUseConfiguredParallelismWithoutExceedingIt() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val stateMutex = Mutex()
        val twoStarted = CompletableDeferred<Unit>()
        var active = 0
        var maximumActive = 0
        var requests = 0
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport {
                    stateMutex.withLock {
                        active += 1
                        requests += 1
                        maximumActive = maxOf(maximumActive, active)
                        if (active == 2) twoStarted.complete(Unit)
                    }
                    twoStarted.await()
                    try {
                        TransportResponse(200, sourcePng)
                    } finally {
                        stateMutex.withLock { active -= 1 }
                    }
                },
                rawResourceStore = InMemoryRawResourceStore(),
                executionPolicy = ExecutionPolicy(
                    maxConcurrentExchanges = 2,
                    maxConcurrentExchangesPerOrigin = 2,
                    maxConcurrentDecodes = 1,
                ),
            ),
        )
        try {
            val style = rasterizer.prepare(rasterStyle("secret"))
            val batch = rasterizer.prepareBatch(
                style,
                listOf(TileId(2, 0, 0), TileId(2, 1, 0), TileId(2, 2, 0), TileId(2, 3, 0)),
                RenderOptions(256),
            )
            batch.close()

            assertEquals(4, requests)
            assertEquals(2, maximumActive)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun identicalConcurrentFetchesUseLastWaiterSingleFlight() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val transportStarted = CompletableDeferred<Unit>()
        val joined = CompletableDeferred<Unit>()
        val releaseTransport = CompletableDeferred<Unit>()
        var requests = 0
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport {
                    requests += 1
                    transportStarted.complete(Unit)
                    releaseTransport.await()
                    TransportResponse(200, sourcePng)
                },
                rawResourceStore = InMemoryRawResourceStore(),
                metricsSink = MetricsSink { metric ->
                    if (metric.name == MetricName.SINGLE_FLIGHT_JOIN) joined.complete(Unit)
                },
            ),
        )
        try {
            val style = rasterizer.prepare(rasterStyle("secret"))
            val tile = TileId(0, 0, 0)
            val first = async { rasterizer.prepareBatch(style, listOf(tile), RenderOptions(256)) }
            transportStarted.await()
            val second = async { rasterizer.prepareBatch(style, listOf(tile), RenderOptions(256)) }
            joined.await()

            first.cancelAndJoin()
            releaseTransport.complete(Unit)
            val survivingBatch = second.await()
            survivingBatch.close()

            assertEquals(1, requests)
        } finally {
            releaseTransport.complete(Unit)
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    private fun testRasterizer(
        transport: ResourceTransport = ResourceTransport { error("Unexpected transport request") },
    ): BasemapRasterizer = Rentile.create(
        RentileConfiguration(
            transport = transport,
            rawResourceStore = InMemoryRawResourceStore(),
        ),
    )

    private fun rasterStyle(apiKey: String): StyleInput.InlineJson = StyleInput.InlineJson(
        """{"version":8,"sources":{"tiles":{"type":"raster","tiles":["https://tiles.example.test/{z}/{x}/{y}.png?key=$apiKey"],"tileSize":256}},"layers":[{"id":"raster","type":"raster","source":"tiles"}]}""",
    )

    private fun overzoomVectorTile(): ByteArray {
        val land = Tile.Feature(
            tags = listOf(0, 0),
            type = Tile.GeomType.POLYGON,
            geometry = listOf(
                command(1, 1), zigZag(3300), zigZag(548),
                command(2, 3), zigZag(24), zigZag(0), zigZag(0), zigZag(24), zigZag(-24), zigZag(0),
                command(7, 1),
            ),
        )
        val road = Tile.Feature(
            type = Tile.GeomType.LINESTRING,
            geometry = listOf(
                command(1, 1), zigZag(3300), zigZag(560),
                command(2, 1), zigZag(24), zigZag(0),
            ),
        )
        val poi = Tile.Feature(
            tags = listOf(0, 0, 1, 1, 2, 2),
            type = Tile.GeomType.POINT,
            geometry = listOf(command(1, 1), zigZag(2048), zigZag(2048)),
        )
        return Tile.ADAPTER.encode(
            Tile(
                layers = listOf(
                    Tile.Layer(
                        version = 2,
                        name = "land",
                        features = listOf(land),
                        keys = listOf("kind"),
                        values = listOf(Tile.Value(string_value = "land")),
                        extent = 4096,
                    ),
                    Tile.Layer(
                        version = 2,
                        name = "poi",
                        features = listOf(poi),
                        keys = listOf("kind", "icon", "rank"),
                        values = listOf(
                            Tile.Value(string_value = "poi"),
                            Tile.Value(string_value = "marker"),
                            Tile.Value(int_value = 1),
                        ),
                        extent = 4096,
                    ),
                    Tile.Layer(
                        version = 2,
                        name = "roads",
                        features = listOf(road),
                        extent = 4096,
                    ),
                ),
            ),
        )
    }

    private fun command(id: Int, count: Int): Int = (count shl 3) or id

    private fun zigZag(value: Int): Int = (value shl 1) xor (value shr 31)

    private fun renderTerrainDemPng(sizePx: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(sizePx, sizePx)
        try {
            val low = Paint().apply { color = Color.makeARGB(255, 1, 134, 160) }
            val high = Paint().apply { color = Color.makeARGB(255, 1, 173, 176) }
            try {
                surface.canvas.drawRect(Rect.makeLTRB(0f, 0f, sizePx / 2f, sizePx.toFloat()), low)
                surface.canvas.drawRect(Rect.makeLTRB(sizePx / 2f, 0f, sizePx.toFloat(), sizePx.toFloat()), high)
            } finally {
                low.close()
                high.close()
            }
            val image = surface.makeImageSnapshot()
            try {
                val encoded = image.encodeToData(EncodedImageFormat.PNG)
                    ?: error("Skia could not encode synthetic Terrain RGB")
                try {
                    return encoded.bytes
                } finally {
                    encoded.close()
                }
            } finally {
                image.close()
            }
        } finally {
            surface.close()
        }
    }
}

private class InMemoryRawResourceStore : RawResourceStore {
    private val mutex = Mutex()
    private val entries = mutableMapOf<RawResourceKey, StoredRawResource>()
    val firstWrite = CompletableDeferred<Unit>()

    override suspend fun read(key: RawResourceKey): StoredRawResource? = mutex.withLock { entries[key] }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        mutex.withLock { entries[key] = resource }
        firstWrite.complete(Unit)
    }

    override suspend fun remove(key: RawResourceKey) {
        mutex.withLock { entries.remove(key) }
    }

    suspend fun size(): Int = mutex.withLock { entries.size }
}

private fun ByteArray.startsWithPngSignature(): Boolean =
    size >= 8 && this[0] == 0x89.toByte() && decodeToString(1, 4) == "PNG"

private fun ByteArray.pngWidth(): Int = bigEndianInt(16)

private fun ByteArray.pngHeight(): Int = bigEndianInt(20)

private fun ByteArray.centerPixelColor(): Int {
    val image = Image.makeFromEncoded(this)
    try {
        val bitmap = Bitmap()
        try {
            check(bitmap.allocN32Pixels(image.width, image.height, false))
            check(image.readPixels(bitmap))
            return bitmap.getColor(bitmap.width / 2, bitmap.height / 2)
        } finally {
            bitmap.close()
        }
    } finally {
        image.close()
    }
}

private fun assertColorClose(expected: Int, actual: Int, tolerance: Int) {
    fun channel(color: Int, shift: Int): Int = color ushr shift and 0xff
    listOf(24, 16, 8, 0).forEach { shift ->
        assertTrue(
            kotlin.math.abs(channel(expected, shift) - channel(actual, shift)) <= tolerance,
            "Expected color 0x${expected.toUInt().toString(16)}, got 0x${actual.toUInt().toString(16)}",
        )
    }
}

private fun ByteArray.bigEndianInt(offset: Int): Int =
    ((this[offset].toInt() and 0xff) shl 24) or
        ((this[offset + 1].toInt() and 0xff) shl 16) or
        ((this[offset + 2].toInt() and 0xff) shl 8) or
        (this[offset + 3].toInt() and 0xff)
