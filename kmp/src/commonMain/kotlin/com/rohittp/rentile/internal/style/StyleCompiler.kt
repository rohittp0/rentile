package com.rohittp.rentile.internal.style

import com.rohittp.rentile.CompatibilityPolicy
import com.rohittp.rentile.DiagnosticCode
import com.rohittp.rentile.DiagnosticSeverity
import com.rohittp.rentile.LabelLayerDescriptor
import com.rohittp.rentile.PipelineStage
import com.rohittp.rentile.RenderDiagnostic
import com.rohittp.rentile.ResourceClass
import com.rohittp.rentile.StylePreparationException
import com.rohittp.rentile.internal.canonicalJson
import com.rohittp.rentile.internal.redactedForIdentity
import com.rohittp.rentile.internal.sha256Hex
import com.rohittp.rentile.internal.SecretContext
import com.rohittp.rentile.internal.withRedactedAuthenticationQuery
import com.rohittp.rentile.internal.metadata.ResolvedTileJson
import com.rohittp.rentile.internal.metadata.resolveHttpReference
import com.rohittp.rentile.internal.sprite.CompiledSpriteAtlas
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

private data class SymbolClassification(
    val retained: Boolean,
    val diagnostic: RenderDiagnostic?,
    /**
     * True only when [retained] is true because the layer's text was removed and its icon is
     * retained independently of that text ([retainsIconIndependentOfText]). This is the sole
     * signal the main compile loop uses to decide whether an icon layer is newly reachable and
     * therefore needs the compile-then-fall-back-to-excluded handling: an explicit field rather
     * than an inferred comparison against [diagnostic]'s code, so a future retained-with-diagnostic
     * outcome cannot silently fall through to the unguarded (fail-loudly) path.
     */
    val retainedIndependentOfText: Boolean = false,
)

