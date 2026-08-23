package com.rohittp.rentile.internal.glyph

import com.rohittp.rentile.LabelPlacement
import com.rohittp.rentile.TileId
import com.rohittp.rentile.internal.mvt.DecodedVectorGeometry
import com.rohittp.rentile.internal.mvt.VectorCoordinate
import com.rohittp.rentile.internal.mvt.VectorRing
import com.rohittp.rentile.internal.style.IconAnchor
import com.rohittp.rentile.internal.style.StyleValue
import com.rohittp.rentile.internal.style.TextJustify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LabelCandidateAssemblerTest {
    @Test
    fun concavePolygonAnchorIsInsideInsteadOfAtTheOutsideVertexAverage() {
        // A U-shaped exterior. Its arithmetic vertex average is (2000, 2125), in the open notch
        // rather than in the polygon. This is the exact shape a first-ring average cannot handle.
        val exterior = ring(
            1000 to 1000,
            3000 to 1000,
            3000 to 3000,
            2500 to 3000,
            2500 to 1500,
            1500 to 1500,
            1500 to 3000,
            1000 to 3000,
        )
        assertTrue(exterior.signedAreaTwice > 0.0, "fixture exterior must use MVT winding")
        val vertexAverage = VectorCoordinate(
            exterior.points.sumOf { it.x } / exterior.points.size,
            exterior.points.sumOf { it.y } / exterior.points.size,
        )
        assertFalse(pointInRing(vertexAverage, exterior.points))

        val anchor = geometryAnchors(
            DecodedVectorGeometry.Polygons(listOf(exterior)),
            LabelPlacement.POINT,
        ).single().point

        assertNotEquals(vertexAverage, anchor)
        assertTrue(pointInRing(anchor, exterior.points), "anchor $anchor must be inside the concave exterior")
    }

    @Test
    fun multipartPolygonContributesOneAnchorPerExteriorAndNoneForAHole() {
        val firstExterior = ring(
            400 to 400,
            1800 to 400,
            1800 to 1800,
            400 to 1800,
        )
        val firstHole = ring(
            800 to 800,
            800 to 1500,
            1500 to 1500,
            1500 to 800,
        )
        val secondExterior = ring(
            2300 to 500,
            3700 to 500,
            3700 to 1900,
            2300 to 1900,
        )
        assertTrue(firstExterior.signedAreaTwice > 0.0)
        assertTrue(firstHole.signedAreaTwice < 0.0)
        assertTrue(secondExterior.signedAreaTwice > 0.0)

        val anchors = geometryAnchors(
            DecodedVectorGeometry.Polygons(listOf(firstExterior, firstHole, secondExterior)),
            LabelPlacement.POINT,
        ).map(GeometryAnchor::point)

        assertEquals(2, anchors.size, "the hole must constrain its component, not create a label")
        val first = anchors.single { it.x < 2000 }
        val second = anchors.single { it.x > 2000 }
        assertTrue(pointInRing(first, firstExterior.points))
        assertFalse(pointInRing(first, firstHole.points), "the first component anchor must avoid its hole")
        assertTrue(pointInRing(second, secondExterior.points))
    }

    @Test
    fun automaticTextJustifyFollowsTheEvaluatedAnchor() {
        val auto = StyleValue.StringValue("auto")
        val tile = TileId(0, 0, 0)

        listOf(IconAnchor.LEFT, IconAnchor.TOP_LEFT, IconAnchor.BOTTOM_LEFT).forEach { anchor ->
            assertEquals(TextJustify.LEFT, textJustifyOf(auto, anchor, tile), anchor.name)
        }
        listOf(IconAnchor.RIGHT, IconAnchor.TOP_RIGHT, IconAnchor.BOTTOM_RIGHT).forEach { anchor ->
            assertEquals(TextJustify.RIGHT, textJustifyOf(auto, anchor, tile), anchor.name)
        }
        listOf(IconAnchor.CENTER, IconAnchor.TOP, IconAnchor.BOTTOM).forEach { anchor ->
            assertEquals(TextJustify.CENTER, textJustifyOf(auto, anchor, tile), anchor.name)
        }
    }

    private fun ring(vararg coordinates: Pair<Int, Int>): VectorRing {
        val points = coordinates.map { (x, y) -> VectorCoordinate(x, y) }
        val signedAreaTwice = points.indices.sumOf { index ->
            val current = points[index]
            val next = points[(index + 1) % points.size]
            current.x.toDouble() * next.y - next.x.toDouble() * current.y
        }
        return VectorRing(points, signedAreaTwice)
    }

    private fun pointInRing(point: VectorCoordinate, ring: List<VectorCoordinate>): Boolean {
        var inside = false
        var previous = ring.last()
        for (current in ring) {
            val crosses = (current.y > point.y) != (previous.y > point.y)
            if (crosses) {
                val boundaryX = (previous.x - current.x).toDouble() *
                    (point.y - current.y) / (previous.y - current.y) + current.x
                if (point.x < boundaryX) inside = !inside
            }
            previous = current
        }
        return inside
    }
}
