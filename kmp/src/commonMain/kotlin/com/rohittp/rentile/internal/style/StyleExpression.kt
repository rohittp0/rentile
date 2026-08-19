package com.rohittp.rentile.internal.style

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.pow
import kotlin.math.roundToInt

internal enum class StyleType {
    NULL,
    BOOLEAN,
    NUMBER,
    STRING,
    ARRAY,
    OBJECT,
    IMAGE,
    COLOR,
    VALUE,
}

internal sealed interface StyleValue {
    data object Null : StyleValue

    data class BooleanValue(val value: Boolean) : StyleValue

    data class NumberValue(val value: Double) : StyleValue

    data class StringValue(val value: String) : StyleValue

    data class ArrayValue(val values: List<StyleValue>) : StyleValue

    data class ObjectValue(val values: Map<String, StyleValue>) : StyleValue

    data class ImageValue(val name: String) : StyleValue

    data class ColorValue(val value: CompiledColor) : StyleValue
}

internal enum class FeatureGeometryType(val styleName: String) {
    POINT("Point"),
    LINE_STRING("LineString"),
    POLYGON("Polygon"),
}

internal data class StyleEvaluationContext(
    val zoom: Double,
    val geometryType: FeatureGeometryType? = null,
    val featureId: StyleValue = StyleValue.Null,
    val properties: Map<String, StyleValue> = emptyMap(),
    val imageAvailable: (String) -> Boolean = { false },
)

internal sealed interface StyleExpression {
    val resultType: StyleType

    fun evaluate(context: StyleEvaluationContext): StyleValue
}

internal class StyleExpressionCompilationException(
    message: String,
) : IllegalArgumentException(message)

internal object StyleExpressionCompiler {
    fun compile(element: JsonElement, expectedType: StyleType = StyleType.VALUE): StyleExpression {
        val expression = compileNode(element)
        requireCompatible(expression.resultType, expectedType, "Expression result")
        return expression
    }

    fun compileBooleanFilter(element: JsonElement): StyleExpression = compile(element, StyleType.BOOLEAN)

