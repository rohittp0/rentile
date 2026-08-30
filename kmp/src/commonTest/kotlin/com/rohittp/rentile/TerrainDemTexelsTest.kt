package com.rohittp.rentile

import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `ValidatedDemTile.texels` exists so a consumer with no image decoder can read elevation out of a
 * DEM tile whatever container the provider served it in. Every guarantee that makes those bytes
 * usable is asserted here against values computed from the fixture's own formula rather than from
 * an observed run, because a DEM that is mirrored, channel-swapped or premultiplied still looks
 * like a plausible terrain rather than like a failure.
 */
class TerrainDemTexelsTest {

    // Deliberately not square, so a width/height transposition cannot survive; deliberately
    // non-opaque, so premultiplication cannot be mistaken for the identity; and deliberately
    // varying down rows and across columns in different channels, so neither a flipped row order
    // nor a swapped channel order can survive either.
    private val fixtureWidth = 4
    private val fixtureHeight = 3

    private fun expectedRed(x: Int, y: Int): Int = 200 - y * 40
    private fun expectedGreen(x: Int, y: Int): Int = 10 + x * 20
    private fun expectedBlue(x: Int, y: Int): Int = 90 + x + y * 7
    private fun expectedAlpha(x: Int, y: Int): Int = 128

    /** Varies in all four channels and half-opaque: the fixture every guarantee can fail on. */
    private fun defaultTexel(x: Int, y: Int): IntArray =
        intArrayOf(expectedRed(x, y), expectedGreen(x, y), expectedBlue(x, y), expectedAlpha(x, y))

    /** Grey and opaque, so only a row flip can change it. */
    private fun greyByRowTexel(x: Int, y: Int): IntArray =
        intArrayOf(rowGrey(y), rowGrey(y), rowGrey(y), 255)

    /** Uniform and opaque, so only a channel permutation can change it. */
    private fun constantTexel(x: Int, y: Int): IntArray = CONSTANT_TEXEL.copyOf()

    private fun rowGrey(y: Int): Int = 200 - y * 40

    @Test
    fun everyTexelDecodesToTheChannelValuesTheImageEncoded() = runTest {
        val dem = acquireFixtureDemTile()

        assertEquals(fixtureWidth, dem.texels.width)
        assertEquals(fixtureHeight, dem.texels.height)
        assertEquals(fixtureWidth * fixtureHeight * 4, dem.texels.rgba.size)
        for (y in 0 until fixtureHeight) {
            for (x in 0 until fixtureWidth) {
                val offset = (y * fixtureWidth + x) * 4
                assertEquals(expectedRed(x, y), dem.texels.rgba[offset].toInt() and 0xff, "red at $x,$y")
                assertEquals(expectedGreen(x, y), dem.texels.rgba[offset + 1].toInt() and 0xff, "green at $x,$y")
                assertEquals(expectedBlue(x, y), dem.texels.rgba[offset + 2].toInt() and 0xff, "blue at $x,$y")
                assertEquals(expectedAlpha(x, y), dem.texels.rgba[offset + 3].toInt() and 0xff, "alpha at $x,$y")
            }
        }
    }

    @Test
    fun texelRowsRunTopDown() = runTest {
        // Grey and opaque, so that neither a permuted channel order nor a premultiplication can
        // move these numbers: this fixture varies down rows and in no other way, leaving row order
        // as the only property it can fail on.
        val dem = acquireFixtureDemTile(png = demPng(texel = ::greyByRowTexel))

        // Grey descends by row, so the first row of bytes must carry the brightest value. A
        // renderer that walks these rows bottom-up draws a mirrored planet, which no dimension or
        // byte count would reveal.
        val firstRow = dem.texels.rgba[0].toInt() and 0xff
        val lastRow = dem.texels.rgba[(fixtureHeight - 1) * fixtureWidth * 4].toInt() and 0xff
        assertEquals(rowGrey(0), firstRow, "the first row of bytes must be the tile's top row")
        assertEquals(rowGrey(fixtureHeight - 1), lastRow, "the last row of bytes must be the tile's bottom row")
        assertTrue(firstRow > lastRow, "the fixture must distinguish the two row orders")
    }

