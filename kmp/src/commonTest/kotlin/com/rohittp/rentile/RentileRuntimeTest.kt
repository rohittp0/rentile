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
import com.rohittp.rentile.internal.style.StyleEvaluationContext
import com.rohittp.rentile.internal.style.StyleValue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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
        // SpriteResourceAcquirer fetches the sprite JSON and the sprite PNG in two concurrent
        // coroutines, so this lambda runs twice at once and an unguarded ArrayList loses an append.
        val requested = mutableListOf<Pair<ResourceClass, String>>()
        val requestedMutex = Mutex()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    requestedMutex.withLock { requested += request.resourceClass to request.url }
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
                requestedMutex.withLock { requested.toSet() },
            )
            assertEquals(2, requestedMutex.withLock { requested.size })
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
    fun outputRequestKeyIsAvailableBeforeAcquisitionAndTracksOutputInputs() = runTest {
        val rasterizer = testRasterizer()
        try {
            val firstStyle = rasterizer.prepare(
                StyleInput.InlineJson("""{"version":8,"layers":[]}"""),
            )
            val secondStyle = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"layers":[{"id":"base","type":"background"}]}""",
                ),
            )
            val tile = TileId(1, 0, 0)
            val first = rasterizer.outputRequestKey(firstStyle, tile, RenderOptions(256))

            assertEquals(first, rasterizer.outputRequestKey(firstStyle, tile, RenderOptions(256)))
            assertNotEquals(first, rasterizer.outputRequestKey(firstStyle, tile, RenderOptions(512)))
            assertNotEquals(first, rasterizer.outputRequestKey(firstStyle, TileId(1, 1, 0), RenderOptions(256)))
            assertNotEquals(first, rasterizer.outputRequestKey(secondStyle, tile, RenderOptions(256)))
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
    fun requiredTextIsRemovedAndAnIndependentIconIsRetained() = runTest {
        val spritePng = renderSyntheticPng(8)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(
                        200,
                        """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}""".encodeToByteArray(),
                    )
                    ResourceClass.SPRITE_IMAGE -> TransportResponse(200, spritePng)
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )

            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.TEXT_COMPONENT_REMOVED_ICON_RETAINED })
            assertTrue(style.diagnostics.none { it.code == DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun onlySpriteRequiringLayerWithRequiredTextStillFetchesSprite() = runTest {
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
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            // This style's only symbol layer has a meaningful icon-image and a required
            // (non-optional) text-field with no icon-text-fit. The icon's geometry does not
            // depend on the text, so the layer is retained and must fetch the sprite atlas it
            // needs to draw that icon.
            rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )

            assertEquals(
                setOf(ResourceClass.SPRITE_JSON, ResourceClass.SPRITE_IMAGE),
                requestedClassesMutex.withLock { requestedClasses.toSet() },
            )
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun anIconSizedFromTextExtentsStaysExcluded() = runTest {
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
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"shield","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","icon-text-fit":"width","text-field":["get","name"]}}]}""",
                ),
            )

            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED })
            // This style's only symbol layer is icon-text-fit coupled and therefore excluded; it
            // must not require the sprite it can never use, so no sprite request should occur.
            assertEquals(emptySet(), requestedClassesMutex.withLock { requestedClasses.toSet() })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun anExpressionValuedIconTextFitStaysExcluded() = runTest {
        // icon-text-fit is data-driven in the style spec. An expression value is a JsonArray, not
        // a JsonPrimitive, so asPrimitive() returns null for it - indistinguishable from an absent
        // icon-text-fit unless retainsIconIndependentOfText treats a non-primitive as coupled. If
        // it did not, this layer would be retained and its icon drawn unstretched even though the
        // expression could resolve to "width" or "both" per feature.
        val requestedClasses = mutableListOf<ResourceClass>()
        val requestedClassesMutex = Mutex()
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                requestedClassesMutex.withLock { requestedClasses += request.resourceClass }
                error("Unexpected resource class ${request.resourceClass}")
            },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"shield","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","icon-text-fit":["case",["==",["get","kind"],"shield"],"both","none"],"text-field":["get","name"]}}]}""",
                ),
            )

            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED })
            // No layer ends up retaining or desiring the sprite, so it must not be requested.
            assertEquals(emptySet(), requestedClassesMutex.withLock { requestedClasses.toSet() })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun textCoupledIconWithAnUnsupportedConstructIsExcludedInsteadOfFailingPreparation() = runTest {
        val spritePng = renderSyntheticPng(8)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(
                        200,
                        """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}""".encodeToByteArray(),
                    )
                    ResourceClass.SPRITE_IMAGE -> TransportResponse(200, spritePng)
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            // This layer has meaningful text and no icon-text-fit, so classifySymbol retains it and
            // compileIconLayer runs against it for the first time. Its viewport-aligned line
            // placement is a construct the compatibility profile rejects; that rejection must fall
            // back to the pre-existing text-coupled exclusion instead of failing the whole style,
            // and the sibling background layer must still be usable.
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"shield","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"],"symbol-placement":"line","icon-rotation-alignment":"viewport"}}]}""",
                ),
            )

            val excluded = style.diagnostics.single { it.code == DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED }
            assertTrue(style.diagnostics.none { it.severity == DiagnosticSeverity.ERROR })
            // The original failRetained reason must survive into the exclusion diagnostic's
            // details, since it is the only signal about what to fix next in the corpus.
            assertEquals(
                "viewport-aligned line icons are outside the compatibility profile",
                excluded.details["cause"],
            )
            // The construct sentence never doubles as a causeCode; that key is for typed resource
            // error codes only.
            assertFalse("causeCode" in excluded.details)

            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()
            assertTrue(output.pngBytes.startsWithPngSignature())
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun iconOnlyLayerWithAnUnsupportedConstructStillFailsPreparation() = runTest {
        val spritePng = renderSyntheticPng(8)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(
                        200,
                        """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}""".encodeToByteArray(),
                    )
                    ResourceClass.SPRITE_IMAGE -> TransportResponse(200, spritePng)
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            // This layer has no text at all, so it is author-intended as an icon layer:
            // classifySymbol has always retained it unconditionally and compileIconLayer has
            // always run against it. Its viewport-aligned line placement must still fail
            // preparation loudly, exactly as before this change - the new try/catch guarding the
            // text-present branch must not weaken this contract.
            val error = assertFailsWith<StylePreparationException> {
                rasterizer.prepare(
                    StyleInput.InlineJson(
                        """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"shield","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","symbol-placement":"line","icon-rotation-alignment":"viewport"}}]}""",
                    ),
                )
            }

            assertTrue(error.diagnostics.any { it.code == DiagnosticCode.UNSUPPORTED_RETAINED_CONSTRUCT })
            assertTrue(error.diagnostics.any { it.severity == DiagnosticSeverity.ERROR })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aTextOptionalLayerWithAnUnsupportedConstructStillFailsPreparation() = runTest {
        // text-optional: true is the author declaring that the icon stands alone, so this profile
        // was already retaining this layer and already compiling it through the strict path before
        // it grew the icon-text-fit rule. Folding it into the lenient repaired bucket would
        // silently exclude it with an INFO and make its icon disappear, so the same viewport-
        // aligned line placement that the repaired branch degrades must still fail loudly here.
        val spritePng = renderSyntheticPng(8)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(
                        200,
                        """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}""".encodeToByteArray(),
                    )
                    ResourceClass.SPRITE_IMAGE -> TransportResponse(200, spritePng)
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            val error = assertFailsWith<StylePreparationException> {
                rasterizer.prepare(
                    StyleInput.InlineJson(
                        """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"shield","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"],"text-optional":true,"symbol-placement":"line","icon-rotation-alignment":"viewport"}}]}""",
                    ),
                )
            }

            assertTrue(error.diagnostics.any { it.code == DiagnosticCode.UNSUPPORTED_RETAINED_CONSTRUCT })
            assertTrue(error.diagnostics.any { it.severity == DiagnosticSeverity.ERROR })
            assertTrue(error.diagnostics.none { it.code == DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aTextOptionalLayerIsRetainedAndKeepsTheStrictRenderPath() = runTest {
        // Withholding leniency must not withhold retention: the layer still reports
        // TEXT_COMPONENT_REMOVED_ICON_RETAINED and still fetches its atlas. Those two assertions
        // alone hold whether the layer is treated as repaired or strict, so the load-bearing one
        // is the last: icon-halo-width: -1 is a constant every feature trips on, and a repaired
        // layer would skip those features and return a tile. This layer was already on the strict
        // path before this profile grew the icon-text-fit rule, so it must still fail the tile.
        val mvt = iconOffsetVectorTile()
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
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"],"text-optional":true},"paint":{"icon-halo-width":-1}}]}""",
                ),
            )
            val classesAfterPrepare = requestedClassesMutex.withLock { requestedClasses.toSet() }

            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.TEXT_COMPONENT_REMOVED_ICON_RETAINED })
            assertTrue(style.diagnostics.none { it.code == DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED })
            assertEquals(setOf(ResourceClass.SPRITE_JSON, ResourceClass.SPRITE_IMAGE), classesAfterPrepare)
            assertFailsWith<RasterizationException> {
                rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256))
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aTextOptionalLayerWithNoResolvableSpriteStillFailsPreparation() = runTest {
        // Same rule at the sprite gate. A text-optional layer already required its atlas, so it
        // belongs in layerRequiresSpriteUnconditionally rather than the lenient desiring bucket:
        // an absent sprite key must still fail preparation loudly, not leave the author's icon
        // quietly undrawn. The throwing default transport also proves nothing is fetched.
        val rasterizer = testRasterizer()
        try {
            assertFailsWith<StylePreparationException> {
                rasterizer.prepare(
                    StyleInput.InlineJson(
                        """{"version":8,"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"],"text-optional":true}}]}""",
                    ),
                )
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aTextOptionalIconSizedFromTextExtentsNeedsNoSpriteAndStillPrepares() = runTest {
        // The boundary of the rule above. text-optional: true puts a layer back on the strict
        // path, but only for the layers this profile was actually drawing icons for. This one's
        // icon is sized from text extents, so it was never retained and never needed an atlas:
        // demanding one would fail a style that prepared fine, which is the exact class of
        // regression this branch exists to avoid. It must stay excluded and stay silent.
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"shield","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","icon-text-fit":"width","text-field":["get","name"],"text-optional":true}}]}""",
                ),
            )

            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED })
            assertTrue(style.diagnostics.none { it.severity == DiagnosticSeverity.ERROR })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aNonConstructFailureInARepairedLayerIsRethrownRatherThanExcluded() = runTest {
        // The catch guarding the repair-compile attempt must only swallow an
        // UNSUPPORTED_RETAINED_CONSTRUCT failure. A malformed vector tile template ("tiles":[123],
        // a number instead of a string) throws a bare StylePreparationException with no
        // diagnostics from deep inside compileVectorSource - a different class of problem
        // (a malformed source, not a rejected construct) that must still fail preparation. This
        // pins the rethrow branch: inverting its condition would silently swallow this failure
        // into a text-coupled exclusion instead.
        val spritePng = renderSyntheticPng(8)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(
                        200,
                        """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}""".encodeToByteArray(),
                    )
                    ResourceClass.SPRITE_IMAGE -> TransportResponse(200, spritePng)
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            val error = assertFailsWith<StylePreparationException> {
                rasterizer.prepare(
                    StyleInput.InlineJson(
                        """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":[123]}},"layers":[{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                    ),
                )
            }

            assertTrue(error.diagnostics.any { it.severity == DiagnosticSeverity.ERROR })
            assertTrue(error.diagnostics.none { it.code == DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aTextBearingIconLayerWithNoSpriteKeyDoesNotFailAStyleThatUsedToPrepare() = runTest {
        // No "sprite" key at all. Before this fix, a text-bearing icon layer with no
        // icon-text-fit made the sprite block treat the sprite as unconditionally required, and
        // an absent sprite failed preparation outright - even though a style shaped exactly like
        // this prepared fine before this layer could ever be retained. The default (throwing)
        // transport proves no network resource is requested: the icon layer falls back to
        // exclusion before any sprite or vector tile fetch is attempted.
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )

            val excluded = style.diagnostics.single { it.code == DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED }
            // Nothing was fetched, so there is no cause to report. That absence is what makes a
            // real fetch failure legible in the tests above.
            assertFalse("causeCode" in excluded.details)
            assertTrue(style.diagnostics.none { it.severity == DiagnosticSeverity.ERROR })

            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()
            assertTrue(output.pngBytes.startsWithPngSignature())
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aTextBearingIconLayerWithAnArrayFormSpriteDoesNotFailAStyleThatUsedToPrepare() = runTest {
        // "sprite" in the style-spec v8 array form ([{id, url}, ...]) rather than a single string.
        // root["sprite"]?.asPrimitive() is null for a JsonArray, which used to be indistinguishable
        // from an absent sprite and hit the same unconditional failUnsupported.
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":[{"id":"default","url":"https://sprite.example.test/icons"}],"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )

            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED })
            assertTrue(style.diagnostics.none { it.severity == DiagnosticSeverity.ERROR })

            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()
            assertTrue(output.pngBytes.startsWithPngSignature())
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aTextBearingIconLayerWithARelativeSpriteAndNoBaseUriDoesNotFailAStyleThatUsedToPrepare() = runTest {
        // A relative sprite reference with no base URI to resolve it against - the situation for
        // every StyleInput.InlineJson and StyleInput.Prefetched, since neither carries a base URI
        // unless the caller supplies one. resolveHttpReference has nothing to resolve against, so
        // this used to hit the "cannot be resolved against the style base URI" failure.
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"icons/default","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )

            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED })
            assertTrue(style.diagnostics.none { it.severity == DiagnosticSeverity.ERROR })

            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()
            assertTrue(output.pngBytes.startsWithPngSignature())
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aTextBearingIconLayerWithAnUnfetchableSpriteDoesNotFailAStyleThatUsedToPrepare() = runTest {
        // The sprite reference resolves perfectly - absolute URL, nothing offline about it - and
        // then the fetch fails with a 403. resolveSprite throws ResourceAcquisitionException,
        // which is not a StylePreparationException, so it used to escape every try block and kill
        // a style that prepared fine before this layer could ever be retained. A style behind an
        // expired sprite credential is exactly this shape.
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(403, ByteArray(0))
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )

            // The blast radius is the whole style, so the failure must stay legible: an expired
            // sprite credential cannot look identical to a style that has no sprite key.
            val excluded = style.diagnostics.single { it.code == DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED }
            assertEquals("RESOURCE_ACQUISITION_FAILED", excluded.details["causeCode"])
            assertTrue(style.diagnostics.none { it.severity == DiagnosticSeverity.ERROR })

            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()
            assertTrue(output.pngBytes.startsWithPngSignature())
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aTextBearingIconLayerWithUndecodableSpriteJsonDoesNotFailAStyleThatUsedToPrepare() = runTest {
        // Same shape, but the sprite fetch succeeds and the sprite JSON will not decode, so
        // resolveSprite throws ResourceDecodeException instead. Also not a
        // StylePreparationException, so it needs the same degradation.
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(200, "not sprite json at all".encodeToByteArray())
                    ResourceClass.SPRITE_IMAGE -> TransportResponse(200, renderSyntheticPng(8))
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )

            // A decode failure must be distinguishable from an acquisition failure, not just from
            // an absent sprite.
            val excluded = style.diagnostics.single { it.code == DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED }
            assertEquals("RESOURCE_DECODE_FAILED", excluded.details["causeCode"])
            assertTrue(style.diagnostics.none { it.severity == DiagnosticSeverity.ERROR })

            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()
            assertTrue(output.pngBytes.startsWithPngSignature())
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun anUnfetchableSpriteStillFailsPreparationLoudlyWhenALayerRequiresIt() = runTest {
        // The loud half of the same rule: a fill-pattern cannot draw at all without an atlas, so
        // it goes through resolveRequiredSpriteAtlas and a 403 must still propagate untouched.
        // Degrading this one would render pattern fills silently unpatterned.
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(403, ByteArray(0))
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            assertFailsWith<ResourceAcquisitionException> {
                rasterizer.prepare(
                    StyleInput.InlineJson(
                        """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"land","type":"fill","source":"v","source-layer":"land","paint":{"fill-pattern":"marker"}}]}""",
                    ),
                )
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aSourceReachableOnlyThroughATextBearingIconLayerDoesNotFailAStyleThatUsedToPrepare() = runTest {
        // The same hole one step later. This GeoJSON source is referenced only by the text-bearing
        // symbol layer, so nothing fetched it during preparation until this compatibility profile
        // started retaining that layer's icon. compileIconLayer -> compileLayerVectorSource ->
        // resolveGeoJson throws ResourceAcquisitionException on the 404 - not a
        // StylePreparationException - so the repair guard used to let it kill the style.
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(
                        200,
                        """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}""".encodeToByteArray(),
                    )
                    ResourceClass.SPRITE_IMAGE -> TransportResponse(200, renderSyntheticPng(8))
                    ResourceClass.GEO_JSON -> TransportResponse(404, ByteArray(0))
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"pois":{"type":"geojson","data":"https://data.example.test/pois.json"}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"poi","type":"symbol","source":"pois","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )

            val excluded = style.diagnostics.single { it.code == DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED }
            // A resource failure reports the typed error code under its own key, never the
            // exception's message: only failRetained's reason strings carry the no-secrets contract
            // that lets a message be folded into a public diagnostic. It must not land in "cause",
            // which always holds a construct sentence, or a consumer grouping on that key would see
            // two vocabularies.
            assertEquals("RESOURCE_ACQUISITION_FAILED", excluded.details["causeCode"])
            assertFalse("cause" in excluded.details)
            assertTrue(style.diagnostics.none { it.severity == DiagnosticSeverity.ERROR })

            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()
            assertTrue(output.pngBytes.startsWithPngSignature())
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun everyLayerThatCannotDrawWithoutASpriteStillFailsPreparationWhenTheSpriteIsUnusable() = runTest {
        // The loud half of the sprite split, which nothing pinned. If
        // layerRequiresSpriteUnconditionally were ever narrowed - to false, or to symbol layers
        // only - every one of these styles would start preparing successfully and pattern fills
        // would silently render unpatterned, with the whole suite still green. The three sprite
        // shapes are exactly the ones resolveOptionalSpriteAtlas tolerates for a merely desiring
        // layer: absent key, style-spec v8 array form, and a relative reference with no base URI.
        // For a layer that cannot draw anything at all without an atlas, all three must still fail
        // preparation. The throwing default transport also proves the failure is decided offline,
        // before any fetch is attempted.
        val vectorSource = """"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"""
        val layersThatCannotDrawWithoutASprite = mapOf(
            "background-pattern" to """{"id":"bg","type":"background","paint":{"background-pattern":"marker"}}""",
            "fill-pattern" to """{"id":"land","type":"fill","source":"v","source-layer":"land","paint":{"fill-pattern":"marker"}}""",
            "line-pattern" to """{"id":"road","type":"line","source":"v","source-layer":"roads","paint":{"line-pattern":"marker"}}""",
            "icon-only-symbol" to """{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker"}}""",
        )
        val unusableSprites = mapOf(
            "absent" to "",
            "array-form" to """"sprite":[{"id":"default","url":"https://sprite.example.test/icons"}],""",
            "relative-with-no-base-uri" to """"sprite":"icons/default",""",
        )
        val rasterizer = testRasterizer()
        try {
            for ((layerName, layerJson) in layersThatCannotDrawWithoutASprite) {
                for ((spriteName, spriteJson) in unusableSprites) {
                    assertFailsWith<StylePreparationException>(
                        "$layerName with a $spriteName sprite must still fail preparation",
                    ) {
                        rasterizer.prepare(
                            StyleInput.InlineJson("""{"version":8,$spriteJson$vectorSource"layers":[$layerJson]}"""),
                        )
                    }
                }
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aRepairedIconLayerSkipsAFeatureWithAMalformedPropertyAndStillRendersTheRest() = runTest {
        // icon-offset is data-driven here. The "good" feature has no "offset" property, so
        // ["get","offset"] evaluates to Null and falls back to its default [0, 0]. The "bad"
        // feature's "offset" is a string, so evaluatedNumberArray throws RasterizationException.
        // This layer is retained only because its text was removed and its icon is independent of
        // that text, so the failure must skip just the bad feature - not fail the whole tile - and
        // the good feature's icon must still draw, at the exact pixel a retained icon promises.
        val mvt = iconOffsetVectorTile()
        val spritePng = renderSyntheticPng(8)
        val recordedDiagnostics = RecordingDiagnosticSink()
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
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
            diagnosticSink = recordedDiagnostics,
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","icon-offset":["get","offset"],"text-field":["get","name"]},"paint":{"icon-color":"#ff0000"}}]}""",
                ),
            )
            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.TEXT_COMPONENT_REMOVED_ICON_RETAINED })

            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()

            assertTrue(recordedDiagnostics.snapshot().any { it.code == DiagnosticCode.ICON_FEATURE_SKIPPED })
            // The good feature sits at the vector tile's exact center, which output pixel (128,
            // 128) of a 256px tile maps to - the same pixel centerPixelColor() reads. A red,
            // fully-opaque icon-color there proves the icon actually drew, not just that
            // rendering did not throw.
            assertColorClose(
                expected = Color.makeARGB(255, 255, 0, 0),
                actual = output.pngBytes.centerPixelColor(),
                tolerance = 1,
            )
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun severalBadFeaturesOnOneRepairedLayerRecordExactlyOneDiagnosticPerTile() = runTest {
        // The diagnostic bound is once per layer per tile, which is what other render-stage
        // diagnostics do. Nothing pinned it: moving the record call inside the catch would make it
        // per-feature and every other test would still pass. Four candidate features, three of
        // them bad, must produce exactly one diagnostic carrying the aggregate counts - and one
        // good feature keeps this on the WARNING side of the escalation below.
        val mvt = iconOffsetVectorTile(goodFeatureCount = 1, badFeatureCount = 3)
        val spritePng = renderSyntheticPng(8)
        val recordedDiagnostics = RecordingDiagnosticSink()
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
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
            diagnosticSink = recordedDiagnostics,
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","icon-offset":["get","offset"],"text-field":["get","name"]},"paint":{"icon-color":"#ff0000"}}]}""",
                ),
            )
            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()

            val skipped = recordedDiagnostics.snapshot().single { it.code == DiagnosticCode.ICON_FEATURE_SKIPPED }
            assertEquals(DiagnosticSeverity.WARNING, skipped.severity)
            assertEquals("4", skipped.details["candidateFeatures"])
            assertEquals("3", skipped.details["skippedFeatures"])
            // The code is cause-neutral, so details must say which cause this was: no missing
            // sprites means all three skips were properties that would not evaluate.
            assertEquals("0", skipped.details["skippedMissingSprite"])
            // Collapsing the two message branches into one string would otherwise go unnoticed.
            assertEquals(
                "A repaired icon layer skipped one or more features it could not draw rather than failing the tile",
                skipped.message,
            )
            assertEquals(
                1,
                output.diagnostics.count { it.code == DiagnosticCode.ICON_FEATURE_SKIPPED },
            )
            // The one good feature still drew, so the escalation below is genuinely about layers
            // that lost everything rather than layers that lost most of it.
            assertColorClose(
                expected = Color.makeARGB(255, 255, 0, 0),
                actual = output.pngBytes.centerPixelColor(),
                tolerance = 1,
            )
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aRepairedIconLayerThatDrawsNoneOfItsFeaturesReportsTheWholeLayerLoss() = runTest {
        // icon-halo-width: -1 is a style-level constant that nothing validates at prepare time, so
        // every feature throws identically: the layer draws nothing at all while prepare() reports
        // success. That whole-layer loss is reported distinctly from one bad feature - equal
        // skipped and candidate counts in details - but still as a WARNING, because in Rentile an
        // ERROR diagnostic means the operation failed and nothing failed here: preparation
        // succeeded and the tile rendered and is returned. It must not fail the tile either, since
        // these layers were not drawn at all before this profile retained them. No diagnosticSink
        // is configured here on purpose: DiagnosticSink.None is the default, so the
        // always-available RenderedTile.diagnostics list is the only place a caller can see this,
        // and routing it to the sink alone would make it unobservable.
        val mvt = iconOffsetVectorTile()
        val spritePng = renderSyntheticPng(8)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
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
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]},"paint":{"icon-halo-width":-1}}]}""",
                ),
            )
            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.TEXT_COMPONENT_REMOVED_ICON_RETAINED })
            assertTrue(style.diagnostics.none { it.severity == DiagnosticSeverity.ERROR })

            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()

            assertTrue(output.pngBytes.startsWithPngSignature())
            val skipped = output.diagnostics.single { it.code == DiagnosticCode.ICON_FEATURE_SKIPPED }
            assertEquals(DiagnosticSeverity.WARNING, skipped.severity)
            assertEquals(PipelineStage.RASTERIZATION, skipped.stage)
            // Equal counts are the machine-readable signal that the layer lost everything, which
            // is what distinguishes this case now that severity does not.
            assertEquals(skipped.details["candidateFeatures"], skipped.details["skippedFeatures"])
            assertEquals("2", skipped.details["candidateFeatures"])
            assertEquals("0", skipped.details["skippedMissingSprite"])
            // Factual about this tile only: everyCandidateSkipped is a per-tile fact, and the same
            // layer can draw fine on the next tile, so the message must not diagnose the style.
            assertEquals(
                "A repaired icon layer drew none of the features that wanted an icon on this tile",
                skipped.message,
            )
            // No render-stage diagnostic may claim the operation failed when it did not.
            assertTrue(output.diagnostics.none { it.severity == DiagnosticSeverity.ERROR })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aRepairedIconLayerWhoseSpriteNameIsMissingFromTheAtlasReportsTheWholeLayerLoss() = runTest {
        // Every feature names an icon the atlas does not contain, so nothing draws. Counting
        // candidates only after the atlas lookup used to report zero candidates, zero skips and no
        // diagnostic at all - a whole-layer loss that showed up as nothing. skippedMissingSprite
        // keeps this distinguishable from a property that would not evaluate.
        val mvt = iconOffsetVectorTile()
        val spritePng = renderSyntheticPng(8)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
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
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"not-in-atlas","text-field":["get","name"]}}]}""",
                ),
            )

            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()

            assertTrue(output.pngBytes.startsWithPngSignature())
            val skipped = output.diagnostics.single { it.code == DiagnosticCode.ICON_FEATURE_SKIPPED }
            assertEquals("2", skipped.details["candidateFeatures"])
            assertEquals("2", skipped.details["skippedFeatures"])
            assertEquals("2", skipped.details["skippedMissingSprite"])
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun anIconOnlyLayerWithTheSameMalformedPropertyStillFailsRendering() = runTest {
        // The same "bad" offset property, but on a layer with no text at all - author-intended as
        // an icon layer. Unlike the repaired case above, this must still fail the tile exactly as
        // before this change: retainedIndependentOfText is false, so the render-time catch must
        // not apply here.
        val mvt = iconOffsetVectorTile()
        val spritePng = renderSyntheticPng(8)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
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
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","icon-offset":["get","offset"]}}]}""",
                ),
            )

            assertFailsWith<RasterizationException> {
                rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256))
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aPoiSourceReachableOnlyThroughRepairedIconLayersIsBestEffortWhenItFails() = runTest {
        // The shape this branch exists to help: a raster basemap plus a separately-sourced vector
        // POI tileset whose symbol layers all carry text-field. On main that tileset was fetched
        // never - no fill, line or author-intended icon layer referenced it - so restoring the
        // icons put it in the render-time fetch set for the first time. One 404 on an empty POI
        // tile, the commonest tile-server behaviour there is, then failed the whole batch, because
        // TileSubstitutionPolicy.Disabled is the default and makes any acquisition failure fatal
        // at planning time. The tile must render without its icons instead.
        val spritePng = renderSyntheticPng(8)
        val basemapPng = renderSyntheticPng(256)
        val recordedDiagnostics = RecordingDiagnosticSink()
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(
                        200,
                        """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}""".encodeToByteArray(),
                    )
                    ResourceClass.SPRITE_IMAGE -> TransportResponse(200, spritePng)
                    ResourceClass.RASTER_TILE -> TransportResponse(200, basemapPng)
                    ResourceClass.VECTOR_TILE -> TransportResponse(404, ByteArray(0))
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
            diagnosticSink = recordedDiagnostics,
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"base":{"type":"raster","tiles":["https://tiles.example.test/{z}/{x}/{y}.png"],"tileSize":256},"pois":{"type":"vector","tiles":["https://pois.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"raster","source":"base"},{"id":"poi","type":"symbol","source":"pois","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )
            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.TEXT_COMPONENT_REMOVED_ICON_RETAINED })

            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()

            assertTrue(output.pngBytes.startsWithPngSignature())
            val skipped = output.diagnostics.single { it.code == DiagnosticCode.ICON_LAYER_SKIPPED_SOURCE_UNAVAILABLE }
            assertEquals(DiagnosticSeverity.WARNING, skipped.severity)
            assertEquals(PipelineStage.RESOURCE_ACQUISITION, skipped.stage)
            assertEquals("404", skipped.details["statusCode"])
            assertEquals("RESOURCE_ACQUISITION_FAILED", skipped.details["causeCode"])
            // Also reaches a configured sink, and never burns the substitution budget on the way.
            assertTrue(
                recordedDiagnostics.snapshot().any { it.code == DiagnosticCode.ICON_LAYER_SKIPPED_SOURCE_UNAVAILABLE },
            )
            assertTrue(output.diagnostics.none { it.code == DiagnosticCode.TILE_RESOURCE_SUBSTITUTED })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aPoiSourceReachableOnlyThroughRepairedIconLayersIsBestEffortInCacheOnlyMode() = runTest {
        // The offline-export case, and the worse one: under CACHE_ONLY every tile failed, because
        // nothing ever warmed a tileset the renderer never used before this branch. The acquirer
        // throws "Vector resource is unavailable in cache-only mode" rather than a transport
        // error, so the fix has to key on which source failed, not on how it failed. The raster
        // basemap is pre-warmed by a NORMAL render first, exactly as an offline export would.
        val spritePng = renderSyntheticPng(8)
        val basemapPng = renderSyntheticPng(256)
        val store = InMemoryRawResourceStore()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    when (request.resourceClass) {
                        ResourceClass.SPRITE_JSON -> TransportResponse(
                            200,
                            """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}""".encodeToByteArray(),
                        )
                        ResourceClass.SPRITE_IMAGE -> TransportResponse(200, spritePng)
                        ResourceClass.RASTER_TILE -> TransportResponse(200, basemapPng)
                        // Warming never stores the POI tileset, which is the whole point.
                        ResourceClass.VECTOR_TILE -> TransportResponse(404, ByteArray(0))
                        else -> error("Unexpected resource class ${request.resourceClass}")
                    }
                },
                rawResourceStore = store,
            ),
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"base":{"type":"raster","tiles":["https://tiles.example.test/{z}/{x}/{y}.png"],"tileSize":256},"pois":{"type":"vector","tiles":["https://pois.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"raster","source":"base"},{"id":"poi","type":"symbol","source":"pois","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )

            // An offline export warms what it can first; the POI tileset 404s and is skipped, so
            // nothing about it ever reaches the store.
            rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256))

            val output = rasterizer.render(
                style,
                listOf(TileId(0, 0, 0)),
                RenderOptions(256),
                ResourceAccessMode.CACHE_ONLY,
            ).tiles.single()

            assertTrue(output.pngBytes.startsWithPngSignature())
            val skipped = output.diagnostics.single { it.code == DiagnosticCode.ICON_LAYER_SKIPPED_SOURCE_UNAVAILABLE }
            assertEquals("RESOURCE_ACQUISITION_FAILED", skipped.details["causeCode"])
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aVectorSourceSharedWithAFillLayerStillFailsTheBatchOnTheSame404() = runTest {
        // The over-reach guard. The same 404, but the POI tileset also backs a fill layer, so it
        // is not reachable only through repaired icon layers and keeps today's strict behaviour
        // exactly: the batch fails rather than quietly dropping a fill nobody asked to be
        // best-effort.
        val spritePng = renderSyntheticPng(8)
        val basemapPng = renderSyntheticPng(256)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(
                        200,
                        """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}""".encodeToByteArray(),
                    )
                    ResourceClass.SPRITE_IMAGE -> TransportResponse(200, spritePng)
                    ResourceClass.RASTER_TILE -> TransportResponse(200, basemapPng)
                    ResourceClass.VECTOR_TILE -> TransportResponse(404, ByteArray(0))
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"base":{"type":"raster","tiles":["https://tiles.example.test/{z}/{x}/{y}.png"],"tileSize":256},"pois":{"type":"vector","tiles":["https://pois.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"raster","source":"base"},{"id":"land","type":"fill","source":"pois","source-layer":"land","paint":{"fill-color":"#00ff00"}},{"id":"poi","type":"symbol","source":"pois","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )

            assertFailsWith<ResourceAcquisitionException> {
                rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256))
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aSourceRequiredOnlyByALayerInactiveAtThisZoomIsStillBestEffort() = runTest {
        // Source "v" backs a minzoom:14 fill and a repaired POI symbol layer. At z=10 the fill
        // draws nothing and never asks for the tile, so on main nothing fetched "v" at this zoom
        // and the tile rendered. A zoom-agnostic required set counted the fill anyway, so the 404
        // failed the batch - the same invariant leaking, in the direction that fails loudly.
        val spritePng = renderSyntheticPng(8)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(
                        200,
                        """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}""".encodeToByteArray(),
                    )
                    ResourceClass.SPRITE_IMAGE -> TransportResponse(200, spritePng)
                    ResourceClass.VECTOR_TILE -> TransportResponse(404, ByteArray(0))
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://v.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"buildings","type":"fill","source":"v","source-layer":"buildings","minzoom":14,"paint":{"fill-color":"#cccccc"}},{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )

            val output = rasterizer.render(style, listOf(TileId(10, 0, 0)), RenderOptions(256)).tiles.single()

            assertTrue(output.pngBytes.startsWithPngSignature())
            assertTrue(output.diagnostics.any { it.code == DiagnosticCode.ICON_LAYER_SKIPPED_SOURCE_UNAVAILABLE })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aSourceSharedWithAnActiveFillLayerStillFailsAtThatZoom() = runTest {
        // The other side of the same rule: at z=14 the fill is active, so "v" is required again
        // and the identical 404 must still fail the batch. Without this, narrowing the required
        // set to active layers could quietly become "never required".
        val spritePng = renderSyntheticPng(8)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(
                        200,
                        """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}""".encodeToByteArray(),
                    )
                    ResourceClass.SPRITE_IMAGE -> TransportResponse(200, spritePng)
                    ResourceClass.VECTOR_TILE -> TransportResponse(404, ByteArray(0))
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://v.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"buildings","type":"fill","source":"v","source-layer":"buildings","minzoom":14,"paint":{"fill-color":"#cccccc"}},{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )

            assertFailsWith<ResourceAcquisitionException> {
                rasterizer.render(style, listOf(TileId(14, 0, 0)), RenderOptions(256))
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aVectorSourceSharedWithALineOrAnIconOnlyLayerIsNotBestEffort() = runTest {
        // Subtraction is pinned for fill elsewhere; these are the two other vector-sourced layer
        // kinds that must keep a source strict. Both styles 404 identically and both must throw.
        val spritePng = renderSyntheticPng(8)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(
                        200,
                        """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}""".encodeToByteArray(),
                    )
                    ResourceClass.SPRITE_IMAGE -> TransportResponse(200, spritePng)
                    ResourceClass.VECTOR_TILE -> TransportResponse(404, ByteArray(0))
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        val sharedWith = mapOf(
            "line" to """{"id":"roads","type":"line","source":"v","source-layer":"roads","paint":{"line-color":"#888888"}}""",
            "author-intended-icon" to
                """{"id":"shield","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker"}}""",
        )
        try {
            for ((kind, layerJson) in sharedWith) {
                assertFailsWith<ResourceAcquisitionException>("a source shared with a $kind layer must stay strict") {
                    rasterizer.render(
                        rasterizer.prepare(
                            StyleInput.InlineJson(
                                """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://v.example.test/{z}/{x}/{y}.pbf"]}},"layers":[$layerJson,{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                            ),
                        ),
                        listOf(TileId(0, 0, 0)),
                        RenderOptions(256),
                    )
                }
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aBestEffortSkipDoesNotMaskARequiredFailureInTheSameBatch() = runTest {
        // Both vector sources 404 on the same tile. "pois" is reachable only through the repaired
        // icon layer and is skipped; "land" backs a fill and must still fail the batch. The single
        // ResourceAcquisitionException rather than a BatchRenderException is the assertion that
        // matters: it proves exactly one failure survived filtering, so the skip neither masked
        // the required failure nor joined it.
        val spritePng = renderSyntheticPng(8)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(
                        200,
                        """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}""".encodeToByteArray(),
                    )
                    ResourceClass.SPRITE_IMAGE -> TransportResponse(200, spritePng)
                    ResourceClass.VECTOR_TILE -> TransportResponse(404, ByteArray(0))
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"land":{"type":"vector","tiles":["https://land.example.test/{z}/{x}/{y}.pbf"]},"pois":{"type":"vector","tiles":["https://pois.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"land","type":"fill","source":"land","source-layer":"land","paint":{"fill-color":"#00ff00"}},{"id":"poi","type":"symbol","source":"pois","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )

            val error = assertFailsWith<ResourceAcquisitionException> {
                rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256))
            }
            assertEquals(404, error.statusCode)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun twoIconSourcesFailingOnOneTileAreReportedSeparately() = runTest {
        // The diagnostics used to carry nothing identifying the source, so the .distinct() that
        // builds batch state collapsed two identical failures into one and undercounted the loss.
        // Each must name its own source, with redacted digests and no URL.
        val spritePng = renderSyntheticPng(8)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(
                        200,
                        """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}""".encodeToByteArray(),
                    )
                    ResourceClass.SPRITE_IMAGE -> TransportResponse(200, spritePng)
                    ResourceClass.VECTOR_TILE -> TransportResponse(404, ByteArray(0))
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"poisA":{"type":"vector","tiles":["https://a.example.test/{z}/{x}/{y}.pbf"]},"poisB":{"type":"vector","tiles":["https://b.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"poiA","type":"symbol","source":"poisA","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}},{"id":"poiB","type":"symbol","source":"poisB","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )

            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()

            val skipped = output.diagnostics.filter { it.code == DiagnosticCode.ICON_LAYER_SKIPPED_SOURCE_UNAVAILABLE }
            assertEquals(2, skipped.size)
            assertEquals(2, skipped.mapNotNull { it.details["sourceIdDigest"] }.distinct().size)
            assertEquals(2, skipped.mapNotNull { it.details["resourceId"] }.distinct().size)
            assertTrue(skipped.none { diagnostic -> diagnostic.details.values.any { "example.test" in it } })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun retryExactCannotRecoverASkippedIconSourceButKeepsItsDiagnostic() = runTest {
        // A skip leaves no resource entry, and recoverVectorResources only revisits resources
        // carrying a substitution, so retryExact cannot bring the icons back - it must at least
        // not lose the diagnostic when it rebuilds batch state. The raster basemap does substitute
        // and then recovers, which is what gets retryExact past its empty-substitutions guard.
        val spritePng = renderSyntheticPng(8)
        val basemapPng = renderSyntheticPng(256)
        var exactAvailable = false
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(
                        200,
                        """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}""".encodeToByteArray(),
                    )
                    ResourceClass.SPRITE_IMAGE -> TransportResponse(200, spritePng)
                    ResourceClass.VECTOR_TILE -> TransportResponse(404, ByteArray(0))
                    ResourceClass.RASTER_TILE ->
                        if (request.url.contains("/1/0/0.png") && !exactAvailable) {
                            TransportResponse(404, ByteArray(0))
                        } else {
                            TransportResponse(200, basemapPng)
                        }
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"base":{"type":"raster","tiles":["https://tiles.example.test/{z}/{x}/{y}.png"],"tileSize":256},"pois":{"type":"vector","tiles":["https://pois.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"raster","source":"base"},{"id":"poi","type":"symbol","source":"pois","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )
            val tile = TileId(1, 0, 0)
            val batch = rasterizer.prepareBatch(
                style = style,
                tiles = listOf(tile),
                options = RenderOptions(256),
                substitutionPolicy = TileSubstitutionPolicy(maximumSubstitutedTiles = 1),
            )
            try {
                // The skipped POI source never counted against the budget of one, which the raster
                // substitution needed in full.
                assertTrue(batch.diagnostics.any { it.code == DiagnosticCode.ICON_LAYER_SKIPPED_SOURCE_UNAVAILABLE })
                exactAvailable = true

                val recovery = rasterizer.retryExact(batch)

                assertEquals(setOf(tile), recovery.upgradedTiles)
                // Rebuilt state, diagnostic still there - and still there, not recovered.
                assertTrue(batch.diagnostics.any { it.code == DiagnosticCode.ICON_LAYER_SKIPPED_SOURCE_UNAVAILABLE })
                val output = rasterizer.render(batch).tiles.single()
                assertTrue(output.diagnostics.any { it.code == DiagnosticCode.ICON_LAYER_SKIPPED_SOURCE_UNAVAILABLE })
            } finally {
                batch.close()
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun oneBatchSpanningTwoZoomsClassifiesEachTileAtItsOwnZoom() = runTest {
        // Every other multi-tile batch in this suite is single-zoom, and the zoom tests above use
        // single-tile batches, so nothing caught a lookup hoisted out of the per-tile loop -
        // flattening every zoom, or keying on the batch's first zoom, passed the whole suite.
        //
        // Source "v" backs a minzoom:14 fill and a repaired POI symbol layer, and 404s for both
        // tiles in one batch. At z=14 the fill is active, so "v" is required and that failure must
        // surface; at z=10 nothing else wants it, so that failure must degrade. Exactly one
        // failure therefore survives filtering, which is why this throws a plain
        // ResourceAcquisitionException naming only the z=14 tile: flattening the zooms would leave
        // two failures and raise BatchRenderException instead, and keying on the first zoom would
        // leave none and not throw at all.
        val spritePng = renderSyntheticPng(8)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.SPRITE_JSON -> TransportResponse(
                        200,
                        """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}""".encodeToByteArray(),
                    )
                    ResourceClass.SPRITE_IMAGE -> TransportResponse(200, spritePng)
                    ResourceClass.VECTOR_TILE -> TransportResponse(404, ByteArray(0))
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://v.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"buildings","type":"fill","source":"v","source-layer":"buildings","minzoom":14,"paint":{"fill-color":"#cccccc"}},{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )

            val error = assertFailsWith<ResourceAcquisitionException> {
                rasterizer.prepareBatch(
                    style = style,
                    tiles = listOf(TileId(10, 0, 0), TileId(14, 0, 0)),
                    options = RenderOptions(256),
                )
            }

            // Only the zoom whose fill is active failed; the z=10 tile's identical 404 degraded.
            assertEquals(listOf(TileId(14, 0, 0)), error.affectedTiles)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aFailingResourceStoreSurfacesRatherThanDegradingABestEffortSource() = runTest {
        // The narrowed degradable set excludes ResourceStoreException, and nothing pinned that
        // because the only store in this suite never throws - reverting the predicate to the whole
        // RentileException hierarchy passed everything. A cache read that fails is the caller's own
        // store misbehaving, not a tile that is missing, so it must surface rather than hide behind
        // absent icons. Only VECTOR_TILE reads fail here, so the sprite still resolves and the icon
        // layer really is retained and really is best-effort.
        val spritePng = renderSyntheticPng(8)
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    when (request.resourceClass) {
                        ResourceClass.SPRITE_JSON -> TransportResponse(
                            200,
                            """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}""".encodeToByteArray(),
                        )
                        ResourceClass.SPRITE_IMAGE -> TransportResponse(200, spritePng)
                        ResourceClass.VECTOR_TILE -> TransportResponse(200, ByteArray(0))
                        else -> error("Unexpected resource class ${request.resourceClass}")
                    }
                },
                rawResourceStore = FailingReadRawResourceStore(ResourceClass.VECTOR_TILE),
            ),
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"pois":{"type":"vector","tiles":["https://pois.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"poi","type":"symbol","source":"pois","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )
            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.TEXT_COMPONENT_REMOVED_ICON_RETAINED })

            assertFailsWith<ResourceStoreException> {
                rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256))
            }
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

    /**
     * `tileSize` declares the source's tile grid, not the byte size of any one served image, so
     * a source may legitimately serve an image smaller than its declared tile size and Rentile
     * must scale it into the requested output tile. Pinning this keeps a caller from "fixing" a
     * small fixture image by shrinking the declared tile size out of the compatibility profile.
     */
    @Test
    fun aDeclaredRasterTileSizeDoesNotHaveToMatchTheServedImageSize() = runTest {
        val sourcePng = renderSyntheticPng(8)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { TransportResponse(200, sourcePng) },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sources":{"imagery":{"type":"raster","tiles":["https://tiles.example.test/{z}/{x}/{y}.png"],"tileSize":256}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#1f3f5f"}},{"id":"imagery","type":"raster","source":"imagery"}]}""",
                ),
            )
            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256)).tiles.single()

            assertTrue(output.pngBytes.startsWithPngSignature())
            assertEquals(256, output.pngBytes.pngWidth())
            assertEquals(256, output.pngBytes.pngHeight())
            assertColorClose(
                expected = Color.makeARGB(255, 22, 76, 60),
                actual = output.pngBytes.centerPixelColor(),
                tolerance = 1,
            )
            // The served image is not already the requested output size, so the drawn result is a
            // real composite rather than the pass-through of ADR 0009.
            assertTrue(output.diagnostics.none { it.code == DiagnosticCode.RASTER_PASSTHROUGH_USED })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aRasterTileSizeOutsideTheCompatibilityProfileFailsPreparationWithAPreciseDiagnostic() = runTest {
        val rasterizer = testRasterizer()
        try {
            val error = assertFailsWith<StylePreparationException> {
                rasterizer.prepare(
                    StyleInput.InlineJson(
                        """{"version":8,"sources":{"imagery":{"type":"raster","tiles":["https://tiles.example.test/{z}/{x}/{y}.png"],"tileSize":8}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#1f3f5f"}},{"id":"imagery","type":"raster","source":"imagery"}]}""",
                    ),
                )
            }

            assertEquals(RentileErrorCode.STYLE_PREPARATION_FAILED, error.code)
            val diagnostic = error.diagnostics.single { it.severity == DiagnosticSeverity.ERROR }
            assertEquals(DiagnosticCode.UNSUPPORTED_RETAINED_CONSTRUCT, diagnostic.code)
            assertEquals(PipelineStage.STYLE_PREPARATION, diagnostic.stage)
            assertEquals("Raster source tile size is outside the compatibility profile", diagnostic.message)
            // The diagnostic names the offending raster layer, not the style as a whole.
            assertEquals("1", diagnostic.details["layerIndex"])
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
    fun substitutionBudgetCountsFailedOutputTilesAcrossResources() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                if (request.url.contains("/2/0/0.png") || request.url.contains("/2/1/0.png")) {
                    TransportResponse(404, ByteArray(0))
                } else {
                    TransportResponse(200, sourcePng)
                }
            },
        )
        try {
            val style = rasterizer.prepare(rasterStyle("secret"))

            val error = assertFailsWith<TileSubstitutionLimitException> {
                rasterizer.prepareBatch(
                    style = style,
                    tiles = listOf(TileId(2, 0, 0), TileId(2, 1, 0), TileId(2, 2, 0)),
                    options = RenderOptions(256),
                    substitutionPolicy = TileSubstitutionPolicy(maximumSubstitutedTiles = 1),
                )
            }

            assertEquals(1, error.maximumSubstitutedTiles)
            assertEquals(2, error.requiredSubstitutedTiles)
            assertEquals(setOf(TileId(2, 0, 0), TileId(2, 1, 0)), error.affectedTiles.toSet())
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun substitutionPrefersAllImmediateChildren() = runTest {
        val sourcePng = renderSyntheticPng(256)
        // substituteRaster acquires all four immediate children in one awaitAll, so this lambda
        // runs four times at once.
        val requestedUrls = mutableListOf<String>()
        val requestedUrlsMutex = Mutex()
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                requestedUrlsMutex.withLock { requestedUrls += request.url }
                when {
                    request.url.contains("/1/0/0.png") -> TransportResponse(404, ByteArray(0))
                    else -> TransportResponse(200, sourcePng)
                }
            },
        )
        try {
            val style = rasterizer.prepare(rasterStyle("secret"))
            val tile = TileId(1, 0, 0)
            val batch = rasterizer.prepareBatch(
                style = style,
                tiles = listOf(tile),
                options = RenderOptions(256),
                substitutionPolicy = TileSubstitutionPolicy(maximumSubstitutedTiles = 1),
            )
            try {
                val substitution = batch.substitutions.getValue(tile).single()
                val rendered = rasterizer.render(batch).tiles.single()

                assertEquals(TileSubstitutionStrategy.IMMEDIATE_CHILDREN, substitution.strategy)
                assertEquals(
                    setOf(TileId(2, 0, 0), TileId(2, 1, 0), TileId(2, 0, 1), TileId(2, 1, 1)),
                    substitution.sourceTiles.toSet(),
                )
                assertTrue(requestedUrlsMutex.withLock { requestedUrls.none { url -> url.contains("/0/0/0.png") } })
                assertTrue(rendered.pngBytes.startsWithPngSignature())
            } finally {
                batch.close()
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun vectorChildSubstitutionMergesAllFourResourcesIntoOneRenderableTile() = runTest {
        val vectorTile = overzoomVectorTile()
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                if (request.url.contains("/1/0/0.pbf")) {
                    TransportResponse(404, ByteArray(0))
                } else {
                    TransportResponse(200, vectorTile)
                }
            },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"land","type":"fill","source":"v","source-layer":"land","paint":{"fill-color":"#00ff00"}}]}""",
                ),
            )
            val tile = TileId(1, 0, 0)
            val batch = rasterizer.prepareBatch(
                style = style,
                tiles = listOf(tile),
                options = RenderOptions(256),
                substitutionPolicy = TileSubstitutionPolicy(maximumSubstitutedTiles = 1),
            )
            try {
                val substitution = batch.substitutions.getValue(tile).single()
                val rendered = rasterizer.render(batch).tiles.single()

                assertEquals(ResourceClass.VECTOR_TILE, substitution.resourceClass)
                assertEquals(TileSubstitutionStrategy.IMMEDIATE_CHILDREN, substitution.strategy)
                assertEquals(4, substitution.sourceTiles.size)
                assertTrue(rendered.pngBytes.startsWithPngSignature())
            } finally {
                batch.close()
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun preparedStyleExposesEvaluatedGroundRadianceWithoutStyleSecrets() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"lights":[{"id":"ambient","type":"ambient","properties":{"color":"#804020","intensity":0.25}},{"id":"sun","type":"directional","properties":{"color":"#2060c0","intensity":0.75,"direction":[200.0,40.0]}}],"layers":[]}""",
                ),
            )

            val light = checkNotNull(rasterizer.groundRadianceDescriptor(style))

            assertEquals(0.28015833334292367, light.red, 1e-15)
            assertEquals(0.3152992357932026, light.green, 1e-15)
            assertEquals(0.5875155021576477, light.blue, 1e-15)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun preparedStyleExposesValidatedLabelAndTerrainResourcesWithoutTemplates() = runTest {
        val vectorTile = overzoomVectorTile()
        val demTile = renderTerrainDemPng(256)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.VECTOR_TILE -> TransportResponse(200, vectorTile)
                    ResourceClass.DEM_TILE -> TransportResponse(200, demTile)
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sources":{"private-source":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf?key=private"],"maxzoom":14},"dem":{"type":"raster-dem","tiles":["https://tiles.example.test/{z}/{x}/{y}.png?key=private"],"tileSize":256,"maxzoom":12,"encoding":"terrarium"}},"terrain":{"source":"dem"},"layers":[{"id":"places","type":"symbol","source":"private-source","source-layer":"place","metadata":{"token":"private"},"layout":{"text-field":["coalesce",["get","name:en"],["get","name"]],"text-size":16}}]}""",
                ),
            )
            val requested = TileId(3, 2, 1)

            val descriptor = rasterizer.labelLayerDescriptors(style).single()
            val terrain = rasterizer.terrainSourceDescriptor(style)!!
            val labelTile = rasterizer.acquireLabelTiles(style, listOf(requested)).single()
            val dem = rasterizer.acquireTerrainTiles(style, listOf(requested)).single()

            assertEquals("places", descriptor.id)
            assertEquals("place", descriptor.sourceLayer)
            assertEquals(14, descriptor.sourceMaximumZoom)
            assertFalse(descriptor.layerJson.contains("private"))
            assertEquals(TerrainDemEncoding.TERRARIUM, terrain.encoding)
            assertEquals(12, terrain.maximumZoom)
            assertEquals(256, terrain.tileSizePx)
            assertEquals(requested, labelTile.requestedTile)
            assertEquals(requested, labelTile.sourceTile)
            assertTrue(labelTile.bytes.contentEquals(vectorTile))
            assertEquals(TerrainDemEncoding.TERRARIUM, dem.encoding)
            assertTrue(dem.bytes.contentEquals(demTile))
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun preparationResolvesTheGlyphsTemplateAndKeepsTheLabelDescriptor() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"glyphs":"https://glyphs.example.test/{fontstack}/{range}.pbf?key=secret","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"],"maxzoom":14}},"layers":[{"id":"places","type":"symbol","source":"v","source-layer":"place","layout":{"text-field":["get","name"],"text-font":["Open Sans Regular"],"text-size":14}}]}""",
                ),
            ) as CompiledPreparedStyle

            val descriptors = rasterizer.labelLayerDescriptors(style)
            assertEquals(1, descriptors.size)
            assertEquals("place", descriptors.single().sourceLayer)

            // Actually observe the resolved template, so this fails if glyphs resolution were
            // ever removed or short-circuited to null - the descriptor assertions above pass
            // whether or not this code exists at all, since they only exercise pre-task
            // behaviour.
            assertEquals(
                "https://glyphs.example.test/{fontstack}/{range}.pbf?key=secret",
                style.glyphsTemplate,
            )

            // glyphsTemplate deliberately retains the credential - mirroring the sprite path -
            // because Task 10 needs the real key to fetch glyph ranges later. What must never
            // happen is that credential leaking into anything this style exposes to a consumer
            // who never asks for labels. Diagnostics is one such surface:
            assertTrue(style.diagnostics.none { it.details.values.any { value -> value.contains("secret") } })

            // digest is the other, and it is public (PreparedStyle.digest). It is computed from
            // the whole style JSON, which contains the glyphs URL, so a second style differing
            // only in the glyphs credential must hash identically - proving redactedForIdentity()
            // strips the credential before it reaches the digest input, rather than merely
            // burying it inside a hash that would look the same regardless.
            val styleWithDifferentGlyphsKey = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"glyphs":"https://glyphs.example.test/{fontstack}/{range}.pbf?key=another-secret","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"],"maxzoom":14}},"layers":[{"id":"places","type":"symbol","source":"v","source-layer":"place","layout":{"text-field":["get","name"],"text-font":["Open Sans Regular"],"text-size":14}}]}""",
                ),
            )
            assertEquals(style.digest, styleWithDifferentGlyphsKey.digest)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aPlaceNameLayerWithARejectedFilterKeepsItsDescriptorAndRawMvtAccess() = runTest {
        val vectorTile = overzoomVectorTile()
        val rasterizer = testRasterizer(
            transport = ResourceTransport { TransportResponse(200, vectorTile) },
        )
        try {
            // ["!=", ["get", "class"], "country"] is not legacy-filter-shaped (its second element
            // is an array, not a bare property-name string), so it compiles as an expression -
            // and "!=" is not a supported expression operator (only "==", "<=", ">=" are), so this
            // filter genuinely fails to compile. compileFilter never ran for a label layer before
            // this task; this is the exact regression the review caught: a rejected filter must
            // degrade only the text program, never the raw-MVT descriptor below.
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"],"maxzoom":14}},"layers":[{"id":"places","type":"symbol","source":"v","source-layer":"place","filter":["!=",["get","class"],"country"],"layout":{"text-field":["get","name"]}}]}""",
                ),
            )

            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.UNSUPPORTED_TEXT_CONSTRUCT })

            val descriptors = rasterizer.labelLayerDescriptors(style)
            assertEquals(1, descriptors.size)
            assertEquals("places", descriptors.single().id)

            // Not just the descriptor - acquireLabelTiles, the pre-existing, non-opted-in raw-MVT
            // escape hatch, must keep serving this layer's source too.
            val labelTile = rasterizer.acquireLabelTiles(style, listOf(TileId(2, 1, 1))).single()
            assertTrue(labelTile.bytes.contentEquals(vectorTile))
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aLinePlacedPlaceLayerIsExcludedWithADiagnostic() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"glyphs":"https://glyphs.example.test/{fontstack}/{range}.pbf","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"],"maxzoom":14}},"layers":[{"id":"places","type":"symbol","source":"v","source-layer":"place","layout":{"text-field":["get","name"],"symbol-placement":"line"}}]}""",
                ),
            ) as CompiledPreparedStyle

            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.LINE_PLACEMENT_LABEL_EXCLUDED })
            // The text program is excluded (no label candidates), but the descriptor still ships
            // unconditionally - this layer's raw-MVT access must be exactly as unaffected as any
            // other construct this compiler declines to compile.
            assertEquals(1, rasterizer.labelLayerDescriptors(style).size)
            assertEquals(null, style.labelLayers.single().textProgram)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aTextFontGetExpressionIsCompiledAsAnExpressionNotALiteralFontStack() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"places","type":"symbol","source":"v","source-layer":"place","layout":{"text-field":["get","name"],"text-font":["get","fontProperty"]}}]}""",
                ),
            ) as CompiledPreparedStyle
            val font = style.labelLayers.single().textProgram!!.font

            // If ["get", "fontProperty"] were misread as a literal two-entry font stack (it is
            // exactly as all-strings-shaped as one), this would always evaluate to
            // ["get", "fontProperty"] regardless of feature properties. Evaluating it against a
            // feature that actually carries "fontProperty" proves it is a real property lookup.
            val resolvedFont = font.evaluate(
                StyleEvaluationContext(
                    zoom = 10.0,
                    properties = mapOf(
                        "fontProperty" to StyleValue.ArrayValue(listOf(StyleValue.StringValue("Noto Sans Bold"))),
                    ),
                ),
            )

            assertEquals(StyleValue.ArrayValue(listOf(StyleValue.StringValue("Noto Sans Bold"))), resolvedFont)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun preparedStyleExposesEveryPlaceNameSourceLayerAcrossBothTileSchemas() = runTest {
        val vectorTile = overzoomVectorTile()
        val rasterizer = testRasterizer(
            transport = ResourceTransport { TransportResponse(200, vectorTile) },
        )
        try {
            // MapTiler Planet v4 splits v3's single `place` layer into one layer per class
            // family; a v4 style therefore names several source-layers, and the POI and road
            // label layers beside them must stay out of the place-name closure.
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sources":{"v4":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"],"maxzoom":14}},"layers":[""" +
                        """{"id":"Continent labels","type":"symbol","source":"v4","source-layer":"continent_label","layout":{"text-field":["get","name"]}},""" +
                        """{"id":"Country labels","type":"symbol","source":"v4","source-layer":"country_label","layout":{"text-field":["get","name"]}},""" +
                        """{"id":"Disputed country labels","type":"symbol","source":"v4","source-layer":"country_disputed_label","layout":{"text-field":["get","name"]}},""" +
                        """{"id":"State labels","type":"symbol","source":"v4","source-layer":"state_label","layout":{"text-field":["get","name"]}},""" +
                        """{"id":"City labels","type":"symbol","source":"v4","source-layer":"city_label","layout":{"text-field":["get","name"]}},""" +
                        """{"id":"Town labels","type":"symbol","source":"v4","source-layer":"town_label","layout":{"text-field":["get","name"]}},""" +
                        """{"id":"Place labels","type":"symbol","source":"v4","source-layer":"place_label","layout":{"text-field":["get","name"]}},""" +
                        """{"id":"Island labels","type":"symbol","source":"v4","source-layer":"island_label","layout":{"text-field":["get","name"]}},""" +
                        """{"id":"Archipelago labels","type":"symbol","source":"v4","source-layer":"archipelago_label","layout":{"text-field":["get","name"]}},""" +
                        """{"id":"Road labels","type":"symbol","source":"v4","source-layer":"road_label","layout":{"text-field":["get","name"]}},""" +
                        """{"id":"Water labels","type":"symbol","source":"v4","source-layer":"water_label","layout":{"text-field":["get","name"]}},""" +
                        """{"id":"Points of interest","type":"symbol","source":"v4","source-layer":"poi","layout":{"text-field":["get","name"]}}""" +
                        """]}""",
                ),
            )

            val descriptors = rasterizer.labelLayerDescriptors(style)

            assertEquals(
                listOf(
                    "continent_label",
                    "country_label",
                    "country_disputed_label",
                    "state_label",
                    "city_label",
                    "town_label",
                    "place_label",
                    "island_label",
                    "archipelago_label",
                ),
                descriptors.map(LabelLayerDescriptor::sourceLayer),
            )
            // Style order is preserved so the host can keep using it as draw/priority order.
            assertEquals("Continent labels", descriptors.first().id)
            assertEquals("Archipelago labels", descriptors.last().id)
            // Every descriptor names the layer it must decode, not a schema-wide assumption.
            descriptors.forEach { descriptor ->
                assertTrue(descriptor.layerJson.contains(descriptor.sourceLayer))
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun auxiliaryLabelAcquisitionIsAllOrError() = runTest {
        val rasterizer = testRasterizer(
            transport = ResourceTransport { TransportResponse(404, ByteArray(0)) },
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"places","type":"symbol","source":"v","source-layer":"place","layout":{"text-field":"{name}"}}]}""",
                ),
            )

            val error = assertFailsWith<ResourceAcquisitionException> {
                rasterizer.acquireLabelTiles(style, listOf(TileId(2, 1, 1)))
            }

            assertEquals(ResourceClass.VECTOR_TILE, error.resourceClass)
            assertEquals(404, error.statusCode)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun failedChildSetFallsBackToNearestAncestor() = runTest {
        val sourcePng = renderSyntheticPng(256)
        // Same concurrent child fan-out in substituteRaster before the ancestor fallback runs.
        val requestedUrls = mutableListOf<String>()
        val requestedUrlsMutex = Mutex()
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                requestedUrlsMutex.withLock { requestedUrls += request.url }
                when {
                    request.url.contains("/1/0/0.png") -> TransportResponse(404, ByteArray(0))
                    request.url.contains("/2/1/1.png") -> TransportResponse(404, ByteArray(0))
                    else -> TransportResponse(200, sourcePng)
                }
            },
        )
        try {
            val style = rasterizer.prepare(rasterStyle("secret"))
            val tile = TileId(1, 0, 0)
            val batch = rasterizer.prepareBatch(
                style = style,
                tiles = listOf(tile),
                options = RenderOptions(256),
                substitutionPolicy = TileSubstitutionPolicy(maximumSubstitutedTiles = 1),
            )
            try {
                val substitution = batch.substitutions.getValue(tile).single()

                assertEquals(TileSubstitutionStrategy.ANCESTOR, substitution.strategy)
                assertEquals(1, substitution.ancestorZoomDistance)
                assertEquals(listOf(TileId(0, 0, 0)), substitution.sourceTiles)
                val urls = requestedUrlsMutex.withLock { requestedUrls.toList() }
                assertTrue(urls.any { it.contains("/2/1/1.png") })
                assertTrue(urls.any { it.contains("/0/0/0.png") })
            } finally {
                batch.close()
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun unavailableChildrenAndAncestorsFailWithSpecificSubstitutionError() = runTest {
        val rasterizer = testRasterizer(
            transport = ResourceTransport { TransportResponse(404, ByteArray(0)) },
        )
        try {
            val style = rasterizer.prepare(rasterStyle("secret"))
            val tile = TileId(1, 0, 0)

            val error = assertFailsWith<TileSubstitutionException> {
                rasterizer.prepareBatch(
                    style = style,
                    tiles = listOf(tile),
                    options = RenderOptions(256),
                    substitutionPolicy = TileSubstitutionPolicy(maximumSubstitutedTiles = 1),
                )
            }

            assertEquals(tile, error.tile)
            assertEquals(ResourceClass.RASTER_TILE, error.resourceClass)
            assertEquals(
                listOf(TileSubstitutionStrategy.IMMEDIATE_CHILDREN, TileSubstitutionStrategy.ANCESTOR),
                error.attemptedStrategies,
            )
            assertEquals(5, error.substitutionFailures.size)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun retryExactUpgradesTheExistingBatchWithoutAddingSubstitutions() = runTest {
        val sourcePng = renderSyntheticPng(256)
        var exactAvailable = false
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                if (request.url.contains("/1/0/0.png") && !exactAvailable) {
                    TransportResponse(404, ByteArray(0))
                } else {
                    TransportResponse(200, sourcePng)
                }
            },
        )
        try {
            val style = rasterizer.prepare(rasterStyle("secret"))
            val tile = TileId(1, 0, 0)
            val batch = rasterizer.prepareBatch(
                style = style,
                tiles = listOf(tile),
                options = RenderOptions(256),
                substitutionPolicy = TileSubstitutionPolicy(maximumSubstitutedTiles = 1),
            )
            try {
                val substitutedContentKey = batch.contentKeys.getValue(tile)
                exactAvailable = true

                val recovery = rasterizer.retryExact(batch)

                assertEquals(setOf(tile), recovery.upgradedTiles)
                assertTrue(recovery.remainingSubstitutedTiles.isEmpty())
                assertTrue(batch.substitutions.isEmpty())
                assertNotEquals(substitutedContentKey, batch.contentKeys.getValue(tile))
                assertTrue(rasterizer.render(batch).tiles.single().pngBytes.startsWithPngSignature())
            } finally {
                batch.close()
            }
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
        // Two prepareBatch calls race toward this lambda and single-flight is what stops the
        // second one entering it - which is the assertion. An unguarded counter could lose an
        // increment and report 1 for two real entries, making a single-flight regression look
        // like a pass. The lock is released before the await so it cannot serialise the test.
        var requests = 0
        val requestsMutex = Mutex()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport {
                    requestsMutex.withLock { requests += 1 }
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

            assertEquals(1, requestsMutex.withLock { requests })
        } finally {
            releaseTransport.complete(Unit)
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    private fun testRasterizer(
        transport: ResourceTransport = ResourceTransport { error("Unexpected transport request") },
        diagnosticSink: DiagnosticSink = DiagnosticSink.None,
    ): BasemapRasterizer = Rentile.create(
        RentileConfiguration(
            transport = transport,
            rawResourceStore = InMemoryRawResourceStore(),
            diagnosticSink = diagnosticSink,
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

    /**
     * A single "poi" layer of point features at zoom-0 tile coordinates. A "good" feature has no
     * "offset" property, so a data-driven icon-offset expression evaluates to Null and falls back
     * to its default. A "bad" feature declares "offset" as a string, so the same expression
     * evaluates to a value that is not a numeric array and cannot be an icon-offset. The first
     * feature emitted sits at the tile's exact center, which centerPixelColor() reads.
     */
    private fun iconOffsetVectorTile(goodFeatureCount: Int = 1, badFeatureCount: Int = 1): ByteArray {
        val positions = listOf(
            2048 to 2048,
            1024 to 1024,
            3072 to 3072,
            1024 to 3072,
            3072 to 1024,
        )
        val features = buildList {
            repeat(goodFeatureCount) { index ->
                val (x, y) = positions[size]
                add(
                    Tile.Feature(
                        type = Tile.GeomType.POINT,
                        geometry = listOf(command(1, 1), zigZag(x), zigZag(y)),
                    ),
                )
            }
            repeat(badFeatureCount) { index ->
                val (x, y) = positions[size]
                add(
                    Tile.Feature(
                        tags = listOf(0, 0),
                        type = Tile.GeomType.POINT,
                        geometry = listOf(command(1, 1), zigZag(x), zigZag(y)),
                    ),
                )
            }
        }
        return Tile.ADAPTER.encode(
            Tile(
                layers = listOf(
                    Tile.Layer(
                        version = 2,
                        name = "poi",
                        features = features,
                        keys = listOf("offset"),
                        values = listOf(Tile.Value(string_value = "not-an-array")),
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

/**
 * Collects what a [DiagnosticSink] is given, safely.
 *
 * Rentile may record diagnostics from several coroutines at once - `prepareBatch` runs the raster
 * and vector acquisition plans concurrently and each acquirer records its own cache diagnostics -
 * so appending to a plain list from the sink lambda can lose entries. [DiagnosticSink.record] is
 * not a suspend function, so the Mutex the transport lambdas use cannot guard it; this mirrors
 * SecretContext's compare-and-set accumulation instead, which exists for the same reason.
 */
@OptIn(ExperimentalAtomicApi::class)
private class RecordingDiagnosticSink : DiagnosticSink {
    private val recorded = AtomicReference<List<RenderDiagnostic>>(emptyList())

    override fun record(diagnostic: RenderDiagnostic) {
        while (true) {
            val current = recorded.load()
            if (recorded.compareAndSet(current, current + diagnostic)) return
        }
    }

    fun snapshot(): List<RenderDiagnostic> = recorded.load()
}

/** Fails every read for one resource class, so the acquirer raises ResourceStoreException. */
private class FailingReadRawResourceStore(private val failingClass: ResourceClass) : RawResourceStore {
    private val delegate = InMemoryRawResourceStore()

    override suspend fun read(key: RawResourceKey): StoredRawResource? {
        if (key.resourceClass == failingClass) error("Simulated raw cache read failure")
        return delegate.read(key)
    }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) = delegate.write(key, resource)

    override suspend fun remove(key: RawResourceKey) = delegate.remove(key)
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
