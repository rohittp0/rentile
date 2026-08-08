package com.rohittp.rentile

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import platform.posix.getenv
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in public-corpus execution for real Kotlin/Native Linux and iOS targets.
 *
 * Set RENTILE_NATIVE_CORPUS=1. Normal unit/CI runs skip the network corpus.
 */
internal class NativeMapCatalogCorpusSmokeRunner(
    private val client: HttpClient,
) {
    fun run(): Unit = runBlocking {
        if (nativeCorpusEnvironment("RENTILE_NATIVE_CORPUS") != "1") return@runBlocking

        val transport = NativeCorpusTransport(client)
        val z0Only = nativeCorpusEnvironment("RENTILE_NATIVE_CORPUS_Z0_ONLY") == "1"
        val outputDirectory = nativeCorpusEnvironment("RENTILE_NATIVE_CORPUS_OUTPUT_DIR")?.toPath()
        outputDirectory?.let(FileSystem.SYSTEM::createDirectories)
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = transport,
                rawResourceStore = NativeCorpusRawResourceStore(),
            ),
        )
        try {
            val styles = loadMapCatalog(transport)
            assertEquals(EXPECTED_STYLE_IDS, styles.map(CatalogStyleEntry::id).toSet())
            val tiles = if (z0Only) listOf(WORLD_TILE) else coverageTiles()
            val seams = if (z0Only) emptyList() else seamCases()
            assertEquals(if (z0Only) 1 else EXPECTED_UNIQUE_TILES_PER_STYLE, tiles.size)

            val failures = mutableListOf<String>()
            val z0Outputs = mutableListOf<Pair<String, ByteArray>>()
            var renderedTileCount = 0
            var renderedMosaicCount = 0
            styles.sortedBy { it.id.toInt() }.forEachIndexed { index, style ->
                try {
                    val prepared = rasterizer.prepare(StyleInput.Remote(style.url))
                    val outputs = tiles.associateWith { tile ->
                        rasterizer.render(prepared, listOf(tile), RenderOptions(OUTPUT_SIZE_PX))
                            .tiles
                            .single()
                            .also { rendered -> validatePng(rendered.pngBytes, OUTPUT_SIZE_PX) }
                    }
                    val z0Png = outputs.getValue(WORLD_TILE).pngBytes
                    z0Outputs += style.id to z0Png
                    outputDirectory?.let { writeOutput(it / "style-${style.id}-z0.png", z0Png) }
                    val z0Luma = meanLuma(z0Png)
                    seams.forEach { seam ->
                        val mosaic = createMosaic(seam, outputs)
                        validatePng(mosaic, OUTPUT_SIZE_PX * 3)
                        renderedMosaicCount += 1
                    }
                    renderedTileCount += outputs.size
                    println(
                            "NATIVE_CORPUS_STYLE ${index + 1}/${styles.size} " +
                            "id=${style.id} tiles=${outputs.size} mosaics=${seams.size} " +
                            "z0_luma=$z0Luma status=RENDERED",
                    )
                } catch (error: RentileException) {
                    val acquisition = error as? ResourceAcquisitionException
                    val causeType = error.cause?.let { it::class.simpleName } ?: "NONE"
                    failures +=
                        "${style.id}:${error.code}:${error.stage}:" +
                            "${acquisition?.resourceClass}:${acquisition?.statusCode}:$causeType"
                    println(
                        "NATIVE_CORPUS_STYLE id=${style.id} status=FAILED code=${error.code} " +
                            "stage=${error.stage} resource_class=${acquisition?.resourceClass} " +
                            "status_code=${acquisition?.statusCode} cause=$causeType",
                    )
                } catch (error: Throwable) {
                    val type = error::class.simpleName ?: "UNKNOWN_FAILURE"
                    failures += "${style.id}:$type"
                    println("NATIVE_CORPUS_STYLE id=${style.id} status=FAILED type=$type")
                }
            }

            assertTrue(failures.isEmpty(), "Native corpus failures: ${failures.joinToString()}")
            assertEquals(EXPECTED_STYLE_IDS.size * tiles.size, renderedTileCount)
            assertEquals(EXPECTED_STYLE_IDS.size * seams.size, renderedMosaicCount)
            outputDirectory?.let { directory ->
                writeOutput(directory / "z0-contact-sheet.png", createContactSheet(z0Outputs))
                FileSystem.SYSTEM.write(directory / "style-order.txt") {
                    writeUtf8(z0Outputs.joinToString(separator = "\n") { it.first })
                }
            }
            println(
                "NATIVE_CORPUS_SUMMARY styles=${styles.size} tiles=$renderedTileCount " +
                    "mosaics=$renderedMosaicCount failures=${failures.size}",
            )
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
            transport.close()
        }
    }

    private suspend fun loadMapCatalog(transport: ResourceTransport): List<CatalogStyleEntry> {
        val styles = mutableListOf<CatalogStyleEntry>()
        val visited = mutableSetOf<String>()
        var declaredCount: Int? = null
        var next: String? = PUBLIC_MAP_CATALOG_URL
        while (next != null) {
            val pageUrl = next
            require(isAllowedCatalogPage(pageUrl)) { "Public catalog pagination left its allowed origin" }
            require(visited.size < MAX_CATALOG_PAGES && visited.add(pageUrl)) {
                "Public catalog pagination is cyclic or exceeds its limit"
            }
            val response = transport.execute(
                TransportRequest(
                    url = pageUrl,
                    resourceClass = ResourceClass.STYLE,
                    maxResponseBytes = MAX_CATALOG_PAGE_BYTES,
                    metadata = TransportRequestMetadata(accept = "application/json"),
                ),
            )
            require(response.statusCode in 200..299) { "Public catalog returned a non-success status" }
            val page = CATALOG_JSON.decodeFromString<MapCatalogPage>(response.body.decodeToString())
            require(page.count in 1..MAX_CATALOG_STYLES)
            require(declaredCount == null || declaredCount == page.count)
            declaredCount = page.count
            styles += page.results.map { item ->
                require(item.mapUrl.startsWith("https://"))
                CatalogStyleEntry(item.id.toString(), item.mapUrl)
            }
            next = page.next
        }
        require(styles.size == declaredCount)
        return styles
    }

    private fun createMosaic(
        tiles: List<TileId>,
        outputs: Map<TileId, RenderedTile>,
    ): ByteArray {
        val minX = tiles.minOf(TileId::x)
        val minY = tiles.minOf(TileId::y)
        val surface = Surface.makeRasterN32Premul(OUTPUT_SIZE_PX * 3, OUTPUT_SIZE_PX * 3)
        try {
            surface.canvas.clear(Color.TRANSPARENT)
            val paint = Paint()
            try {
                tiles.forEach { tile ->
                    val image = Image.makeFromEncoded(outputs.getValue(tile).pngBytes)
                    try {
                        val left = ((tile.x - minX) * OUTPUT_SIZE_PX).toFloat()
                        val top = ((tile.y - minY) * OUTPUT_SIZE_PX).toFloat()
                        surface.canvas.drawImageRect(
                            image,
                            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                            Rect.makeLTRB(
                                left,
                                top,
                                left + OUTPUT_SIZE_PX,
                                top + OUTPUT_SIZE_PX,
                            ),
                            SamplingMode.DEFAULT,
                            paint,
                            true,
                        )
                    } finally {
                        image.close()
                    }
                }
            } finally {
                paint.close()
            }
            val image = surface.makeImageSnapshot()
            try {
                val data = image.encodeToData(EncodedImageFormat.PNG)
                    ?: error("Native Skia could not encode a corpus mosaic")
                try {
                    return data.bytes
                } finally {
                    data.close()
                }
            } finally {
                image.close()
            }
        } finally {
            surface.close()
        }
    }

    private fun createContactSheet(outputs: List<Pair<String, ByteArray>>): ByteArray {
        val rows = (outputs.size + CONTACT_SHEET_COLUMNS - 1) / CONTACT_SHEET_COLUMNS
        val surface = Surface.makeRasterN32Premul(
            CONTACT_SHEET_COLUMNS * CONTACT_SHEET_CELL_PX,
            rows * CONTACT_SHEET_CELL_PX,
        )
        try {
            surface.canvas.clear(Color.TRANSPARENT)
            val paint = Paint()
            try {
                outputs.forEachIndexed { index, (_, pngBytes) ->
                    val image = Image.makeFromEncoded(pngBytes)
                    try {
                        val left = (index % CONTACT_SHEET_COLUMNS * CONTACT_SHEET_CELL_PX).toFloat()
                        val top = (index / CONTACT_SHEET_COLUMNS * CONTACT_SHEET_CELL_PX).toFloat()
                        surface.canvas.drawImageRect(
                            image,
                            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                            Rect.makeLTRB(
                                left,
                                top,
                                left + CONTACT_SHEET_CELL_PX,
                                top + CONTACT_SHEET_CELL_PX,
                            ),
                            SamplingMode.DEFAULT,
                            paint,
                            true,
                        )
                    } finally {
                        image.close()
                    }
                }
            } finally {
                paint.close()
            }
            val image = surface.makeImageSnapshot()
            try {
                val data = image.encodeToData(EncodedImageFormat.PNG)
                    ?: error("Native Skia could not encode the z0 contact sheet")
                try {
                    return data.bytes
                } finally {
                    data.close()
                }
            } finally {
                image.close()
            }
        } finally {
            surface.close()
        }
    }

    private fun writeOutput(path: Path, pngBytes: ByteArray) {
        FileSystem.SYSTEM.write(path) { write(pngBytes) }
    }

    private fun validatePng(bytes: ByteArray, expectedSize: Int) {
        assertTrue(bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes.decodeToString(1, 4) == "PNG")
        val image = Image.makeFromEncoded(bytes)
        try {
            assertEquals(expectedSize, image.width)
            assertEquals(expectedSize, image.height)
        } finally {
            image.close()
        }
    }

    private fun meanLuma(bytes: ByteArray): Int {
        val image = Image.makeFromEncoded(bytes)
        try {
            val bitmap = Bitmap()
            try {
                check(bitmap.allocN32Pixels(image.width, image.height, false))
                check(image.readPixels(bitmap))
                var total = 0L
                for (y in 0 until bitmap.height) {
                    for (x in 0 until bitmap.width) {
                        val color = bitmap.getColor(x, y)
                        val red = color ushr 16 and 0xff
                        val green = color ushr 8 and 0xff
                        val blue = color and 0xff
                        total += (77 * red + 150 * green + 29 * blue) ushr 8
                    }
                }
                return (total / (bitmap.width.toLong() * bitmap.height)).toInt()
            } finally {
                bitmap.close()
            }
        } finally {
            image.close()
        }
    }

    private fun coverageTiles(): List<TileId> = buildList {
        add(TileId(0, 0, 0))
        addAll(
            listOf(
                TileId(1, 0, 0),
                TileId(2, 1, 1),
                TileId(3, 2, 3),
                TileId(4, 4, 6),
                TileId(5, 9, 12),
                TileId(6, 18, 24),
                TileId(7, 37, 48),
                TileId(8, 75, 96),
                TileId(9, 150, 192),
                TileId(10, 301, 385),
                TileId(11, 602, 770),
                TileId(12, 1205, 1540),
                TileId(13, 2411, 3080),
                TileId(14, 4823, 6160),
                TileId(15, 9647, 12320),
                TileId(16, 19295, 24640),
                TileId(17, 38591, 49280),
                TileId(18, 77182, 98561),
                TileId(19, 154364, 197122),
                TileId(20, 308729, 394244),
                TileId(21, 617459, 788488),
                TileId(22, 1234919, 1576977),
            ),
        )
        seamCases().forEach(::addAll)
    }.distinct()

    private fun seamCases(): List<List<TileId>> = listOf(
        threeByThree(6, 18, 24),
        threeByThree(12, 1205, 1540),
        threeByThree(16, 19295, 24640),
        threeByThree(22, 1234919, 1576977),
    )

    private fun threeByThree(z: Int, centerX: Int, centerY: Int): List<TileId> = buildList {
        for (y in centerY - 1..centerY + 1) {
            for (x in centerX - 1..centerX + 1) add(TileId(z, x, y))
        }
    }

    private fun isAllowedCatalogPage(url: String): Boolean =
        url == PUBLIC_MAP_CATALOG_URL || CATALOG_PAGE.matches(url)

    @Serializable
    private data class MapCatalogPage(
        val count: Int,
        val next: String?,
        val results: List<MapCatalogItem>,
    )

    @Serializable
    private data class MapCatalogItem(
        val id: Int,
        @SerialName("map_url") val mapUrl: String,
    )

    private data class CatalogStyleEntry(
        val id: String,
        val url: String,
    )

    private companion object {
        const val PUBLIC_MAP_CATALOG_URL = "https://dashboard.lascade.com/travel_animator/v0/maps/"
        const val MAX_CATALOG_PAGE_BYTES = 1024L * 1024L
        const val MAX_CATALOG_PAGES = 10
        const val MAX_CATALOG_STYLES = 1_000
        const val OUTPUT_SIZE_PX = 512
        const val CONTACT_SHEET_COLUMNS = 6
        const val CONTACT_SHEET_CELL_PX = 256
        const val EXPECTED_UNIQUE_TILES_PER_STYLE = 55
        val WORLD_TILE: TileId = TileId(0, 0, 0)
        val CATALOG_PAGE: Regex =
            Regex("https://dashboard\\.lascade\\.com/travel_animator/v0/maps/\\?page=[1-9][0-9]*")
        val EXPECTED_STYLE_IDS: Set<String> = setOf(
            "17", "49", "51", "52", "53", "54", "55", "56", "57", "58", "59", "60", "61", "62",
            "63", "64", "65", "67", "68", "69", "70", "71", "72", "73", "76", "77", "78", "79",
            "80", "81", "83", "84", "85", "86",
        )
        val CATALOG_JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }
    }
}

