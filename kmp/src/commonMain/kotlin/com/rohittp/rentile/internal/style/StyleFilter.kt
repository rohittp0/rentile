package com.rohittp.rentile.internal.style

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal fun interface CompiledStyleFilter {
    fun matches(context: StyleEvaluationContext): Boolean
}

internal object StyleFilterCompiler {
    fun compile(element: JsonElement): CompiledStyleFilter {
        val array = element as? JsonArray ?: fail("Style filter must be an array")
        return if (isLegacyFilter(array)) {
            compileLegacy(array)
        } else {
            val expression = StyleExpressionCompiler.compileBooleanFilter(array)
            CompiledStyleFilter { context ->
                (expression.evaluate(context) as? StyleValue.BooleanValue)?.value == true
            }
        }
    }

    private fun isLegacyFilter(filter: JsonArray): Boolean {
        val operator = filter.operatorOrNull() ?: return false
        return when (operator) {
            "has", "!has" -> filter.size == 2 && filter[1] is JsonPrimitive && (filter[1] as JsonPrimitive).isString
            "==", "!=", "<", "<=", ">", ">=", "in", "!in" ->
                filter.size >= 3 && filter[1] is JsonPrimitive && (filter[1] as JsonPrimitive).isString
            "all", "any" -> filter.drop(1).all { it is JsonArray && isLegacyFilter(it) }
            else -> false
        }
    }

    private fun compileLegacy(filter: JsonArray): CompiledStyleFilter {
        val operator = filter.operatorOrNull() ?: fail("Legacy filter operator must be a string")
        return when (operator) {
            "has", "!has" -> {
                requireSize(operator, filter, 2)
                val key = filter[1].legacyPropertyName(operator)
                CompiledStyleFilter { context -> legacyHas(context, key) == (operator == "has") }
            }
            "==", "!=", "<", "<=", ">", ">=" -> {
                requireSize(operator, filter, 3)
                val key = filter[1].legacyPropertyName(operator)
                val expected = filter[2].legacyLiteral()
                CompiledStyleFilter { context ->
                    val comparison = compareLegacy(legacyValue(context, key), expected)
                    when (operator) {
                        "==" -> comparison == 0
                        "!=" -> comparison != 0
                        "<" -> comparison?.let { it < 0 } == true
                        "<=" -> comparison?.let { it <= 0 } == true
                        ">" -> comparison?.let { it > 0 } == true
                        ">=" -> comparison?.let { it >= 0 } == true
                        else -> false
                    }
                }
            }
            "in", "!in" -> {
                if (filter.size < 3) fail("$operator requires a property and at least one value")
                val key = filter[1].legacyPropertyName(operator)
                val candidates = filter.drop(2).map { it.legacyLiteral() }.toSet()
                CompiledStyleFilter { context ->
                    val contains = legacyValue(context, key) in candidates
                    if (operator == "in") contains else !contains
                }
            }
            "all", "any" -> {
                val children = filter.drop(1).map { child -> compileLegacy(child as JsonArray) }
                CompiledStyleFilter { context ->
                    if (operator == "all") children.all { it.matches(context) } else children.any { it.matches(context) }
                }
            }
            else -> fail("Unsupported legacy filter operator: $operator")
        }
    }

    private fun legacyValue(context: StyleEvaluationContext, key: String): StyleValue = when (key) {
        "\$type" -> context.geometryType?.let { StyleValue.StringValue(it.styleName) } ?: StyleValue.Null
        "\$id" -> context.featureId
        else -> context.properties[key] ?: StyleValue.Null
    }

    private fun legacyHas(context: StyleEvaluationContext, key: String): Boolean = when (key) {
        "\$type" -> context.geometryType != null
        "\$id" -> context.featureId != StyleValue.Null
        else -> key in context.properties
    }

    private fun compareLegacy(left: StyleValue, right: StyleValue): Int? = when {
        left is StyleValue.NumberValue && right is StyleValue.NumberValue -> left.value.compareTo(right.value)
        left is StyleValue.StringValue && right is StyleValue.StringValue -> left.value.compareTo(right.value)
        left is StyleValue.BooleanValue && right is StyleValue.BooleanValue -> left.value.compareTo(right.value)
        left == StyleValue.Null && right == StyleValue.Null -> 0
        else -> null
    }

    private fun requireSize(operator: String, filter: JsonArray, size: Int) {
        if (filter.size != size) fail("$operator legacy filter has an invalid argument count")
    }

    private fun JsonArray.operatorOrNull(): String? =
        (firstOrNull() as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonElement.legacyPropertyName(operator: String): String =
        (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: fail("$operator legacy filter property must be a string")

    private fun JsonElement.legacyLiteral(): StyleValue = when (this) {
        is JsonPrimitive -> when {
            isString -> StyleValue.StringValue(content)
            content == "true" -> StyleValue.BooleanValue(true)
            content == "false" -> StyleValue.BooleanValue(false)
            content == "null" -> StyleValue.Null
            else -> content.toDoubleOrNull()?.let { StyleValue.NumberValue(it) }
                ?: fail("Legacy filter value must be a scalar literal")
        }
        else -> fail("Legacy filter value must be a scalar literal")
    }

    private fun fail(message: String): Nothing = throw StyleExpressionCompilationException(message)
}
