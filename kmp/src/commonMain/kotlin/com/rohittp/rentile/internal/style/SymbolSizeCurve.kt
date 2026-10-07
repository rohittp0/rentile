package com.rohittp.rentile.internal.style

import com.rohittp.rentile.LabelSymbolSize
import com.rohittp.rentile.SymbolSizeKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The shape of a `text-size` or `icon-size` value that decides how Mapbox GL draws it between
 * integer zooms: whether a zoom curve drives it and where that curve's stops are, how it
 * interpolates, and whether it reads the feature.
 *
 * Derived from the style JSON rather than from the compiled property, because a compiled property
 * is an opaque evaluator: it can be sampled at any zoom but cannot say where its stops are, and the
 * stops are the whole of Mapbox's answer. See [LabelSymbolSize] for the semantics this feeds.
 */
internal data class SymbolSizeCurve(
    /**
     * Mapbox's `zoomStops`: the inputs of the top-level zoom curve, with a `step`'s default output
     * at negative infinity. Null when the size does not depend on zoom - or depends on it in a way
     * Mapbox refuses, which is reported at the tile zoom instead.
     */
    val zoomStops: List<Double>?,
    /** 1 for linear, the base for exponential, null for a curve that never interpolates. */
    val interpolationBase: Double?,
    val featureDependent: Boolean,
) {
    /**
     * Resolves this curve for one feature on one requested tile. [property] is the compiled size
     * (default fallback included) evaluated in [context]; [tileZoomSize] is its already-validated
     * value at the tile's own zoom; [specificationDefault] replaces a covering-stop value that is
     * not a finite number, as Mapbox's own evaluation does.
     */
    fun resolve(
        property: CompiledStyleProperty,
        context: StyleEvaluationContext,
        tileZoom: Int,
        tileZoomSize: Double,
        specificationDefault: Double,
    ): LabelSymbolSize {
        val stops = zoomStops
        if (stops == null) {
            return LabelSymbolSize(
                kind = if (featureDependent) SymbolSizeKind.SOURCE else SymbolSizeKind.CONSTANT,
                tileZoomSize = tileZoomSize,
                lowerZoom = tileZoom.toDouble(),
                upperZoom = tileZoom.toDouble(),
                lowerSize = tileZoomSize,
                upperSize = tileZoomSize,
                interpolationBase = null,
            )
        }
        // Mapbox GL JS getSizeData: the last stop at or below z and the first at or above z + 1,
        // each clamped to the stop list. gl-native's getCoveringStops selects the same pair.
        var lower = 0
        while (lower < stops.size && stops[lower] <= tileZoom) lower++
        lower = maxOf(0, lower - 1)
        var upper = lower
        while (upper < stops.size && stops[upper] < tileZoom + 1) upper++
        upper = minOf(stops.size - 1, upper)
        fun sizeAt(zoom: Double): Double =
            (property.evaluate(context.copy(zoom = zoom)) as? StyleValue.NumberValue)
                ?.value?.takeIf(Double::isFinite) ?: specificationDefault
        return LabelSymbolSize(
            kind = if (featureDependent) SymbolSizeKind.COMPOSITE else SymbolSizeKind.CAMERA,
            tileZoomSize = tileZoomSize,
            lowerZoom = stops[lower],
            upperZoom = stops[upper],
            lowerSize = sizeAt(stops[lower]),
            upperSize = sizeAt(stops[upper]),
            interpolationBase = interpolationBase,
        )
    }

    companion object {
        private val CONSTANT = SymbolSizeCurve(zoomStops = null, interpolationBase = null, featureDependent = false)

        /** Operators whose value comes from the feature, which is what makes a size source or composite. */
        private val FEATURE_OPERATORS = setOf("get", "has", "geometry-type", "id", "properties", "feature-state")

        /**
         * Classifies [element], the raw `*-size` value, after it already compiled successfully.
         * Null means the property was absent and takes its constant default.
         */
        fun of(element: JsonElement?): SymbolSizeCurve = when (element) {
            null -> CONSTANT
            is JsonObject -> legacyFunction(element)
            is JsonArray -> {
                val operator = (element.firstOrNull() as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)
                if (operator == null) CONSTANT else expression(element)
            }
            else -> CONSTANT
        }

        /**
         * A legacy function: an identity function reads the feature; a zoom function is a camera
         * curve whose stops are its own, interpolating at its `base` (1 when absent) unless it is an
         * `interval` function - Mapbox's `createFunction` gives that one no interpolation type.
         * Property and composite legacy functions do not compile under this profile, so they never
         * reach here.
         */
        private fun legacyFunction(function: JsonObject): SymbolSizeCurve {
            if ("property" in function) return SymbolSizeCurve(null, null, featureDependent = true)
            val stops = (function["stops"] as? JsonArray)?.map { stop ->
                ((stop as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.doubleOrNull ?: return CONSTANT
            } ?: return CONSTANT
            val type = (function["type"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            val base = if (type == "interval") null else (function["base"] as? JsonPrimitive)?.doubleOrNull ?: 1.0
            return SymbolSizeCurve(stops, base, featureDependent = false)
        }

        private fun expression(element: JsonArray): SymbolSizeCurve {
            val featureDependent = readsFeature(element)
            val zoomUses = zoomReferences(element)
            if (zoomUses == 0) return SymbolSizeCurve(null, null, featureDependent)
            // Mapbox's findZoomCurve: the curve must be the expression itself or sit inside
            // `coalesce`, and `zoom` may appear nowhere but as that curve's input. Anything else is
            // a style Mapbox will not load, so there is no Mapbox size to reproduce.
            val curve = zoomCurve(element)
            if (curve == null || zoomUses != 1) return SymbolSizeCurve(null, null, featureDependent)
            val operator = (curve[0] as JsonPrimitive).content
            return if (operator == "step") {
                SymbolSizeCurve(
                    zoomStops = listOf(Double.NEGATIVE_INFINITY) + stopInputs(curve),
                    interpolationBase = null,
                    featureDependent = featureDependent,
                )
            } else {
                val interpolation = curve[1] as JsonArray
                val base = when ((interpolation[0] as JsonPrimitive).content) {
                    "exponential" -> (interpolation.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
                        ?: return SymbolSizeCurve(null, null, featureDependent)
                    else -> 1.0
                }
                SymbolSizeCurve(stopInputs(curve), base, featureDependent)
            }
        }

        /**
         * The stop inputs of a `step` or an `interpolate`: in both, the first sits at index 3 and the
         * rest follow every other element, after the operator, the input or interpolation, and the
         * default output or the input.
         */
        private fun stopInputs(curve: JsonArray): List<Double> =
            (3 until curve.size step 2).map { index -> (curve[index] as JsonPrimitive).doubleOrNull ?: 0.0 }

        private fun zoomCurve(element: JsonArray): JsonArray? {
            val operator = (element.firstOrNull() as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            return when (operator) {
                "step" -> element.takeIf { isZoom(element.getOrNull(1)) }
                "interpolate" -> element.takeIf { isZoom(element.getOrNull(2)) }
                "coalesce" -> element.drop(1).firstNotNullOfOrNull { (it as? JsonArray)?.let(::zoomCurve) }
                else -> null
            }
        }

        private fun isZoom(element: JsonElement?): Boolean =
            element is JsonArray && element.size == 1 && (element[0] as? JsonPrimitive)?.content == "zoom"

        private fun zoomReferences(element: JsonElement): Int = when {
            element !is JsonArray -> 0
            isZoom(element) -> 1
            isLiteral(element) -> 0
            else -> element.sumOf(::zoomReferences)
        }

        private fun readsFeature(element: JsonElement): Boolean {
            if (element !is JsonArray || isLiteral(element)) return false
            val operator = (element.firstOrNull() as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            return operator in FEATURE_OPERATORS || element.any(::readsFeature)
        }

        private fun isLiteral(element: JsonArray): Boolean =
            (element.firstOrNull() as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content == "literal"
    }
}