    @Test
    fun texelChannelsRunRedGreenBlueAlpha() = runTest {
        // Uniform across every pixel and opaque, so that neither a flipped row order nor a
        // premultiplication can move these numbers: channel order is the only property left.
        val dem = acquireFixtureDemTile(png = demPng(texel = ::constantTexel))

        // Four distinct values, checked in order. N32 - the idiom used where pixels only have to
        // look right - is BGRA on some of Rentile's targets and RGBA on others, so a channel order
        // inherited from the platform would decode one DEM to different elevations on different
        // targets.
        val texel = List(4) { dem.texels.rgba[4 + it].toInt() and 0xff }
        assertEquals(CONSTANT_TEXEL.toList(), texel)
        assertEquals(4, texel.distinct().size, "the fixture must distinguish all four channel orders")
    }

    @Test
    fun texelsAreNotPremultiplied() = runTest {
        val dem = acquireFixtureDemTile()

        // Elevation is packed across red, green and blue, so premultiplying by this fixture's
        // half-opaque alpha would halve the decoded height rather than merely darken a picture.
        for (y in 0 until fixtureHeight) {
            for (x in 0 until fixtureWidth) {
                val offset = (y * fixtureWidth + x) * 4
                val alpha = dem.texels.rgba[offset + 3].toInt() and 0xff
                assertTrue(alpha < 255, "the fixture must be non-opaque or it cannot detect premultiplication")
                for ((channel, expected) in listOf(
                    "red" to expectedRed(x, y),
                    "green" to expectedGreen(x, y),
                    "blue" to expectedBlue(x, y),
                )) {
                    val index = offset + listOf("red", "green", "blue").indexOf(channel)
                    val actual = dem.texels.rgba[index].toInt() and 0xff
                    val premultiplied = expected * alpha / 255
                    assertTrue(
                        premultiplied != expected,
                        "the fixture's $channel at $x,$y must change under premultiplication",
                    )
                    assertEquals(expected, actual, "$channel at $x,$y must be unpremultiplied")
                }
            }
        }
    }

    @Test
    fun texelsAreNotColourConvertedByADeclaredProfile() = runTest {
        // The same channel values, in an image declaring Display P3 primaries and a 2.2 transfer.
        // Reading into a destination that named a colour space would let Skia transform these
        // channels into it and silently restate the elevation.
        val profiled = demPng(declareNonSrgbProfile = true)
        assertNonSrgbProfileIsDeclared(profiled)

        val dem = acquireFixtureDemTile(png = profiled)

        for (y in 0 until fixtureHeight) {
            for (x in 0 until fixtureWidth) {
                val offset = (y * fixtureWidth + x) * 4
                assertEquals(expectedRed(x, y), dem.texels.rgba[offset].toInt() and 0xff, "red at $x,$y")
                assertEquals(expectedGreen(x, y), dem.texels.rgba[offset + 1].toInt() and 0xff, "green at $x,$y")
                assertEquals(expectedBlue(x, y), dem.texels.rgba[offset + 2].toInt() and 0xff, "blue at $x,$y")
            }
        }
    }

    @Test
    fun theEncodedBytesSurviveAlongsideTheTexels() = runTest {
        val encoded = demPng()
        val dem = acquireFixtureDemTile(png = encoded)

        // RenG hashes these for cache identity. texels is an addition, not a replacement.
        assertTrue(dem.bytes.contentEquals(encoded), "the exact encoded resource must still be exposed")
        assertTrue(dem.texels.rgba.size != dem.bytes.size, "the two must not be confused for one another")
    }