internal class NativeCorpusTransport(
    private val client: HttpClient,
) : ResourceTransport, AutoCloseable {
    private val httpsBridgeOrigin: String? =
        nativeCorpusEnvironment("RENTILE_NATIVE_HTTPS_BRIDGE_ORIGIN")?.also { origin ->
            require(LOCAL_PROXY_ORIGIN.matches(origin)) {
                "Native corpus HTTPS bridge must be an HTTP IPv4 loopback origin"
            }
        }

    override suspend fun execute(request: TransportRequest): TransportResponse {
        val response = try {
            client.get(proxiedUrl(request.url)) {
                request.metadata.ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) }
                request.metadata.ifModifiedSince?.let { header(HttpHeaders.IfModifiedSince, it) }
                request.metadata.accept?.let { header(HttpHeaders.Accept, it) }
            }
        } catch (error: Throwable) {
            println(
                "NATIVE_CORPUS_TRANSPORT_FAILURE resource_class=${request.resourceClass} " +
                    "origin=${resourceOrigin(request.url)} cause=${error::class.simpleName ?: "UNKNOWN"} " +
                    "network_code=${networkErrorCode(error)}",
            )
            throw error
        }
        val body = response.bodyAsBytes()
        check(body.size.toLong() <= request.maxResponseBytes) {
            "Native corpus response exceeded its Rentile request limit"
        }
        return TransportResponse(
            statusCode = response.status.value,
            body = body,
            metadata = TransportResponseMetadata(
                contentType = response.headers[HttpHeaders.ContentType],
                etag = response.headers[HttpHeaders.ETag],
                lastModified = response.headers[HttpHeaders.LastModified],
                cacheControl = response.headers[HttpHeaders.CacheControl],
                redirectLocation = response.headers[HttpHeaders.Location],
                wireByteCount = body.size.toLong(),
            ),
        )
    }

    override fun close() {
        client.close()
    }

    private fun proxiedUrl(url: String): String {
        val bridgeOrigin = httpsBridgeOrigin ?: return url
        if (!url.startsWith("https://")) return url
        return "$bridgeOrigin/fetch/${url.encodeToByteArray().hexEncoded()}"
    }

    private fun resourceOrigin(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return "NON_HTTP"
        val authorityStart = schemeEnd + 3
        val authorityEnd = url.indexOf('/', authorityStart).let { if (it < 0) url.length else it }
        return url.substring(0, authorityEnd).substringBefore('?')
    }

    private fun networkErrorCode(error: Throwable): String =
        NETWORK_ERROR_CODE.find(error.message.orEmpty())?.groupValues?.get(1) ?: "UNKNOWN"

    private companion object {
        val LOCAL_PROXY_ORIGIN: Regex = Regex("http://127\\.0\\.0\\.1:[1-9][0-9]{0,4}")
        val NETWORK_ERROR_CODE: Regex = Regex("NSURLErrorDomain Code=(-?[0-9]+)")
    }
}

private fun ByteArray.hexEncoded(): String = buildString(size * 2) {
    this@hexEncoded.forEach { byte ->
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0f])
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

@OptIn(ExperimentalForeignApi::class)
internal fun nativeCorpusEnvironment(name: String): String? = getenv(name)?.toKString()

private class NativeCorpusRawResourceStore : RawResourceStore {
    private val mutex = Mutex()
    private val entries = mutableMapOf<RawResourceKey, StoredRawResource>()

    override suspend fun read(key: RawResourceKey): StoredRawResource? = mutex.withLock { entries[key] }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        mutex.withLock { entries[key] = resource }
    }

    override suspend fun remove(key: RawResourceKey) {
        mutex.withLock { entries.remove(key) }
    }
}
