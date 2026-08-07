package com.rohittp.rentile.internal.style

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StyleFilterTest {
    private val context = StyleEvaluationContext(
        zoom = 14.0,
        geometryType = FeatureGeometryType.LINE_STRING,
        featureId = StyleValue.NumberValue(42.0),
        properties = mapOf(
            "class" to StyleValue.StringValue("primary"),
            "rank" to StyleValue.NumberValue(3.0),
            "bridge" to StyleValue.BooleanValue(true),
        ),
    )

    @Test
    fun evaluatesLegacyFiltersWithoutTreatingPropertyNamesAsExpressions() {
        assertTrue(matches("""["==","class","primary"]"""))
        assertTrue(matches("""["!=","class","secondary"]"""))
        assertTrue(matches("""[">","rank",2]"""))
        assertTrue(matches("""["in","class","primary","motorway"]"""))
        assertTrue(matches("""["!in","class","secondary","tertiary"]"""))
        assertTrue(matches("""["has","bridge"]"""))
        assertTrue(matches("""["!has","tunnel"]"""))
        assertTrue(matches("""["==","${'$'}type","LineString"]"""))
        assertTrue(matches("""["==","${'$'}id",42]"""))
        assertTrue(matches("""["all",["==","class","primary"],["any",[">","rank",10],["has","bridge"]]]"""))
    }

    @Test
    fun evaluatesModernExpressionFiltersThroughTypedAst() {
        assertTrue(matches("""["all",["==",["get","class"],"primary"],[">=",["zoom"],12]]"""))
        assertFalse(matches("""["==",["geometry-type"],"Polygon"]"""))
    }

    @Test
    fun missingOrMismatchedLegacyValuesDoNotMatch() {
        assertFalse(matches("""["==","missing","value"]"""))
        assertFalse(matches("""[">","class",2]"""))
    }

    private fun matches(source: String): Boolean =
        StyleFilterCompiler.compile(Json.parseToJsonElement(source)).matches(context)
}
