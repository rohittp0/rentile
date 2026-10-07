package com.rohittp.rentile

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * Opt-in measurement of the label glyph atlas a live style produces under each
 * [LabelGlyphAtlasPolicy], against the public map catalog. It asserts nothing: it is how the
 * numbers in the 0.12.0 migration notes were taken, kept so they can be taken again.
 *
 * Runs only when `RENTILE_GLYPH_ATLAS_PROBE_DIR` names a directory, where it writes
 * `glyph-atlas-footprint.tsv`. `RENTILE_GLYPH_ATLAS_PROBE_STYLE` selects the catalog style by
 * name and defaults to `Outdoor`, the style the corpus README's Tokyo measurement used. Like the
 * corpus gate, it records no URL, credential or font stack.
 */
class GlyphAtlasFootprintProbe {
    @Test
    fun measuresTheGlyphAtlasUnderEachPolicy(): Unit = runBlocking {
        val output = System.getenv("RENTILE_GLYPH_ATLAS_PROBE_DIR")?.takeIf(String::isNotBlank)?.let(Path::of)
            ?: return@runBlocking
        Files.createDirectories(output)
        val styleName = System.getenv("RENTILE_GLYPH_ATLAS_PROBE_STYLE")?.takeIf(String::isNotBlank) ?: "Outdoor"
        val styleUrl = catalogStyleUrl(styleName)
        // One store for every rasterizer, so only the first policy pays for the network and the
        // rest measure the same bytes.
        val store = ProbeRawResourceStore()
        val rows = mutableListOf(
            listOf(
                "case", "tiles", "policy", "glyphRanges", "candidates", "atlasEntries", "atlasWidth",
                "atlasHeight", "decodedBytes", "pngBytes", "acquireMillis", "outcome",
            ).joinToString("\t"),
        )
        for ((case, tiles) in CASES) {
            for ((policyName, policy) in POLICIES) {
                val rasterizer = Rentile.create(
                    RentileConfiguration(transport = probeTransport, rawResourceStore = store, labelGlyphAtlas = policy),
                )
                try {
                    val style = rasterizer.prepare(StyleInput.Remote(styleUrl))
                    val plan = rasterizer.planLabelCandidates(style, tiles)
                    val ranges = plan.glyphClosure.size
                    val started = TimeSource.Monotonic.markNow()
                    val row = try {
                        val batch = rasterizer.acquireLabelCandidates(plan)
                        val millis = started.elapsedNow().inWholeMilliseconds
                        val atlas = batch.atlas
                        listOf(
                            batch.candidates.size, atlas.entries.size, atlas.width, atlas.height,
                            atlas.width.toLong() * atlas.height * 4L, atlas.pngBytes.size, millis, "OK",
                        )
                    } catch (failure: SafetyLimitException) {
                        listOf("-", "-", "-", "-", "-", "-", started.elapsedNow().inWholeMilliseconds,
                            "SafetyLimitException ${failure.limitName} limit=${failure.limit} observed=${failure.observed}")
                    } finally {
                        plan.close()
                    }
                    rows += (listOf(case, tiles.size, policyName, ranges) + row).joinToString("\t")
                } finally {
                    rasterizer.close()
                    rasterizer.awaitClosed()
                }
            }
        }
        val report = rows.joinToString("\n", postfix = "\n")
        Files.writeString(output.resolve("glyph-atlas-footprint.tsv"), report)
        println(report)
    }

    private suspend fun catalogStyleUrl(name: String): String {
        var next: String? = CATALOG_URL
        while (next != null) {
            val page = Json.parseToJsonElement(fetch(next).body.decodeToString()) as JsonObject
            val match = (page["results"] as JsonArray).map { it as JsonObject }
                .firstOrNull { (it["name"] as JsonPrimitive).content.trim() == name }
            if (match != null) return (match["map_url"] as JsonPrimitive).content
            next = (page["next"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
        error("The public catalog lists no style with that name")
    }

    private suspend fun fetch(url: String): TransportResponse = probeTransport.execute(
        TransportRequest(url = url, resourceClass = ResourceClass.STYLE, maxResponseBytes = 1L shl 20),
    )

    private val probeTransport = ResourceTransport { request ->
        val connection = URI(request.url).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            request.metadata.accept?.let { connection.setRequestProperty("Accept", it) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val maximum = request.maxResponseBytes.coerceAtMost(Int.MAX_VALUE.toLong() - 1).toInt()
            val body = stream?.use { it.readNBytes(maximum + 1) } ?: ByteArray(0)
            check(body.size <= maximum) { "Probe response exceeded its Rentile request limit" }
            TransportResponse(
                statusCode = status,
                body = body,
                metadata = TransportResponseMetadata(
                    contentType = connection.contentType,
                    redirectLocation = connection.getHeaderField("Location"),
                    wireByteCount = body.size.toLong(),
                ),
            )
        } finally {
            connection.disconnect()
        }
    }

    private class ProbeRawResourceStore : RawResourceStore {
        private val entries = ConcurrentHashMap<RawResourceKey, StoredRawResource>()

        override suspend fun read(key: RawResourceKey): StoredRawResource? = entries[key]

        override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
            entries[key] = resource
        }

        override suspend fun remove(key: RawResourceKey) {
            entries.remove(key)
        }
    }

    private companion object {
        const val CATALOG_URL = "https://dashboard.lascade.com/travel_animator/v0/maps/"

        /** The corpus manifest's `tokyo-cjk-dense` and `new-york-zoom-ladder` z14 tiles, alone and as 3x3 viewports. */
        val CASES: List<Pair<String, List<TileId>>> = listOf(
            "tokyo-z14" to listOf(TileId(14, 14547, 6451)),
            "tokyo-z14-3x3" to around(TileId(14, 14547, 6451)),
            "new-york-z14" to listOf(TileId(14, 4823, 6160)),
            "new-york-z14-3x3" to around(TileId(14, 4823, 6160)),
        )

        val POLICIES: List<Pair<String, LabelGlyphAtlasPolicy>> = listOf(
            "ACQUIRED_RANGES" to LabelGlyphAtlasPolicy(),
            "REFERENCED_GLYPHS" to LabelGlyphAtlasPolicy(packing = LabelGlyphPacking.REFERENCED_GLYPHS),
            "ACQUIRED_RANGES+4096" to LabelGlyphAtlasPolicy(maxDimensionPx = 4096),
            "REFERENCED_GLYPHS+4096" to LabelGlyphAtlasPolicy(packing = LabelGlyphPacking.REFERENCED_GLYPHS, maxDimensionPx = 4096),
        )

        fun around(center: TileId): List<TileId> =
            (-1..1).flatMap { dy -> (-1..1).map { dx -> TileId(center.z, center.x + dx, center.y + dy) } }
    }
}