internal class StyleCompiler(
    private val owner: Any,
    private val resolveTileJson: suspend (String) -> ResolvedTileJson,
    private val resolveSprite: suspend (String) -> CompiledSpriteAtlas,
    private val resolveGeoJson: suspend (String) -> CompiledGeoJsonData,
) {
    private val json = Json {
        isLenient = false
        allowTrailingComma = false
    }

    suspend fun compile(bytes: ByteArray, policy: CompatibilityPolicy, baseUri: String?): CompiledPreparedStyle {
        val secretContext = SecretContext()
        return try {
            compileWithSecretContext(bytes, policy, baseUri, secretContext)
        } catch (error: Throwable) {
            secretContext.clear()
            throw error
        }
    }

    private suspend fun compileWithSecretContext(
        bytes: ByteArray,
        policy: CompatibilityPolicy,
        baseUri: String?,
        secretContext: SecretContext,
    ): CompiledPreparedStyle {
        val root = try {
            json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
                ?: throw StylePreparationException("Style root must be a JSON object")
        } catch (error: StylePreparationException) {
            throw error
        } catch (_: SerializationException) {
            throw StylePreparationException("Style JSON is malformed")
        } catch (_: IllegalArgumentException) {
            throw StylePreparationException("Style JSON is not valid UTF-8 JSON")
        }

        if (root["version"]?.asPrimitive()?.intOrNull != 8) {
            failUnsupported("Style version must be 8")
        }
        if (root["imports"]?.let { it !is JsonArray || it.isNotEmpty() } == true) {
            failUnsupported("Style imports are not supported by this compatibility profile")
        }

        val diagnostics = mutableListOf<RenderDiagnostic>()
        for (rootKey in EXCLUDED_ROOT_KEYS) {
            if (rootKey in root) {
                diagnostics += diagnostic(
                    code = DiagnosticCode.ROOT_BEHAVIOR_EXCLUDED,
                    severity = DiagnosticSeverity.INFO,
                    message = "A root presentation behavior is excluded by the compatibility profile",
                    details = mapOf("rootProperty" to rootKey),
                )
            }
        }

        val layers = root["layers"] as? JsonArray
            ?: throw StylePreparationException("Style layers must be a JSON array")
        val sources = root["sources"]?.let { value ->
            value as? JsonObject ?: throw StylePreparationException("Style sources must be a JSON object")
        } ?: JsonObject(emptyMap())
        // A sprite can be wanted for two different reasons. A background/fill/line pattern, or a
        // symbol layer with no text at all, cannot draw without one: for those, an unresolvable
        // sprite reference must still fail preparation loudly, exactly as always. A symbol layer
        // retained only because its text was removed and its icon is independent of that text is
        // merely hoping for a sprite: that layer did not exist as a retained construct before this
        // compatibility profile grew this feature, so a style that omits, array-forms, or cannot
        // resolve its sprite reference must keep preparing exactly as it did before - those layers
        // simply fall back to a text-coupled exclusion instead (handled per-layer below).
        val spriteAtlas = when {
            layers.any(::layerRequiresSpriteUnconditionally) ->
                resolveRequiredSpriteAtlas(root, baseUri, secretContext)
            layers.any(::layerDesiresSpriteIndependentOfText) ->
                resolveOptionalSpriteAtlas(root, baseUri, secretContext)
            else -> null
        }
        val compiledRasterSources = mutableMapOf<String, CompiledRasterSource>()
        val compiledVectorSources = mutableMapOf<String, CompiledVectorSource>()
        val layerIds = mutableSetOf<String>()
        val drawLayers = mutableListOf<CompiledDrawLayer>()
        val labelLayers = mutableListOf<CompiledLabelLayer>()

        for ((index, element) in layers.withIndex()) {
            val layer = element as? JsonObject
                ?: throw StylePreparationException("Every style layer must be a JSON object")
            val layerId = layer["id"]?.asPrimitive()?.takeIf { it.isString }?.content
                ?: throw StylePreparationException("Every style layer must have a string id")
            if (!layerIds.add(layerId)) {
                throw StylePreparationException("Style layer ids must be unique")
            }
            val type = layer["type"]?.asPrimitive()?.takeIf { it.isString }?.content
                ?: throw StylePreparationException("Every style layer must have a string type")
            validateZoomRange(layer)

            val layout = objectOrEmpty(layer, "layout", index, layerId)
            val visibility = layout["visibility"]?.let { value ->
                value.asPrimitive()?.takeIf { it.isString }?.content
                    ?: failRetained(index, layerId, "visibility must be a string")
            } ?: "visible"
            if (visibility !in setOf("visible", "none")) {
                failRetained(index, layerId, "visibility is invalid")
            }
            val hidden = visibility == "none"
            val identity = mapOf(
                "layerIndex" to index.toString(),
                "layerIdDigest" to layerId.sha256Hex(),
            )

            try {
                if (type == "symbol") {
                    if (isAuxiliaryLabelLayer(layer, layout, hidden, sources)) {
                        val source = compileLayerVectorSource(
                            layer = layer,
                            sources = sources,
                            compiledSources = compiledVectorSources,
                            secretContext = secretContext,
                            baseUri = baseUri,
                            index = index,
                            layerId = layerId,
                        )
                        if (source.geoJson == null) {
                            val sourceLayer = sourceLayerFor(layer, source, index, layerId)
                            labelLayers += CompiledLabelLayer(
                                descriptor = LabelLayerDescriptor(
                                    id = layerId,
                                    sourceId = source.idDigest,
                                    sourceLayer = sourceLayer,
                                    sourceMinimumZoom = source.minZoom,
                                    sourceMaximumZoom = source.maxZoom,
                                    layerJson = sanitizedLabelLayerJson(layer),
                                ),
                                source = source,
                            )
                        }
                    }
                    val classification = classifySymbol(layout, hidden, identity)
                    val retainedIconDiagnostic = classification.diagnostic
                    if (classification.retained && classification.retainedIndependentOfText && retainedIconDiagnostic != null) {
                        // This layer has meaningful text, so classifySymbol used to exclude it
                        // outright and compileIconLayer never ran against it. Now that its icon is
                        // reachable, attempt the compile; if the compatibility profile rejects a
                        // construct in it, fall back to the pre-existing text-coupled exclusion
                        // instead of failing the whole style over a layer that used to be silently
                        // dropped anyway.
                        //
                        // This layer alone (via layerDesiresSpriteIndependentOfText) is also the
                        // only reason an absent, non-primitive, or unresolvable sprite reference
                        // does not fail preparation above: such a style prepared fine before this
                        // layer could ever be retained, so a missing atlas here must fall back to
                        // the same text-coupled exclusion rather than either failing preparation or
                        // silently retaining an icon that has nothing to draw from.
                        if (spriteAtlas == null) {
                            diagnostics += diagnostic(
                                code = DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED,
                                severity = DiagnosticSeverity.INFO,
                                message = "A text-coupled icon layer is excluded because no sprite atlas could be resolved",
                                details = identity,
                            )
                            continue
                        }
                        try {
                            drawLayers += compileIconLayer(
                                layer = layer,
                                sources = sources,
                                compiledSources = compiledVectorSources,
                                secretContext = secretContext,
                                baseUri = baseUri,
                                index = index,
                                layerId = layerId,
                            )
                            diagnostics += retainedIconDiagnostic
                        } catch (error: StylePreparationException) {
                            // The cause is the only signal about what to fix next in the corpus -
                            // it names the exact rejected construct (e.g. "an icon layout property
                            // is unsupported", "viewport-aligned line icons are outside the
                            // compatibility profile") with this layer's identity already attached.
                            // failRetained never puts secrets in that message, so folding it into
                            // details is as safe as the UNSUPPORTED_RETAINED_CONSTRUCT diagnostic
                            // this cause came from.
                            val cause = error.diagnostics.firstOrNull { it.code == DiagnosticCode.UNSUPPORTED_RETAINED_CONSTRUCT }
                                ?: throw error
                            diagnostics += diagnostic(
                                code = DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED,
                                severity = DiagnosticSeverity.INFO,
                                message = "A text-coupled icon layer could not be compiled and is excluded by the compatibility profile",
                                details = identity + ("cause" to cause.message),
                            )
                        }
                        continue
                    }
                    retainedIconDiagnostic?.let(diagnostics::add)
                    if (classification.retained) {
                        drawLayers += compileIconLayer(
                            layer = layer,
                            sources = sources,
                            compiledSources = compiledVectorSources,
                            secretContext = secretContext,
                            baseUri = baseUri,
                            index = index,
                            layerId = layerId,
                        )
                    }
                    continue
                }
                if (hidden) {
                    diagnostics += diagnostic(
                        code = DiagnosticCode.HIDDEN_LAYER_NO_DRAW,
                        severity = DiagnosticSeverity.INFO,
                        message = "A hidden layer is not drawn",
                        details = identity,
                    )
                    continue
                }
                if (type == "fill-extrusion") {
                    diagnostics += diagnostic(
                        code = DiagnosticCode.EXTRUSION_FLATTENED,
                        severity = DiagnosticSeverity.WARNING,
                        message = "A fill extrusion is transformed into a flat footprint",
                        details = identity,
                    )
                    drawLayers += compileFlattenedExtrusionLayer(
                        layer,
                        sources,
                        compiledVectorSources,
                        secretContext,
                        baseUri,
                        index,
                        layerId,
                    )
                    continue
                }
                if (type == "raster") {
                    drawLayers += compileRasterLayer(
                        layer,
                        sources,
                        compiledRasterSources,
                        secretContext,
                        baseUri,
                        index,
                        layerId,
                    )
                    continue
                }
                if (type == "hillshade") {
                    drawLayers += compileHillshadeLayer(
                        layer,
                        sources,
                        compiledRasterSources,
                        secretContext,
                        baseUri,
                        index,
                        layerId,
                    )
                    continue
                }
                if (type == "fill") {
                    drawLayers += compileFillLayer(
                        layer,
                        sources,
                        compiledVectorSources,
                        secretContext,
                        baseUri,
                        index,
                        layerId,
                    )
                    continue
                }
                if (type == "line") {
                    drawLayers += compileLineLayer(
                        layer,
                        sources,
                        compiledVectorSources,
                        secretContext,
                        baseUri,
                        index,
                        layerId,
                    )
                    continue
                }
                if (type != "background") {
                    val construct = if (type in SUPPORTED_LAYER_TYPES) "$type drawing" else "layer type"
                    failRetained(index, layerId, "$construct is not implemented yet")
                }
                drawLayers += BackgroundDrawLayer(
                    background = compileBackground(layer, index, layerId),
                    minZoom = layer["minzoom"]?.asPrimitive()?.doubleOrNull ?: 0.0,
                    maxZoom = layer["maxzoom"]?.asPrimitive()?.doubleOrNull ?: 31.0,
                )
            } catch (error: StylePreparationException) {
                if (error.diagnostics.isNotEmpty()) {
                    diagnostics += error.diagnostics
                } else {
                    diagnostics += diagnostic(
                        code = DiagnosticCode.UNSUPPORTED_RETAINED_CONSTRUCT,
                        severity = DiagnosticSeverity.ERROR,
                        message = error.message ?: "A retained layer is invalid or unsupported",
                        details = identity,
                    )
                }
            }
        }

        if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            throw StylePreparationException(
                message = "One or more reachable retained style constructs are unsupported",
                diagnostics = diagnostics.toList(),
            )
        }

        val terrainSource = compileTerrainSource(
            root = root,
            sources = sources,
            compiledSources = compiledRasterSources,
            secretContext = secretContext,
            baseUri = baseUri,
        )
        val groundRadiance = compileGroundRadiance(root)

        val externalMetadataDigests = (drawLayers.mapNotNull { layer ->
            when (layer) {
                is RasterDrawLayer -> layer.source.metadataDigest
                is HillshadeDrawLayer -> layer.source.metadataDigest
                is FillDrawLayer -> layer.source.metadataDigest
                is LineDrawLayer -> layer.source.metadataDigest
                is IconDrawLayer -> layer.source.metadataDigest
                else -> null
            }
        } + labelLayers.mapNotNull { it.source.metadataDigest } + listOfNotNull(terrainSource?.metadataDigest))
            .distinct().sorted().joinToString("\n")
        val digest = (RENDERER_SEMANTIC_VERSION + "\n" + policy.id + "\n" +
            baseUri?.withRedactedAuthenticationQuery().orEmpty() + "\n" +
            root.redactedForIdentity().canonicalJson() + "\n" + externalMetadataDigests + "\n" +
            spriteAtlas?.contentDigest.orEmpty()).sha256Hex()
        return CompiledPreparedStyle(
            owner = owner,
            digest = digest,
            policy = policy,
            diagnostics = diagnostics.toList(),
            drawLayers = drawLayers.toList(),
            labelLayers = labelLayers.toList(),
            terrainSource = terrainSource,
            groundRadiance = groundRadiance,
            spriteAtlas = spriteAtlas,
            secretContext = secretContext,
        )
    }

    private fun hasMeaningfulText(layout: JsonObject): Boolean {
        val textField = layout["text-field"]
        return textField != null && !(textField is JsonPrimitive && textField.isString && textField.content.isEmpty())
    }

    private fun sanitizedLabelLayerJson(layer: JsonObject): String = JsonObject(
        listOf("id", "type", "source-layer", "minzoom", "maxzoom", "filter", "layout", "paint")
            .mapNotNull { key -> layer[key]?.let { key to it } }
            .toMap(),
    ).canonicalJson()

    private fun isAuxiliaryLabelLayer(
        layer: JsonObject,
        layout: JsonObject,
        hidden: Boolean,
        sources: JsonObject,
    ): Boolean {
        if (hidden || !hasMeaningfulText(layout)) return false
        val sourceLayer = layer["source-layer"]?.asPrimitive()?.takeIf { it.isString }?.content
        if (sourceLayer !in PLACE_NAME_SOURCE_LAYERS) return false
        val sourceId = layer["source"]?.asPrimitive()?.takeIf { it.isString }?.content ?: return false
        val source = sources[sourceId] as? JsonObject ?: return false
        return source["type"]?.asPrimitive()?.takeIf { it.isString }?.content == "vector"
    }

    private suspend fun compileTerrainSource(
        root: JsonObject,
        sources: JsonObject,
        compiledSources: MutableMap<String, CompiledRasterSource>,
        secretContext: SecretContext,
        baseUri: String?,
    ): CompiledRasterSource? {
        val terrain = root["terrain"] ?: return null
        val terrainObject = terrain as? JsonObject
            ?: throw StylePreparationException("Style terrain must be a JSON object")
        val sourceId = terrainObject["source"]?.asPrimitive()?.takeIf { it.isString }?.content
            ?: throw StylePreparationException("Style terrain must name its raster-dem source")
        return compiledSources[sourceId] ?: compileRasterSource(
            sourceId = sourceId,
            sources = sources,
            secretContext = secretContext,
            baseUri = baseUri,
            layerIndex = -1,
            layerId = "terrain",
            expectedType = "raster-dem",
        ).also { compiledSources[sourceId] = it }
    }

    private suspend fun compileFillLayer(
        layer: JsonObject,
        sources: JsonObject,
        compiledSources: MutableMap<String, CompiledVectorSource>,
        secretContext: SecretContext,
        baseUri: String?,
        index: Int,
        layerId: String,
    ): FillDrawLayer {
        validateVectorLayerKeys(layer, index, layerId)
        val layout = objectOrEmpty(layer, "layout", index, layerId)
        if ((layout.keys - setOf("visibility")).isNotEmpty()) {
            failRetained(index, layerId, "a fill layout property is unsupported")
        }
        val paint = objectOrEmpty(layer, "paint", index, layerId)
        val supportedPaint = setOf(
            "fill-antialias",
            "fill-color",
            "fill-opacity",
            "fill-outline-color",
            "fill-pattern",
            "fill-translate",
            "fill-translate-anchor",
        )
        if ((paint.keys - supportedPaint).isNotEmpty()) {
            failRetained(index, layerId, "a fill paint property is not implemented yet")
        }
        val antialias = paint["fill-antialias"]?.let { value ->
            value.asPrimitive()?.booleanOrNull
                ?: failRetained(index, layerId, "fill-antialias must currently be a boolean constant")
        } ?: true
        val color = compileColorProperty(paint["fill-color"] ?: JsonPrimitive("#000000"), index, layerId, "fill-color")
        val opacity = compileProperty(paint["fill-opacity"] ?: JsonPrimitive(1.0), StyleType.NUMBER, index, layerId, "fill-opacity")
        val outline = paint["fill-outline-color"]?.let {
            compileColorProperty(it, index, layerId, "fill-outline-color")
        }
        val pattern = paint["fill-pattern"]?.let {
            compileProperty(it, StyleType.STRING, index, layerId, "fill-pattern")
        }
        val translate = compileProperty(
            paint["fill-translate"] ?: JsonArray(listOf(JsonPrimitive(0.0), JsonPrimitive(0.0))),
            StyleType.ARRAY,
            index,
            layerId,
            "fill-translate",
        )
        val translateAnchor = compileTranslateAnchor(
            paint["fill-translate-anchor"],
            index,
            layerId,
            "fill-translate-anchor",
        )
        val compiledSource = compileLayerVectorSource(
            layer, sources, compiledSources, secretContext, baseUri, index, layerId,
        )
        return FillDrawLayer(
            source = compiledSource,
            sourceLayer = sourceLayerFor(layer, compiledSource, index, layerId),
            filter = compileFilter(layer["filter"], index, layerId),
            antialias = antialias,
            color = color,
            opacity = opacity,
            outlineColor = outline,
            pattern = pattern,
            translate = translate,
            translateAnchor = translateAnchor,
            minZoom = layer["minzoom"]?.asPrimitive()?.doubleOrNull ?: 0.0,
            maxZoom = layer["maxzoom"]?.asPrimitive()?.doubleOrNull ?: 31.0,
        )
    }

    private suspend fun compileFlattenedExtrusionLayer(
        layer: JsonObject,
        sources: JsonObject,
        compiledSources: MutableMap<String, CompiledVectorSource>,
        secretContext: SecretContext,
        baseUri: String?,
        index: Int,
        layerId: String,
    ): FillDrawLayer {
        validateVectorLayerKeys(layer, index, layerId)
        val layout = objectOrEmpty(layer, "layout", index, layerId)
        if ((layout.keys - setOf("visibility")).isNotEmpty()) {
            failRetained(index, layerId, "a fill-extrusion layout property is unsupported")
        }
        val paint = objectOrEmpty(layer, "paint", index, layerId)
        val supportedPaint = setOf(
            "fill-extrusion-base",
            "fill-extrusion-color",
            "fill-extrusion-height",
            "fill-extrusion-opacity",
            "fill-extrusion-vertical-gradient",
        )
        if ((paint.keys - supportedPaint).isNotEmpty()) {
            failRetained(index, layerId, "a fill-extrusion paint property is unsupported")
        }

        // Base and height are deliberately not evaluated for flat footprints, but
        // reachable syntax is still compiled so unsupported functions fail early.
        paint["fill-extrusion-base"]?.let {
            compileProperty(it, StyleType.NUMBER, index, layerId, "fill-extrusion-base")
        }
        paint["fill-extrusion-height"]?.let {
            compileProperty(it, StyleType.NUMBER, index, layerId, "fill-extrusion-height")
        }
        paint["fill-extrusion-vertical-gradient"]?.let { value ->
            value.asPrimitive()?.booleanOrNull
                ?: failRetained(index, layerId, "fill-extrusion-vertical-gradient must be a boolean constant")
        }

        val compiledSource = compileLayerVectorSource(
            layer, sources, compiledSources, secretContext, baseUri, index, layerId,
        )
        return FillDrawLayer(
            source = compiledSource,
            sourceLayer = sourceLayerFor(layer, compiledSource, index, layerId),
            filter = compileFilter(layer["filter"], index, layerId),
            antialias = true,
            color = compileColorProperty(
                paint["fill-extrusion-color"] ?: JsonPrimitive("#000000"),
                index,
                layerId,
                "fill-extrusion-color",
            ),
            opacity = compileProperty(
                paint["fill-extrusion-opacity"] ?: JsonPrimitive(1.0),
                StyleType.NUMBER,
                index,
                layerId,
                "fill-extrusion-opacity",
            ),
            outlineColor = null,
            pattern = null,
            translate = StylePropertyCompiler.compileConstant(
                JsonArray(listOf(JsonPrimitive(0.0), JsonPrimitive(0.0))),
                StyleType.ARRAY,
            ),
            translateAnchor = TranslateAnchor.MAP,
            minZoom = layer["minzoom"]?.asPrimitive()?.doubleOrNull ?: 0.0,
            maxZoom = layer["maxzoom"]?.asPrimitive()?.doubleOrNull ?: 31.0,
        )
    }

    private suspend fun compileLineLayer(
        layer: JsonObject,
        sources: JsonObject,
        compiledSources: MutableMap<String, CompiledVectorSource>,
        secretContext: SecretContext,
        baseUri: String?,
        index: Int,
        layerId: String,
    ): LineDrawLayer {
        validateVectorLayerKeys(layer, index, layerId)
        val layout = objectOrEmpty(layer, "layout", index, layerId)
        val supportedLayout = setOf(
            "visibility",
            "line-cap",
            "line-join",
            "line-miter-limit",
            "line-round-limit",
            "line-sort-key",
        )
        if ((layout.keys - supportedLayout).isNotEmpty()) {
            failRetained(index, layerId, "a line layout property is unsupported")
        }
        val capValue = layout["line-cap"]?.let { value ->
            value.asPrimitive()?.takeIf { it.isString }?.content
                ?: failRetained(index, layerId, "line-cap must currently be a string constant")
        } ?: "butt"
        val cap = when (capValue) {
            "butt" -> CompiledLineCap.BUTT
            "round" -> CompiledLineCap.ROUND
            "square" -> CompiledLineCap.SQUARE
            else -> failRetained(index, layerId, "line-cap must currently be a supported constant")
        }
        val joinValue = layout["line-join"]?.let { value ->
            value.asPrimitive()?.takeIf { it.isString }?.content
                ?: failRetained(index, layerId, "line-join must currently be a string constant")
        } ?: "miter"
        val join = when (joinValue) {
            "bevel" -> CompiledLineJoin.BEVEL
            "miter" -> CompiledLineJoin.MITER
            "round" -> CompiledLineJoin.ROUND
            else -> failRetained(index, layerId, "line-join must currently be a supported constant")
        }
        val miterLimit = compileProperty(
            layout["line-miter-limit"] ?: JsonPrimitive(2.0),
            StyleType.NUMBER,
            index,
            layerId,
            "line-miter-limit",
        )
        val roundLimit = layout["line-round-limit"]?.let { value ->
            value.asPrimitive()?.doubleOrNull
                ?: failRetained(index, layerId, "line-round-limit must currently be a numeric constant")
        } ?: 1.05
        if (!roundLimit.isFinite()) {
            throw StylePreparationException("line-round-limit must be finite")
        }
        val sortKey = layout["line-sort-key"]?.let {
            compileProperty(it, StyleType.NUMBER, index, layerId, "line-sort-key")
        }
        val paint = objectOrEmpty(layer, "paint", index, layerId)
        val supportedPaint = setOf(
            "line-blur",
            "line-color",
            "line-dasharray",
            "line-gap-width",
            "line-offset",
            "line-opacity",
            "line-pattern",
            "line-translate",
            "line-width",
        )
        if ((paint.keys - supportedPaint).isNotEmpty()) {
            failRetained(index, layerId, "a line paint property is not implemented yet")
        }
        val compiledSource = compileLayerVectorSource(
            layer, sources, compiledSources, secretContext, baseUri, index, layerId,
        )
        return LineDrawLayer(
            source = compiledSource,
            sourceLayer = sourceLayerFor(layer, compiledSource, index, layerId),
            filter = compileFilter(layer["filter"], index, layerId),
            cap = cap,
            join = join,
            miterLimit = miterLimit,
            roundLimit = roundLimit.toFloat(),
            sortKey = sortKey,
            color = compileColorProperty(paint["line-color"] ?: JsonPrimitive("#000000"), index, layerId, "line-color"),
            opacity = compileProperty(paint["line-opacity"] ?: JsonPrimitive(1.0), StyleType.NUMBER, index, layerId, "line-opacity"),
            width = compileProperty(paint["line-width"] ?: JsonPrimitive(1.0), StyleType.NUMBER, index, layerId, "line-width"),
            blur = compileProperty(paint["line-blur"] ?: JsonPrimitive(0.0), StyleType.NUMBER, index, layerId, "line-blur"),
            dashArray = paint["line-dasharray"]?.let {
                compileProperty(it, StyleType.ARRAY, index, layerId, "line-dasharray")
            },
            gapWidth = compileProperty(
                paint["line-gap-width"] ?: JsonPrimitive(0.0),
                StyleType.NUMBER,
                index,
                layerId,
                "line-gap-width",
            ),
            offset = compileProperty(
                paint["line-offset"] ?: JsonPrimitive(0.0),
                StyleType.NUMBER,
                index,
                layerId,
                "line-offset",
            ),
            pattern = paint["line-pattern"]?.let {
                compileProperty(it, StyleType.STRING, index, layerId, "line-pattern")
            },
            translate = compileProperty(
                paint["line-translate"] ?: JsonArray(listOf(JsonPrimitive(0.0), JsonPrimitive(0.0))),
                StyleType.ARRAY,
                index,
                layerId,
                "line-translate",
            ),
            minZoom = layer["minzoom"]?.asPrimitive()?.doubleOrNull ?: 0.0,
            maxZoom = layer["maxzoom"]?.asPrimitive()?.doubleOrNull ?: 31.0,
        )
    }

    private suspend fun compileIconLayer(
        layer: JsonObject,
        sources: JsonObject,
        compiledSources: MutableMap<String, CompiledVectorSource>,
        secretContext: SecretContext,
        baseUri: String?,
        index: Int,
        layerId: String,
    ): IconDrawLayer {
        validateVectorLayerKeys(layer, index, layerId)
        val layout = objectOrEmpty(layer, "layout", index, layerId)
        val supportedLayout = setOf(
            "visibility",
            "icon-allow-overlap",
            "icon-anchor",
            "icon-image",
            "icon-offset",
            "icon-optional",
            "icon-overlap",
            "icon-padding",
            "icon-rotate",
            "icon-rotation-alignment",
            "icon-size",
            "icon-text-fit",
            "symbol-avoid-edges",
            "symbol-placement",
            "symbol-sort-key",
            "symbol-spacing",
            "symbol-z-order",
        )
        val unsupportedLayout = layout.keys.filter { key -> key !in supportedLayout && !key.startsWith("text-") }
        if (unsupportedLayout.isNotEmpty()) failRetained(index, layerId, "an icon layout property is unsupported")
        val paint = objectOrEmpty(layer, "paint", index, layerId)
        val supportedPaint = setOf(
            "icon-color",
            "icon-halo-blur",
            "icon-halo-color",
            "icon-halo-width",
            "icon-opacity",
            "icon-translate",
        )
        val unsupportedPaint = paint.keys.filter { key -> key !in supportedPaint && !key.startsWith("text-") }
        if (unsupportedPaint.isNotEmpty()) failRetained(index, layerId, "an icon paint property is unsupported")

        val placement = when (layout["symbol-placement"]?.asPrimitive()?.takeIf { it.isString }?.content ?: "point") {
            "point" -> SymbolPlacement.POINT
            "line" -> SymbolPlacement.LINE
            else -> failRetained(index, layerId, "symbol-placement is unsupported for retained icons")
        }
        val alignment = layout["icon-rotation-alignment"]?.asPrimitive()?.takeIf { it.isString }?.content ?: "auto"
        if (alignment !in setOf("auto", "map", "viewport")) {
            failRetained(index, layerId, "icon-rotation-alignment is invalid")
        }
        if (placement == SymbolPlacement.LINE && alignment == "viewport") {
            failRetained(index, layerId, "viewport-aligned line icons are outside the compatibility profile")
        }
        val anchor = when (layout["icon-anchor"]?.asPrimitive()?.takeIf { it.isString }?.content ?: "center") {
            "center" -> IconAnchor.CENTER
            "left" -> IconAnchor.LEFT
            "right" -> IconAnchor.RIGHT
            "top" -> IconAnchor.TOP
            "bottom" -> IconAnchor.BOTTOM
            "top-left" -> IconAnchor.TOP_LEFT
            "top-right" -> IconAnchor.TOP_RIGHT
            "bottom-left" -> IconAnchor.BOTTOM_LEFT
            "bottom-right" -> IconAnchor.BOTTOM_RIGHT
            else -> failRetained(index, layerId, "icon-anchor is invalid")
        }
        val allowOverlap = layout["icon-allow-overlap"]?.let { value ->
            value.asPrimitive()?.booleanOrNull
                ?: failRetained(index, layerId, "icon-allow-overlap must be a boolean constant")
        } ?: when (val overlap = layout["icon-overlap"]?.asPrimitive()?.takeIf { it.isString }?.content ?: "never") {
            "always" -> true
            "never", "cooperative" -> false
            else -> failRetained(index, layerId, "icon-overlap is invalid: $overlap")
        }
        val padding = layout["icon-padding"]?.asPrimitive()?.doubleOrNull ?: 2.0
        if (!padding.isFinite() || padding < 0.0) failRetained(index, layerId, "icon-padding must be non-negative")
        val avoidEdges = layout["symbol-avoid-edges"]?.let { value ->
            value.asPrimitive()?.booleanOrNull
                ?: failRetained(index, layerId, "symbol-avoid-edges must be a boolean constant")
        } ?: false
        layout["symbol-z-order"]?.let { value ->
            val order = value.asPrimitive()?.takeIf { it.isString }?.content
                ?: failRetained(index, layerId, "symbol-z-order must be a string constant")
            if (order !in setOf("auto", "source", "viewport-y")) failRetained(index, layerId, "symbol-z-order is invalid")
        }

        val compiledSource = compileLayerVectorSource(
            layer, sources, compiledSources, secretContext, baseUri, index, layerId,
        )
        return IconDrawLayer(
            source = compiledSource,
            sourceLayer = sourceLayerFor(layer, compiledSource, index, layerId),
            filter = compileFilter(layer["filter"], index, layerId),
            layerOrder = index,
            placement = placement,
            image = compileProperty(
                layout.getValue("icon-image"),
                StyleType.VALUE,
                index,
                layerId,
                "icon-image",
            ),
            size = compilePropertyWithDefault(
                layout["icon-size"], JsonPrimitive(1.0), StyleType.NUMBER, index, layerId, "icon-size",
            ),
            opacity = compilePropertyWithDefault(
                paint["icon-opacity"], JsonPrimitive(1.0), StyleType.NUMBER, index, layerId, "icon-opacity",
            ),
            color = compileColorPropertyWithDefault(
                paint["icon-color"], JsonPrimitive("#000000"), index, layerId, "icon-color",
            ),
            haloColor = compileColorPropertyWithDefault(
                paint["icon-halo-color"], JsonPrimitive("rgba(0,0,0,0)"),
                index,
                layerId,
                "icon-halo-color",
            ),
            haloWidth = compilePropertyWithDefault(
                paint["icon-halo-width"],
                JsonPrimitive(0.0),
                StyleType.NUMBER,
                index,
                layerId,
                "icon-halo-width",
            ),
            haloBlur = compilePropertyWithDefault(
                paint["icon-halo-blur"],
                JsonPrimitive(0.0),
                StyleType.NUMBER,
                index,
                layerId,
                "icon-halo-blur",
            ),
            rotate = compilePropertyWithDefault(
                layout["icon-rotate"], JsonPrimitive(0.0), StyleType.NUMBER, index, layerId, "icon-rotate",
            ),
            padding = padding,
            offset = compilePropertyWithDefault(
                layout["icon-offset"],
                JsonArray(listOf(JsonPrimitive(0.0), JsonPrimitive(0.0))),
                StyleType.ARRAY,
                index,
                layerId,
                "icon-offset",
            ),
            translate = compilePropertyWithDefault(
                paint["icon-translate"],
                JsonArray(listOf(JsonPrimitive(0.0), JsonPrimitive(0.0))),
                StyleType.ARRAY,
                index,
                layerId,
                "icon-translate",
            ),
            anchor = anchor,
            sortKey = layout["symbol-sort-key"]?.let {
                compileProperty(it, StyleType.NUMBER, index, layerId, "symbol-sort-key")
            },
            spacing = compilePropertyWithDefault(
                layout["symbol-spacing"],
                JsonPrimitive(250.0),
                StyleType.NUMBER,
                index,
                layerId,
                "symbol-spacing",
            ),
            allowOverlap = allowOverlap,
            avoidEdges = avoidEdges,
            minZoom = layer["minzoom"]?.asPrimitive()?.doubleOrNull ?: 0.0,
            maxZoom = layer["maxzoom"]?.asPrimitive()?.doubleOrNull ?: 31.0,
        )
    }

    private fun validateVectorLayerKeys(layer: JsonObject, index: Int, layerId: String) {
        val supported = setOf("id", "type", "source", "source-layer", "minzoom", "maxzoom", "filter", "layout", "paint", "metadata")
        if ((layer.keys - supported).isNotEmpty()) {
            failRetained(index, layerId, "a vector layer property is unsupported")
        }
    }

    private fun compileTranslateAnchor(
        element: JsonElement?,
        index: Int,
        layerId: String,
        property: String,
    ): TranslateAnchor = when (val value = element?.asPrimitive()?.takeIf { it.isString }?.content ?: "map") {
        "map" -> TranslateAnchor.MAP
        "viewport" -> TranslateAnchor.VIEWPORT
        else -> failRetained(index, layerId, "$property is invalid: $value")
    }

    private fun sourceLayerFor(
        layer: JsonObject,
        source: CompiledVectorSource,
        index: Int,
        layerId: String,
    ): String {
        val declared = layer["source-layer"]?.asPrimitive()?.takeIf { it.isString }?.content
        if (source.geoJson != null) {
            if (declared != null) failRetained(index, layerId, "a GeoJSON layer cannot declare source-layer")
            return GEO_JSON_SOURCE_LAYER
        }
        return declared ?: failRetained(index, layerId, "a vector layer must declare source-layer")
    }

    private suspend fun compileLayerVectorSource(
        layer: JsonObject,
        sources: JsonObject,
        compiledSources: MutableMap<String, CompiledVectorSource>,
        secretContext: SecretContext,
        baseUri: String?,
        index: Int,
        layerId: String,
    ): CompiledVectorSource {
        val sourceId = layer["source"]?.asPrimitive()?.takeIf { it.isString }?.content
            ?: failRetained(index, layerId, "a vector layer must name its source")
        return compiledSources[sourceId] ?: compileVectorSource(
            sourceId,
            sources,
            secretContext,
            baseUri,
            index,
            layerId,
        ).also { compiledSources[sourceId] = it }
    }

    private suspend fun compileVectorSource(
        sourceId: String,
        sources: JsonObject,
        secretContext: SecretContext,
        baseUri: String?,
        layerIndex: Int,
        layerId: String,
    ): CompiledVectorSource {
        val source = sources[sourceId] as? JsonObject
            ?: failRetained(layerIndex, layerId, "a vector layer source is missing")
        val sourceType = source["type"]?.asPrimitive()?.takeIf { it.isString }?.content
        if (sourceType == "geojson") {
            val known = setOf("type", "data", "attribution")
            if ((source.keys - known).isNotEmpty()) {
                failRetained(layerIndex, layerId, "A GeoJSON source property is unsupported")
            }
            val dataReference = source["data"]?.asPrimitive()?.takeIf { it.isString }?.content
                ?: failRetained(layerIndex, layerId, "GeoJSON source data must be a URL string")
            val resolvedReference = resolveSourceReference(dataReference, baseUri, layerIndex, layerId)
            val geoJson = resolveGeoJson(secretContext.protectUrl(resolvedReference).resolve())
            return CompiledVectorSource(
                idDigest = sourceId.sha256Hex(),
                metadataDigest = geoJson.contentDigest,
                tileTemplates = emptyList(),
                scheme = TileScheme.XYZ,
                minZoom = 0,
                maxZoom = 22,
                geoJson = geoJson,
            )
        }
        if (sourceType != "vector") {
            failRetained(layerIndex, layerId, "a vector layer source is not a vector or GeoJSON source")
        }
        val tileJsonUrl = source["url"]?.asPrimitive()?.takeIf { it.isString }?.content
        if (tileJsonUrl != null && "tiles" in source) {
            failRetained(layerIndex, layerId, "A vector source cannot declare both url and tiles")
        }
        val resolvedTileJson = tileJsonUrl?.let { reference ->
            resolveTileJson(resolveSourceReference(reference, baseUri, layerIndex, layerId))
        }
        val inlineTemplates = (source["tiles"] as? JsonArray)?.map { item ->
            val template = item.asPrimitive()?.takeIf { it.isString }?.content
                ?: throw StylePreparationException("Vector tile templates must be strings")
            resolveSourceReference(template, baseUri, layerIndex, layerId)
        }.orEmpty()
        val templates = (resolvedTileJson?.tileTemplates ?: inlineTemplates).map(secretContext::protectUrl)
        if (templates.isEmpty()) {
            throw StylePreparationException("Inline vector sources must declare at least one tile template")
        }
        val schemeValue = source["scheme"]?.let { value ->
            value.asPrimitive()?.takeIf { it.isString }?.content
                ?: failRetained(layerIndex, layerId, "Vector source scheme must be a string")
        } ?: resolvedTileJson?.scheme?.name?.lowercase() ?: "xyz"
        val scheme = when (schemeValue) {
            "xyz" -> TileScheme.XYZ
            "tms" -> TileScheme.TMS
            else -> failRetained(layerIndex, layerId, "Vector source scheme is unsupported")
        }
        val declaredMinZoom = source["minzoom"]?.let { value ->
            value.asPrimitive()?.intOrNull ?: throw StylePreparationException("Vector source minzoom must be an integer")
        }
        val declaredMaxZoom = source["maxzoom"]?.let { value ->
            value.asPrimitive()?.intOrNull ?: throw StylePreparationException("Vector source maxzoom must be an integer")
        }
        val minZoom = maxOf(declaredMinZoom ?: 0, resolvedTileJson?.minZoom ?: 0)
        val maxZoom = minOf(declaredMaxZoom ?: 22, resolvedTileJson?.maxZoom ?: 22)
        if (minZoom !in 0..30 || maxZoom !in minZoom..30) {
            throw StylePreparationException("Vector source zoom range is invalid")
        }
        val bounds = source["bounds"]?.let { parseSourceBounds(it, layerIndex, layerId) } ?: resolvedTileJson?.bounds
        val known = setOf("type", "url", "tiles", "scheme", "minzoom", "maxzoom", "bounds", "attribution")
        if ((source.keys - known).isNotEmpty()) {
            failRetained(layerIndex, layerId, "A vector source property is unsupported")
        }
        return CompiledVectorSource(
            idDigest = sourceId.sha256Hex(),
            metadataDigest = resolvedTileJson?.identityDigest,
            tileTemplates = templates,
            scheme = scheme,
            minZoom = minZoom,
            maxZoom = maxZoom,
            bounds = bounds,
        )
    }

    private fun compileFilter(element: JsonElement?, index: Int, layerId: String): CompiledStyleFilter {
        if (element == null) return CompiledStyleFilter { true }
        return try {
            StyleFilterCompiler.compile(element)
        } catch (error: StyleExpressionCompilationException) {
            failRetained(index, layerId, "filter is unsupported: ${error.message}")
        }
    }

    private fun compileColorProperty(
        element: JsonElement,
        index: Int,
        layerId: String,
        property: String,
    ): CompiledStyleProperty {
        if (element is JsonPrimitive && element.isString && parseCssColor(element.content) == null) {
            failRetained(index, layerId, "$property must be a CSS color or supported expression")
        }
        return compileProperty(element, StyleType.STRING, index, layerId, property)
    }

    private suspend fun compileRasterLayer(
        layer: JsonObject,
        sources: JsonObject,
        compiledSources: MutableMap<String, CompiledRasterSource>,
        secretContext: SecretContext,
        baseUri: String?,
        index: Int,
        layerId: String,
    ): RasterDrawLayer {
        val knownLayerKeys = setOf("id", "type", "source", "minzoom", "maxzoom", "layout", "paint", "metadata")
        if ((layer.keys - knownLayerKeys).isNotEmpty()) {
            failRetained(index, layerId, "a raster layer property is unsupported")
        }
        val layout = objectOrEmpty(layer, "layout", index, layerId)
        val unsupportedLayout = layout.keys - setOf("visibility")
        if (unsupportedLayout.isNotEmpty()) {
            failRetained(index, layerId, "a raster layout property is unsupported")
        }
        val sourceId = layer["source"]?.asPrimitive()?.takeIf { it.isString }?.content
            ?: throw StylePreparationException("A raster layer must name its source")
        val source = compiledSources[sourceId] ?: compileRasterSource(
            sourceId,
            sources,
            secretContext,
            baseUri,
            index,
            layerId,
        ).also {
            compiledSources[sourceId] = it
        }
        val paint = objectOrEmpty(layer, "paint", index, layerId)
        val knownPaint = setOf(
            "raster-brightness-min",
            "raster-brightness-max",
            "raster-contrast",
            "raster-hue-rotate",
            "raster-opacity",
            "raster-resampling",
            "raster-saturation",
            "raster-fade-duration",
        )
        if ((paint.keys - knownPaint).isNotEmpty()) {
            failRetained(index, layerId, "a raster paint property is unsupported")
        }
        val brightnessMinimum = compileProperty(
            paint["raster-brightness-min"] ?: JsonPrimitive(0.0),
            StyleType.NUMBER,
            index,
            layerId,
            "raster-brightness-min",
        )
        val brightnessMaximum = compileProperty(
            paint["raster-brightness-max"] ?: JsonPrimitive(1.0),
            StyleType.NUMBER,
            index,
            layerId,
            "raster-brightness-max",
        )
        val contrast = compileProperty(
            paint["raster-contrast"] ?: JsonPrimitive(0.0),
            StyleType.NUMBER,
            index,
            layerId,
            "raster-contrast",
        )
        val hueRotate = compileProperty(
            paint["raster-hue-rotate"] ?: JsonPrimitive(0.0),
            StyleType.NUMBER,
            index,
            layerId,
            "raster-hue-rotate",
        )
        val saturation = compileProperty(
            paint["raster-saturation"] ?: JsonPrimitive(0.0),
            StyleType.NUMBER,
            index,
            layerId,
            "raster-saturation",
        )
        paint["raster-fade-duration"]?.let { value ->
            val duration = value.asPrimitive()?.doubleOrNull
                ?: failRetained(index, layerId, "raster-fade-duration must currently be constant")
            if (!duration.isFinite() || duration < 0.0) {
                throw StylePreparationException("raster-fade-duration must not be negative")
            }
        }
        val opacity = compileProperty(
            paint["raster-opacity"] ?: JsonPrimitive(1.0),
            StyleType.NUMBER,
            index,
            layerId,
            "raster-opacity",
        )
        val resamplingValue = paint["raster-resampling"]?.let { value ->
            value.asPrimitive()?.takeIf { it.isString }?.content
                ?: failRetained(index, layerId, "raster-resampling must currently be a string constant")
        } ?: "linear"
        val resampling = when (resamplingValue) {
            "linear" -> RasterResampling.LINEAR
            "nearest" -> RasterResampling.NEAREST
            else -> throw StylePreparationException("raster-resampling is invalid")
        }
        val minZoom = layer["minzoom"]?.asPrimitive()?.doubleOrNull ?: 0.0
        val maxZoom = layer["maxzoom"]?.asPrimitive()?.doubleOrNull ?: 31.0
        return RasterDrawLayer(
            source = source,
            opacity = opacity,
            brightnessMinimum = brightnessMinimum,
            brightnessMaximum = brightnessMaximum,
            contrast = contrast,
            hueRotate = hueRotate,
            saturation = saturation,
            resampling = resampling,
            minZoom = minZoom,
            maxZoom = maxZoom,
        )
    }

    private suspend fun compileHillshadeLayer(
        layer: JsonObject,
        sources: JsonObject,
        compiledSources: MutableMap<String, CompiledRasterSource>,
        secretContext: SecretContext,
        baseUri: String?,
        index: Int,
        layerId: String,
    ): HillshadeDrawLayer {
        val knownLayerKeys = setOf("id", "type", "source", "minzoom", "maxzoom", "layout", "paint", "metadata")
        if ((layer.keys - knownLayerKeys).isNotEmpty()) {
            failRetained(index, layerId, "a hillshade layer property is unsupported")
        }
        val layout = objectOrEmpty(layer, "layout", index, layerId)
        if ((layout.keys - setOf("visibility")).isNotEmpty()) {
            failRetained(index, layerId, "a hillshade layout property is unsupported")
        }
        val sourceId = layer["source"]?.asPrimitive()?.takeIf { it.isString }?.content
            ?: failRetained(index, layerId, "A hillshade layer must name its source")
        val source = compiledSources[sourceId] ?: compileRasterSource(
            sourceId,
            sources,
            secretContext,
            baseUri,
            index,
            layerId,
            expectedType = "raster-dem",
        ).also { compiledSources[sourceId] = it }
        val paint = objectOrEmpty(layer, "paint", index, layerId)
        val knownPaint = setOf(
            "hillshade-accent-color",
            "hillshade-exaggeration",
            "hillshade-highlight-color",
            "hillshade-shadow-color",
        )
        if ((paint.keys - knownPaint).isNotEmpty()) {
            failRetained(index, layerId, "a hillshade paint property is unsupported")
        }
        return HillshadeDrawLayer(
            source = source,
            accentColor = compileColorProperty(
                paint["hillshade-accent-color"] ?: JsonPrimitive("#000000"),
                index,
                layerId,
                "hillshade-accent-color",
            ),
            exaggeration = compileProperty(
                paint["hillshade-exaggeration"] ?: JsonPrimitive(0.5),
                StyleType.NUMBER,
                index,
                layerId,
                "hillshade-exaggeration",
            ),
            highlightColor = compileColorProperty(
                paint["hillshade-highlight-color"] ?: JsonPrimitive("#ffffff"),
                index,
                layerId,
                "hillshade-highlight-color",
            ),
            shadowColor = compileColorProperty(
                paint["hillshade-shadow-color"] ?: JsonPrimitive("#000000"),
                index,
                layerId,
                "hillshade-shadow-color",
            ),
            minZoom = layer["minzoom"]?.asPrimitive()?.doubleOrNull ?: 0.0,
            maxZoom = layer["maxzoom"]?.asPrimitive()?.doubleOrNull ?: 31.0,
        )
    }

    private suspend fun compileRasterSource(
        sourceId: String,
        sources: JsonObject,
        secretContext: SecretContext,
        baseUri: String?,
        layerIndex: Int,
        layerId: String,
        expectedType: String = "raster",
    ): CompiledRasterSource {
        val source = sources[sourceId] as? JsonObject
            ?: failRetained(layerIndex, layerId, "a raster layer source is missing")
        if (source["type"]?.asPrimitive()?.content != expectedType) {
            failRetained(layerIndex, layerId, "a raster layer source has the wrong source type")
        }
        val tileJsonUrl = source["url"]?.asPrimitive()?.takeIf { it.isString }?.content
        if (tileJsonUrl != null && "tiles" in source) {
            failRetained(layerIndex, layerId, "A raster source cannot declare both url and tiles")
        }
        val resolvedTileJson = tileJsonUrl?.let { reference ->
            resolveTileJson(resolveSourceReference(reference, baseUri, layerIndex, layerId))
        }
        val inlineTemplates = (source["tiles"] as? JsonArray)?.map { item ->
            val template = item.asPrimitive()?.takeIf { it.isString }?.content
                ?: throw StylePreparationException("Raster tile templates must be strings")
            resolveSourceReference(template, baseUri, layerIndex, layerId)
        }.orEmpty()
        val templates = (resolvedTileJson?.tileTemplates ?: inlineTemplates).map(secretContext::protectUrl)
        if (templates.isEmpty()) {
            throw StylePreparationException("Inline raster sources must declare at least one tile template")
        }
        val declaredTileSize = source["tileSize"] ?: source["tile-size"]
        val tileSize = declaredTileSize?.let { value ->
            value.asPrimitive()?.intOrNull ?: throw StylePreparationException("Raster source tile size must be an integer")
        } ?: resolvedTileJson?.tileSize ?: 512
        if (tileSize !in setOf(64, 256, 512)) {
            failRetained(layerIndex, layerId, "Raster source tile size is outside the compatibility profile")
        }
        val schemeValue = source["scheme"]?.let { value ->
            value.asPrimitive()?.takeIf { it.isString }?.content
                ?: failRetained(layerIndex, layerId, "Raster source scheme must be a string")
        } ?: resolvedTileJson?.scheme?.name?.lowercase() ?: "xyz"
        val scheme = when (schemeValue) {
            "xyz" -> TileScheme.XYZ
            "tms" -> TileScheme.TMS
            else -> failRetained(layerIndex, layerId, "Raster source scheme is unsupported")
        }
        val declaredMinZoom = source["minzoom"]?.let { value ->
            value.asPrimitive()?.intOrNull ?: throw StylePreparationException("Raster source minzoom must be an integer")
        }
        val declaredMaxZoom = source["maxzoom"]?.let { value ->
            value.asPrimitive()?.intOrNull ?: throw StylePreparationException("Raster source maxzoom must be an integer")
        }
        val minZoom = maxOf(declaredMinZoom ?: 0, resolvedTileJson?.minZoom ?: 0)
        val maxZoom = minOf(declaredMaxZoom ?: 30, resolvedTileJson?.maxZoom ?: 30)
        if (minZoom !in 0..30 || maxZoom !in minZoom..30) {
            throw StylePreparationException("Raster source zoom range is invalid")
        }
        val bounds = source["bounds"]?.let { parseSourceBounds(it, layerIndex, layerId) } ?: resolvedTileJson?.bounds
        val known = setOf(
            "type", "url", "tiles", "tileSize", "tile-size", "scheme", "minzoom", "maxzoom", "bounds", "attribution",
        ) + if (expectedType == "raster-dem") setOf("encoding") else emptySet()
        if ((source.keys - known).isNotEmpty()) {
            failRetained(layerIndex, layerId, "A raster source property is unsupported")
        }
        return CompiledRasterSource(
            idDigest = sourceId.sha256Hex(),
            metadataDigest = resolvedTileJson?.identityDigest,
            tileTemplates = templates,
            tileSize = tileSize,
            scheme = scheme,
            minZoom = minZoom,
            maxZoom = maxZoom,
            bounds = bounds,
            resourceClass = if (expectedType == "raster-dem") ResourceClass.DEM_TILE else ResourceClass.RASTER_TILE,
            demEncoding = if (expectedType == "raster-dem") {
                when (source["encoding"]?.asPrimitive()?.takeIf { it.isString }?.content ?: "mapbox") {
                    "mapbox" -> DemEncoding.MAPBOX
                    "terrarium" -> DemEncoding.TERRARIUM
                    else -> failRetained(layerIndex, layerId, "DEM source encoding is unsupported")
                }
            } else {
                null
            },
        )
    }

    private fun parseSourceBounds(
        element: JsonElement,
        layerIndex: Int,
        layerId: String,
    ): SourceBounds {
        val array = element as? JsonArray ?: failRetained(layerIndex, layerId, "Source bounds must be an array")
        if (array.size != 4) failRetained(layerIndex, layerId, "Source bounds must contain four numbers")
        val numbers = array.map { value ->
            value.asPrimitive()?.doubleOrNull?.takeIf(Double::isFinite)
                ?: failRetained(layerIndex, layerId, "Source bounds must contain finite numbers")
        }
        val bounds = SourceBounds(numbers[0], numbers[1], numbers[2], numbers[3])
        if (bounds.west !in -180.0..180.0 || bounds.east !in -180.0..180.0 ||
            bounds.south !in -90.0..90.0 || bounds.north !in -90.0..90.0 || bounds.south > bounds.north
        ) {
            failRetained(layerIndex, layerId, "Source bounds are outside the geographic range")
        }
        return bounds
    }

    private fun resolveSourceReference(
        reference: String,
        baseUri: String?,
        layerIndex: Int,
        layerId: String,
    ): String {
        if (reference.startsWith("https://") || reference.startsWith("http://")) return reference
        val resolved = baseUri?.let { resolveHttpReference(it, reference) }
        return resolved ?: failRetained(layerIndex, layerId, "A source URL cannot be resolved against the style base URI")
    }

    private fun compileBackground(
        layer: JsonObject,
        index: Int,
        layerId: String,
    ): CompiledBackgroundLayer {
        val knownLayerKeys = setOf("id", "type", "minzoom", "maxzoom", "layout", "paint", "metadata")
        if ((layer.keys - knownLayerKeys).isNotEmpty()) {
            failRetained(index, layerId, "a background layer property is unsupported")
        }
        val layout = objectOrEmpty(layer, "layout", index, layerId)
        val unsupportedLayout = layout.keys - setOf("visibility")
        if (unsupportedLayout.isNotEmpty()) {
            failRetained(index, layerId, "a background layout property is unsupported")
        }
        val paint = objectOrEmpty(layer, "paint", index, layerId)
        val unsupportedPaint = paint.keys - setOf("background-color", "background-opacity", "background-pattern")
        if (unsupportedPaint.isNotEmpty()) {
            failRetained(index, layerId, "a background paint property is unsupported")
        }
        val colorElement = paint["background-color"] ?: JsonPrimitive("#000000")
        if (colorElement is JsonPrimitive && colorElement.isString && parseCssColor(colorElement.content) == null) {
            failRetained(index, layerId, "background-color must be a CSS color or supported expression")
        }
        val opacityElement = paint["background-opacity"] ?: JsonPrimitive(1.0)
        opacityElement.asPrimitive()?.doubleOrNull?.let { opacity ->
            if (!opacity.isFinite() || opacity !in 0.0..1.0) {
                throw StylePreparationException("background-opacity must be between zero and one")
            }
        }
        return CompiledBackgroundLayer(
            color = compileProperty(colorElement, StyleType.STRING, index, layerId, "background-color"),
            opacity = compileProperty(opacityElement, StyleType.NUMBER, index, layerId, "background-opacity"),
            pattern = paint["background-pattern"]?.let {
                compileProperty(it, StyleType.STRING, index, layerId, "background-pattern")
            },
        )
    }

    private fun compileProperty(
        element: JsonElement,
        expectedType: StyleType,
        layerIndex: Int,
        layerId: String,
        property: String,
    ): CompiledStyleProperty = try {
        StylePropertyCompiler.compile(element, expectedType)
    } catch (error: StyleExpressionCompilationException) {
        failRetained(layerIndex, layerId, "$property is unsupported: ${error.message}")
    }

    private fun compilePropertyWithDefault(
        element: JsonElement?,
        default: JsonElement,
        expectedType: StyleType,
        layerIndex: Int,
        layerId: String,
        property: String,
    ): CompiledStyleProperty {
        val compiled = compileProperty(element ?: default, expectedType, layerIndex, layerId, property)
        val fallback = StylePropertyCompiler.compileConstant(default, expectedType)
        return CompiledStyleProperty { context ->
            compiled.evaluate(context).takeUnless { it == StyleValue.Null } ?: fallback.evaluate(context)
        }
    }

    private fun compileColorPropertyWithDefault(
        element: JsonElement?,
        default: JsonElement,
        layerIndex: Int,
        layerId: String,
        property: String,
    ): CompiledStyleProperty {
        val compiled = compileColorProperty(element ?: default, layerIndex, layerId, property)
        val fallback = StylePropertyCompiler.compileConstant(default, StyleType.STRING)
        return CompiledStyleProperty { context ->
            compiled.evaluate(context).takeUnless { it == StyleValue.Null } ?: fallback.evaluate(context)
        }
    }

    private fun objectOrEmpty(
        container: JsonObject,
        property: String,
        layerIndex: Int,
        layerId: String,
    ): JsonObject = container[property]?.let { value ->
        value as? JsonObject ?: failRetained(layerIndex, layerId, "$property must be a JSON object")
    } ?: JsonObject(emptyMap())

    private fun classifySymbol(
        layout: JsonObject,
        hidden: Boolean,
        identity: Map<String, String>,
    ): SymbolClassification {
        val iconDeclared = layout["icon-image"] != null
        val meaningfulIcon = meaningfulLayoutValue(layout, "icon-image")
        val textField = layout["text-field"]
        val meaningfulText = textField != null && !(textField is JsonPrimitive && textField.isString && textField.content == "")

        if (!meaningfulIcon) {
            val code = if (iconDeclared) DiagnosticCode.EMPTY_ICON_IMAGE_NO_DRAW else DiagnosticCode.TEXT_ONLY_LAYER_EXCLUDED
            return SymbolClassification(false, diagnostic(
                code = code,
                severity = DiagnosticSeverity.INFO,
                message = "A text-only symbol layer is excluded by the compatibility profile",
                details = identity,
            ))
        }
        if (hidden) {
            return SymbolClassification(false, diagnostic(
                code = DiagnosticCode.HIDDEN_LAYER_NO_DRAW,
                severity = DiagnosticSeverity.INFO,
                message = "A hidden icon layer is not drawn",
                details = identity,
            ))
        }
        if (!meaningfulText) {
            return SymbolClassification(true, null)
        }

        if (retainsIconIndependentOfText(layout)) {
            val diagnostic = diagnostic(
                code = DiagnosticCode.TEXT_COMPONENT_REMOVED_ICON_RETAINED,
                severity = DiagnosticSeverity.WARNING,
                message = "Text is removed and the icon is retained independently",
                details = identity,
            )
            return SymbolClassification(true, diagnostic, retainedIndependentOfText = true)
        }
        return SymbolClassification(false, diagnostic(
            code = DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED,
            severity = DiagnosticSeverity.INFO,
            message = "An icon sized from text extents is excluded by the compatibility profile",
            details = identity,
        ))
    }

    /**
     * The single source of truth for whether a symbol layer's icon geometry is independent of
     * its text: true when the layer has a meaningful `icon-image` and `icon-text-fit` is absent
     * or the constant `"none"`, so the sprite and `icon-size` alone determine the icon.
     * `icon-text-fit` is data-driven in the current style spec, so an expression value (any
     * `JsonElement` that is not a `JsonPrimitive`, e.g. `["case", ..., "both", "none"]`) is
     * treated as coupled rather than independent: this compatibility profile has no way to
     * evaluate it per-feature at prepare time, and assuming independence would draw a genuinely
     * text-sized icon unstretched. Shared by [classifySymbol] (which decides whether to retain
     * the icon) and [layerDesiresSpriteIndependentOfText] (which decides whether the layer merely
     * hopes for a sprite atlas) so the two can never drift apart.
     */
    private fun retainsIconIndependentOfText(layout: JsonObject): Boolean {
        if (!meaningfulLayoutValue(layout, "icon-image")) return false
        return when (val iconTextFit = layout["icon-text-fit"]) {
            null -> true
            is JsonPrimitive -> iconTextFit.content == "none"
            else -> false
        }
    }

    private fun meaningfulLayoutValue(layout: JsonObject, property: String): Boolean {
        val value = layout[property]
        return value != null && !(value is JsonPrimitive && value.isString && value.content.isEmpty())
    }

    /**
     * True for a layer that cannot draw anything at all without a sprite atlas: a
     * background/fill/line pattern, or a symbol layer whose icon has no text to fall back on. An
     * unresolvable sprite reference must still fail preparation loudly for these, exactly as
     * before this compatibility profile retained any text-coupled icon.
     */
    private fun layerRequiresSpriteUnconditionally(element: JsonElement): Boolean {
        val layer = element as? JsonObject ?: return false
        val layout = layer["layout"] as? JsonObject ?: JsonObject(emptyMap())
        if (layout["visibility"]?.asPrimitive()?.content == "none") return false
        val paint = layer["paint"] as? JsonObject ?: JsonObject(emptyMap())
        return when (layer["type"]?.asPrimitive()?.content) {
            "background" -> "background-pattern" in paint
            "fill" -> "fill-pattern" in paint
            "line" -> "line-pattern" in paint
            "symbol" -> meaningfulLayoutValue(layout, "icon-image") && !meaningfulLayoutValue(layout, "text-field")
            else -> false
        }
    }

    /**
     * True for a symbol layer that would like a sprite atlas only to draw an icon that is also
     * retained independently of its (meaningful) text. Such a layer did not exist as a retained
     * construct before this compatibility profile grew this feature, so it must not turn an
     * unresolvable sprite reference into a preparation failure for a style that used to prepare
     * fine - it simply falls back to a text-coupled exclusion instead.
     */
    private fun layerDesiresSpriteIndependentOfText(element: JsonElement): Boolean {
        val layer = element as? JsonObject ?: return false
        if (layer["type"]?.asPrimitive()?.content != "symbol") return false
        val layout = layer["layout"] as? JsonObject ?: JsonObject(emptyMap())
        if (layout["visibility"]?.asPrimitive()?.content == "none") return false
        return meaningfulLayoutValue(layout, "text-field") && retainsIconIndependentOfText(layout)
    }

    private fun resolveAbsoluteSpriteUrl(spriteReference: String, baseUri: String?): String? = when {
        spriteReference.startsWith("https://") || spriteReference.startsWith("http://") -> spriteReference
        baseUri != null -> resolveHttpReference(baseUri, spriteReference)
        else -> null
    }

    private suspend fun resolveRequiredSpriteAtlas(
        root: JsonObject,
        baseUri: String?,
        secretContext: SecretContext,
    ): CompiledSpriteAtlas {
        val spriteReference = root["sprite"]?.asPrimitive()?.takeIf { it.isString }?.content
            ?: failUnsupported("A retained pattern or icon layer requires a sprite URL")
        val resolvedSpriteUrl = resolveAbsoluteSpriteUrl(spriteReference, baseUri)
            ?: failUnsupported("The sprite URL cannot be resolved against the style base URI")
        return resolveSprite(secretContext.protectUrl(resolvedSpriteUrl).resolve())
    }

    /**
     * Like [resolveRequiredSpriteAtlas], but for a sprite that only a
     * [layerDesiresSpriteIndependentOfText] layer wants: an absent `sprite` key, a non-primitive
     * (array-form) sprite, or a relative reference with no base URI to resolve against are all
     * permanent, deterministic, offline conditions that a style could have carried before this
     * compatibility profile ever retained such a layer. None of them may fail preparation here;
     * they simply leave the atlas unresolved so the wanting layers fall back to exclusion.
     */
    private suspend fun resolveOptionalSpriteAtlas(
        root: JsonObject,
        baseUri: String?,
        secretContext: SecretContext,
    ): CompiledSpriteAtlas? {
        val spriteReference = root["sprite"]?.asPrimitive()?.takeIf { it.isString }?.content ?: return null
        val resolvedSpriteUrl = resolveAbsoluteSpriteUrl(spriteReference, baseUri) ?: return null
        return resolveSprite(secretContext.protectUrl(resolvedSpriteUrl).resolve())
    }

    private fun validateZoomRange(layer: JsonObject) {
        val minZoom = layer["minzoom"]?.let { value ->
            value.asPrimitive()?.doubleOrNull ?: throw StylePreparationException("Layer minzoom must be numeric")
        }
        val maxZoom = layer["maxzoom"]?.let { value ->
            value.asPrimitive()?.doubleOrNull ?: throw StylePreparationException("Layer maxzoom must be numeric")
        }
        if (minZoom != null && (!minZoom.isFinite() || minZoom < 0.0)) {
            throw StylePreparationException("Layer minzoom is invalid")
        }
        if (maxZoom != null && (!maxZoom.isFinite() || maxZoom < 0.0)) {
            throw StylePreparationException("Layer maxzoom is invalid")
        }
        if (minZoom != null && maxZoom != null && minZoom > maxZoom) {
            throw StylePreparationException("Layer zoom range is invalid")
        }
    }

    private fun failRetained(
        layerIndex: Int,
        layerId: String,
        reason: String,
        extraDiagnostics: List<RenderDiagnostic> = emptyList(),
    ): Nothing {
        val details = buildMap {
            put("layerIndex", layerIndex.toString())
            if (layerId.isNotEmpty()) put("layerIdDigest", layerId.sha256Hex())
        }
        val error = diagnostic(
            code = DiagnosticCode.UNSUPPORTED_RETAINED_CONSTRUCT,
            severity = DiagnosticSeverity.ERROR,
            message = reason,
            details = details,
        )
        throw StylePreparationException(
            message = "A reachable retained style construct is unsupported",
            diagnostics = extraDiagnostics + error,
        )
    }

    private fun failUnsupported(message: String): Nothing {
        throw StylePreparationException(message)
    }

    private fun diagnostic(
        code: DiagnosticCode,
        severity: DiagnosticSeverity,
        message: String,
        details: Map<String, String>,
    ): RenderDiagnostic = RenderDiagnostic(
        code = code,
        severity = severity,
        stage = PipelineStage.STYLE_PREPARATION,
        message = message,
        details = details,
    )

    private fun JsonElement.asPrimitive(): JsonPrimitive? = this as? JsonPrimitive

    private companion object {
        const val RENDERER_SEMANTIC_VERSION = "rentile-renderer-3"
        val SUPPORTED_LAYER_TYPES = setOf("background", "fill", "line", "raster", "hillshade")
        val EXCLUDED_ROOT_KEYS = setOf(
            "bearing",
            "center",
            "fog",
            "light",
            "pitch",
            "projection",
            "sky",
            "snow",
            "terrain",
            "zoom",
        )
    }
}