    @Test
    fun oneAcquisitionDecodesTheTileExactlyOnce() = runTest {
        val decodes = mutableListOf<RentileMetric>()
        acquireFixtureDemTile(
            metricsSink = MetricsSink { metric ->
                if (metric.name == MetricName.RESOURCE_DECODED_BYTES) decodes += metric
            },
        )

        // RESOURCE_DECODED_BYTES is recorded once per pass through the bounded decode gate, and
        // that gate holds the only Image.makeFromEncoded on this path. Retaining the pixels must
        // not add a second pass through it.
        assertEquals(1, decodes.size, "acquiring one DEM tile must decode it once")
        assertEquals(ResourceClass.DEM_TILE, decodes.single().resourceClass)
        assertEquals(fixtureWidth.toLong() * fixtureHeight.toLong() * 4L, decodes.single().value)
    }

    @Test
    fun texelsAreNotSharedBetweenTwoRequestedTilesBackedByOneSourceTile() = runTest {
        // Both output tiles resolve to the same maxzoom source tile, so both are served by one
        // single-flight result. Handing consumers the same mutable array would let one of them
        // corrupt the other's elevation.
        val rasterizer = rasterizerFor(demPng())
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(TERRAIN_STYLE))
            val tiles = rasterizer.acquireTerrainTiles(style, listOf(TileId(3, 0, 0), TileId(3, 1, 0)))

            assertEquals(2, tiles.size)
            assertEquals(tiles[0].sourceTile, tiles[1].sourceTile)
            assertEquals(tiles[0].texels, tiles[1].texels)
            tiles[0].texels.rgba[0] = 0
            assertEquals(
                expectedRed(0, 0),
                tiles[1].texels.rgba[0].toInt() and 0xff,
                "one tile's texels must not alias another's",
            )
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun demTexelsRejectABufferThatIsNotOneRgbaTexelPerPixel() {
        assertFailsWith<IllegalArgumentException> { DemTexels(2, 2, ByteArray(2 * 2 * 3)) }
        assertFailsWith<IllegalArgumentException> { DemTexels(0, 2, ByteArray(0)) }
    }

    @Test
    fun demTexelsCompareByContentRatherThanByArrayIdentity() {
        val first = DemTexels(1, 1, byteArrayOf(1, 2, 3, 4))
        val second = DemTexels(1, 1, byteArrayOf(1, 2, 3, 4))
        val different = DemTexels(1, 1, byteArrayOf(1, 2, 3, 5))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertTrue(first != different)
        assertTrue(!first.toString().contains("["), "toString must summarise the buffer, not dump it")
    }

    /**
     * Without this the colour-space test would be unfalsifiable: if Skia ignored the fixture's
     * profile chunks, every channel would match for the trivial reason that nothing declared a
     * conversion in the first place.
     */
    private fun assertNonSrgbProfileIsDeclared(png: ByteArray) {
        val image = Image.makeFromEncoded(png)
        try {
            val colorSpace = image.imageInfo.colorInfo.colorSpace
            assertTrue(colorSpace != null, "the fixture must declare a colour space")
            assertTrue(colorSpace != ColorSpace.sRGB, "the declared colour space must not be sRGB")
        } finally {
            image.close()
        }
    }

    private suspend fun acquireFixtureDemTile(
        png: ByteArray = demPng(),
        metricsSink: MetricsSink = MetricsSink.None,
    ): ValidatedDemTile {
        val rasterizer = rasterizerFor(png, metricsSink)
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(TERRAIN_STYLE))
            return rasterizer.acquireTerrainTiles(style, listOf(TileId(3, 2, 1))).single()
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    private fun rasterizerFor(
        png: ByteArray,
        metricsSink: MetricsSink = MetricsSink.None,
    ): BasemapRasterizer = Rentile.create(
        RentileConfiguration(
            transport = ResourceTransport { request ->
                when (request.resourceClass) {
                    ResourceClass.DEM_TILE -> TransportResponse(200, png)
                    else -> error("Unexpected resource class ${request.resourceClass}")
                }
            },
            rawResourceStore = InMemoryRawResourceStore(),
            metricsSink = metricsSink,
        ),
    )

