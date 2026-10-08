package com.rohittp.rentile.internal.style

import com.rohittp.rentile.ExtrusionLayerDescriptor
import com.rohittp.rentile.ExtrusionLayerStyle
import com.rohittp.rentile.ExtrusionPaint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor

internal class CompiledExtrusionLayer(
    val source: CompiledVectorSource,
    val descriptor: ExtrusionLayerDescriptor,
    val program: ExtrusionProgram,
)

internal class ExtrusionProgram(
    private val descriptor: ExtrusionLayerDescriptor,
    private val filter: CompiledStyleFilter,
    private val base: CompiledStyleProperty,
    private val height: CompiledStyleProperty,
    private val color: CompiledStyleProperty,
    private val opacity: CompiledStyleProperty,
    val zoomDependent: Boolean,
    private val defaultBase: Double,
    private val defaultHeight: Double,
    private val defaultColor: CompiledColor,
    private val defaultOpacity: Double,
) {
    fun layerStyle(): ExtrusionLayerStyle = ExtrusionLayerStyle(descriptor) { zoom ->
        if (!active(zoom)) 0.0 else {
            val value = (opacity.evaluate(StyleEvaluationContext(zoom)) as? StyleValue.NumberValue)?.value
            (value?.takeIf { it.isFinite() } ?: defaultOpacity).coerceIn(0.0, 1.0)
        }
    }

    fun bind(context: StyleEvaluationContext): (Double) -> ExtrusionPaint? {
        // Cache constants, including identity functions. Inactive layers do not change their paint.
        if (zoomDependent) return { zoom -> if (active(zoom)) evaluate(context.copy(zoom = zoom)) else null }
        val constant = evaluate(context)
        // A separate closure releases the property map for constant paint.
        return { zoom -> if (active(zoom)) constant else null }
    }

    private fun active(zoom: Double): Boolean = zoom >= descriptor.minimumZoom && zoom < descriptor.maximumZoom

    private fun evaluate(context: StyleEvaluationContext): ExtrusionPaint? {
        if (!filter.matches(context.copy(zoom = floor(context.zoom)))) return null
        val h = (height.evaluate(context) as? StyleValue.NumberValue)?.value?.takeIf { it.isFinite() } ?: defaultHeight
        val b = (base.evaluate(context) as? StyleValue.NumberValue)?.value?.takeIf { it.isFinite() } ?: defaultBase
        val c = when (val value = color.evaluate(context)) {
            is StyleValue.StringValue -> parseCssColor(value.value)
            is StyleValue.ColorValue -> value.value
            else -> null
        } ?: defaultColor
        val nonnegativeHeight = h.coerceAtLeast(0.0)
        return ExtrusionPaint(b.coerceIn(0.0, nonnegativeHeight), nonnegativeHeight,
            (0xff shl 24) or (c.red shl 16) or (c.green shl 8) or c.blue)
    }
}

/** Dependency inspection follows expression nodes, never arbitrary strings or literal payloads. */
internal fun extrusionUsesZoom(value: JsonElement): Boolean = when (value) {
    is JsonArray -> value.firstOrNull()?.let { it as? JsonPrimitive }?.content.let { op ->
        op != "literal" && (op == "zoom" || value.drop(1).any(::extrusionUsesZoom))
    }
    is JsonObject -> ("stops" in value && ("property" !in value ||
        (value["stops"] as? JsonArray)?.firstOrNull()?.let { it as? JsonArray }?.firstOrNull() is JsonArray)) ||
        value.values.any(::extrusionUsesZoom)
    else -> false
}

internal fun extrusionUsesFeature(value: JsonElement): Boolean = when (value) {
    is JsonArray -> value.firstOrNull()?.let { it as? JsonPrimitive }?.content.let { op ->
        op != "literal" && (op in setOf("get", "has", "properties", "id", "geometry-type", "feature-state") ||
            value.drop(1).any(::extrusionUsesFeature))
    }
    is JsonObject -> "property" in value || value.values.any(::extrusionUsesFeature)
    else -> false
}
