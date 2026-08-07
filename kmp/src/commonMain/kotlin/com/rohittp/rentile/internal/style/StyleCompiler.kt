package com.rohittp.rentile.internal.style

import com.rohittp.rentile.CompatibilityPolicy
import com.rohittp.rentile.DiagnosticCode
import com.rohittp.rentile.DiagnosticSeverity
import com.rohittp.rentile.PipelineStage
import com.rohittp.rentile.RenderDiagnostic
import com.rohittp.rentile.StylePreparationException
import com.rohittp.rentile.internal.canonicalJson
import com.rohittp.rentile.internal.redactedForIdentity
import com.rohittp.rentile.internal.sha256Hex
import com.rohittp.rentile.internal.SecretContext
import com.rohittp.rentile.internal.withRedactedAuthenticationQuery
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

internal class StyleCompiler(
    private val owner: Any,
) {
    private val json = Json {
        isLenient = false
        allowTrailingComma = false
    }

    fun compile(bytes: ByteArray, policy: CompatibilityPolicy, baseUri: String?): CompiledPreparedStyle {
        val secretContext = SecretContext()
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
        val sources = root["sources"] as? JsonObject ?: JsonObject(emptyMap())
        val compiledRasterSources = mutableMapOf<String, CompiledRasterSource>()
        val layerIds = mutableSetOf<String>()
        val drawLayers = mutableListOf<CompiledDrawLayer>()

        for ((index, element) in layers.withIndex()) {
            val layer = element as? JsonObject
                ?: throw StylePreparationException("Every style layer must be a JSON object")
            val layerId = layer["id"]?.asPrimitive()?.content
                ?: throw StylePreparationException("Every style layer must have a string id")
            if (!layerIds.add(layerId)) {
                throw StylePreparationException("Style layer ids must be unique")
            }
            val type = layer["type"]?.asPrimitive()?.content
                ?: throw StylePreparationException("Every style layer must have a string type")
            validateZoomRange(layer)

            val layout = layer["layout"] as? JsonObject ?: JsonObject(emptyMap())
            val hidden = layout["visibility"]?.asPrimitive()?.content == "none"
            val identity = mapOf(
                "layerIndex" to index.toString(),
                "layerIdDigest" to layerId.sha256Hex(),
            )

            try {
                if (type == "symbol") {
                    classifySymbol(layout, hidden, identity)?.let(diagnostics::add)
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
                    failRetained(index, layerId, "fill-extrusion drawing is not implemented yet")
                }
                if (type == "raster") {
                    drawLayers += compileRasterLayer(
                        layer,
                        sources,
                        compiledRasterSources,
                        secretContext,
                        index,
                        layerId,
                    )
                    continue
                }
                if (type != "background") {
                    val construct = if (type in SUPPORTED_LAYER_TYPES) "$type drawing" else "layer type"
                    failRetained(index, layerId, "$construct is not implemented yet")
                }
                drawLayers += BackgroundDrawLayer(compileBackground(layer, index, layerId))
            } catch (error: StylePreparationException) {
                if (error.diagnostics.isNotEmpty()) {
                    diagnostics += error.diagnostics
                } else {
                    diagnostics += diagnostic(
                        code = DiagnosticCode.UNSUPPORTED_RETAINED_CONSTRUCT,
                        severity = DiagnosticSeverity.ERROR,
                        message = "A retained layer is invalid or unsupported",
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

        val digest = (RENDERER_SEMANTIC_VERSION + "\n" + policy.id + "\n" +
            baseUri?.withRedactedAuthenticationQuery().orEmpty() + "\n" +
            root.redactedForIdentity().canonicalJson()).sha256Hex()
        return CompiledPreparedStyle(
            owner = owner,
            digest = digest,
            policy = policy,
            diagnostics = diagnostics.toList(),
            drawLayers = drawLayers.toList(),
            secretContext = secretContext,
        )
    }

    private fun compileRasterLayer(
        layer: JsonObject,
        sources: JsonObject,
        compiledSources: MutableMap<String, CompiledRasterSource>,
        secretContext: SecretContext,
        index: Int,
        layerId: String,
    ): RasterDrawLayer {
        val knownLayerKeys = setOf("id", "type", "source", "minzoom", "maxzoom", "layout", "paint", "metadata")
        if ((layer.keys - knownLayerKeys).isNotEmpty()) {
            failRetained(index, layerId, "a raster layer property is unsupported")
        }
        val layout = layer["layout"] as? JsonObject
        val unsupportedLayout = layout?.keys?.minus(setOf("visibility")).orEmpty()
        if (unsupportedLayout.isNotEmpty()) {
            failRetained(index, layerId, "a raster layout property is unsupported")
        }
        val sourceId = layer["source"]?.asPrimitive()?.content
            ?: throw StylePreparationException("A raster layer must name its source")
        val source = compiledSources[sourceId] ?: compileRasterSource(
            sourceId,
            sources,
            secretContext,
            index,
            layerId,
        ).also {
            compiledSources[sourceId] = it
        }
        val paint = layer["paint"] as? JsonObject ?: JsonObject(emptyMap())
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
        requireDefaultNumber(paint, "raster-brightness-min", 0.0, index, layerId)
        requireDefaultNumber(paint, "raster-brightness-max", 1.0, index, layerId)
        requireDefaultNumber(paint, "raster-contrast", 0.0, index, layerId)
        requireDefaultNumber(paint, "raster-hue-rotate", 0.0, index, layerId)
        requireDefaultNumber(paint, "raster-saturation", 0.0, index, layerId)
        paint["raster-fade-duration"]?.let { value ->
            val duration = value.asPrimitive()?.doubleOrNull
                ?: failRetained(index, layerId, "raster-fade-duration must currently be constant")
            if (!duration.isFinite() || duration < 0.0) {
                throw StylePreparationException("raster-fade-duration must not be negative")
            }
        }
        val opacity = paint["raster-opacity"]?.asPrimitive()?.doubleOrNull ?: 1.0
        if (!opacity.isFinite() || opacity !in 0.0..1.0) {
            throw StylePreparationException("raster-opacity must be between zero and one")
        }
        val resampling = when (paint["raster-resampling"]?.asPrimitive()?.content ?: "linear") {
            "linear" -> RasterResampling.LINEAR
            "nearest" -> RasterResampling.NEAREST
            else -> throw StylePreparationException("raster-resampling is invalid")
        }
        val minZoom = layer["minzoom"]?.asPrimitive()?.doubleOrNull ?: 0.0
        val maxZoom = layer["maxzoom"]?.asPrimitive()?.doubleOrNull ?: 31.0
        return RasterDrawLayer(source, opacity.toFloat(), resampling, minZoom, maxZoom)
    }

    private fun compileRasterSource(
        sourceId: String,
        sources: JsonObject,
        secretContext: SecretContext,
        layerIndex: Int,
        layerId: String,
    ): CompiledRasterSource {
        val source = sources[sourceId] as? JsonObject
            ?: failRetained(layerIndex, layerId, "a raster layer source is missing")
        if (source["type"]?.asPrimitive()?.content != "raster") {
            failRetained(layerIndex, layerId, "a raster layer source is not a raster source")
        }
        if ("url" in source) {
            failRetained(layerIndex, layerId, "Raster TileJSON resolution is not implemented yet")
        }
        val templates = (source["tiles"] as? JsonArray)?.map { item ->
            val template = item.asPrimitive()?.takeIf { it.isString }?.content
                ?: throw StylePreparationException("Raster tile templates must be strings")
            if (!template.startsWith("https://") && !template.startsWith("http://")) {
                failRetained(layerIndex, layerId, "Relative raster tile templates are not implemented yet")
            }
            secretContext.protectUrl(template)
        }.orEmpty()
        if (templates.isEmpty()) {
            throw StylePreparationException("Inline raster sources must declare at least one tile template")
        }
        val tileSize = source["tileSize"]?.asPrimitive()?.intOrNull
            ?: source["tile-size"]?.asPrimitive()?.intOrNull
            ?: 512
        if (tileSize !in setOf(64, 256, 512)) {
            failRetained(layerIndex, layerId, "Raster source tile size is outside the compatibility profile")
        }
        val scheme = when (source["scheme"]?.asPrimitive()?.content ?: "xyz") {
            "xyz" -> RasterScheme.XYZ
            "tms" -> RasterScheme.TMS
            else -> failRetained(layerIndex, layerId, "Raster source scheme is unsupported")
        }
        val minZoom = source["minzoom"]?.asPrimitive()?.intOrNull ?: 0
        val maxZoom = source["maxzoom"]?.asPrimitive()?.intOrNull ?: 30
        if (minZoom !in 0..30 || maxZoom !in minZoom..30) {
            throw StylePreparationException("Raster source zoom range is invalid")
        }
        val known = setOf("type", "tiles", "tileSize", "tile-size", "scheme", "minzoom", "maxzoom", "attribution")
        if ((source.keys - known).isNotEmpty()) {
            failRetained(layerIndex, layerId, "A raster source property is unsupported")
        }
        return CompiledRasterSource(
            idDigest = sourceId.sha256Hex(),
            tileTemplates = templates,
            tileSize = tileSize,
            scheme = scheme,
            minZoom = minZoom,
            maxZoom = maxZoom,
        )
    }

    private fun requireDefaultNumber(
        paint: JsonObject,
        property: String,
        default: Double,
        layerIndex: Int,
        layerId: String,
    ) {
        val value = paint[property] ?: return
        val number = value.asPrimitive()?.doubleOrNull
            ?: failRetained(layerIndex, layerId, "$property must currently be constant")
        if (number != default) {
            failRetained(layerIndex, layerId, "$property rendering is not implemented yet")
        }
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
        val layout = layer["layout"] as? JsonObject
        val unsupportedLayout = layout?.keys?.minus(setOf("visibility")).orEmpty()
        if (unsupportedLayout.isNotEmpty()) {
            failRetained(index, layerId, "a background layout property is unsupported")
        }
        val paint = layer["paint"] as? JsonObject ?: JsonObject(emptyMap())
        val unsupportedPaint = paint.keys - setOf("background-color", "background-opacity", "background-pattern")
        if (unsupportedPaint.isNotEmpty()) {
            failRetained(index, layerId, "a background paint property is unsupported")
        }
        if ("background-pattern" in paint) {
            failRetained(index, layerId, "background-pattern is not implemented yet")
        }

        val colorText = paint["background-color"]?.asPrimitive()?.content ?: "#000000"
        val color = parseCssColor(colorText)
            ?: failRetained(index, layerId, "background-color must currently be a constant CSS color")
        val opacity = paint["background-opacity"]?.asPrimitive()?.doubleOrNull ?: 1.0
        if (!opacity.isFinite() || opacity !in 0.0..1.0) {
            throw StylePreparationException("background-opacity must be between zero and one")
        }
        return CompiledBackgroundLayer(color, opacity.toFloat())
    }

    private fun classifySymbol(
        layout: JsonObject,
        hidden: Boolean,
        identity: Map<String, String>,
    ): RenderDiagnostic? {
        val iconImage = layout["icon-image"]
        val iconDeclared = iconImage != null
        val meaningfulIcon = iconDeclared && !(iconImage is JsonPrimitive && iconImage.isString && iconImage.content == "")
        val textField = layout["text-field"]
        val meaningfulText = textField != null && !(textField is JsonPrimitive && textField.isString && textField.content == "")

        if (!meaningfulIcon) {
            val code = if (iconDeclared) DiagnosticCode.EMPTY_ICON_IMAGE_NO_DRAW else DiagnosticCode.TEXT_ONLY_LAYER_EXCLUDED
            return diagnostic(
                code = code,
                severity = DiagnosticSeverity.INFO,
                message = "A text-only symbol layer is excluded by the compatibility profile",
                details = identity,
            )
        }
        if (hidden) {
            return diagnostic(
                code = DiagnosticCode.HIDDEN_LAYER_NO_DRAW,
                severity = DiagnosticSeverity.INFO,
                message = "A hidden icon layer is not drawn",
                details = identity,
            )
        }
        if (!meaningfulText) {
            failRetained(identity.getValue("layerIndex").toInt(), "", "independent icon drawing is not implemented yet")
        }

        val textOptional = layout["text-optional"]?.asPrimitive()?.booleanOrNull == true
        val iconTextFit = layout["icon-text-fit"]?.asPrimitive()?.content
        if (textOptional && (iconTextFit == null || iconTextFit == "none")) {
            val diagnostic = diagnostic(
                code = DiagnosticCode.TEXT_COMPONENT_REMOVED_ICON_RETAINED,
                severity = DiagnosticSeverity.WARNING,
                message = "Optional text is removed and the icon is retained independently",
                details = identity,
            )
            failRetained(
                identity.getValue("layerIndex").toInt(),
                "",
                "independent icon drawing is not implemented yet",
                extraDiagnostics = listOf(diagnostic),
            )
        }
        return diagnostic(
            code = DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED,
            severity = DiagnosticSeverity.INFO,
            message = "A text-coupled icon layer is excluded by the compatibility profile",
            details = identity,
        )
    }

    private fun validateZoomRange(layer: JsonObject) {
        val minZoom = layer["minzoom"]?.asPrimitive()?.doubleOrNull
        val maxZoom = layer["maxzoom"]?.asPrimitive()?.doubleOrNull
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
        const val RENDERER_SEMANTIC_VERSION = "rentile-renderer-1"
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
