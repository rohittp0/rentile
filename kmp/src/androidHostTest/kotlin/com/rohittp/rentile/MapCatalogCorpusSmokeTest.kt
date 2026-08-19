package com.rohittp.rentile

import com.rohittp.rentile.internal.glyph.ScriptSupport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Opt-in smoke test for the rolling public map catalog.
 *
 * RENTILE_COVERAGE_MANIFEST points to the committed credential-free coverage
 * definition. No style URL or raw provider response is written to the report.
 */
class MapCatalogCorpusSmokeTest {
    @Test
    fun rendersPublicCatalogCoverageThroughPublicInterface(): Unit = runBlocking {
        val coveragePath = environmentPath("RENTILE_COVERAGE_MANIFEST") ?: return@runBlocking
        val outputDirectory = environmentPath("RENTILE_CORPUS_REPORT_DIR")
            ?: Path.of("build", "reports", "rentile-corpus")
        Files.createDirectories(outputDirectory)

        val transport = smokeTransport()
        val styles = loadMapCatalog(transport, PUBLIC_MAP_CATALOG_URL)
        val coverage = loadCoverageManifest(coveragePath)
        validateInputs(styles, coverage)
        val selectedStyleId = System.getenv("RENTILE_CORPUS_STYLE_ID")?.takeIf(String::isNotBlank)
        val selectedStyles = selectedStyleId?.let { id ->
            styles.filter { style -> style.id == id }.also { matches ->
                require(matches.size == 1) { "Requested corpus style id is not present in the public catalog" }
            }
        } ?: styles

        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = transport,
                rawResourceStore = SmokeRawResourceStore(),
            ),
        )
        val results = try {
            selectedStyles
                .sortedBy { style -> style.id.toInt() }
                .map { style -> renderStyle(rasterizer, style, coverage, outputDirectory) }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }

        writeReports(outputDirectory, coverage, results)
        val completedStyles = results.count { result ->
            result.preparationErrorCode == null &&
                result.tiles.values.all { it.status == SmokeStatus.RENDERED } &&
                result.labelResults.all { it.errorCode == null }
        }
        assertEquals(
            expected = results.size,
            actual = completedStyles,
            message = "Only $completedStyles/${results.size} styles passed the Coverage Manifest; inspect ${outputDirectory.resolve("index.html")}",
        )
    }

    private suspend fun renderStyle(
        rasterizer: BasemapRasterizer,
        style: CatalogStyleEntry,
        coverage: CoverageManifest,
        outputDirectory: Path,
    ): StyleSmokeResult {
        val prepared = try {
            rasterizer.prepare(StyleInput.Remote(style.url))
        } catch (error: RentileException) {
            return StyleSmokeResult(
                styleId = style.id,
                styleName = style.name,
                preparationErrorCode = error.code.name,
                diagnostics = error.redactedDiagnosticSummaries(),
                tiles = emptyMap(),
                mosaics = emptyMap(),
                labelResults = emptyList(),
            )
        } catch (error: Throwable) {
            return StyleSmokeResult(
                styleId = style.id,
                styleName = style.name,
                preparationErrorCode = error::class.simpleName ?: "UNKNOWN_FAILURE",
                diagnostics = emptyList(),
                tiles = emptyMap(),
                mosaics = emptyMap(),
                labelResults = emptyList(),
            )
        }

        val uniqueTiles = coverage.cases.flatMap(CoverageCase::tiles).map(CoverageTile::asTileId).distinct()
        val outcomes = uniqueTiles.associateWith { tile ->
            renderTile(rasterizer, prepared, style.id, tile, outputDirectory)
        }
        val mosaics = createMosaics(style.id, coverage, outcomes, outputDirectory)
        val labelResults = acquireLabelSmoke(rasterizer, prepared, coverage)
        return StyleSmokeResult(
            styleId = style.id,
            styleName = style.name,
            preparationErrorCode = null,
            diagnostics = prepared.diagnostics.map { "${it.code}: ${it.message}" }.distinct(),
            tiles = outcomes,
            mosaics = mosaics,
            labelResults = labelResults,
        )
    }

    /**
     * Acquires label candidates for the three cases whose geography and script exercise the
     * label pipeline against live styles: [LABEL_SMOKE_CASE_IDS]. Only the highest-zoom tile of
     * each case is used - label correctness varies by geography and script, not by zoom, and the
     * publish gate is already a long-running job. See `compatibility/README.md`.
     */
    private suspend fun acquireLabelSmoke(
        rasterizer: BasemapRasterizer,
        prepared: PreparedStyle,
        coverage: CoverageManifest,
    ): List<LabelCaseSmokeResult> =
        LABEL_SMOKE_CASE_IDS.map { caseId ->
            val case = coverage.cases.first { it.id == caseId }
            val tile = case.tiles.maxBy(CoverageTile::z).asTileId()
            acquireLabelCaseSmoke(rasterizer, prepared, caseId, tile)
        }

    /**
     * Mirrors [renderTile]'s architecture deliberately: a failed invariant becomes an
     * [LabelCaseSmokeResult.errorCode] rather than a thrown assertion, so one style's violation
     * neither stops the remaining styles from acquiring nor loses the Corpus Report that
     * [writeReports] would otherwise never reach. The one final `assertEquals` in
     * [rendersPublicCatalogCoverageThroughPublicInterface] still fails the gate when any style
     * carries a non-null error code here - acquisition throwing included.
     */
    private suspend fun acquireLabelCaseSmoke(
        rasterizer: BasemapRasterizer,
        prepared: PreparedStyle,
        caseId: String,
        tile: TileId,
    ): LabelCaseSmokeResult {
        val batch = try {
            rasterizer.acquireLabelCandidates(prepared, listOf(tile))
        } catch (error: RentileException) {
            return LabelCaseSmokeResult(
                caseId = caseId,
                tile = tile,
                errorCode = error.code.name,
                candidateCount = 0,
                atlasWidth = 0,
                atlasHeight = 0,
                glyphRangeCount = 0,
                diagnostics = error.redactedDiagnosticSummaries(),
            )
        } catch (error: Throwable) {
            return LabelCaseSmokeResult(
                caseId = caseId,
                tile = tile,
                errorCode = error::class.simpleName ?: "UNKNOWN_FAILURE",
                candidateCount = 0,
                atlasWidth = 0,
                atlasHeight = 0,
                glyphRangeCount = 0,
                diagnostics = emptyList(),
            )
        }

        // Distinct (font stack, 256-codepoint block) pairs: what `maxGlyphRangesPerBatch` counts.
        val glyphRangeCount = batch.atlas.entries
            .map { entry -> entry.fontStackDigest to entry.codepoint / GLYPH_RANGE_SIZE }
            .distinct()
            .size
        val diagnostics = batch.diagnostics.map { "${it.code}: ${it.message}" }.distinct().toMutableList()

        var errorCode: String? = null
        if (glyphRangeCount > MAX_GLYPH_RANGES_PER_BATCH) {
            errorCode = "GLYPH_RANGE_LIMIT_EXCEEDED"
            diagnostics += "$errorCode: acquired $glyphRangeCount glyph ranges, over the " +
                "$MAX_GLYPH_RANGES_PER_BATCH maxGlyphRangesPerBatch ceiling acquireLabelCandidates " +
                "is supposed to enforce"
        }
        if (caseId == CAIRO_CASE_ID) {
            cairoOutcomeFailure(batch)?.let { failureDetail ->
                errorCode = errorCode ?: "CAIRO_SCRIPT_OUTCOME_INVALID"
                diagnostics += "CAIRO_SCRIPT_OUTCOME_INVALID: $failureDetail"
            }
        }

        return LabelCaseSmokeResult(
            caseId = caseId,
            tile = tile,
            errorCode = errorCode,
            candidateCount = batch.candidates.size,
            atlasWidth = batch.atlas.width,
            atlasHeight = batch.atlas.height,
            glyphRangeCount = glyphRangeCount,
            diagnostics = diagnostics,
        )
    }

    /**
     * Cairo is right-to-left, so exactly two outcomes are acceptable: either the style branched
     * on `is-supported-script` and every candidate's resolved text is a script this renderer's
     * glyph-metrics-only layout can lay out (typically a `name:latin` fallback), or no candidates
     * exist because [DiagnosticCode.COMPLEX_SCRIPT_LABEL_EXCLUDED] reported the exclusion.
     * Candidates whose text still requires complex shaping would render as garbled output and
     * must fail the gate - that is the one outcome this check exists to catch. Returns a
     * redaction-safe description of the violation, or null when the outcome is acceptable.
     */
    private fun cairoOutcomeFailure(batch: LabelCandidateBatch): String? {
        if (batch.candidates.isEmpty()) {
            return if (batch.diagnostics.any { it.code == DiagnosticCode.COMPLEX_SCRIPT_LABEL_EXCLUDED }) {
                null
            } else {
                "no label candidates were acquired without COMPLEX_SCRIPT_LABEL_EXCLUDED being " +
                    "reported - every corpus style has a resolvable glyphs template, so an empty " +
                    "batch here is otherwise unexplained"
            }
        }
        val garbledCandidateCount = batch.candidates.count { candidate ->
            val text = candidate.glyphs.joinToString(separator = "") { quad ->
                String(Character.toChars(batch.atlas.entries[quad.entryIndex].codepoint))
            }
            ScriptSupport.requiresComplexShaping(text)
        }
        return if (garbledCandidateCount == 0) {
            null
        } else {
            "$garbledCandidateCount/${batch.candidates.size} label candidates still require a " +
                "script this renderer cannot lay out; the style must fall back to a supported " +
                "script via is-supported-script or the label must be excluded entirely"
        }
    }

    @Test
    fun catalogLoaderFollowsBoundedPagination(): Unit = runBlocking {
        val secondPage = "$PUBLIC_MAP_CATALOG_URL?page=2"
        val responses = mapOf(
            PUBLIC_MAP_CATALOG_URL to catalogPageJson(
                count = 2,
                next = secondPage,
                id = 17,
                name = "First",
            ),
            secondPage to catalogPageJson(
                count = 2,
                next = null,
                id = 49,
                name = "Second",
            ),
        )
        val requested = mutableListOf<String>()
        val styles = loadMapCatalog(
            transport = ResourceTransport { request ->
                requested += request.url
                TransportResponse(200, responses.getValue(request.url).encodeToByteArray())
            },
            initialUrl = PUBLIC_MAP_CATALOG_URL,
        )

        assertEquals(listOf(PUBLIC_MAP_CATALOG_URL, secondPage), requested)
        assertEquals(listOf("17", "49"), styles.map(CatalogStyleEntry::id))
    }

    private suspend fun renderTile(
        rasterizer: BasemapRasterizer,
        prepared: PreparedStyle,
        styleId: String,
        tile: TileId,
        outputDirectory: Path,
    ): TileSmokeResult = try {
        val rendered = rasterizer.render(
            style = prepared,
            tiles = listOf(tile),
            options = RenderOptions(512),
        ).tiles.single()
        val relativePath = Path.of("tiles", styleId, "${tile.z}-${tile.x}-${tile.y}.png")
        val outputPath = outputDirectory.resolve(relativePath)
        Files.createDirectories(outputPath.parent)
        Files.write(
            outputPath,
            rendered.pngBytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        TileSmokeResult(
            status = SmokeStatus.RENDERED,
            pngFile = relativePath.toString(),
            errorCode = null,
            diagnostics = rendered.diagnostics.map { "${it.code}: ${it.message}" }.distinct(),
        )
    } catch (error: RentileException) {
        TileSmokeResult(
            status = SmokeStatus.FAILED,
            pngFile = null,
            errorCode = error.code.name,
            diagnostics = error.redactedDiagnosticSummaries(),
        )
    } catch (error: Throwable) {
        TileSmokeResult(
            status = SmokeStatus.FAILED,
            pngFile = null,
            errorCode = error::class.simpleName ?: "UNKNOWN_FAILURE",
            diagnostics = emptyList(),
        )
    }

    private fun createMosaics(
        styleId: String,
        coverage: CoverageManifest,
        outcomes: Map<TileId, TileSmokeResult>,
        outputDirectory: Path,
    ): Map<String, String> = buildMap {
        coverage.cases.filter { "mosaic-3x3" in it.tags }.forEach { case ->
            val tiles = case.tiles.map(CoverageTile::asTileId)
            val files = tiles.associateWith { outcomes[it]?.pngFile }
            if (files.values.any { it == null }) return@forEach
            val minX = tiles.minOf(TileId::x)
            val minY = tiles.minOf(TileId::y)
            val sizePx = 512
            val surface = Surface.makeRasterN32Premul(sizePx * 3, sizePx * 3)
            try {
                surface.canvas.clear(Color.TRANSPARENT)
                val paint = Paint()
                try {
                    files.forEach { (tile, relativeFile) ->
                        val image = Image.makeFromEncoded(Files.readAllBytes(outputDirectory.resolve(relativeFile!!)))
                        try {
                            val left = ((tile.x - minX) * sizePx).toFloat()
                            val top = ((tile.y - minY) * sizePx).toFloat()
                            surface.canvas.drawImageRect(
                                image,
                                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                                Rect.makeLTRB(left, top, left + sizePx, top + sizePx),
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
                    val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("Skia could not encode a corpus mosaic")
                    try {
                        val relativePath = Path.of("mosaics", "$styleId-${case.id}.png")
                        val outputPath = outputDirectory.resolve(relativePath)
                        Files.createDirectories(outputPath.parent)
                        Files.write(
                            outputPath,
                            data.bytes,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                        )
                        put(case.id, relativePath.toString())
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
    }

    private suspend fun loadMapCatalog(
        transport: ResourceTransport,
        initialUrl: String,
    ): List<CatalogStyleEntry> {
        validateCatalogPageUrl(initialUrl)
        val styles = mutableListOf<CatalogStyleEntry>()
        val visitedPages = mutableSetOf<String>()
        var expectedCount: Int? = null
        var nextUrl: String? = initialUrl
        while (nextUrl != null) {
            require(visitedPages.size < MAX_CATALOG_PAGES) { "Map catalog exceeds its page limit" }
            require(visitedPages.add(nextUrl)) { "Map catalog pagination contains a cycle" }
            val response = transport.execute(
                TransportRequest(
                    url = nextUrl,
                    resourceClass = ResourceClass.STYLE,
                    maxResponseBytes = MAX_CATALOG_PAGE_BYTES,
                    metadata = TransportRequestMetadata(accept = "application/json"),
                ),
            )
            require(response.statusCode in 200..299) { "Map catalog returned a non-success status" }
            val page = catalogJson.decodeFromString<MapCatalogPage>(response.body.decodeToString())
            require(page.count in 1..MAX_CATALOG_STYLES) { "Map catalog count is outside its limit" }
            require(expectedCount == null || expectedCount == page.count) { "Map catalog count changed between pages" }
            expectedCount = page.count
            styles += page.results.map { item ->
                CatalogStyleEntry(
                    id = item.id.toString(),
                    name = item.name,
                    url = item.mapUrl,
                )
            }
            nextUrl = page.next?.also(::validateCatalogPageUrl)
        }
        require(expectedCount != null && styles.size == expectedCount) {
            "Map catalog pagination did not return its declared count"
        }
        return styles
    }

    private fun validateCatalogPageUrl(url: String) {
        val uri = runCatching { URI(url) }.getOrNull()
        require(
            uri?.scheme == "https" &&
                uri.host == PUBLIC_CATALOG_HOST &&
                uri.path == PUBLIC_CATALOG_PATH &&
                uri.userInfo == null &&
                uri.fragment == null,
        ) { "Map catalog pagination left the configured public catalog" }
        val queryNames = uri.rawQuery.orEmpty()
            .split('&')
            .filter(String::isNotEmpty)
            .map { parameter -> parameter.substringBefore('=').lowercase() }
        require(queryNames.all { it == "page" }) { "Map catalog pagination contains an unsupported query" }
    }

    private fun loadCoverageManifest(path: Path): CoverageManifest =
        strictJson.decodeFromString(Files.readString(path))

    private fun validateInputs(styles: List<CatalogStyleEntry>, coverage: CoverageManifest) {
        require(coverage.schemaVersion == 1 && coverage.profileId == "rentile-v1") {
            "Unexpected Coverage Manifest schema or profile"
        }
        require(coverage.outputZoomRange == ZoomRange(minimum = 0, maximum = 22)) {
            "RentileV1 coverage must span z0 through z22"
        }
        require(styles.isNotEmpty()) { "Public map catalog contains no styles" }
        val ids = styles.map(CatalogStyleEntry::id)
        require(ids.size == ids.toSet().size && ids.all { STYLE_REF.matches(it) }) {
            "Public map catalog style references must be unique safe identifiers"
        }
        styles.forEach { style ->
            val uri = runCatching { URI(style.url) }.getOrNull()
            require(uri?.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
                "Every catalog style must use an absolute HTTPS URL without user-info"
            }
        }
        require(ids.toSet() == coverage.styleRefs.toSet()) {
            "Public map catalog references do not match the Coverage Manifest"
        }
        require(coverage.cases.flatMap(CoverageCase::tiles).all { it.z in 0..22 }) {
            "Coverage Manifest contains an unsupported output zoom"
        }
        require(LABEL_SMOKE_CASE_IDS.all { id -> coverage.cases.any { case -> case.id == id } }) {
            "Coverage Manifest is missing a case the corpus gate acquires label candidates for"
        }
    }

    private fun writeReports(
        outputDirectory: Path,
        coverage: CoverageManifest,
        results: List<StyleSmokeResult>,
    ) {
        val rows = buildList {
            results.forEach { result ->
                if (result.preparationErrorCode != null) {
                    add(
                        listOf(
                            result.styleId,
                            result.styleName,
                            "PREPARATION",
                            "",
                            "",
                            "",
                            SmokeStatus.FAILED.name,
                            result.preparationErrorCode,
                            "",
                            result.diagnostics.joinToString(" | "),
                            "",
                            "",
                            "",
                        ),
                    )
                } else {
                    coverage.cases.forEach { case ->
                        case.tiles.forEach { tile ->
                            val outcome = result.tiles.getValue(tile.asTileId())
                            add(
                                listOf(
                                    result.styleId,
                                    result.styleName,
                                    case.id,
                                    tile.z.toString(),
                                    tile.x.toString(),
                                    tile.y.toString(),
                                    outcome.status.name,
                                    outcome.errorCode.orEmpty(),
                                    outcome.pngFile.orEmpty(),
                                    outcome.diagnostics.joinToString(" | "),
                                    "",
                                    "",
                                    "",
                                ),
                            )
                        }
                    }
                    result.labelResults.forEach { labelResult ->
                        add(
                            listOf(
                                result.styleId,
                                result.styleName,
                                "label:${labelResult.caseId}",
                                labelResult.tile.z.toString(),
                                labelResult.tile.x.toString(),
                                labelResult.tile.y.toString(),
                                if (labelResult.errorCode == null) "ACQUIRED" else "FAILED",
                                labelResult.errorCode.orEmpty(),
                                "",
                                labelResult.diagnostics.joinToString(" | "),
                                "candidates=${labelResult.candidateCount}",
                                "atlas=${labelResult.atlasWidth}x${labelResult.atlasHeight}",
                                "ranges=${labelResult.glyphRangeCount}",
                            ),
                        )
                    }
                }
            }
        }
        val tsv = buildString {
            appendLine(
                "style_id\tstyle_name\tcase_id\tz\tx\ty\tstatus\terror_code\tpng\tdiagnostics\t" +
                    "candidates\tatlas\tranges",
            )
            rows.forEach { row -> appendLine(row.joinToString("\t") { it.tsvSafe() }) }
        }
        Files.writeString(
            outputDirectory.resolve("results.tsv"),
            tsv,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        Files.writeString(
            outputDirectory.resolve("capabilities.txt"),
            coverage.requiredCapabilities.joinToString(separator = "\n", postfix = "\n"),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )

        val z0 = TileId(0, 0, 0)
        val z0Rendered = results.count { it.tiles[z0]?.status == SmokeStatus.RENDERED }
        val complete = results.count { result ->
            result.preparationErrorCode == null &&
                result.tiles.values.all { it.status == SmokeStatus.RENDERED } &&
                result.labelResults.all { it.errorCode == null }
        }
        val html = buildString {
            appendLine("<!doctype html>")
            appendLine("<html lang=\"en\"><head><meta charset=\"utf-8\">")
            appendLine("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            appendLine("<title>Rentile corpus report</title>")
            appendLine("<style>")
            appendLine("body{margin:0;padding:24px;background:#11151b;color:#eef2f7;font:15px system-ui,sans-serif}")
            appendLine("h1{margin:0 0 8px}.summary{margin:0 0 24px;color:#aeb9c6}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:18px}")
            appendLine("article{overflow:hidden;border:1px solid #34404d;border-radius:12px;background:#1a2028}img,.missing{display:block;width:100%;aspect-ratio:1;background:#0c1015;object-fit:contain}")
            appendLine(".missing{display:grid;place-items:center;color:#ff9b9b;font-weight:700}.content{padding:12px}h2{font-size:17px;margin:0 0 5px}.meta{color:#9da9b7;margin:0 0 9px}")
            appendLine("details{color:#cbd3dc}code{white-space:normal;color:#ffb4b4}a{color:#8dc8ff}")
            appendLine("</style></head><body>")
            appendLine("<h1>Rentile rolling map corpus</h1>")
            appendLine("<p class=\"summary\">$z0Rendered/${results.size} z0 maps and $complete/${results.size} complete style coverage runs succeeded. Failed cards are not substitute images.</p>")
            appendLine("<main class=\"grid\">")
            results.forEach { result ->
                val z0Outcome = result.tiles[z0]
                appendLine("<article>")
                if (z0Outcome?.pngFile != null) {
                    appendLine("<img src=\"${z0Outcome.pngFile.htmlSafe()}\" alt=\"${result.styleName.htmlSafe()} zoom-zero render\">")
                } else {
                    appendLine("<div class=\"missing\">RENDER FAILED</div>")
                }
                appendLine("<div class=\"content\"><h2>${result.styleName.htmlSafe()}</h2>")
                val successfulTiles = result.tiles.values.count { it.status == SmokeStatus.RENDERED }
                appendLine("<p class=\"meta\">Style ${result.styleId.htmlSafe()} · $successfulTiles/${result.tiles.size} unique tiles${result.preparationErrorCode?.let { " · ${it.htmlSafe()}" }.orEmpty()}</p>")
                if (result.mosaics.isNotEmpty()) {
                    appendLine("<details><summary>Seam mosaics</summary><ul>")
                    result.mosaics.forEach { (caseId, file) ->
                        appendLine("<li><a href=\"${file.htmlSafe()}\">${caseId.htmlSafe()}</a></li>")
                    }
                    appendLine("</ul></details>")
                }
                if (result.diagnostics.isNotEmpty()) {
                    appendLine("<details><summary>${result.diagnostics.size} preparation diagnostics</summary><ul>")
                    result.diagnostics.forEach { appendLine("<li><code>${it.htmlSafe()}</code></li>") }
                    appendLine("</ul></details>")
                }
                if (result.labelResults.isNotEmpty()) {
                    appendLine("<details><summary>Label candidates</summary><ul>")
                    result.labelResults.forEach { labelResult ->
                        val summary = if (labelResult.errorCode != null) {
                            "FAILED (${labelResult.errorCode})"
                        } else {
                            "${labelResult.candidateCount} candidates · " +
                                "atlas ${labelResult.atlasWidth}x${labelResult.atlasHeight} · " +
                                "${labelResult.glyphRangeCount} glyph ranges"
                        }
                        appendLine(
                            "<li><strong>${labelResult.caseId.htmlSafe()}</strong> " +
                                "(z${labelResult.tile.z}): ${summary.htmlSafe()}" +
                                if (labelResult.diagnostics.isNotEmpty()) {
                                    "<br><code>${labelResult.diagnostics.joinToString(" | ").htmlSafe()}</code>"
                                } else {
                                    ""
                                } +
                                "</li>",
                        )
                    }
                    appendLine("</ul></details>")
                }
                appendLine("</div></article>")
            }
            appendLine("</main></body></html>")
        }
        Files.writeString(
            outputDirectory.resolve("index.html"),
            html,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    private fun smokeTransport(): ResourceTransport = ResourceTransport { request ->
        val connection = URI(request.url).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            request.metadata.accept?.let { connection.setRequestProperty("Accept", it) }
            request.metadata.ifNoneMatch?.let { connection.setRequestProperty("If-None-Match", it) }
            request.metadata.ifModifiedSince?.let { connection.setRequestProperty("If-Modified-Since", it) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val maximum = request.maxResponseBytes.coerceAtMost(Int.MAX_VALUE.toLong() - 1).toInt()
            val body = stream?.use { it.readNBytes(maximum + 1) } ?: ByteArray(0)
            check(body.size <= maximum) { "Smoke response exceeded its Rentile request limit" }
            TransportResponse(
                statusCode = status,
                body = body,
                metadata = TransportResponseMetadata(
                    contentType = connection.contentType,
                    etag = connection.getHeaderField("ETag"),
                    lastModified = connection.getHeaderField("Last-Modified"),
                    cacheControl = connection.getHeaderField("Cache-Control"),
                    redirectLocation = connection.getHeaderField("Location"),
                    wireByteCount = body.size.toLong(),
                ),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun RentileException.redactedDiagnosticSummaries(): List<String> =
        (listOfNotNull(message) + diagnostics.map { "${it.code}: ${it.message}" }).distinct()

    private fun catalogPageJson(count: Int, next: String?, id: Int, name: String): String =
        """{"count":$count,"next":${next?.let { "\"$it\"" } ?: "null"},"previous":null,"results":[{"free_for":0,"id":$id,"map_url":"https://dashboard.lascade.com/travel_animator/v0/maps/$id/","name":"$name","premium":false,"thumbnail":"https://dashboard.lascade.com/static/$id.png"}]}"""

    @Serializable
    private data class MapCatalogPage(
        val count: Int,
        val next: String?,
        val results: List<MapCatalogItem>,
    )

    @Serializable
    private data class MapCatalogItem(
        val id: Int,
        val name: String,
        @SerialName("map_url") val mapUrl: String,
    )

    private data class CatalogStyleEntry(
        val id: String,
        val name: String,
        val url: String,
    )

    @Serializable
    private data class CoverageManifest(
        val schemaVersion: Int,
        val profileId: String,
        val outputZoomRange: ZoomRange,
        val styleRefs: List<String>,
        val requiredCapabilities: List<String>,
        val cases: List<CoverageCase>,
    )

    @Serializable
    private data class ZoomRange(
        val minimum: Int,
        val maximum: Int,
    )

    @Serializable
    private data class CoverageCase(
        val id: String,
        val tags: List<String>,
        val tiles: List<CoverageTile>,
    )

    @Serializable
    private data class CoverageTile(
        val z: Int,
        val x: Int,
        val y: Int,
    ) {
        fun asTileId(): TileId = TileId(z, x, y)
    }

    private data class StyleSmokeResult(
        val styleId: String,
        val styleName: String,
        val preparationErrorCode: String?,
        val diagnostics: List<String>,
        val tiles: Map<TileId, TileSmokeResult>,
        val mosaics: Map<String, String>,
        val labelResults: List<LabelCaseSmokeResult>,
    )

    private data class TileSmokeResult(
        val status: SmokeStatus,
        val pngFile: String?,
        val errorCode: String?,
        val diagnostics: List<String>,
    )

    private data class LabelCaseSmokeResult(
        val caseId: String,
        val tile: TileId,
        val errorCode: String?,
        val candidateCount: Int,
        val atlasWidth: Int,
        val atlasHeight: Int,
        val glyphRangeCount: Int,
        val diagnostics: List<String>,
    )

    private enum class SmokeStatus {
        RENDERED,
        FAILED,
    }

    private class SmokeRawResourceStore : RawResourceStore {
        private val entries = ConcurrentHashMap<RawResourceKey, StoredRawResource>()

        override suspend fun read(key: RawResourceKey): StoredRawResource? = entries[key]

        override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
            entries[key] = resource
        }

        override suspend fun remove(key: RawResourceKey) {
            entries.remove(key)
        }
    }

    private fun environmentPath(name: String): Path? =
        System.getenv(name)?.takeIf(String::isNotBlank)?.let(Path::of)

    private fun String.tsvSafe(): String = replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')

    private fun String.htmlSafe(): String = buildString(length) {
        this@htmlSafe.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '\"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> character
                },
            )
        }
    }

    private companion object {
        const val PUBLIC_MAP_CATALOG_URL = "https://dashboard.lascade.com/travel_animator/v0/maps/"
        const val PUBLIC_CATALOG_HOST = "dashboard.lascade.com"
        const val PUBLIC_CATALOG_PATH = "/travel_animator/v0/maps/"
        const val MAX_CATALOG_PAGE_BYTES = 1024L * 1024L
        const val MAX_CATALOG_PAGES = 10
        const val MAX_CATALOG_STYLES = 1_000

        /**
         * Cases the corpus gate acquires label candidates for, one highest-zoom tile each: a
         * Latin baseline, and the two non-Latin cases exercising CJK glyph-range fan-out and the
         * complex-script exclusion path. See `compatibility/README.md`.
         */
        val LABEL_SMOKE_CASE_IDS: List<String> = listOf("new-york-zoom-ladder", "tokyo-cjk-dense", "cairo-rtl")
        const val CAIRO_CASE_ID = "cairo-rtl"

        /** Glyph endpoints always serve 256-codepoint blocks; the `{range}` template is `N-(N+255)`. */
        const val GLYPH_RANGE_SIZE = 256

        /** The ceiling `acquireLabelCandidates` enforces under the default [ResourceLimits] this gate uses. */
        val MAX_GLYPH_RANGES_PER_BATCH: Int = ResourceLimits().maxGlyphRangesPerBatch
        val catalogJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }
        val strictJson: Json = Json {
            ignoreUnknownKeys = false
            isLenient = false
        }
        val STYLE_REF: Regex = Regex("[A-Za-z0-9._-]+")
    }
}
