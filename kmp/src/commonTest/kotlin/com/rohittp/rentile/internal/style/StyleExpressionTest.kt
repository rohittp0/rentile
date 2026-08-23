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
    fun evaluatesStrictComparisonOperatorsForNumbersAndStrings() {
        val context = StyleEvaluationContext(
            zoom = 0.0,
            properties = mapOf(
                "class" to StyleValue.StringValue("primary"),
                "rank" to StyleValue.NumberValue(3.0),
            ),
        )

        assertEquals(StyleValue.BooleanValue(true), evaluate("""["!=",["get","class"],"secondary"]""", context))
        assertEquals(StyleValue.BooleanValue(true), evaluate("""["<",["get","rank"],4]""", context))
        assertEquals(StyleValue.BooleanValue(true), evaluate("""[">",["get","rank"],2]""", context))
        assertEquals(StyleValue.BooleanValue(true), evaluate("""["<","alpha","beta"]""", context))
        assertEquals(StyleValue.BooleanValue(true), evaluate("""["!=",["get","rank"],"3"]""", context))
        assertEquals(StyleValue.Null, evaluate("""["<",["get","rank"],"4"]""", context))
    }

    @Test
    fun comparisonsRejectWrongArityAndStaticallyIncompatibleTypes() {
        listOf(
            """["!=",1]""",
            """["<",1,2,3]""",
            """[">",true,false]""",
            """["!=",1,"1"]""",
        ).forEach { source ->
            assertFailsWith<StyleExpressionCompilationException>(source) {
                compile(source)
            }
        }
    }

    @Test
    fun sliceEvaluatesStringsArraysBoundsAndFractionalIndices() {
        val context = StyleEvaluationContext(zoom = 0.0)

        assertEquals(StyleValue.StringValue("bcd"), evaluate("""["slice","abcdef",1,4]""", context))
        assertEquals(StyleValue.StringValue("cdef"), evaluate("""["slice","abcdef",2]""", context))
        assertEquals(StyleValue.StringValue("ef"), evaluate("""["slice","abcdef",-2,99]""", context))
        assertEquals(StyleValue.StringValue(""), evaluate("""["slice","abcdef",4,2]""", context))
        assertEquals(StyleValue.StringValue("bc"), evaluate("""["slice","abcdef",1.9,3.9]""", context))
        assertEquals(
            StyleValue.ArrayValue(listOf(StyleValue.NumberValue(2.0), StyleValue.NumberValue(3.0))),
            evaluate("""["slice",["literal",[1,2,3,4]],1,3]""", context),
        )
    }

    @Test
    fun sliceCountsUnicodeSurrogatePairsAsOnePosition() {
        assertEquals(
            StyleValue.StringValue("😀"),
            evaluate("""["slice","A😀B",1,2]""", StyleEvaluationContext(zoom = 0.0)),
        )
    }

    @Test
    fun sliceReturnsNullForRuntimeTypeErrors() {
        val context = StyleEvaluationContext(
            zoom = 0.0,
            properties = mapOf(
                "input" to StyleValue.BooleanValue(true),
                "index" to StyleValue.StringValue("1"),
            ),
        )

        assertEquals(StyleValue.Null, evaluate("""["slice",["get","input"],0]""", context))
        assertEquals(StyleValue.Null, evaluate("""["slice","abc",["get","index"]]""", context))
    }

    @Test
    fun sliceRejectsWrongArityAndStaticallyInvalidTypes() {
        listOf(
            """["slice","abc"]""",
            """["slice","abc",0,1,2]""",
            """["slice",true,0]""",
            """["slice","abc","0"]""",
        ).forEach { source ->
            assertFailsWith<StyleExpressionCompilationException>(source) {
                compile(source)
            }
        }
    }

    @Test
    fun toStringConvertsScalarsCollectionsAndColors() {
        val context = StyleEvaluationContext(zoom = 0.0)

        assertEquals(StyleValue.StringValue(""), evaluate("""["to-string",null]""", context))
        assertEquals(StyleValue.StringValue("true"), evaluate("""["to-string",true]""", context))
        assertEquals(StyleValue.StringValue("7"), evaluate("""["to-string",7]""", context))
        assertEquals(
            StyleValue.StringValue("[1,\"two\",true,null]"),
            evaluate("""["to-string",["literal",[1,"two",true,null]]]""", context),
        )
        assertEquals(
            StyleValue.StringValue("{\"name\":\"A\\nB\",\"enabled\":true}"),
            evaluate("""["to-string",["literal",{"name":"A\nB","enabled":true}]]""", context),
        )
        assertEquals(
            StyleValue.StringValue("rgba(255,0,0,1)"),
            evaluate("""["to-string",["interpolate",["linear"],["zoom"],-1,"#ff0000",1,"#ff0000"]]""", context),
        )
    }

    @Test
    fun toStringRequiresExactlyOneArgument() {
        listOf(
            """["to-string"]""",
            """["to-string",1,2]""",
        ).forEach { source ->
            assertFailsWith<StyleExpressionCompilationException>(source) {
                compile(source)
            }
        }
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
    fun isSupportedScriptRequiresEveryArgumentToBeSupported() {
        // A single-argument call cannot tell `.all` from `.any`, so this genuinely needs two
        // arguments where only one is unsupported: `.all` reports false, but a mutant `.any`
        // would short-circuit true on the first (supported) argument and pass silently.
        assertEquals(
            StyleValue.BooleanValue(false),
            evaluate("""["is-supported-script","Cairo","القاهرة"]""", StyleEvaluationContext(zoom = 0.0)),
        )
        assertEquals(
            StyleValue.BooleanValue(true),
            evaluate("""["is-supported-script","Cairo","Tokyo"]""", StyleEvaluationContext(zoom = 0.0)),
        )
    }

    @Test
    fun isSupportedScriptTreatsNonStringArgumentsAsSupported() {
        // A number or boolean renders as plain ASCII with no shaping risk, so it never
        // disqualifies the expression. Documented here rather than left as an untested default.
        assertEquals(
            StyleValue.BooleanValue(true),
            evaluate("""["is-supported-script",42,true]""", StyleEvaluationContext(zoom = 0.0)),
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
