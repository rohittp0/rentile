package com.rohittp.rentile

import com.rohittp.rentile.internal.glyph.ScriptSupport
import com.rohittp.rentile.internal.metadata.resolveHttpReference
import com.rohittp.rentile.internal.sha256Hex
import com.rohittp.rentile.internal.withRedactedAuthenticationQuery
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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
import kotlin.test.assertTrue

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

        val transport = GlyphClosureRecordingTransport(smokeTransport())
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
                .map { style -> renderStyle(rasterizer, style, transport, coverage, outputDirectory) }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }

        val declaredCapabilities = results.flatMapTo(mutableSetOf()) { it.declaredCapabilities }
        val runtimeLabelCapabilities = results.flatMapTo(mutableSetOf()) { it.runtimeLabelCapabilities }
        val nonLabelEvidence = declaredCapabilities +
            transport.observedSpriteCapabilities
        val observedCapabilities = coverage.requiredCapabilities.filterTo(mutableSetOf()) { capability ->
            if (coverage.capabilityDispositions[capability] == LABEL_CANDIDATE_DISPOSITION) {
                capability in runtimeLabelCapabilities
            } else {
                capability in nonLabelEvidence
            }
        }
        val runtimeLabelEvidence = results
            .flatMap { it.runtimeLabelEvidence.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, values) -> values.flatten().toSet() }
        val corpusFidelityErrors = if (selectedStyleId == null) {
            (coverage.requiredCapabilities.toSet() - observedCapabilities).sorted().map { capability ->
                if (coverage.capabilityDispositions[capability] == LABEL_CANDIDATE_DISPOSITION) {
                    "LABEL_CAPABILITY_NOT_EMITTED: $capability was declared by the rolling corpus " +
                        "but no sampled LabelCandidate provided its required semantic evidence"
                } else {
                    "CAPABILITY_NOT_EXERCISED: $capability was required by the Coverage Manifest " +
                        "but no live style or decoded sprite exercised it"
                }
            }
        } else {
            // A single-style run is a debugging aid. It still enforces every per-style fidelity
            // invariant, but cannot prove that the complete rolling corpus exercises every
            // capability in the manifest.
            emptyList()
        }

        writeReports(
            outputDirectory,
            coverage,
            results,
            declaredCapabilities,
            observedCapabilities,
            runtimeLabelEvidence,
            corpusFidelityErrors,
        )
        val completedStyles = results.count { result ->
            result.preparationErrorCode == null &&
                result.tiles.values.all { it.status == SmokeStatus.RENDERED } &&
                result.labelResults.all { it.errorCode == null } &&
                result.fidelityErrors.isEmpty()
        }
        assertEquals(
            expected = true,
            actual = completedStyles == results.size && corpusFidelityErrors.isEmpty(),
            message = buildString {
                append("Only $completedStyles/${results.size} styles passed the Coverage Manifest")
                if (corpusFidelityErrors.isNotEmpty()) {
                    append("; ")
                    append(corpusFidelityErrors.joinToString(" | "))
                }
                append("; inspect ${outputDirectory.resolve("index.html")}")
            },
        )
    }

    /**
     * The style with [sourceName] and every layer reading it removed.
     *
     * Dropping the source alone would leave layers pointing at nothing, which is a different and
     * less honest experiment than the one being run: the question is what the map looks like when
     * that source is not part of the style at all.
     */
    private fun styleWithoutSource(styleBody: String, sourceName: String): String {
        val style = catalogJson.parseToJsonElement(styleBody).jsonObject
        val sources = style["sources"]?.jsonObject ?: JsonObject(emptyMap())
        require(sourceName in sources) {
            "style declares no source named '$sourceName'; nothing would change"
        }
        val kept = JsonObject(sources.filterKeys { it != sourceName })
        val layers = style["layers"]?.jsonArray ?: JsonArray(emptyList())
        val keptLayers = JsonArray(
            layers.filter { layer ->
                layer.jsonObject["source"]?.jsonPrimitive?.contentOrNull != sourceName
            },
        )
        return JsonObject(style + mapOf("sources" to kept, "layers" to keptLayers)).toString()
    }

    private suspend fun renderStyle(
        rasterizer: BasemapRasterizer,
        style: CatalogStyleEntry,
        transport: GlyphClosureRecordingTransport,
        coverage: CoverageManifest,
        outputDirectory: Path,
    ): StyleSmokeResult {
        // RENTILE_CORPUS_OMIT_SOURCE renders a style as if one of its sources did not exist, so the
        // same coverage can be rendered with and without it and the two mosaics compared. A source
        // costs one fetch per tile however few layers read it, so "is this source worth its
        // acquisition" is a question about pixels, and this makes it answerable by looking.
        val omitSource = System.getenv("RENTILE_CORPUS_OMIT_SOURCE")?.takeIf(String::isNotBlank)
        val prepared = try {
            if (omitSource == null) {
                rasterizer.prepare(StyleInput.Remote(style.url))
            } else {
                // Fetched directly rather than via a first remote prepare: styleBody only returns a
                // response the transport already recorded, and preparing twice on one rasterizer to
                // record it made the second prepare fail acquiring the sprite.
                val body = transport.execute(
                    TransportRequest(
                        url = style.url,
                        resourceClass = ResourceClass.STYLE,
                        maxResponseBytes = STYLE_BODY_LIMIT_BYTES,
                    ),
                ).body.decodeToString()
                rasterizer.prepare(StyleInput.InlineJson(styleWithoutSource(body, omitSource)))
            }
        } catch (error: RentileException) {
            return StyleSmokeResult(
                styleId = style.id,
                styleName = style.name,
                preparationErrorCode = error.code.name,
                diagnostics = error.redactedDiagnosticSummaries(),
                tiles = emptyMap(),
                mosaics = emptyMap(),
                labelResults = emptyList(),
                declaredCapabilities = emptySet(),
                runtimeLabelCapabilities = emptySet(),
                runtimeLabelEvidence = emptyMap(),
                fidelityErrors = emptyList(),
                fidelityObservations = emptyList(),
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
                declaredCapabilities = emptySet(),
                runtimeLabelCapabilities = emptySet(),
                runtimeLabelEvidence = emptyMap(),
                fidelityErrors = emptyList(),
                fidelityObservations = emptyList(),
            )
        }

        val styleJson = catalogJson.parseToJsonElement(transport.styleBody(style.url).decodeToString()).jsonObject
        val inspection = inspectStyle(styleJson, coverage)
        val descriptorIds = rasterizer.labelLayerDescriptors(prepared).mapTo(mutableSetOf()) { it.id }
        val missingDescriptors = inspection.textVectorSymbolLayerIds - descriptorIds
        val forbiddenPreparationDiagnostics = prepared.diagnostics.filter { diagnostic ->
            diagnostic.code.name in coverage.fidelityPolicy.forbiddenPreparationDiagnosticCodes
        }
        val preparationFidelityErrors = buildList {
            if (coverage.fidelityPolicy.requireAllVisibleTextVectorSymbolsHaveDescriptors) {
                missingDescriptors.sorted().forEach { layerId ->
                    add(
                        "TEXT_VECTOR_SYMBOL_WITHOUT_DESCRIPTOR: layerIdDigest=${layerId.sha256Hex()} " +
                            "is a visible text-bearing vector symbol layer but has no LabelLayerDescriptor",
                    )
                }
            }
            forbiddenPreparationDiagnostics.forEach { diagnostic ->
                add("FORBIDDEN_PREPARATION_DIAGNOSTIC: ${diagnostic.code.name}")
            }
            inspection.labelCandidateLayers.forEach { (capability, layerIds) ->
                if (coverage.capabilityDispositions[capability] == LABEL_CANDIDATE_DISPOSITION) {
                    (layerIds - descriptorIds).sorted().forEach { layerId ->
                        add(
                            "CAPABILITY_WITHOUT_DESCRIPTOR: $capability layerIdDigest=${layerId.sha256Hex()} " +
                                "has no LabelLayerDescriptor",
                        )
                    }
                }
            }
        }.distinct()
        val uniqueTiles = coverage.cases.flatMap(CoverageCase::tiles).map(CoverageTile::asTileId).distinct()
        val outcomes = uniqueTiles.associateWith { tile ->
            renderTile(rasterizer, prepared, style.id, tile, outputDirectory)
        }
        val mosaics = createMosaics(style.id, coverage, outcomes, outputDirectory)
        val labelResults = acquireLabelSmoke(rasterizer, prepared, style, styleJson, transport, coverage)
        val runtimeLabelObservation = observeLabelCandidateSemantics(
            inspection,
            labelResults.flatMap(LabelCaseSmokeResult::candidateEvidence),
        )
        val contributingLabelLayerIds = labelResults.flatMapTo(mutableSetOf()) { it.contributingLayerIds }
        val unobservedDescriptorIds = descriptorIds - contributingLabelLayerIds
        val contributionObservation = unobservedDescriptorIds.takeIf(Set<String>::isNotEmpty)?.let { missing ->
            "LABEL_CONTRIBUTION_UNOBSERVED: ${missing.size} descriptors produced no candidate in the " +
                "sampled low/high-zoom cases; layerIdDigests=" +
                missing.sorted().take(MAX_REPORTED_LAYER_DIGESTS).joinToString(",") { it.sha256Hex() }
        }
        val fidelityErrors = preparationFidelityErrors + if (
            coverage.fidelityPolicy.requireEveryDescriptorToContribute && contributionObservation != null
        ) {
            listOf(contributionObservation)
        } else {
            emptyList()
        }
        return StyleSmokeResult(
            styleId = style.id,
            styleName = style.name,
            preparationErrorCode = null,
            diagnostics = prepared.diagnostics.map { "${it.code}: ${it.message}" }.distinct(),
            tiles = outcomes,
            mosaics = mosaics,
            labelResults = labelResults,
            declaredCapabilities = inspection.declaredCapabilities,
            runtimeLabelCapabilities = runtimeLabelObservation.capabilities,
            runtimeLabelEvidence = runtimeLabelObservation.evidence,
            fidelityErrors = fidelityErrors,
            fidelityObservations = listOfNotNull(contributionObservation),
        )
    }

    /**
     * Acquires label candidates for the three cases whose geography and script exercise the
     * label pipeline against live styles: [LABEL_SMOKE_CASE_IDS]. The lowest and highest zoom tile
     * of each case are used, plus New York z14: low zoom exercises settlement labels and glyph-
     * range fan-out, high zoom reaches road, POI, water and other labels, and z14 intersects the
     * z9/z12 functional text-transform layers in the live catalog while still carrying place
     * features. Acquiring every intermediate tile would add substantial runtime without turning
     * a missing source feature into stronger evidence. See `compatibility/README.md`.
     */
    private suspend fun acquireLabelSmoke(
        rasterizer: BasemapRasterizer,
        prepared: PreparedStyle,
        style: CatalogStyleEntry,
        styleJson: JsonObject,
        transport: GlyphClosureRecordingTransport,
        coverage: CoverageManifest,
    ): List<LabelCaseSmokeResult> {
        return LABEL_SMOKE_CASE_IDS.flatMap { caseId ->
            val case = coverage.cases.first { it.id == caseId }
            labelSmokeTiles(case)
                .map { coverageTile ->
                    acquireLabelCaseSmoke(
                        rasterizer,
                        prepared,
                        caseId,
                        coverageTile.asTileId(),
                        style.url,
                        styleJson,
                        transport,
                        coverage.fidelityPolicy,
                    )
                }
        }
    }

    private fun labelSmokeTiles(case: CoverageCase): List<CoverageTile> = buildList {
        add(case.tiles.minBy(CoverageTile::z))
        if (case.id == NEW_YORK_CASE_ID) {
            add(case.tiles.single { tile -> tile.z == FUNCTIONAL_TEXT_SAMPLE_ZOOM })
        }
        add(case.tiles.maxBy(CoverageTile::z))
    }.distinct()

    /**
     * Mirrors [renderTile]'s architecture deliberately: a failed invariant becomes an
     * [LabelCaseSmokeResult.errorCode] rather than a thrown assertion, so one style's violation
     * neither stops the remaining styles from acquiring nor loses the Corpus Report that
     * [writeReports] would otherwise never reach. The one final `assertEquals` in
     * [rendersPublicCatalogCoverageThroughPublicInterface] still fails the gate when any style
     * carries a non-null error code here - acquisition throwing included.
     *
     * One exception to that soft-fail architecture, deliberately: [LabelCandidatePlan.glyphUrls]
     * is called uncaught, so a [GlyphTemplateMismatchException] - this gate's own template
     * resolution disagreeing with the style's - aborts the run loudly instead of hiding as one
     * more error code. The closure-exactness check that follows it, in contrast, *is* folded into
     * this case's [LabelCaseSmokeResult.errorCode] rather than thrown - see the comment at
     * `unpredictedGlyphUrls` below for why, and for why a thrown assertion here would be the wrong
     * choice for this particular property.
     */
    private suspend fun acquireLabelCaseSmoke(
        rasterizer: BasemapRasterizer,
        prepared: PreparedStyle,
        caseId: String,
        tile: TileId,
        styleUrl: String,
        styleJson: JsonObject,
        transport: GlyphClosureRecordingTransport,
        fidelityPolicy: FidelityPolicy,
    ): LabelCaseSmokeResult {
        // Cleared per case: [unpredictedGlyphUrls] below must reflect only what this case's own
        // acquisition requests, not leftovers from a previous case or style.
        transport.recordedGlyphUrls.clear()
        val plan = try {
            rasterizer.planLabelCandidates(prepared, listOf(tile))
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
        return plan.use { activePlan ->
            // Deliberately outside any catch for RentileException: GlyphTemplateMismatchException
            // firing here means this test's own template resolution disagrees with what the style
            // actually resolved, which is a bug in this gate, not a flaky acquisition outcome - it
            // must fail loudly rather than fold into a soft errorCode like the acquisition below.
            val template = glyphsTemplateFor(styleJson, styleUrl) ?: NO_GLYPHS_TEMPLATE_PLACEHOLDER
            val predictedGlyphUrls = activePlan.glyphUrls(template).toSet()

            val batch = try {
                rasterizer.acquireLabelCandidates(activePlan)
            } catch (error: RentileException) {
                return@use LabelCaseSmokeResult(
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
                return@use LabelCaseSmokeResult(
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
            // This case's acquisition can only ever request URLs derived from this same plan's
            // frozen closure and the template just validated above, so
            // `unpredictedGlyphUrls` should always be empty by construction - it exists to catch
            // the case where that invariant breaks. It is deliberately a subset check
            // (`recorded - predicted`, not a two-way `assertEquals`): this gate's raw resource
            // store (`SmokeRawResourceStore`) is one plain, never-evicted `ConcurrentHashMap`
            // shared by every case and every style in the run, and
            // `GlyphResourceAcquirer.acquireRaw`'s cache key redacts the credential before
            // hashing the URL - so a range this case's plan predicts can already be warm from an
            // earlier case or style using the same glyph provider, font stack, and 256-codepoint
            // block (common: most corpus styles end their stack in the same Latin/Noto Sans
            // fallback). A warm cache only ever makes what this case actually requests smaller
            // than what it predicted, never larger, so it can only ever shrink `recorded` - a
            // subset check tolerates that by construction, where a two-way equality would not.
            // The reverse direction - that the closure never *under*-predicts what a fresh
            // acquisition requests - is still pinned as true equality, just against fixtures
            // rather than the live corpus: RentileRuntimeTest's
            // theGlyphClosureIsExactlyWhatTheAcquisitionRequests and
            // glyphUrlsEncodeAFontStackExactlyAsTheFetchDoes each build a fresh
            // InMemoryRawResourceStore per rasterizer, so they have no warm-cache confound.
            // Between the two suites, both halves of the property are covered - do not tighten
            // this one back to equality, or a legitimate warm cache will fail the gate.
            //
            // A violation is folded into this case's errorCode/diagnostics below rather than
            // thrown: rendersPublicCatalogCoverageThroughPublicInterface has no catch around its
            // style loop, so a thrown assertion here would discard the Corpus Report - the HTML
            // and TSV - for every style already rendered, not just this one.
            // Redacted on both sides before the difference, not just for display after: this
            // gate's own recorded URL and the plan's predicted URL both still carry the provider
            // credential at this point, and unpredictedGlyphUrls flows straight into this case's
            // diagnostics below, then into the published Corpus Report (results.tsv, index.html)
            // that CONTEXT.md requires to be "credential-free" and docs/error-model.md forbids
            // carrying "full signed URLs, unrestricted query strings" in. Redaction is
            // deterministic, so a URL differing from its counterpart only by credential redacts
            // to the same string on both sides and still cancels out of the difference - the
            // template-agreement check in DefaultBasemapRasterizer's glyphUrls() already treats a
            // credential-only difference as agreement, so this costs the check nothing.
            val unpredictedGlyphUrls = transport.recordedGlyphUrls
                .mapTo(mutableSetOf()) { it.withRedactedAuthenticationQuery() } -
                predictedGlyphUrls.mapTo(mutableSetOf()) { it.withRedactedAuthenticationQuery() }
            acquireLabelCaseSmokeResult(
                rasterizer,
                prepared,
                caseId,
                tile,
                batch,
                unpredictedGlyphUrls,
                fidelityPolicy,
            )
        }
    }

    private fun acquireLabelCaseSmokeResult(
        rasterizer: BasemapRasterizer,
        prepared: PreparedStyle,
        caseId: String,
        tile: TileId,
        batch: LabelCandidateBatch,
        unpredictedGlyphUrls: Set<String>,
        fidelityPolicy: FidelityPolicy,
    ): LabelCaseSmokeResult {
        // Distinct (font stack, 256-codepoint block) pairs: what `maxGlyphRangesPerBatch` counts.
        val glyphRangeCount = batch.atlas.entries
            .map { entry -> entry.fontStackDigest to entry.codepoint / GLYPH_RANGE_SIZE }
            .distinct()
            .size
        val diagnostics = batch.diagnostics.map { "${it.code}: ${it.message}" }.distinct().toMutableList()

        var errorCode: String? = null
        if (unpredictedGlyphUrls.isNotEmpty()) {
            errorCode = "GLYPH_CLOSURE_UNDERPREDICTED"
            diagnostics += "$errorCode: acquireLabelCandidates requested glyph-range URL(s) " +
                "planLabelCandidates never predicted for this case - an exact-URL firewall " +
                "preregistered from the plan would have refused these: " +
                unpredictedGlyphUrls.joinToString(", ")
        }
        if (glyphRangeCount > MAX_GLYPH_RANGES_PER_BATCH) {
            errorCode = errorCode ?: "GLYPH_RANGE_LIMIT_EXCEEDED"
            diagnostics += "GLYPH_RANGE_LIMIT_EXCEEDED: acquired $glyphRangeCount glyph ranges, " +
                "over the $MAX_GLYPH_RANGES_PER_BATCH maxGlyphRangesPerBatch ceiling " +
                "acquireLabelCandidates is supposed to enforce"
        }
        val forbiddenDiagnostics = batch.diagnostics.filter { diagnostic ->
            diagnostic.code.name in fidelityPolicy.forbiddenLabelDiagnosticCodes
        }
        if (forbiddenDiagnostics.isNotEmpty()) {
            errorCode = errorCode ?: "FORBIDDEN_LABEL_DIAGNOSTIC"
            diagnostics += "FORBIDDEN_LABEL_DIAGNOSTIC: label acquisition reported " +
                forbiddenDiagnostics.joinToString(",") { it.code.name }
        }
        if (caseId == CAIRO_CASE_ID) {
            when (val outcome = classifyCairoOutcome(rasterizer, prepared, batch)) {
                is CairoOutcome.Invalid -> {
                    errorCode = errorCode ?: "CAIRO_SCRIPT_OUTCOME_INVALID"
                    diagnostics += "CAIRO_SCRIPT_OUTCOME_INVALID: ${outcome.detail}"
                }
                CairoOutcome.NoLabelLayers -> diagnostics += "CAIRO_NO_LABEL_LAYERS: style " +
                    "declares no label layers at all, so it cannot say anything about " +
                    "complex-script handling one way or the other"
                CairoOutcome.NoMatchingFeatures -> diagnostics += "CAIRO_NO_MATCHING_FEATURES: style " +
                    "has label layers, but this tile carried zero label candidates and " +
                    "no label-relevant diagnostic; acceptable only when the tile genuinely has no " +
                    "matching features. Surfaced here rather than silently accepted in case this " +
                    "turns out to be the common outcome instead of the rare one"
                CairoOutcome.SupportedScriptCandidates, CairoOutcome.ExcludedByComplexScript -> Unit
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
            contributingLayerIds = batch.layerStyles.mapTo(mutableSetOf()) { it.layerId },
            candidateEvidence = batch.candidates.map { candidate ->
                val layerId = batch.layerStyles.getOrNull(candidate.layerStyleIndex)?.layerId
                    ?: error("LabelCandidate references a missing layer style")
                val resolvedText = candidate.glyphs.joinToString(separator = "") { quad ->
                    String(Character.toChars(batch.atlas.entries[quad.entryIndex].codepoint))
                }
                LabelCandidateEvidence(
                    layerId = layerId,
                    hasGlyphs = candidate.glyphs.isNotEmpty(),
                    hasResolvedGeometry = candidate.glyphs.isNotEmpty() &&
                        candidate.boundingBox.left.isFinite() &&
                        candidate.boundingBox.top.isFinite() &&
                        candidate.boundingBox.right.isFinite() &&
                        candidate.boundingBox.bottom.isFinite(),
                    placement = candidate.placement,
                    hasLineGeometry = candidate.line.isNotEmpty(),
                    hasIcon = candidate.icon != null,
                    iconTextFit = candidate.icon?.textFit ?: IconTextFit.NONE,
                    padding = candidate.padding,
                    resolvedTextCase = resolvedTextCase(resolvedText),
                )
            },
        )
    }

    /**
     * Cairo is right-to-left, so exactly four outcomes are acceptable and nothing else:
     *
     * - [CairoOutcome.SupportedScriptCandidates]: the style branched on `is-supported-script` and
     *   every candidate's resolved text is a script this renderer's glyph-metrics-only layout can
     *   lay out (typically a `name:latin` fallback).
     * - [CairoOutcome.ExcludedByComplexScript]: no candidates exist because
     *   [DiagnosticCode.COMPLEX_SCRIPT_LABEL_EXCLUDED] reported the exclusion.
     * - [CairoOutcome.NoLabelLayers]: `labelLayerDescriptors` is empty for this style, so it has no
     *   label layers at all and cannot say anything about complex-script handling
     *   either way. Checked and reported ahead of [NoMatchingFeatures] deliberately - "no label
     *   layers exist" and "label layers exist but none matched this tile" are different situations
     *   a corpus reader needs to tell apart, not one outcome hiding the other.
     * - [CairoOutcome.NoMatchingFeatures]: the style does have label layers, but no candidates and
     *   no *label-relevant* diagnostic came back for this tile - it genuinely carries no matching
     *   features. Keyed on label-relevant diagnostics only
     *   ([LABEL_RELEVANT_DIAGNOSTIC_CODES]: [DiagnosticCode.COMPLEX_SCRIPT_LABEL_EXCLUDED],
     *   [DiagnosticCode.GLYPH_RANGE_UNAVAILABLE], [DiagnosticCode.LABEL_FEATURE_SKIPPED],
     *   [DiagnosticCode.UNSUPPORTED_TEXT_CONSTRUCT], [DiagnosticCode.LINE_PLACEMENT_LABEL_EXCLUDED])
     *   so an unrelated style-preparation diagnostic - `ROOT_BEHAVIOR_EXCLUDED`,
     *   `TEXT_ONLY_LAYER_EXCLUDED` and the like - never disqualifies a tile that is genuinely empty
     *   of label candidates. Still narrow in the way that matters: any *label-relevant* diagnostic
     *   present without candidates, other than the complex-script exclusion itself, still falls
     *   through to [Invalid] - a lone `GLYPH_RANGE_UNAVAILABLE`, for instance, means broken
     *   template resolution (every corpus style has a resolvable glyphs template), not an empty
     *   tile.
     *
     * Candidates whose text still requires complex shaping - garbled output - are the one outcome
     * this check exists to catch, and always resolve to [CairoOutcome.Invalid] regardless of the
     * other three outcomes above.
     */
    private sealed interface CairoOutcome {
        data object SupportedScriptCandidates : CairoOutcome
        data object ExcludedByComplexScript : CairoOutcome
        data object NoLabelLayers : CairoOutcome
        data object NoMatchingFeatures : CairoOutcome
        data class Invalid(val detail: String) : CairoOutcome
    }

    private fun classifyCairoOutcome(
        rasterizer: BasemapRasterizer,
        prepared: PreparedStyle,
        batch: LabelCandidateBatch,
    ): CairoOutcome {
        if (rasterizer.labelLayerDescriptors(prepared).isEmpty()) {
            return CairoOutcome.NoLabelLayers
        }
        if (batch.candidates.isEmpty()) {
            val labelDiagnostics = batch.diagnostics.filter { it.code in LABEL_RELEVANT_DIAGNOSTIC_CODES }
            return when {
                labelDiagnostics.any { it.code == DiagnosticCode.COMPLEX_SCRIPT_LABEL_EXCLUDED } ->
                    CairoOutcome.ExcludedByComplexScript
                labelDiagnostics.isEmpty() -> CairoOutcome.NoMatchingFeatures
                else -> CairoOutcome.Invalid(
                    "no label candidates were acquired without COMPLEX_SCRIPT_LABEL_EXCLUDED being " +
                        "reported, and other label-relevant diagnostics were present instead (" +
                        labelDiagnostics.joinToString(", ") { it.code.name } +
                        "); every corpus style has a resolvable glyphs template, so this is " +
                        "otherwise unexplained",
                )
            }
        }
        val garbledCandidateCount = batch.candidates.count { candidate ->
            val text = candidate.glyphs.joinToString(separator = "") { quad ->
                String(Character.toChars(batch.atlas.entries[quad.entryIndex].codepoint))
            }
            ScriptSupport.requiresComplexShaping(text)
        }
        return if (garbledCandidateCount == 0) {
            CairoOutcome.SupportedScriptCandidates
        } else {
            CairoOutcome.Invalid(
                "$garbledCandidateCount/${batch.candidates.size} label candidates still require a " +
                    "script this renderer cannot lay out; the style must fall back to a supported " +
                    "script via is-supported-script or the label must be excluded entirely",
            )
        }
    }

    /**
     * Builds credential-free capability evidence from the style document already fetched by
     * [BasemapRasterizer.prepare]. This is deliberately an inventory, not a second style
     * compiler: this records static declarations only. [observeLabelCandidateSemantics] separately
     * proves label-candidate dispositions from sampled public output, while focused tests pin the
     * detailed evaluation semantics.
     */
    private fun inspectStyle(styleJson: JsonObject, coverage: CoverageManifest): StyleInspection {
        val sources = styleJson["sources"] as? JsonObject ?: JsonObject(emptyMap())
        val layers = styleJson["layers"] as? JsonArray ?: JsonArray(emptyList())
        val capabilities = mutableSetOf<String>()
        val textVectorSymbolLayerIds = mutableSetOf<String>()
        val labelCandidateLayers = mutableMapOf<String, MutableSet<String>>()

        fun recordLabelCapability(capability: String, layerId: String) {
            capabilities += capability
            labelCandidateLayers.getOrPut(capability, ::mutableSetOf) += layerId
        }

        val visibleLayers = layers.mapNotNull { it as? JsonObject }.filterNot { layer ->
            val layout = layer["layout"] as? JsonObject
            (layout?.get("visibility") as? JsonPrimitive)?.content == "none"
        }
        val usedSourceIds = visibleLayers.mapNotNullTo(mutableSetOf()) { layer ->
            (layer["source"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        }
        usedSourceIds.forEach { sourceId ->
            val source = sources[sourceId] as? JsonObject ?: return@forEach
            val sourceType = source.string("type")
            when (source.string("scheme")) {
                "tms" -> capabilities += "tms-source"
                else -> capabilities += "xyz-source"
            }
            when (sourceType) {
                "geojson" -> capabilities += "geojson-line"
                "raster" -> if (coverage.hasOverzoomCoverage()) capabilities += "raster-overzoom"
                "vector" -> if (coverage.hasOverzoomCoverage()) capabilities += "vector-overzoom"
            }
        }

        visibleLayers.forEach { layer ->
            val layerId = layer.string("id") ?: return@forEach
            val type = layer.string("type") ?: return@forEach
            val layout = layer["layout"] as? JsonObject ?: JsonObject(emptyMap())
            val paint = layer["paint"] as? JsonObject ?: JsonObject(emptyMap())
            when (type) {
                "background" -> {
                    capabilities += "background"
                    if ("background-pattern" in paint) capabilities += "background-pattern"
                }
                "fill" -> {
                    capabilities += "fill"
                    if ("fill-pattern" in paint) capabilities += "fill-pattern"
                }
                "fill-extrusion" -> capabilities += "flat-extrusion"
                "hillshade" -> capabilities += "hillshade"
                "line" -> {
                    capabilities += "line"
                    if ("line-pattern" in paint) capabilities += "line-pattern"
                    if ("line-round-limit" in layout) capabilities += "line-round-limit"
                }
                "raster" -> capabilities += "raster"
            }

            layer["filter"]?.let { filter ->
                if (filter is JsonArray && isLegacyFilter(filter)) {
                    capabilities += "legacy-filter"
                } else {
                    capabilities += "modern-filter"
                }
            }
            scanStyleValue(layer, capabilities)

            if (type != "symbol") return@forEach
            val sourceId = layer.string("source") ?: return@forEach
            if ((sources[sourceId] as? JsonObject)?.string("type") != "vector") return@forEach
            val meaningfulText = layout.hasMeaningfulValue("text-field")
            val meaningfulIcon = layout.hasMeaningfulValue("icon-image")
            val placement = (layout["symbol-placement"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
            val iconTextFitValue = layout["icon-text-fit"]
            val iconTextFit = (iconTextFitValue as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
            val iconIndependentOfText = meaningfulIcon &&
                (!meaningfulText || iconTextFitValue == null || iconTextFit == "none")

            if (iconIndependentOfText) {
                capabilities += if (placement == "line" || placement == "line-center") {
                    "independent-line-icon"
                } else {
                    "independent-point-icon"
                }
            }
            if (!meaningfulText) return@forEach

            textVectorSymbolLayerIds += layerId
            recordLabelCapability("label-candidate", layerId)
            recordLabelCapability("symbol-text", layerId)
            if (meaningfulIcon) recordLabelCapability("symbol-text-icon", layerId)
            if (meaningfulIcon && iconTextFitValue != null && iconTextFit != "none") {
                recordLabelCapability("symbol-icon-text-fit", layerId)
            }
            if (placement == "line" || placement == "line-center") {
                recordLabelCapability("symbol-line-placement", layerId)
            }
            if (layout["symbol-placement"] is JsonObject || layout["symbol-placement"] is JsonArray) {
                recordLabelCapability("symbol-functional-placement", layerId)
            }
            if (layout["text-anchor"] is JsonObject || layout["text-anchor"] is JsonArray) {
                recordLabelCapability("symbol-functional-text-anchor", layerId)
            }
            if (layout["text-transform"] is JsonObject || layout["text-transform"] is JsonArray) {
                recordLabelCapability("symbol-functional-text-transform", layerId)
            }
            if (layout["text-padding"] is JsonObject || layout["text-padding"] is JsonArray) {
                recordLabelCapability("symbol-functional-text-padding", layerId)
            }
        }
        return StyleInspection(
            declaredCapabilities = capabilities,
            textVectorSymbolLayerIds = textVectorSymbolLayerIds,
            labelCandidateLayers = labelCandidateLayers.mapValues { it.value.toSet() },
        )
    }

    /**
     * Converts sampled candidate values into capability evidence. Static style inspection answers
     * only "is this construct declared?"; this function answers "did the public candidate API emit
     * the semantics that construct requires?" without retaining provider layer names in reports.
     */
    private fun observeLabelCandidateSemantics(
        inspection: StyleInspection,
        candidates: List<LabelCandidateEvidence>,
    ): RuntimeLabelObservation {
        val capabilities = mutableSetOf<String>()
        val evidence = mutableMapOf<String, MutableSet<String>>()

        fun matching(capability: String): List<LabelCandidateEvidence> {
            val layers = inspection.labelCandidateLayers[capability].orEmpty()
            return candidates.filter { it.layerId in layers }
        }

        fun record(capability: String, values: Collection<String>, satisfiesCapability: Boolean = true) {
            if (values.isEmpty()) return
            evidence.getOrPut(capability, ::mutableSetOf) += values
            if (satisfiesCapability) capabilities += capability
        }

        record("label-candidate", matching("label-candidate").map { "candidate" })
        record(
            "symbol-text",
            matching("symbol-text").filter(LabelCandidateEvidence::hasGlyphs).map { "glyphs" },
        )
        record(
            "symbol-text-icon",
            matching("symbol-text-icon").filter(LabelCandidateEvidence::hasIcon).map { "icon" },
        )
        record(
            "symbol-icon-text-fit",
            matching("symbol-icon-text-fit")
                .filter { it.hasIcon && it.iconTextFit != IconTextFit.NONE }
                .map { "text-fit:${it.iconTextFit.name}" },
        )
        record(
            "symbol-line-placement",
            matching("symbol-line-placement")
                .filter { it.hasLineGeometry && it.placement in setOf(LabelPlacement.LINE, LabelPlacement.LINE_CENTER) }
                .map { "placement:${it.placement.name},line-geometry" },
        )

        val functionalPlacements = matching("symbol-functional-placement")
        val placementValues = functionalPlacements.map(LabelCandidateEvidence::placement).distinct()
        record(
            "symbol-functional-placement",
            functionalPlacements.map { "resolved:${it.placement.name}" } +
                if (placementValues.size > 1) {
                    listOf("differing-values")
                } else {
                    emptyList()
                },
            satisfiesCapability = placementValues.size > 1 || placementValues.any { it != LabelPlacement.POINT },
        )
        record(
            "symbol-functional-text-anchor",
            matching("symbol-functional-text-anchor")
                .filter(LabelCandidateEvidence::hasResolvedGeometry)
                .map { "candidate-local-geometry" },
        )
        val functionalPaddings = matching("symbol-functional-text-padding")
        val paddingValues = functionalPaddings.map(LabelCandidateEvidence::padding).distinct()
        record(
            "symbol-functional-text-padding",
            functionalPaddings.map { candidate ->
                val kind = if (candidate.padding == DEFAULT_TEXT_PADDING) "default" else "non-default"
                "$kind:${candidate.padding}"
            } + if (paddingValues.size > 1) {
                listOf("differing-values")
            } else {
                emptyList()
            },
            satisfiesCapability = paddingValues.size > 1 || paddingValues.any { it != DEFAULT_TEXT_PADDING },
        )
        val functionalTransforms = matching("symbol-functional-text-transform")
            .filter { it.resolvedTextCase != ResolvedTextCase.UNCASED }
        val transformValues = functionalTransforms.map(LabelCandidateEvidence::resolvedTextCase).distinct()
        record(
            "symbol-functional-text-transform",
            functionalTransforms.map { "resolved-text:${it.resolvedTextCase.name}" } +
                if (transformValues.size > 1) {
                    listOf("differing-values")
                } else {
                    emptyList()
                },
            satisfiesCapability = transformValues.size > 1 ||
                transformValues.any { it == ResolvedTextCase.UPPERCASE || it == ResolvedTextCase.LOWERCASE },
        )
        return RuntimeLabelObservation(
            capabilities = capabilities,
            evidence = evidence.mapValues { it.value.toSet() },
        )
    }

    private fun resolvedTextCase(text: String): ResolvedTextCase {
        val uppercase = text.uppercase()
        val lowercase = text.lowercase()
        if (uppercase == lowercase) return ResolvedTextCase.UNCASED
        return when (text) {
            uppercase -> ResolvedTextCase.UPPERCASE
            lowercase -> ResolvedTextCase.LOWERCASE
            else -> ResolvedTextCase.MIXED_CASE
        }
    }

    private fun scanStyleValue(element: JsonElement, capabilities: MutableSet<String>, filterRoot: Boolean = false) {
        when (element) {
            is JsonObject -> {
                if ("stops" in element) capabilities += "legacy-function"
                element.forEach { (key, value) ->
                    scanStyleValue(value, capabilities, filterRoot = key == "filter")
                }
            }
            is JsonArray -> {
                val operator = (element.firstOrNull() as? JsonPrimitive)
                    ?.takeIf(JsonPrimitive::isString)
                    ?.content
                val legacyFilterNode = filterRoot && isLegacyFilter(element)
                if (operator in EXPRESSION_OPERATORS && !legacyFilterNode) {
                    capabilities += "modern-expression"
                }
                if (!legacyFilterNode) {
                    EXPRESSION_CAPABILITIES[operator]?.let(capabilities::add)
                }
                val legacyLogicalChildren = legacyFilterNode && operator in setOf("all", "any", "none")
                element.drop(1).forEach { child ->
                    scanStyleValue(child, capabilities, filterRoot = legacyLogicalChildren)
                }
            }
            else -> Unit
        }
    }

    private fun isLegacyFilter(filter: JsonArray): Boolean {
        val operator = (filter.firstOrNull() as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?: return false
        return when (operator) {
            "has", "!has" -> filter.getOrNull(1) is JsonPrimitive
            "==", "!=", "<", "<=", ">", ">=", "in", "!in" ->
                (filter.getOrNull(1) as? JsonPrimitive)?.isString == true
            "all", "any", "none" -> filter.drop(1).all { child -> child is JsonArray && isLegacyFilter(child) }
            else -> false
        }
    }

    private fun CoverageManifest.hasOverzoomCoverage(): Boolean =
        cases.any { case -> case.tags.any { tag -> tag.endsWith("-overzoom") } }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonObject.hasMeaningfulValue(name: String): Boolean = when (val value = get(name)) {
        null -> false
        is JsonPrimitive -> !value.isString || value.content.isNotEmpty()
        else -> true
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

    @Test
    fun labelSmokeSamplingIncludesTheLiveFunctionalTextZoom() {
        val zoomLadder = CoverageCase(
            id = NEW_YORK_CASE_ID,
            tags = listOf("zoom-ladder"),
            tiles = listOf(
                CoverageTile(1, 0, 0),
                CoverageTile(FUNCTIONAL_TEXT_SAMPLE_ZOOM, 4823, 6160),
                CoverageTile(22, 1234919, 1576977),
            ),
        )

        assertEquals(
            listOf(1, FUNCTIONAL_TEXT_SAMPLE_ZOOM, 22),
            labelSmokeTiles(zoomLadder).map(CoverageTile::z),
        )
    }

    @Test
    fun styleInspectionFindsOmissionCapabilitiesWithoutFreezingLayerCounts() {
        val style = catalogJson.parseToJsonElement(
            """
            {
              "version": 8,
              "sources": {
                "vector": {"type": "vector", "tiles": ["https://tiles.invalid/{z}/{x}/{y}.mvt"]},
                "raster": {"type": "raster", "tiles": ["https://tiles.invalid/{z}/{x}/{y}.png"]}
              },
              "layers": [
                {
                  "id": "round-road",
                  "type": "line",
                  "source": "vector",
                  "source-layer": "road",
                  "layout": {"line-round-limit": 0.5}
                },
                {
                  "id": "complete-symbol",
                  "type": "symbol",
                  "source": "vector",
                  "source-layer": "place",
                  "filter": ["!=", ["get", "class"], "hidden"],
                  "layout": {
                    "symbol-placement": {"stops": [[0, "point"], [10, "line"]]},
                    "text-field": ["concat", ["slice", ["get", "name"], 0, 3], ["to-string", ["get", "ref"]]],
                    "text-anchor": ["step", ["zoom"], "center", 10, "top"],
                    "text-transform": {"stops": [[0, "none"], [10, "uppercase"]]},
                    "text-padding": {"stops": [[0, 2], [10, 4]]},
                    "icon-image": ["to-string", ["get", "icon"]],
                    "icon-text-fit": "both"
                  },
                  "paint": {
                    "text-opacity": ["case", [">", ["zoom"], 5], 1, 0],
                    "text-halo-width": ["case", ["<", ["zoom"], 5], 0, 1]
                  }
                },
                {
                  "id": "hidden-symbol",
                  "type": "symbol",
                  "source": "vector",
                  "source-layer": "place",
                  "layout": {"visibility": "none", "text-field": ["get", "name"]}
                },
                {
                  "id": "raster-symbol",
                  "type": "symbol",
                  "source": "raster",
                  "layout": {"text-field": ["get", "name"]}
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject
        val coverage = CoverageManifest(
            schemaVersion = 1,
            profileId = "rentile-v1",
            outputZoomRange = ZoomRange(0, 22),
            styleRefs = listOf("fixture"),
            requiredCapabilities = emptyList(),
            capabilityDispositions = emptyMap(),
            fidelityPolicy = FidelityPolicy(emptyList(), emptyList(), true, false),
            cases = listOf(CoverageCase("overzoom", listOf("vector-overzoom"), listOf(CoverageTile(22, 0, 0)))),
        )

        val inspection = inspectStyle(style, coverage)
        val expected = setOf(
            "expression-greater-than",
            "expression-less-than",
            "expression-not-equal",
            "expression-slice",
            "expression-to-string",
            "label-candidate",
            "legacy-function",
            "line-round-limit",
            "modern-expression",
            "modern-filter",
            "symbol-functional-placement",
            "symbol-functional-text-anchor",
            "symbol-functional-text-padding",
            "symbol-functional-text-transform",
            "symbol-icon-text-fit",
            "symbol-text",
            "symbol-text-icon",
            "vector-overzoom",
            "xyz-source",
        )
        assertEquals(emptySet(), expected - inspection.declaredCapabilities)
        assertEquals(setOf("complete-symbol"), inspection.textVectorSymbolLayerIds)
        assertEquals(setOf("complete-symbol"), inspection.labelCandidateLayers.getValue("symbol-icon-text-fit"))
    }

    @Test
    fun labelCapabilityEvidenceComesFromResolvedCandidateSemantics() {
        val capabilityLayers = setOf(
            "label-candidate",
            "symbol-text",
            "symbol-text-icon",
            "symbol-icon-text-fit",
            "symbol-line-placement",
            "symbol-functional-placement",
            "symbol-functional-text-anchor",
            "symbol-functional-text-padding",
            "symbol-functional-text-transform",
        ).associateWith { setOf("semantic-symbol") }
        val inspection = StyleInspection(
            declaredCapabilities = capabilityLayers.keys,
            textVectorSymbolLayerIds = setOf("semantic-symbol"),
            labelCandidateLayers = capabilityLayers,
        )
        val observation = observeLabelCandidateSemantics(
            inspection,
            listOf(
                LabelCandidateEvidence(
                    layerId = "semantic-symbol",
                    hasGlyphs = true,
                    hasResolvedGeometry = true,
                    placement = LabelPlacement.LINE,
                    hasLineGeometry = true,
                    hasIcon = true,
                    iconTextFit = IconTextFit.BOTH,
                    padding = 7.0,
                    resolvedTextCase = ResolvedTextCase.UPPERCASE,
                ),
                LabelCandidateEvidence(
                    layerId = "semantic-symbol",
                    hasGlyphs = true,
                    hasResolvedGeometry = true,
                    placement = LabelPlacement.POINT,
                    hasLineGeometry = false,
                    hasIcon = false,
                    iconTextFit = IconTextFit.NONE,
                    padding = DEFAULT_TEXT_PADDING,
                    resolvedTextCase = ResolvedTextCase.MIXED_CASE,
                ),
            ),
        )

        assertEquals(capabilityLayers.keys, observation.capabilities)
        assertEquals(setOf("text-fit:BOTH"), observation.evidence.getValue("symbol-icon-text-fit"))
        assertTrue("placement:LINE,line-geometry" in observation.evidence.getValue("symbol-line-placement"))
        assertTrue("differing-values" in observation.evidence.getValue("symbol-functional-placement"))
        assertTrue("non-default:7.0" in observation.evidence.getValue("symbol-functional-text-padding"))
        assertTrue("resolved-text:UPPERCASE" in observation.evidence.getValue("symbol-functional-text-transform"))
    }

    @Test
    fun corpusSafetyLimitDiagnosticsRetainCredentialFreeObservedCounts() {
        val error = SafetyLimitException(
            message = "bounded failure",
            limitName = "maxGlyphRangesPerBatch",
            limit = 256,
            observed = 257,
            stage = PipelineStage.RESOURCE_ACQUISITION,
        )

        assertEquals(
            listOf(
                "bounded failure",
                "SAFETY_LIMIT_DETAIL: limitName=maxGlyphRangesPerBatch limit=256 observed=257",
            ),
            error.redactedDiagnosticSummaries(),
        )
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
        require(coverage.requiredCapabilities == coverage.requiredCapabilities.distinct().sorted()) {
            "Coverage Manifest capability requirements must be unique and sorted"
        }
        require(coverage.capabilityDispositions.keys == coverage.requiredCapabilities.toSet()) {
            "Every required capability must have exactly one disposition"
        }
        require(coverage.capabilityDispositions.values.all { it in CAPABILITY_DISPOSITIONS }) {
            "Coverage Manifest contains an unsupported capability disposition"
        }
        val diagnosticCodes = DiagnosticCode.entries.mapTo(mutableSetOf()) { it.name }
        require(coverage.fidelityPolicy.forbiddenPreparationDiagnosticCodes.all { it in diagnosticCodes }) {
            "Coverage Manifest contains an unknown forbidden preparation diagnostic"
        }
        require(coverage.fidelityPolicy.forbiddenLabelDiagnosticCodes.all { it in diagnosticCodes }) {
            "Coverage Manifest contains an unknown forbidden label diagnostic"
        }
        require(
            coverage.fidelityPolicy.forbiddenPreparationDiagnosticCodes
                .containsAll(REQUIRED_FORBIDDEN_PREPARATION_DIAGNOSTICS),
        ) {
            "Coverage Manifest must reject known layer-level preparation omissions"
        }
        require(
            coverage.fidelityPolicy.forbiddenLabelDiagnosticCodes
                .containsAll(REQUIRED_FORBIDDEN_LABEL_DIAGNOSTICS),
        ) {
            "Coverage Manifest must reject known layer-level label omissions"
        }
        require(DiagnosticCode.TEXT_ONLY_LAYER_EXCLUDED.name !in coverage.fidelityPolicy.forbiddenPreparationDiagnosticCodes) {
            "TEXT_ONLY_LAYER_EXCLUDED describes the PNG path and remains valid when the layer is emitted as labels"
        }
        require(coverage.fidelityPolicy.requireAllVisibleTextVectorSymbolsHaveDescriptors) {
            "Coverage Manifest must require descriptors for every visible text-bearing vector symbol layer"
        }
    }

    private fun writeReports(
        outputDirectory: Path,
        coverage: CoverageManifest,
        results: List<StyleSmokeResult>,
        declaredCapabilities: Set<String>,
        observedCapabilities: Set<String>,
        runtimeLabelEvidence: Map<String, Set<String>>,
        corpusFidelityErrors: List<String>,
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
            coverage.requiredCapabilities.joinToString(separator = "\n", postfix = "\n") { capability ->
                val disposition = coverage.capabilityDispositions.getValue(capability)
                val status = when {
                    capability in observedCapabilities && disposition == LABEL_CANDIDATE_DISPOSITION -> "EMITTED"
                    capability in observedCapabilities -> "EXERCISED"
                    capability in declaredCapabilities -> "DECLARED_ONLY"
                    else -> "MISSING"
                }
                val evidence = runtimeLabelEvidence[capability].orEmpty().sorted().joinToString(",")
                "$capability\t$disposition\t$status\t$evidence"
            },
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )

        val z0 = TileId(0, 0, 0)
        val z0Rendered = results.count { it.tiles[z0]?.status == SmokeStatus.RENDERED }
        val complete = results.count { result ->
            result.preparationErrorCode == null &&
                result.tiles.values.all { it.status == SmokeStatus.RENDERED } &&
                result.labelResults.all { it.errorCode == null } &&
                result.fidelityErrors.isEmpty()
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
            if (corpusFidelityErrors.isNotEmpty()) {
                appendLine("<details open><summary>Corpus capability failures</summary><ul>")
                corpusFidelityErrors.forEach { appendLine("<li><code>${it.htmlSafe()}</code></li>") }
                appendLine("</ul></details>")
            }
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
                if (result.fidelityErrors.isNotEmpty()) {
                    appendLine("<details open><summary>${result.fidelityErrors.size} fidelity failures</summary><ul>")
                    result.fidelityErrors.forEach { appendLine("<li><code>${it.htmlSafe()}</code></li>") }
                    appendLine("</ul></details>")
                }
                if (result.fidelityObservations.isNotEmpty()) {
                    appendLine("<details><summary>${result.fidelityObservations.size} fidelity observations</summary><ul>")
                    result.fidelityObservations.forEach { appendLine("<li><code>${it.htmlSafe()}</code></li>") }
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

    /**
     * Resolves the style document's `glyphs` value against [styleUrl], mirroring
     * `StyleCompiler.kt:146-156` exactly: an absolute `http://` or `https://` reference passes
     * through unchanged, anything else resolves against the style's base URI - which for a
     * [StyleInput.Remote] style is always the style URL itself, never a redirect target, since
     * [smokeTransport] never follows redirects and [DefaultBasemapRasterizer] passes the
     * caller-supplied URL straight through as `baseUri`. [resolveHttpReference] is the very
     * function `StyleCompiler` calls, reused rather than reimplemented, so this cannot drift from
     * it.
     *
     * Returns null when the style has no string `glyphs` value at all: such a style's closure is
     * empty, and [LabelCandidatePlan.glyphUrls] does not check the template it is given in that
     * case, so the caller substitutes [NO_GLYPHS_TEMPLATE_PLACEHOLDER] rather than treating this
     * as an error.
     */
    private fun glyphsTemplateFor(styleJson: JsonObject, styleUrl: String): String? {
        val reference = (styleJson["glyphs"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return null
        return if (reference.startsWith("https://") || reference.startsWith("http://")) {
            reference
        } else {
            resolveHttpReference(styleUrl, reference)
        }
    }

    /**
     * Wraps the gate's real transport, unconditionally delegating every exchange to it, purely to
     * observe two things the closure-exactness assertion in [acquireLabelSmoke] needs and that no
     * existing recording already captures:
     *
     * - [recordedGlyphUrls]: exactly the URLs [BasemapRasterizer.acquireLabelCandidates] requests
     *   with [ResourceClass.GLYPH_RANGE], so they can be compared against what
     *   [LabelCandidatePlan.glyphUrls] predicted.
     * - The raw bytes of each [ResourceClass.STYLE] response, so [glyphsTemplateFor] can read the
     *   same `glyphs` value [StyleCompiler] resolved without a second network round trip to the
     *   catalog - [styleBody] reads back the bytes [BasemapRasterizer.prepare] already fetched.
     */
    private class GlyphClosureRecordingTransport(
        private val delegate: ResourceTransport,
    ) : ResourceTransport {
        val recordedGlyphUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val observedSpriteCapabilities: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val styleBodies = ConcurrentHashMap<String, ByteArray>()

        override suspend fun execute(request: TransportRequest): TransportResponse {
            val response = delegate.execute(request)
            when (request.resourceClass) {
                ResourceClass.GLYPH_RANGE -> recordedGlyphUrls += request.url
                ResourceClass.STYLE -> styleBodies[request.url] = response.body
                ResourceClass.SPRITE_JSON -> if (response.statusCode in 200..299) {
                    recordSpriteCapabilities(response.body)
                }
                else -> Unit
            }
            return response
        }

        fun styleBody(url: String): ByteArray =
            styleBodies[url] ?: error("No STYLE response was recorded for this style's URL")

        private fun recordSpriteCapabilities(bytes: ByteArray) {
            val entries = runCatching {
                catalogJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject
            }.getOrNull() ?: return
            entries.values.forEach { value ->
                val entry = value as? JsonObject ?: return@forEach
                val sdf = (entry["sdf"] as? JsonPrimitive)?.booleanOrNull ?: false
                observedSpriteCapabilities += if (sdf) "sdf-sprite" else "rgba-sprite"
            }
        }
    }

    private fun RentileException.redactedDiagnosticSummaries(): List<String> =
        (
            listOfNotNull(message) +
                if (this is SafetyLimitException) {
                    listOf("SAFETY_LIMIT_DETAIL: limitName=$limitName limit=$limit observed=$observed")
                } else {
                    emptyList()
                } +
                diagnostics.map { "${it.code}: ${it.message}" }
            ).distinct()

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
        val capabilityDispositions: Map<String, String>,
        val fidelityPolicy: FidelityPolicy,
        val cases: List<CoverageCase>,
    )

    @Serializable
    private data class FidelityPolicy(
        val forbiddenPreparationDiagnosticCodes: List<String>,
        val forbiddenLabelDiagnosticCodes: List<String>,
        val requireAllVisibleTextVectorSymbolsHaveDescriptors: Boolean,
        val requireEveryDescriptorToContribute: Boolean,
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
        val declaredCapabilities: Set<String>,
        val runtimeLabelCapabilities: Set<String>,
        val runtimeLabelEvidence: Map<String, Set<String>>,
        val fidelityErrors: List<String>,
        val fidelityObservations: List<String>,
    )

    private data class StyleInspection(
        val declaredCapabilities: Set<String>,
        val textVectorSymbolLayerIds: Set<String>,
        val labelCandidateLayers: Map<String, Set<String>>,
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
        val contributingLayerIds: Set<String> = emptySet(),
        val candidateEvidence: List<LabelCandidateEvidence> = emptyList(),
    )

    private data class LabelCandidateEvidence(
        val layerId: String,
        val hasGlyphs: Boolean,
        val hasResolvedGeometry: Boolean,
        val placement: LabelPlacement,
        val hasLineGeometry: Boolean,
        val hasIcon: Boolean,
        val iconTextFit: IconTextFit,
        val padding: Double,
        val resolvedTextCase: ResolvedTextCase,
    )

    private data class RuntimeLabelObservation(
        val capabilities: Set<String>,
        val evidence: Map<String, Set<String>>,
    )

    private enum class ResolvedTextCase {
        UPPERCASE,
        LOWERCASE,
        MIXED_CASE,
        UNCASED,
    }

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
        const val STYLE_BODY_LIMIT_BYTES = 32L * 1024L * 1024L
        const val PUBLIC_MAP_CATALOG_URL = "https://dashboard.lascade.com/travel_animator/v0/maps/"
        const val PUBLIC_CATALOG_HOST = "dashboard.lascade.com"
        const val PUBLIC_CATALOG_PATH = "/travel_animator/v0/maps/"
        const val MAX_CATALOG_PAGE_BYTES = 1024L * 1024L
        const val MAX_CATALOG_PAGES = 10
        const val MAX_CATALOG_STYLES = 1_000
        const val MAX_REPORTED_LAYER_DIGESTS = 10
        const val DEFAULT_TEXT_PADDING = 2.0
        const val LABEL_CANDIDATE_DISPOSITION = "label-candidate"

        val CAPABILITY_DISPOSITIONS: Set<String> = setOf(
            "evaluated",
            LABEL_CANDIDATE_DISPOSITION,
            "rasterized",
            "resource-acquired",
            "sprite-decoded",
        )
        val REQUIRED_FORBIDDEN_PREPARATION_DIAGNOSTICS: Set<String> = setOf(
            DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED.name,
            DiagnosticCode.UNSUPPORTED_TEXT_CONSTRUCT.name,
            DiagnosticCode.LINE_PLACEMENT_LABEL_EXCLUDED.name,
        )
        val REQUIRED_FORBIDDEN_LABEL_DIAGNOSTICS: Set<String> = setOf(
            DiagnosticCode.UNSUPPORTED_TEXT_CONSTRUCT.name,
            DiagnosticCode.LINE_PLACEMENT_LABEL_EXCLUDED.name,
        )
        val EXPRESSION_CAPABILITIES: Map<String, String> = mapOf(
            "!=" to "expression-not-equal",
            "<" to "expression-less-than",
            ">" to "expression-greater-than",
            "slice" to "expression-slice",
            "to-string" to "expression-to-string",
        )
        val EXPRESSION_OPERATORS: Set<String> = setOf(
            "literal",
            "zoom",
            "geometry-type",
            "get",
            "has",
            "image",
            "!",
            "all",
            "any",
            "+",
            "*",
            "==",
            "!=",
            "<",
            "<=",
            ">",
            ">=",
            "in",
            "slice",
            "boolean",
            "to-number",
            "to-string",
            "coalesce",
            "concat",
            "is-supported-script",
            "case",
            "match",
            "step",
            "interpolate",
        )

        /**
         * Cases the corpus gate acquires label candidates for, one lowest- and one highest-zoom
         * tile each: a Latin baseline, and the two non-Latin cases exercising CJK glyph-range
         * fan-out and the complex-script exclusion path. The two zoom extremes reach both
         * settlement labels and the road/POI/water labels visible deeper in the map; New York z14
         * supplies runtime evidence for the catalog's z9/z12 functional text-transform layers.
         * See `compatibility/README.md`.
         */
        val LABEL_SMOKE_CASE_IDS: List<String> = listOf("new-york-zoom-ladder", "tokyo-cjk-dense", "cairo-rtl")
        const val NEW_YORK_CASE_ID = "new-york-zoom-ladder"
        const val FUNCTIONAL_TEXT_SAMPLE_ZOOM = 14
        const val CAIRO_CASE_ID = "cairo-rtl"

        /**
         * Diagnostics that speak to whether label acquisition itself found or excluded something,
         * as distinct from style-preparation diagnostics like `ROOT_BEHAVIOR_EXCLUDED` or
         * `TEXT_ONLY_LAYER_EXCLUDED`, which describe layers Rentile does not draw at all and say
         * nothing about label candidates. Used to key [CairoOutcome.NoPlaceFeatures] on the right
         * evidence: an unrelated style-preparation diagnostic must never disqualify a tile that is
         * genuinely empty of label candidates.
         */
        val LABEL_RELEVANT_DIAGNOSTIC_CODES: Set<DiagnosticCode> = setOf(
            DiagnosticCode.COMPLEX_SCRIPT_LABEL_EXCLUDED,
            DiagnosticCode.GLYPH_RANGE_UNAVAILABLE,
            DiagnosticCode.LABEL_FEATURE_SKIPPED,
            DiagnosticCode.UNSUPPORTED_TEXT_CONSTRUCT,
            DiagnosticCode.LINE_PLACEMENT_LABEL_EXCLUDED,
        )

        /** Glyph endpoints always serve 256-codepoint blocks; the `{range}` template is `N-(N+255)`. */
        const val GLYPH_RANGE_SIZE = 256

        /**
         * Passed to [LabelCandidatePlan.glyphUrls] for a style with no `glyphs` value at all.
         * Never checked in that case - the closure is empty, so [glyphUrls] returns an empty list
         * without inspecting the template - so any string is safe here; this one just documents
         * why it is unused rather than looking like a real endpoint.
         */
        const val NO_GLYPHS_TEMPLATE_PLACEHOLDER = "https://style-declares-no-glyphs.invalid/{fontstack}/{range}"

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