    /**
     * Encodes the fixture's exact channel values as an 8-bit RGBA PNG, by hand.
     *
     * Skia's own raster encoder cannot be used for this: `Image.makeRaster` with
     * `ColorAlphaType.UNPREMUL` followed by `encodeToData` round-trips the pixels through
     * premultiplied storage, so red 200 at alpha 128 comes back as 199 - lossless only when alpha
     * is opaque, which is exactly the case that cannot detect premultiplication. Writing the
     * chunks here keeps the fixture byte-exact *and* independent of the library under test: these
     * bytes are what the specification says they are, not what Skia agreed to round-trip.
     *
     * Compression is a single stored deflate block, so the pixel bytes appear literally in the
     * IDAT payload.
     */
    private fun demPng(
        declareNonSrgbProfile: Boolean = false,
        texel: (Int, Int) -> IntArray = ::defaultTexel,
    ): ByteArray {
        val raw = ByteArray(fixtureHeight * (1 + fixtureWidth * 4))
        var cursor = 0
        for (y in 0 until fixtureHeight) {
            raw[cursor++] = 0 // filter type 0: None
            for (x in 0 until fixtureWidth) {
                for (channel in texel(x, y)) raw[cursor++] = channel.toByte()
            }
        }

        val header = buildList {
            addAll(be32(fixtureWidth))
            addAll(be32(fixtureHeight))
            add(8.toByte()) // bit depth
            add(6.toByte()) // colour type 6: truecolour with alpha
            add(0.toByte()) // deflate
            add(0.toByte()) // adaptive filtering
            add(0.toByte()) // no interlace
        }

        val png = mutableListOf<Byte>()
        png.addAll(listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).map(Int::toByte))
        png.addAll(chunk("IHDR", header))
        if (declareNonSrgbProfile) {
            // A pure 2.2 power transfer, which is not sRGB's piecewise curve.
            png.addAll(chunk("gAMA", be32(45_455)))
            // Display P3 primaries and a D65 white point, in PNG's hundred-thousandths.
            png.addAll(
                chunk(
                    "cHRM",
                    listOf(31_270, 32_900, 68_000, 32_000, 26_500, 69_000, 15_000, 6_000).flatMap(::be32),
                ),
            )
        }
        png.addAll(chunk("IDAT", zlibStored(raw)))
        png.addAll(chunk("IEND", emptyList()))
        return png.toByteArray()
    }

    private fun be32(value: Int): List<Byte> = listOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun chunk(type: String, data: List<Byte>): List<Byte> {
        val typed = type.map { it.code.toByte() } + data
        return be32(data.size) + typed + be32(crc32(typed))
    }

    /** A zlib stream whose single deflate block is stored, so [raw] appears verbatim. */
    private fun zlibStored(raw: ByteArray): List<Byte> {
        val length = raw.size
        return buildList {
            add(0x78.toByte()) // CMF: deflate, 32 KiB window
            add(0x01.toByte()) // FLG: no preset dictionary, check bits agree
            add(0x01.toByte()) // BFINAL = 1, BTYPE = 00 (stored)
            add(length.toByte())
            add((length ushr 8).toByte())
            add(length.inv().toByte())
            add((length.inv() ushr 8).toByte())
            addAll(raw.toList())
            addAll(be32(adler32(raw)))
        }
    }

    private fun crc32(bytes: List<Byte>): Int {
        var crc = 0.inv()
        for (byte in bytes) {
            crc = crc xor (byte.toInt() and 0xff)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor -0x12477ce0 else crc ushr 1 }
        }
        return crc.inv()
    }

    private fun adler32(bytes: ByteArray): Int {
        var a = 1
        var b = 0
        for (byte in bytes) {
            a = (a + (byte.toInt() and 0xff)) % 65_521
            b = (b + a) % 65_521
        }
        return (b shl 16) or a
    }

    private companion object {
        val CONSTANT_TEXEL: IntArray = intArrayOf(200, 30, 91, 255)

        const val TERRAIN_STYLE: String =
            """{"version":8,"sources":{"dem":{"type":"raster-dem","tiles":["https://tiles.example.test/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":2,"encoding":"terrarium"}},"terrain":{"source":"dem"},"layers":[]}"""
    }
}
