package com.rohittp.rentile.internal.style

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class StyleExpressionTest {
    @Test
    fun evaluatesPropertyBooleanComparisonAndBranchOperators() {
        val context = StyleEvaluationContext(
            zoom = 12.0,
            geometryType = FeatureGeometryType.LINE_STRING,
            properties = mapOf(
                "class" to StyleValue.StringValue("primary"),
                "rank" to StyleValue.NumberValue(3.0),
                "enabled" to StyleValue.BooleanValue(true),
            ),
        )

        assertEquals(
            StyleValue.BooleanValue(true),
            evaluate("""["all",["has","class"],["==",["geometry-type"],"LineString"],["<=",["get","rank"],3],[">=",["zoom"],10]]""", context),
        )
        assertEquals(
            StyleValue.BooleanValue(false),
            evaluate("""["!",["any",false,["boolean",["get","enabled"],false]]]""", context),
        )
        assertEquals(
            StyleValue.StringValue("major"),
            evaluate("""["case",["in",["get","class"],["literal",["primary","secondary"]]],"major","minor"]""", context),
        )
        assertEquals(
            StyleValue.NumberValue(7.0),
            evaluate("""["match",["get","class"],["primary","secondary"],["+",3,["*",2,2]],0]""", context),
        )
    }

    @Test
    fun evaluatesStepInterpolateAndNumberConversionAtOutputZoom() {
        val context = StyleEvaluationContext(
            zoom = 15.0,
            properties = mapOf("width" to StyleValue.StringValue("4.5")),
        )

        assertEquals(
            StyleValue.NumberValue(8.0),
            evaluate("""["step",["zoom"],1,10,4,15,8,20,16]""", context),
        )
        assertEquals(
            StyleValue.NumberValue(45.0),
            evaluate("""["*",["to-number",["get","width"],0],10]""", context),
        )
        assertEquals(
            StyleValue.NumberValue(50.0),
            evaluate("""["interpolate",["linear"],["zoom"],10,0,20,100]""", context),
        )
    }

    @Test
    fun coalesceImageUsesAtlasAvailabilityRatherThanStringPresence() {
        val context = StyleEvaluationContext(
            zoom = 5.0,
            imageAvailable = { it == "fallback" },
        )

        assertEquals(
            StyleValue.ImageValue("fallback"),
            evaluate("""["coalesce",["image","missing"],["image","fallback"]]""", context),
        )
    }

    @Test
    fun interpolateSupportsCssColors() {
        val value = evaluate(
            """["interpolate",["linear"],["zoom"],0,"#000000",10,"#ffffff"]""",
            StyleEvaluationContext(zoom = 5.0),
        )

        assertEquals(
            StyleValue.ColorValue(CompiledColor(128, 128, 128, 255)),
            value,
        )
    }

    @Test
    fun linearInterpolationAcceptsTheCorpusLegacyModeParameter() {
        assertEquals(
            StyleValue.NumberValue(50.0),
            evaluate(
                """["interpolate",["linear",2],["zoom"],10,0,20,100]""",
                StyleEvaluationContext(zoom = 15.0),
            ),
        )
    }

    @Test
    fun interpolationSupportsNumericArrayOutputs() {
        assertEquals(
            StyleValue.ArrayValue(
                listOf(StyleValue.NumberValue(5.0), StyleValue.NumberValue(-10.0)),
            ),
            evaluate(
                """["interpolate",["linear"],["zoom"],0,["literal",[0,0]],10,["literal",[10,-20]]]""",
                StyleEvaluationContext(zoom = 5.0),
            ),
        )
    }

    @Test
    fun missingDynamicValuesRemainNullForPropertyFallback() {
        assertEquals(
            StyleValue.StringValue("fallback"),
            evaluate("""["coalesce",["get","missing"],"fallback"]""", StyleEvaluationContext(zoom = 0.0)),
        )
    }

    @Test
    fun concatJoinsStringsAndStringifiesScalars() {
        assertEquals(
            StyleValue.StringValue("A1true"),
            evaluate("""["concat","A",1,true]""", StyleEvaluationContext(zoom = 0.0)),
        )
    }

    @Test
    fun concatTreatsNullAsEmpty() {
        assertEquals(
            StyleValue.StringValue("AB"),
            evaluate("""["concat","A",["get","missing"],"B"]""", StyleEvaluationContext(zoom = 0.0)),
        )
    }

    @Test
    fun concatDropsTheDecimalOfAWholeNumber() {
        assertEquals(
            StyleValue.StringValue("7"),
            evaluate("""["concat",7]""", StyleEvaluationContext(zoom = 0.0)),
        )
    }

    @Test
    fun isSupportedScriptReflectsWhatLayoutCanRender() {
        assertEquals(
            StyleValue.BooleanValue(true),
            evaluate("""["is-supported-script","Tokyo"]""", StyleEvaluationContext(zoom = 0.0)),
        )
        assertEquals(
            StyleValue.BooleanValue(false),
            evaluate("""["is-supported-script","القاهرة"]""", StyleEvaluationContext(zoom = 0.0)),
        )
    }

    @Test
    fun rejectsUnsupportedOperatorsAndInvalidTypesAtCompilation() {
        assertFailsWith<StyleExpressionCompilationException> {
            compile("""["not-a-real-operator","a","b"]""")
        }
        assertFailsWith<StyleExpressionCompilationException> {
            StyleExpressionCompiler.compile(
                Json.parseToJsonElement("""["+",true,1]"""),
                StyleType.NUMBER,
            )
        }
        assertFailsWith<StyleExpressionCompilationException> {
            compile("""["interpolate",["linear"],["zoom"],10,1,5,2]""")
        }
    }

    @Test
    fun literalPreservesArrayValues() {
        val value = evaluate("""["literal",[1,"two",true]]""", StyleEvaluationContext(zoom = 0.0))
        val array = assertIs<StyleValue.ArrayValue>(value)
        assertEquals(
            listOf(
                StyleValue.NumberValue(1.0),
                StyleValue.StringValue("two"),
                StyleValue.BooleanValue(true),
            ),
            array.values,
        )
    }

    private fun compile(source: String): StyleExpression =
        StyleExpressionCompiler.compile(Json.parseToJsonElement(source))

    private fun evaluate(source: String, context: StyleEvaluationContext): StyleValue =
        compile(source).evaluate(context)
}
