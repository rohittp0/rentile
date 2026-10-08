package com.rohittp.rentile

import com.rohittp.rentile.internal.mvt.Tile

internal object ExtrusionFixtures {
    val outer = listOf(-10 to 0, 100 to 0, 100 to 100, -10 to 100)
    val hole = listOf(20 to 20, 20 to 40, 40 to 40, 40 to 20)
    val second = listOf(200 to 200, 300 to 200, 300 to 300, 200 to 300)
    fun geometry(vararg rings: List<Pair<Int, Int>>): List<Int> {
        var x = 0; var y = 0
        return buildList {
            for (ring in rings) {
                add(9)
                ring.forEachIndexed { i, point ->
                    if (i == 1) add(((ring.size - 1) shl 3) or 2)
                    fun zig(n: Int): Int = (n shl 1) xor (n shr 31)
                    add(zig(point.first - x)); add(zig(point.second - y))
                    x = point.first; y = point.second
                }
                add(15)
            }
        }
    }
    fun tile(
        rings: List<List<Pair<Int, Int>>> = listOf(outer, hole, second),
        id: Long? = null,
        height: Double = 80.0,
        base: Double = 12.0,
        featureCount: Int = 1,
    ): ByteArray = Tile.ADAPTER.encode(Tile(layers = listOf(Tile.Layer(
        version = 2, name = "building", extent = 4096,
        keys = listOf("height", "height_min"),
        values = listOf(Tile.Value(double_value = height), Tile.Value(double_value = base)),
        features = List(featureCount) { Tile.Feature(id = id, tags = listOf(0, 0, 1, 1),
            type = Tile.GeomType.POLYGON, geometry = geometry(*rings.toTypedArray())) },
    ))))
    fun layer(
        id: String = "buildings", paint: String = "", filter: String = "", extra: String = "",
    ): String = """{"id":"$id","type":"fill-extrusion","source":"v","source-layer":"building","minzoom":15,"maxzoom":21$extra$filter,"paint":{"fill-extrusion-height":{"type":"identity","property":"height"},"fill-extrusion-base":{"type":"identity","property":"height_min"},"fill-extrusion-color":"rgba(10,20,30,0.2)"$paint}}"""
    fun style(vararg layers: String): String = LabelFixtures.style(*layers, glyphs = false, sprite = false).replace("\"maxzoom\":14", "\"maxzoom\":15")
}
