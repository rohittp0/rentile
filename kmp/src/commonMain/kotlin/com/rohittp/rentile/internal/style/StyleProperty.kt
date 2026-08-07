package com.rohittp.rentile.internal.style

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.pow

internal fun interface CompiledStyleProperty {
    fun evaluate(context: StyleEvaluationContext): StyleValue
}

internal object StylePropertyCompiler {
    fun compile(element: JsonElement, expectedType: StyleType = StyleType.VALUE): CompiledStyleProperty = when (element) {
        is JsonObject -> compileLegacyFunction(element, expectedType)
        is JsonArray -> if (element.firstOrNull() !is JsonPrimitive || !(element.first() as JsonPrimitive).isString) {
            compileConstant(element, expectedType)
        } else {
            compileExpression(element, expectedType)
        }
        else -> compileConstant(element, expectedType)
    }

    fun compileExpression(element: JsonElement, expectedType: StyleType = StyleType.VALUE): CompiledStyleProperty {
        val expression = StyleExpressionCompiler.compile(element, expectedType)
        return CompiledStyleProperty(expression::evaluate)
    }

    fun compileConstant(element: JsonElement, expectedType: StyleType = StyleType.VALUE): CompiledStyleProperty {
        val value = element.toStyleValue()
        requireCompatible(value.styleType(), expectedType)
        return CompiledStyleProperty { value }
    }

    fun compileLegacyFunction(element: JsonElement, expectedType: StyleType = StyleType.VALUE): CompiledStyleProperty {
        val function = element as? JsonObject ?: fail("Legacy style function must be an object")
        val knownKeys = setOf("base", "default", "property", "stops", "type")
        if ((function.keys - knownKeys).isNotEmpty()) fail("Legacy style function has unsupported fields")

        val property = function["property"]?.constantString("Legacy function property")
        val type = function["type"]?.constantString("Legacy function type")
        if (type == "identity") {
            if (property == null || "stops" in function || "base" in function) {
                fail("Identity functions require a property and cannot declare stops or base")
            }
            val fallback = function["default"]?.toStyleValue()
            fallback?.let { requireCompatible(it.styleType(), expectedType) }
            return CompiledStyleProperty { context ->
                context.properties[property] ?: fallback ?: StyleValue.Null
            }
        }
        if (property != null) fail("Property and composite legacy functions are outside RentileV1")
        if (type != null && type !in setOf("exponential", "interval")) {
            fail("Legacy zoom function type is unsupported")
        }

        val stopsElement = function["stops"] as? JsonArray ?: fail("Legacy zoom function must declare stops")
        if (stopsElement.isEmpty()) fail("Legacy zoom function stops cannot be empty")
        var previous = Double.NEGATIVE_INFINITY
        val stops = stopsElement.map { stopElement ->
            val pair = stopElement as? JsonArray ?: fail("Legacy function stop must be a pair")
            if (pair.size != 2) fail("Legacy function stop must contain input and output")
            val input = (pair[0] as? JsonPrimitive)?.doubleOrNull
                ?: fail("Legacy zoom stop input must be numeric")
            if (!input.isFinite() || input < previous) fail("Legacy zoom stops must be finite and ascending")
            previous = input
            val output = pair[1].toStyleValue()
            requireCompatible(output.styleType(), expectedType)
            input to output
        }
        val outputType = commonType(stops.map { it.second.styleType() })
        val fallback = function["default"]?.toStyleValue()
        fallback?.let {
            requireCompatible(it.styleType(), expectedType)
            requireCompatible(it.styleType(), outputType)
        }
        val base = (function["base"] as? JsonPrimitive)?.doubleOrNull ?: 1.0
        if (!base.isFinite() || base <= 0.0) fail("Legacy function base must be positive")
        val interval = type == "interval"
        return LegacyZoomProperty(stops, base, interval, fallback)
    }

    private fun commonType(types: List<StyleType>): StyleType {
        val concrete = types.filter { it != StyleType.NULL }.toSet()
        if (concrete.isEmpty()) return StyleType.NULL
        return concrete.singleOrNull() ?: fail("Legacy function outputs must have compatible types")
    }

    private fun requireCompatible(actual: StyleType, expected: StyleType) {
        if (expected == StyleType.VALUE || actual == StyleType.NULL || actual == expected) return
        fail("Legacy style value has type $actual but $expected is required")
    }

    private fun JsonElement.constantString(subject: String): String =
        (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: fail("$subject must be a string")

    private fun fail(message: String): Nothing = throw StyleExpressionCompilationException(message)
}

private data class LegacyZoomProperty(
    val stops: List<Pair<Double, StyleValue>>,
    val base: Double,
    val interval: Boolean,
    val fallback: StyleValue?,
) : CompiledStyleProperty {
    override fun evaluate(context: StyleEvaluationContext): StyleValue {
        val zoom = context.zoom
        if (!zoom.isFinite()) return fallback ?: StyleValue.Null
        if (zoom < stops.first().first) return stops.first().second
        if (zoom >= stops.last().first) return stops.last().second
        val upperIndex = stops.indexOfFirst { zoom < it.first }
        val lower = stops[upperIndex - 1]
        if (interval) return lower.second
        val upper = stops[upperIndex]
        val progress = if (base == 1.0) {
            (zoom - lower.first) / (upper.first - lower.first)
        } else {
            val span = upper.first - lower.first
            (base.pow(zoom - lower.first) - 1.0) / (base.pow(span) - 1.0)
        }
        return interpolateStyleValues(lower.second, upper.second, progress).takeUnless { it == StyleValue.Null }
            ?: lower.second
    }
}
