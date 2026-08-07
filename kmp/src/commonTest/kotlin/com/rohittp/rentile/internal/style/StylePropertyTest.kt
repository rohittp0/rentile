package com.rohittp.rentile.internal.style

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class StylePropertyTest {
    @Test
    fun evaluatesLinearAndExponentialLegacyZoomFunctionsAtOutputZoom() {
        val linear = compile("""{"stops":[[0,0],[10,100]]}""", StyleType.NUMBER)
        val exponential = compile("""{"base":2,"stops":[[0,0],[2,100]]}""", StyleType.NUMBER)

        assertEquals(StyleValue.NumberValue(50.0), linear.evaluate(StyleEvaluationContext(zoom = 5.0)))
        val exponentialValue = assertIs<StyleValue.NumberValue>(
            exponential.evaluate(StyleEvaluationContext(zoom = 1.0)),
        )
        assertEquals(100.0 / 3.0, exponentialValue.value, absoluteTolerance = 1e-12)
    }

    @Test
    fun intervalFunctionStepsNonInterpolatedValues() {
        val property = compile(
            """{"type":"interval","stops":[[0,"minor"],[10,"major"]]}""",
            StyleType.STRING,
        )

        assertEquals(StyleValue.StringValue("minor"), property.evaluate(StyleEvaluationContext(zoom = 9.9)))
        assertEquals(StyleValue.StringValue("major"), property.evaluate(StyleEvaluationContext(zoom = 10.0)))
    }

    @Test
    fun duplicateLegacyStopsEncodeADiscontinuity() {
        val property = compile(
            """{"base":1.2,"stops":[[13,0.9],[15,2.3],[15,0.5],[16,5.4]]}""",
            StyleType.NUMBER,
        )

        val immediatelyBefore = assertIs<StyleValue.NumberValue>(
            property.evaluate(StyleEvaluationContext(zoom = 14.999999)),
        )
        assertEquals(2.3, immediatelyBefore.value, absoluteTolerance = 0.00001)
        assertEquals(
            StyleValue.NumberValue(0.5),
            property.evaluate(StyleEvaluationContext(zoom = 15.0)),
        )
    }

    @Test
    fun numericArrayPaintValueIsAConstantRatherThanAnExpression() {
        val property = StylePropertyCompiler.compile(
            Json.parseToJsonElement("""[5,2.5]"""),
            StyleType.ARRAY,
        )

        assertEquals(
            StyleValue.ArrayValue(
                listOf(StyleValue.NumberValue(5.0), StyleValue.NumberValue(2.5)),
            ),
            property.evaluate(StyleEvaluationContext(zoom = 0.0)),
        )
    }

    @Test
    fun legacyColorStopsInterpolate() {
        val property = compile(
            """{"stops":[[0,"#000000"],[10,"#ffffff"]]}""",
            StyleType.STRING,
        )

        assertEquals(
            StyleValue.ColorValue(CompiledColor(128, 128, 128, 255)),
            property.evaluate(StyleEvaluationContext(zoom = 5.0)),
        )
    }

    @Test
    fun identityFunctionReadsFeaturePropertyAndUsesDefault() {
        val property = compile(
            """{"type":"identity","property":"height","default":0}""",
            StyleType.NUMBER,
        )

        assertEquals(
            StyleValue.NumberValue(12.0),
            property.evaluate(
                StyleEvaluationContext(
                    zoom = 5.0,
                    properties = mapOf("height" to StyleValue.NumberValue(12.0)),
                ),
            ),
        )
        assertEquals(StyleValue.NumberValue(0.0), property.evaluate(StyleEvaluationContext(zoom = 5.0)))
    }

    @Test
    fun rejectsUnsortedAndPropertyStopFunctions() {
        assertFailsWith<StyleExpressionCompilationException> {
            compile("""{"stops":[[10,1],[5,2]]}""", StyleType.NUMBER)
        }
        assertFailsWith<StyleExpressionCompilationException> {
            compile("""{"property":"class","stops":[["primary",1]]}""", StyleType.NUMBER)
        }
    }

    private fun compile(source: String, expectedType: StyleType): CompiledStyleProperty =
        StylePropertyCompiler.compileLegacyFunction(Json.parseToJsonElement(source), expectedType)
}
