package com.rohittp.rentile

import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ProvisionalHillshadeTest {
    @Test
    fun provisionalRenderingDoesNotAcquireHillshadeDemAndHasItsOwnCacheIdentity() = runTest {
        var requests = 0
        val rasterizer = Rentile.create(RentileConfiguration(
            transport = ResourceTransport { requests++; error("DEM must not gate provisional pixels") },
            rawResourceStore = InMemoryRawResourceStore(),
        ))
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(STYLE))
            val tile = TileId(15, 5000, 5000)
            val full = RenderOptions(512)
            val provisional = RenderOptions(512, includeHillshade = false)
            val legacyKey = "rentile-output-request-3\n${style.digest}\n15/5000/5000\n512"
                .encodeUtf8().sha256().hex()
            assertEquals(legacyKey, rasterizer.outputRequestKey(style, tile, full))
            assertNotEquals(legacyKey, rasterizer.outputRequestKey(style, tile, provisional))
            val batch = rasterizer.prepareBatch(style, listOf(tile), provisional)
            try {
                val output = rasterizer.renderRaw(batch).tiles.single()
                assertEquals(512 * 512 * 4, output.rgbaBytes.size)
                assertEquals(0, requests)
            } finally { batch.close() }
            assertFailsWith<BatchRenderException> {
                rasterizer.prepareBatch(style, listOf(tile), full)
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    private companion object {
        const val STYLE = """{"version":8,"sources":{"dem":{"type":"raster-dem","tiles":["https://dem.test/{z}/{x}/{y}.png"],"tileSize":512}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"hills","type":"hillshade","source":"dem"}]}"""
    }
}