    private fun compileNode(element: JsonElement): StyleExpression {
        if (element !is JsonArray) return LiteralExpression(element.toStyleValue())
        if (element.isEmpty()) fail("Expression arrays cannot be empty")
        val operator = (element[0] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: fail("Expression operator must be a string")
        val arguments = element.drop(1)
        return when (operator) {
            "literal" -> compileLiteral(arguments)
            "zoom" -> noArguments(operator, arguments, ZoomExpression)
            "geometry-type" -> noArguments(operator, arguments, GeometryTypeExpression)
            "get" -> compileGet(arguments)
            "has" -> compileHas(arguments)
            "image" -> compileImage(arguments)
            "!" -> UnaryBooleanExpression(compileExact(operator, arguments, 1, StyleType.BOOLEAN)) { !it }
            "all" -> LogicalExpression(arguments.map { compile(it, StyleType.BOOLEAN) }, all = true)
            "any" -> LogicalExpression(arguments.map { compile(it, StyleType.BOOLEAN) }, all = false)
            "+" -> NumericFoldExpression(requireAtLeast(operator, arguments, 2).map { compile(it, StyleType.NUMBER) }, add = true)
            "*" -> NumericFoldExpression(requireAtLeast(operator, arguments, 2).map { compile(it, StyleType.NUMBER) }, add = false)
            "==", "<=", ">=" -> compileComparison(operator, arguments)
            "in" -> compileIn(arguments)
            "boolean" -> BooleanAssertionExpression(requireAtLeast(operator, arguments, 1).map(::compileNode))
            "to-number" -> ToNumberExpression(requireAtLeast(operator, arguments, 1).map(::compileNode))
            "coalesce" -> compileCoalesce(arguments)
            "concat" -> compileConcat(arguments)
            "case" -> compileCase(arguments)
            "match" -> compileMatch(arguments)
            "step" -> compileStep(arguments)
            "interpolate" -> compileInterpolate(arguments)
            else -> fail("Unsupported expression operator: $operator")
        }
    }

    private fun compileLiteral(arguments: List<JsonElement>): StyleExpression {
        requireCount("literal", arguments, 1)
        return LiteralExpression(arguments.single().toStyleValue())
    }

    private fun compileGet(arguments: List<JsonElement>): StyleExpression {
        requireCount("get", arguments, 1)
        val name = arguments.single().constantString("get property name")
        return GetExpression(name)
    }

    private fun compileHas(arguments: List<JsonElement>): StyleExpression {
        requireCount("has", arguments, 1)
        val name = arguments.single().constantString("has property name")
        return HasExpression(name)
    }

    private fun compileImage(arguments: List<JsonElement>): StyleExpression {
        requireCount("image", arguments, 1)
        return ImageExpression(compile(arguments.single(), StyleType.STRING))
    }

    private fun compileComparison(operator: String, arguments: List<JsonElement>): StyleExpression {
        requireCount(operator, arguments, 2)
        val left = compileNode(arguments[0])
        val right = compileNode(arguments[1])
        if (operator != "==") {
            requireComparable(left.resultType, operator)
            requireComparable(right.resultType, operator)
        }
        return ComparisonExpression(operator, left, right)
    }

    private fun compileIn(arguments: List<JsonElement>): StyleExpression {
        requireCount("in", arguments, 2)
        return InExpression(compileNode(arguments[0]), compileNode(arguments[1]))
    }

    private fun compileCoalesce(arguments: List<JsonElement>): StyleExpression {
        val expressions = requireAtLeast("coalesce", arguments, 1).map(::compileNode)
        return CoalesceExpression(expressions, commonType(expressions.map(StyleExpression::resultType)))
    }

    private fun compileConcat(arguments: List<JsonElement>): StyleExpression {
        val expressions = requireAtLeast("concat", arguments, 1).map(::compileNode)
        return ConcatExpression(expressions)
    }

    private fun compileCase(arguments: List<JsonElement>): StyleExpression {
        if (arguments.size < 3 || arguments.size % 2 == 0) fail("case requires condition/output pairs and a fallback")
        val branches = arguments.dropLast(1).chunked(2).map { pair ->
            compile(pair[0], StyleType.BOOLEAN) to compileNode(pair[1])
        }
        val fallback = compileNode(arguments.last())
        return CaseExpression(
            branches,
            fallback,
            commonType(branches.map { it.second.resultType } + fallback.resultType),
        )
    }

    private fun compileMatch(arguments: List<JsonElement>): StyleExpression {
        if (arguments.size < 4 || arguments.size % 2 != 0) fail("match requires input, label/output pairs, and a fallback")
        val input = compileNode(arguments.first())
        val branchElements = arguments.drop(1).dropLast(1)
        val labels = mutableSetOf<StyleValue>()
        val branches = branchElements.chunked(2).map { pair ->
            val branchLabels = matchLabels(pair[0])
            if (branchLabels.any { !labels.add(it) }) fail("match labels must be unique")
            branchLabels to compileNode(pair[1])
        }
        val fallback = compileNode(arguments.last())
        return MatchExpression(
            input,
            branches,
            fallback,
            commonType(branches.map { it.second.resultType } + fallback.resultType),
        )
    }

    private fun compileStep(arguments: List<JsonElement>): StyleExpression {
        if (arguments.size < 2 || (arguments.size - 2) % 2 != 0) fail("step requires input, default, and stop/output pairs")
        val input = compile(arguments[0], StyleType.NUMBER)
        val default = compileNode(arguments[1])
        val stops = compileStops(arguments.drop(2))
        return StepExpression(input, default, stops, commonType(listOf(default.resultType) + stops.map { it.second.resultType }))
    }

    private fun compileInterpolate(arguments: List<JsonElement>): StyleExpression {
        if (arguments.size < 6 || (arguments.size - 2) % 2 != 0) {
            fail("interpolate requires interpolation, input, and at least two stop/output pairs")
        }
        val interpolation = arguments[0] as? JsonArray ?: fail("interpolate mode must be an expression array")
        val mode = (interpolation.firstOrNull() as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: fail("interpolate mode is invalid")
        val base = when (mode) {
            "linear" -> {
                if (interpolation.size !in 1..2) fail("linear interpolation accepts at most one compatibility parameter")
                interpolation.getOrNull(1)?.constantNumber("linear compatibility parameter")?.also {
                    if (!it.isFinite() || it <= 0.0) fail("linear compatibility parameter must be positive")
                }
                1.0
            }
            "exponential" -> {
                if (interpolation.size != 2) fail("exponential interpolation requires a base")
                interpolation[1].constantNumber("interpolation base").also {
                    if (!it.isFinite() || it <= 0.0) fail("interpolation base must be positive")
                }
            }
            else -> fail("Unsupported interpolation mode: $mode")
        }
        val input = compile(arguments[1], StyleType.NUMBER)
        val stops = compileStops(arguments.drop(2))
        if (stops.size < 2) fail("interpolate requires at least two stops")
        val resultType = commonType(stops.map { it.second.resultType })
        if (resultType !in setOf(StyleType.NUMBER, StyleType.STRING, StyleType.COLOR, StyleType.ARRAY, StyleType.VALUE)) {
            fail("interpolate output must be numeric, numeric-array, or color-valued")
        }
        return InterpolateExpression(input, stops, base, resultType)
    }

    private fun compileStops(elements: List<JsonElement>): List<Pair<Double, StyleExpression>> {
        if (elements.size % 2 != 0) fail("Stops must be input/output pairs")
        var previous = Double.NEGATIVE_INFINITY
        return elements.chunked(2).map { pair ->
            val stop = pair[0].constantNumber("stop input")
            if (!stop.isFinite() || stop <= previous) fail("Stop inputs must be finite and strictly ascending")
            previous = stop
            stop to compileNode(pair[1])
        }
    }

    private fun matchLabels(element: JsonElement): List<StyleValue> {
        val labels = if (element is JsonArray) {
            element.map(JsonElement::toStyleValue)
        } else {
            listOf(element.toStyleValue())
        }
        if (labels.isEmpty() || labels.any { it !is StyleValue.StringValue && it !is StyleValue.NumberValue && it !is StyleValue.BooleanValue }) {
            fail("match labels must be non-empty scalar literals")
        }
        return labels
    }

    private fun compileExact(
        operator: String,
        arguments: List<JsonElement>,
        count: Int,
        expectedType: StyleType,
    ): StyleExpression {
        requireCount(operator, arguments, count)
        return compile(arguments.single(), expectedType)
    }

    private fun noArguments(
        operator: String,
        arguments: List<JsonElement>,
        expression: StyleExpression,
    ): StyleExpression {
        requireCount(operator, arguments, 0)
        return expression
    }

    private fun requireCount(operator: String, arguments: List<JsonElement>, count: Int) {
        if (arguments.size != count) fail("$operator requires exactly $count arguments")
    }

    private fun requireAtLeast(operator: String, arguments: List<JsonElement>, count: Int): List<JsonElement> {
        if (arguments.size < count) fail("$operator requires at least $count arguments")
        return arguments
    }

    private fun requireCompatible(actual: StyleType, expected: StyleType, subject: String) {
        if (expected == StyleType.VALUE || actual == StyleType.VALUE || actual == StyleType.NULL) return
        if (actual != expected) fail("$subject has type $actual but $expected is required")
    }

    private fun requireComparable(type: StyleType, operator: String) {
        if (type !in setOf(StyleType.NUMBER, StyleType.STRING, StyleType.VALUE)) {
            fail("$operator operands must be numbers or strings")
        }
    }

    private fun commonType(types: List<StyleType>): StyleType {
        val concrete = types.filter { it != StyleType.NULL }.toSet()
        if (concrete.isEmpty()) return StyleType.NULL
        if (StyleType.VALUE in concrete) return StyleType.VALUE
        return concrete.singleOrNull() ?: fail("Expression branches must have compatible output types")
    }

    private fun JsonElement.constantString(subject: String): String =
        (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: fail("$subject must be a string literal")

    private fun JsonElement.constantNumber(subject: String): Double =
        (this as? JsonPrimitive)?.doubleOrNull ?: fail("$subject must be a number literal")

    private fun fail(message: String): Nothing = throw StyleExpressionCompilationException(message)
}

private data class LiteralExpression(
    val value: StyleValue,
) : StyleExpression {
    override val resultType: StyleType = value.styleType()

    override fun evaluate(context: StyleEvaluationContext): StyleValue = value
}

private data object ZoomExpression : StyleExpression {
    override val resultType: StyleType = StyleType.NUMBER

    override fun evaluate(context: StyleEvaluationContext): StyleValue = StyleValue.NumberValue(context.zoom)
}

private data object GeometryTypeExpression : StyleExpression {
    override val resultType: StyleType = StyleType.STRING

    override fun evaluate(context: StyleEvaluationContext): StyleValue =
        context.geometryType?.let { StyleValue.StringValue(it.styleName) } ?: StyleValue.Null
}

private data class GetExpression(
    val name: String,
) : StyleExpression {
    override val resultType: StyleType = StyleType.VALUE

    override fun evaluate(context: StyleEvaluationContext): StyleValue =
        if (name == "\$id") context.featureId else context.properties[name] ?: StyleValue.Null
}

private data class HasExpression(
    val name: String,
) : StyleExpression {
    override val resultType: StyleType = StyleType.BOOLEAN

    override fun evaluate(context: StyleEvaluationContext): StyleValue.BooleanValue =
        StyleValue.BooleanValue(if (name == "\$id") context.featureId != StyleValue.Null else name in context.properties)
}

private data class ImageExpression(
    val name: StyleExpression,
) : StyleExpression {
    override val resultType: StyleType = StyleType.IMAGE

    override fun evaluate(context: StyleEvaluationContext): StyleValue {
        val imageName = (name.evaluate(context) as? StyleValue.StringValue)?.value ?: return StyleValue.Null
        return if (imageName.isNotEmpty() && context.imageAvailable(imageName)) StyleValue.ImageValue(imageName) else StyleValue.Null
    }
}

private data class UnaryBooleanExpression(
    val expression: StyleExpression,
    val transform: (Boolean) -> Boolean,
) : StyleExpression {
    override val resultType: StyleType = StyleType.BOOLEAN

    override fun evaluate(context: StyleEvaluationContext): StyleValue =
        (expression.evaluate(context) as? StyleValue.BooleanValue)?.let { StyleValue.BooleanValue(transform(it.value)) } ?: StyleValue.Null
}

private data class LogicalExpression(
    val expressions: List<StyleExpression>,
    val all: Boolean,
) : StyleExpression {
    override val resultType: StyleType = StyleType.BOOLEAN

    override fun evaluate(context: StyleEvaluationContext): StyleValue {
        val values = expressions.map { (it.evaluate(context) as? StyleValue.BooleanValue)?.value ?: return StyleValue.Null }
        return StyleValue.BooleanValue(if (all) values.all { it } else values.any { it })
    }
}

private data class NumericFoldExpression(
    val expressions: List<StyleExpression>,
    val add: Boolean,
) : StyleExpression {
    override val resultType: StyleType = StyleType.NUMBER

    override fun evaluate(context: StyleEvaluationContext): StyleValue {
        val values = expressions.map { (it.evaluate(context) as? StyleValue.NumberValue)?.value ?: return StyleValue.Null }
        return StyleValue.NumberValue(if (add) values.sum() else values.fold(1.0, Double::times))
    }
}

private data class ComparisonExpression(
    val operator: String,
    val left: StyleExpression,
    val right: StyleExpression,
) : StyleExpression {
    override val resultType: StyleType = StyleType.BOOLEAN

    override fun evaluate(context: StyleEvaluationContext): StyleValue {
        val leftValue = left.evaluate(context)
        val rightValue = right.evaluate(context)
        val result = when (operator) {
            "==" -> leftValue == rightValue
            "<=" -> compare(leftValue, rightValue)?.let { it <= 0 } ?: return StyleValue.Null
            ">=" -> compare(leftValue, rightValue)?.let { it >= 0 } ?: return StyleValue.Null
            else -> return StyleValue.Null
        }
        return StyleValue.BooleanValue(result)
    }

    private fun compare(left: StyleValue, right: StyleValue): Int? = when {
        left is StyleValue.NumberValue && right is StyleValue.NumberValue -> left.value.compareTo(right.value)
        left is StyleValue.StringValue && right is StyleValue.StringValue -> left.value.compareTo(right.value)
        else -> null
    }
}

private data class InExpression(
    val needle: StyleExpression,
    val haystack: StyleExpression,
) : StyleExpression {
    override val resultType: StyleType = StyleType.BOOLEAN

    override fun evaluate(context: StyleEvaluationContext): StyleValue {
        val needleValue = needle.evaluate(context)
        val result = when (val haystackValue = haystack.evaluate(context)) {
            is StyleValue.ArrayValue -> needleValue in haystackValue.values
            is StyleValue.StringValue -> (needleValue as? StyleValue.StringValue)?.value?.let { it in haystackValue.value }
            else -> null
        } ?: return StyleValue.Null
        return StyleValue.BooleanValue(result)
    }
}

private data class BooleanAssertionExpression(
    val expressions: List<StyleExpression>,
) : StyleExpression {
    override val resultType: StyleType = StyleType.BOOLEAN

    override fun evaluate(context: StyleEvaluationContext): StyleValue =
        expressions.firstNotNullOfOrNull { it.evaluate(context) as? StyleValue.BooleanValue } ?: StyleValue.Null
}

private data class ToNumberExpression(
    val expressions: List<StyleExpression>,
) : StyleExpression {
    override val resultType: StyleType = StyleType.NUMBER

    override fun evaluate(context: StyleEvaluationContext): StyleValue {
        expressions.forEach { expression ->
            when (val value = expression.evaluate(context)) {
                is StyleValue.NumberValue -> return value
                is StyleValue.StringValue -> value.value.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { return StyleValue.NumberValue(it) }
                is StyleValue.BooleanValue -> return StyleValue.NumberValue(if (value.value) 1.0 else 0.0)
                else -> Unit
            }
        }
        return StyleValue.Null
    }
}

private data class CoalesceExpression(
    val expressions: List<StyleExpression>,
    override val resultType: StyleType,
) : StyleExpression {
    override fun evaluate(context: StyleEvaluationContext): StyleValue =
        expressions.firstNotNullOfOrNull { expression -> expression.evaluate(context).takeUnless { it == StyleValue.Null } } ?: StyleValue.Null
}

private data class ConcatExpression(
    val expressions: List<StyleExpression>,
) : StyleExpression {
    override val resultType: StyleType = StyleType.STRING

    override fun evaluate(context: StyleEvaluationContext): StyleValue =
        StyleValue.StringValue(
            expressions.joinToString("") { expression ->
                when (val value = expression.evaluate(context)) {
                    is StyleValue.StringValue -> value.value
                    is StyleValue.NumberValue -> value.value.stringifyForText()
                    is StyleValue.BooleanValue -> value.value.toString()
                    else -> ""
                }
            },
        )
}

private data class CaseExpression(
    val branches: List<Pair<StyleExpression, StyleExpression>>,
    val fallback: StyleExpression,
    override val resultType: StyleType,
) : StyleExpression {
    override fun evaluate(context: StyleEvaluationContext): StyleValue {
        branches.forEach { (condition, output) ->
            if ((condition.evaluate(context) as? StyleValue.BooleanValue)?.value == true) return output.evaluate(context)
        }
        return fallback.evaluate(context)
    }
}

private data class MatchExpression(
    val input: StyleExpression,
    val branches: List<Pair<List<StyleValue>, StyleExpression>>,
    val fallback: StyleExpression,
    override val resultType: StyleType,
) : StyleExpression {
    override fun evaluate(context: StyleEvaluationContext): StyleValue {
        val value = input.evaluate(context)
        return branches.firstOrNull { value in it.first }?.second?.evaluate(context) ?: fallback.evaluate(context)
    }
}

private data class StepExpression(
    val input: StyleExpression,
    val default: StyleExpression,
    val stops: List<Pair<Double, StyleExpression>>,
    override val resultType: StyleType,
) : StyleExpression {
    override fun evaluate(context: StyleEvaluationContext): StyleValue {
        val value = (input.evaluate(context) as? StyleValue.NumberValue)?.value ?: return StyleValue.Null
        return stops.lastOrNull { value >= it.first }?.second?.evaluate(context) ?: default.evaluate(context)
    }
}

private data class InterpolateExpression(
    val input: StyleExpression,
    val stops: List<Pair<Double, StyleExpression>>,
    val base: Double,
    override val resultType: StyleType,
) : StyleExpression {
    override fun evaluate(context: StyleEvaluationContext): StyleValue {
        val value = (input.evaluate(context) as? StyleValue.NumberValue)?.value ?: return StyleValue.Null
        if (value <= stops.first().first) return stops.first().second.evaluate(context)
        if (value >= stops.last().first) return stops.last().second.evaluate(context)
        val upperIndex = stops.indexOfFirst { value < it.first }
        val lower = stops[upperIndex - 1]
        val upper = stops[upperIndex]
        val progress = interpolationProgress(value, lower.first, upper.first, base)
        return interpolateStyleValues(lower.second.evaluate(context), upper.second.evaluate(context), progress)
    }
}

private fun interpolationProgress(value: Double, lower: Double, upper: Double, base: Double): Double {
    val span = upper - lower
    if (span == 0.0) return 0.0
    val progress = (value - lower) / span
    return if (base == 1.0) progress else (base.pow(value - lower) - 1.0) / (base.pow(span) - 1.0)
}

internal fun interpolateStyleValues(lower: StyleValue, upper: StyleValue, progress: Double): StyleValue {
    val fraction = progress.coerceIn(0.0, 1.0)
    if (lower is StyleValue.NumberValue && upper is StyleValue.NumberValue) {
        return StyleValue.NumberValue(lower.value + (upper.value - lower.value) * fraction)
    }
    if (lower is StyleValue.ArrayValue && upper is StyleValue.ArrayValue && lower.values.size == upper.values.size) {
        val interpolated = lower.values.zip(upper.values).map { (lowerItem, upperItem) ->
            if (lowerItem !is StyleValue.NumberValue || upperItem !is StyleValue.NumberValue) return StyleValue.Null
            StyleValue.NumberValue(lowerItem.value + (upperItem.value - lowerItem.value) * fraction)
        }
        return StyleValue.ArrayValue(interpolated)
    }
    val lowerColor = lower.asColor() ?: return StyleValue.Null
    val upperColor = upper.asColor() ?: return StyleValue.Null
    return StyleValue.ColorValue(
        CompiledColor(
            red = lerpByte(lowerColor.red, upperColor.red, fraction),
            green = lerpByte(lowerColor.green, upperColor.green, fraction),
            blue = lerpByte(lowerColor.blue, upperColor.blue, fraction),
            alpha = lerpByte(lowerColor.alpha, upperColor.alpha, fraction),
        ),
    )
}

private fun lerpByte(lower: Int, upper: Int, progress: Double): Int =
    (lower + (upper - lower) * progress).roundToInt().coerceIn(0, 255)

private fun StyleValue.asColor(): CompiledColor? = when (this) {
    is StyleValue.ColorValue -> value
    is StyleValue.StringValue -> parseCssColor(value)
    else -> null
}

private fun Double.stringifyForText(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()

internal fun JsonElement.toStyleValue(): StyleValue = when (this) {
    JsonNull -> StyleValue.Null
    is JsonPrimitive -> when {
        isString -> StyleValue.StringValue(content)
        booleanOrNull != null -> StyleValue.BooleanValue(booleanOrNull!!)
        doubleOrNull != null -> StyleValue.NumberValue(doubleOrNull!!)
        else -> StyleValue.Null
    }
    is JsonArray -> StyleValue.ArrayValue(map(JsonElement::toStyleValue))
    is JsonObject -> StyleValue.ObjectValue(mapValues { it.value.toStyleValue() })
}

internal fun StyleValue.styleType(): StyleType = when (this) {
    StyleValue.Null -> StyleType.NULL
    is StyleValue.BooleanValue -> StyleType.BOOLEAN
    is StyleValue.NumberValue -> StyleType.NUMBER
    is StyleValue.StringValue -> StyleType.STRING
    is StyleValue.ArrayValue -> StyleType.ARRAY
    is StyleValue.ObjectValue -> StyleType.OBJECT
    is StyleValue.ImageValue -> StyleType.IMAGE
    is StyleValue.ColorValue -> StyleType.COLOR
}
