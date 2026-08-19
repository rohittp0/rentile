package com.rohittp.rentile.internal.glyph

import com.rohittp.rentile.DiagnosticCode
import com.rohittp.rentile.DiagnosticSeverity
import com.rohittp.rentile.LabelCandidate
import com.rohittp.rentile.LabelCandidateBatch
import com.rohittp.rentile.LabelGlyphAtlas
import com.rohittp.rentile.LabelIconRef
import com.rohittp.rentile.LabelLayerStyle
import com.rohittp.rentile.PipelineStage
import com.rohittp.rentile.RasterizationException
import com.rohittp.rentile.RenderDiagnostic
import com.rohittp.rentile.ResourceLimits
import com.rohittp.rentile.SafetyLimitException
import com.rohittp.rentile.TileId
import com.rohittp.rentile.internal.mvt.DecodedVectorFeature
import com.rohittp.rentile.internal.mvt.DecodedVectorGeometry
import com.rohittp.rentile.internal.mvt.VectorResource
import com.rohittp.rentile.internal.sha256Hex
import com.rohittp.rentile.internal.style.CompiledColor
import com.rohittp.rentile.internal.style.CompiledLabelTextProgram
import com.rohittp.rentile.internal.style.CompiledPreparedStyle
import com.rohittp.rentile.internal.style.IconDrawLayer
import com.rohittp.rentile.internal.style.StyleEvaluationContext
import com.rohittp.rentile.internal.style.StyleValue
import com.rohittp.rentile.internal.style.TextTransform
import com.rohittp.rentile.internal.style.mercatorLatitude
import com.rohittp.rentile.internal.style.parseCssColor
import com.rohittp.rentile.internal.style.spriteAnchoring
import com.rohittp.rentile.internal.withExpandedFeatureTokens
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Per-layer tally behind `LABEL_FEATURE_SKIPPED`.
 *
 * Three losses, counted apart rather than summed, because a consumer seeing no labels needs to
 * know which one happened: an unusable text property ([skipped]), geometry this profile cannot
 * anchor ([nonPointGeometry]), and text the acquired atlas has no glyphs for ([noGlyphs]). Folding
 * them into one number would make it mean three different things at once. Counted rather than
 * flagged for the reason `ICON_FEATURE_SKIPPED` counts: losing one label and losing every one are
 * different situations, and only counts against a denominator can tell them apart.
 *
 * Every count is in the same unit - one label, meaning one anchor of one feature on one requested
 * tile - so the three are strict subsets of [candidates] and comparable to each other.
 */
internal class LabelFeatureSkips(val layerId: String) {
    var candidates: Int = 0
    var skipped: Int = 0
    var noGlyphs: Int = 0
    var nonPointGeometry: Int = 0
    val tiles: MutableSet<TileId> = mutableSetOf()

    /** Nothing to report when the layer lost nothing, however many labels it produced. */
    val reportable: Boolean get() = skipped > 0 || noGlyphs > 0 || nonPointGeometry > 0

    fun toDiagnostic(layerOrder: Int): RenderDiagnostic = RenderDiagnostic(
        code = DiagnosticCode.LABEL_FEATURE_SKIPPED,
        severity = DiagnosticSeverity.INFO,
        stage = PipelineStage.RESOURCE_DECODING,
        message = "A place-name label layer produced no candidate for one or more of its features",
        details = mapOf(
            "layerIndex" to layerOrder.toString(),
            "layerIdDigest" to layerId.sha256Hex(),
            "candidateFeatures" to candidates.toString(),
            "skippedFeatures" to skipped.toString(),
            "skippedNoGlyphs" to noGlyphs.toString(),
            "skippedNonPointGeometry" to nonPointGeometry.toString(),
        ),
        affectedTiles = tiles.sortedWith(LABEL_TILE_ORDER),
    )
}

/** Per-layer tally behind `COMPLEX_SCRIPT_LABEL_EXCLUDED`, in the same unit as [LabelFeatureSkips]. */
internal class ComplexScriptExclusion(val layerId: String) {
    var features: Int = 0
    val tiles: MutableSet<TileId> = mutableSetOf()

    fun toDiagnostic(layerOrder: Int): RenderDiagnostic = RenderDiagnostic(
        code = DiagnosticCode.COMPLEX_SCRIPT_LABEL_EXCLUDED,
        severity = DiagnosticSeverity.INFO,
        stage = PipelineStage.RESOURCE_DECODING,
        message = "A place-name label layer's text resolves to a script this profile cannot lay out",
        details = mapOf(
            "layerIndex" to layerOrder.toString(),
            "layerIdDigest" to layerId.sha256Hex(),
            "excludedFeatures" to features.toString(),
        ),
        affectedTiles = tiles.sortedWith(LABEL_TILE_ORDER),
    )
}

internal val LABEL_TILE_ORDER: Comparator<TileId> = compareBy(TileId::z, TileId::x, TileId::y)

/**
 * Check for cancellation every 1024 items rather than every one. Both loops below are pure CPU
 * work over untrusted, unbounded input, so they need a cancellation point; making it periodic
 * keeps the check off the hot path of a tile with half a million features.
 */
private const val CANCELLATION_CHECK_MASK = 0x3FF

/** One Glyph Range a batch needs: a resolved font stack and the 256-codepoint block within it. */
internal data class GlyphRangeRequest(val fontStack: String, val rangeStart: Int)

/**
 * Everything decided about one label before its glyphs exist: which layer and tiles it came from,
 * where on the globe it sits, the exact text to lay out, and every per-feature scalar the public
 * candidate carries. Held rather than emitted because layout cannot run until the atlas the text
 * needs has been acquired and packed.
 */
internal data class PendingLabel(
    val program: CompiledLabelTextProgram,
    val layerId: String,
    val layerStyle: LabelLayerStyle,
    val requestedTile: TileId,
    val sourceTile: TileId,
    val featureIndex: Int,
    val anchorIndex: Int,
    val longitude: Double,
    val latitude: Double,
    val text: String,
    val textStyle: LabelTextStyle,
    val icon: LabelIconRef?,
    val allowOverlap: Boolean,
    val ignorePlacement: Boolean,
    val padding: Double,
    val sortKey: Double,
    val opacity: Double,
    val haloWidth: Double,
    val haloBlur: Double,
)

/**
 * The result of decoding and evaluating a batch's features: the labels that survived, the Glyph
 * Ranges they need, and the diagnostics that decision produced.
 */
internal class LabelCandidatePlan internal constructor(
    private val pending: List<PendingLabel>,
    val requiredRanges: List<GlyphRangeRequest>,
    private val complexScript: Map<Int, ComplexScriptExclusion>,
    private val featureSkips: Map<Int, LabelFeatureSkips>,
    private val style: CompiledPreparedStyle,
    private val contentDigests: List<String>,
    private val requestedTiles: List<TileId>,
    private val limits: ResourceLimits,
) {
    /**
     * Packs [ranges] into one atlas, lays every surviving label out against it, and assembles the
     * batch. [ranges] must be exactly what [requiredRanges] asked for; ordering does not matter,
     * because [GlyphAtlasPacker] canonicalizes it.
     *
     * [record] receives each label diagnostic for the caller's [com.rohittp.rentile.DiagnosticSink].
     * They are emitted here rather than by `plan` because one of the losses they count - a label
     * whose codepoints the acquired atlas does not cover - is not knowable until layout runs.
     */
    suspend fun assemble(
        ranges: List<AcquiredGlyphRange>,
        record: (RenderDiagnostic) -> Unit,
    ): LabelCandidateBatch {
        val atlas = GlyphAtlasPacker.pack(ranges, limits)
        // Once per batch, never per label: the map depends only on the acquired ranges, and
        // rebuilding it inside layOut cost up to 64 x 256 map operations for every label.
        val whitespace = LabelLayout.whitespaceAdvances(ranges)
        val layerStyles = mutableListOf<LabelLayerStyle>()
        val layerStyleIndex = mutableMapOf<Pair<String, Int>, Int>()
        val candidates = mutableListOf<LabelCandidate>()

        for ((index, label) in pending.withIndex()) {
            if (index and CANCELLATION_CHECK_MASK == 0) currentCoroutineContext().ensureActive()
            val laidOut = LabelLayout.layOut(label.text, atlas, whitespace, label.textStyle)
            if (laidOut == null) {
                // The label wanted glyphs the acquired atlas does not cover, so it lays out to
                // nothing. Counted rather than dropped in silence: 0.2.0 shipped an icon layer
                // that emitted nothing at all when every feature named a sprite the atlas lacked,
                // and a whole-layer loss reported as no signal whatsoever was a real defect.
                featureSkips[label.program.layerOrder]?.let {
                    it.noGlyphs += 1
                    it.tiles += label.requestedTile
                }
                continue
            }
            // Appended only when a candidate actually survives layout, so the list holds one entry
            // per (layer, zoom) genuinely present in the batch and never an orphan.
            val styleIndex = layerStyleIndex.getOrPut(label.layerId to label.requestedTile.z) {
                layerStyles += label.layerStyle
                layerStyles.lastIndex
            }
            candidates += LabelCandidate(
                layerStyleIndex = styleIndex,
                requestedTile = label.requestedTile,
                sourceTile = label.sourceTile,
                longitude = label.longitude,
                latitude = label.latitude,
                glyphs = laidOut.quads,
                boundingBox = laidOut.box,
                icon = label.icon,
                allowOverlap = label.allowOverlap,
                ignorePlacement = label.ignorePlacement,
                padding = label.padding,
                sortKey = label.sortKey,
                opacity = label.opacity,
                haloWidth = label.haloWidth,
                haloBlur = label.haloBlur,
            )
        }

        return LabelCandidateBatch(
            candidates = candidates,
            layerStyles = layerStyles,
            atlas = LabelGlyphAtlas(
                // Defensive copy, the convention ValidatedMvtTile already follows for its
                // bytes: this type's equals and hashCode are computed over the array's
                // contents, so handing out a reference to the packer's own buffer would let
                // a mutation change an already-published atlas's hash.
                pngBytes = atlas.pngBytes.copyOf(),
                width = atlas.width,
                height = atlas.height,
                contentKey = atlas.contentKey,
                entries = atlas.entries,
            ),
            contentKey = contentKey(ranges),
            // The prepared style's own diagnostics ride along exactly as they do on a
            // PreparedBatch: UNSUPPORTED_TEXT_CONSTRUCT and LINE_PLACEMENT_LABEL_EXCLUDED are
            // the reasons a layer the caller can see in labelLayerDescriptors contributed no
            // candidates, so a caller reading only this batch would otherwise have no way to
            // tell an excluded layer from an empty one.
            diagnostics = style.diagnostics + labelDiagnostics().onEach(record),
        )
    }

    /** One entry per layer per code, in layer order, so the list is identical between runs. */
    private fun labelDiagnostics(): List<RenderDiagnostic> =
        (complexScript.keys + featureSkips.keys).sorted().flatMap { layerOrder ->
            listOfNotNull(
                complexScript[layerOrder]?.toDiagnostic(layerOrder),
                featureSkips[layerOrder]?.takeIf { it.reportable }?.toDiagnostic(layerOrder),
            )
        }

    /**
     * Identity of everything this batch resolved: the acquired MVT bytes and the acquired glyph
     * bytes, both by digest, over the style that selected them.
     *
     * The style digest is in the key even though the design names only the two resource digest
     * sets, because two different styles can legitimately resolve the same tiles and the same
     * glyph ranges while producing entirely different candidates - different filters, different
     * text fields, different paint - and a content key that cannot tell those apart would hand a
     * consumer another style's cached candidates.
     */
    private fun contentKey(ranges: List<AcquiredGlyphRange>): String = buildString {
        append("rentile-label-candidates-1\n")
        append(style.digest)
        append('\n')
        append(contentDigests.joinToString(","))
        append('\n')
        append(ranges.map { it.contentDigest }.sorted().joinToString(","))
        append('\n')
        // The requested tiles are part of the identity, not just the resources they resolved to.
        // Two different tile sets can share every MVT and glyph digest - overzoomed siblings of
        // one source tile do exactly that - and would otherwise alias onto one content key.
        append(requestedTiles.joinToString(",") { "${it.z}/${it.x}/${it.y}" })
    }.sha256Hex()
}

/**
 * Turns acquired vector tiles into laid-out label candidates.
 *
 * Split from `DefaultBasemapRasterizer` so the rasterizer stays a thin entry point owning
 * ownership checks, concurrency and lifecycle, while decode-evaluate-clip-layout lives here. The
 * split is also what keeps acquisition ordering honest: [plan] decides which Glyph Ranges the
 * batch needs without fetching anything, the caller acquires them, and
 * [LabelCandidatePlan.assemble] finishes. Glyph needs are not knowable before features are
 * decoded, which is the whole reason labels cannot join `prepareBatch`.
 */
internal object LabelCandidateAssembler {

    /**
     * Decodes and evaluates every qualifying feature, in (style layer order, requested tile,
     * source tile, feature index) order, and reports which Glyph Ranges the survivors need.
     *
     * [resources] is keyed by (source id digest, requested tile) - the same identity
     * `acquireLabelTiles` acquires by - and [iconImageNameOf] is the rasterizer's own
     * `icon-image` evaluator, passed in rather than reimplemented.
     */
    suspend fun plan(
        style: CompiledPreparedStyle,
        tiles: List<TileId>,
        resources: Map<Pair<String, TileId>, VectorResource>,
        limits: ResourceLimits,
        iconImageNameOf: (StyleValue, DecodedVectorFeature) -> String?,
    ): LabelCandidatePlan {
        val pending = mutableListOf<PendingLabel>()
        val ranges = mutableSetOf<GlyphRangeRequest>()
        val complexScript = mutableMapOf<Int, ComplexScriptExclusion>()
        val featureSkips = mutableMapOf<Int, LabelFeatureSkips>()
        // Paint for one (layer, requested zoom) is feature-independent, so it is resolved once and
        // reused. Resolved inside the per-feature guard below rather than at assembly time, so a
        // text-color that will not evaluate degrades exactly like any other unusable text
        // property instead of failing the whole acquisition from a different phase.
        val layerStyles = mutableMapOf<Pair<Int, Int>, LabelLayerStyle>()

        // Style layer order, then requested tile, then feature index: iterating in the contract's
        // own order means the emitted list is already sorted, and the explicit sort below only has
        // to prove it rather than establish it.
        for (layer in style.labelLayers.sortedBy { it.textProgram?.layerOrder ?: Int.MAX_VALUE }) {
            val program = layer.textProgram ?: continue
            for (tile in tiles.sortedWith(TILE_ORDER)) {
                currentCoroutineContext().ensureActive()
                if (tile.z < program.minZoom || tile.z >= program.maxZoom) continue
                val resource = resources[layer.source.idDigest to tile] ?: continue
                val sourceLayer = resource.tile.layers
                    .firstOrNull { it.name == layer.descriptor.sourceLayer } ?: continue
                val sourceTile = TileId(resource.sample.sourceZ, resource.sample.sourceX, resource.sample.sourceY)

                for ((featureIndex, feature) in sourceLayer.features.withIndex()) {
                    // A single dense tile can carry hundreds of thousands of features, and this
                    // whole pass runs before any glyph is fetched, so without this a cancelled
                    // acquisition would still finish decoding and evaluating all of them.
                    if (featureIndex and CANCELLATION_CHECK_MASK == 0) currentCoroutineContext().ensureActive()
                    val context = StyleEvaluationContext(
                        zoom = tile.z.toDouble(),
                        geometryType = feature.geometryType,
                        featureId = feature.id?.let { StyleValue.NumberValue(it.toDouble()) } ?: StyleValue.Null,
                        properties = feature.properties,
                    )
                    if (!program.filter.matches(context)) continue

                    // Evaluation strictly precedes the script gate. Eleven corpus layers wrap
                    // text-field in is-supported-script and select a Latin fallback themselves, so
                    // gating on the raw property would exclude labels the style already healed.
                    val text = evaluateText(program, context, feature) ?: continue

                    val skips = featureSkips.getOrPut(program.layerOrder) {
                        LabelFeatureSkips(layer.descriptor.id)
                    }

                    val anchors = (feature.geometry as? DecodedVectorGeometry.Points)?.points
                    if (anchors == null) {
                        // Place-name source layers are point geometry, so this profile anchors
                        // nothing else and does not invent a midpoint or centroid the way icons
                        // do. Silently producing nothing was the gap: a layer that is entirely
                        // lines or polygons would have looked identical to a layer with no
                        // features at all.
                        skips.candidates += 1
                        skips.nonPointGeometry += 1
                        skips.tiles += tile
                        continue
                    }
                    // One rule covers both ways a feature can be repeated, and it is exact rather
                    // than proximity-based: the anchor must fall inside the requested tile's own
                    // window of its source tile, which is what
                    // VectorTileSample.sourceCoordinateToOutputPixels maps to [0, outputSizePx).
                    //
                    // A tile buffer repeats a point feature into its neighbours; the window
                    // excludes it from every tile but the one that contains it. Overzoom has
                    // several requested tiles share one source tile; the window gives the feature
                    // to the single child whose bounds contain it, so a label is emitted once per
                    // requested zoom and attributed to the tile that actually holds it.
                    //
                    // Attribution must not depend on which other tiles the caller asked for in the
                    // same call: a key like (source tile, requested zoom) would hand the label to
                    // the lowest-ordered tile of whatever group it landed in, so panning a viewport
                    // would move a label between tiles and a consumer holding per-tile lists across
                    // frames would draw it twice. It would also collapse two requested tiles that
                    // are world copies of each other - sampleFor canonicalises x, while validateTile
                    // bounds only z and y, so TileId(1,-1,0) and TileId(1,1,0) share a source tile
                    // and are both legitimate requests.
                    //
                    // When childScale is 1 - every non-overzoom case - this reduces exactly to
                    // the anchor falling inside [0, extent) of the source tile.
                    val sample = resource.sample
                    val extent = sourceLayer.extent
                    val inside = anchors.withIndex().filter { (_, point) ->
                        val localX = point.x.toLong() * sample.childScale - sample.childX.toLong() * extent
                        val localY = point.y.toLong() * sample.childScale - sample.childY.toLong() * extent
                        localX in 0 until extent.toLong() && localY in 0 until extent.toLong()
                    }
                    // Not a loss: the feature belongs to a different tile, not to this one.
                    if (inside.isEmpty()) continue

                    // From here the feature wanted a label on this tile, whatever becomes of it.
                    // Every later loss is counted against this denominator and in this same unit.
                    skips.candidates += inside.size

                    if (ScriptSupport.requiresComplexShaping(text)) {
                        // Counted per layer, never per feature: a dense tile carries thousands of
                        // features in one script, and one diagnostic per feature would be a
                        // stream of identical entries carrying no extra information.
                        val exclusion = complexScript.getOrPut(program.layerOrder) {
                            ComplexScriptExclusion(layer.descriptor.id)
                        }
                        exclusion.features += inside.size
                        exclusion.tiles += tile
                        continue
                    }

                    // Everything left is property evaluation against this feature's own data. A
                    // single feature carrying a value no text property can use must not take the
                    // batch down with it: 0.2.0 spent two rounds removing exactly that failure for
                    // icons, and ADR 0026's reasoning is unchanged here. The feature is skipped,
                    // counted, and reported once for its layer.
                    val resolved = try {
                        val fontStack = fontStackOf(program, context, tile)
                        val size = program.size.evaluate(context).asNumber("text-size", tile)
                        // Not a failure and not a loss: a style that resolves text-size to zero
                        // is asking for no visible text at this zoom, exactly as placeIcons reads
                        // an icon-size of zero. It leaves the denominator rather than sitting in
                        // it uncounted, so the reported counts still account for every label the
                        // layer wanted - and a layer that deliberately hides its text does not
                        // start reporting a loss for doing so.
                        if (size <= 0.0) {
                            skips.candidates -= inside.size
                            null
                        } else ResolvedLabel(
                            fontStack = fontStack,
                            textStyle = textStyleFor(program, context, fontStack, size, tile),
                            scalars = evaluateScalars(program, context, tile),
                            layerStyle = layerStyles.getOrPut(program.layerOrder to tile.z) {
                                resolveLayerStyle(program, layer.descriptor.id, tile.z)
                            },
                        )
                    } catch (error: RasterizationException) {
                        skips.skipped += inside.size
                        skips.tiles += tile
                        continue
                    } ?: continue
                    val fontStack = resolved.fontStack
                    val textStyle = resolved.textStyle
                    val scalars = resolved.scalars
                    val icon = iconRefFor(style, program.layerOrder, tile, feature, context, iconImageNameOf)

                    var index = 0
                    while (index < text.length) {
                        val codepoint = text.codePointAtCompat(index)
                        index += if (codepoint > 0xFFFF) 2 else 1
                        // An astral codepoint has no range to request: glyph endpoints stop at the
                        // Basic Multilingual Plane, so asking would 404 and, because acquisition
                        // is all-or-error, one emoji in one feature would return no labels for the
                        // whole batch. Skipping it here drops that character from the label and
                        // keeps the rest; layout already ignores a codepoint the atlas has no
                        // entry and no whitespace advance for.
                        //
                        // A label that is *entirely* astral therefore requests nothing, lays out
                        // to nothing, and is counted as skippedNoGlyphs by the layout pass - which
                        // is the honest count: the label was lost, and for want of glyphs. No
                        // count is added here, because a label that lost one character out of
                        // several was not skipped, and inflating skippedNoGlyphs with it would
                        // break the invariant that the three loss counts are subsets of
                        // candidateFeatures whose sum means the layer contributed nothing.
                        if (!GlyphResourceAcquirer.servesCodepoint(codepoint)) continue
                        ranges += GlyphRangeRequest(fontStack, GlyphResourceAcquirer.rangeStartFor(codepoint))
                    }

                    val dimension = (1L shl sourceTile.z).toDouble()
                    for ((anchorIndex, point) in inside) {
                        pending += PendingLabel(
                            program = program,
                            layerId = layer.descriptor.id,
                            layerStyle = resolved.layerStyle,
                            requestedTile = tile,
                            sourceTile = sourceTile,
                            featureIndex = featureIndex,
                            anchorIndex = anchorIndex,
                            longitude = (sourceTile.x + point.x.toDouble() / extent) /
                                dimension * 360.0 - 180.0,
                            latitude = mercatorLatitude(sourceTile.y + point.y.toDouble() / extent, dimension),
                            text = text,
                            textStyle = textStyle,
                            icon = icon,
                            allowOverlap = program.allowOverlap,
                            ignorePlacement = program.ignorePlacement,
                            padding = program.padding,
                            sortKey = scalars.sortKey,
                            opacity = scalars.opacity,
                            haloWidth = scalars.haloWidth,
                            haloBlur = scalars.haloBlur,
                        )
                    }
                }
            }
        }

        val required = ranges.sortedWith(compareBy(GlyphRangeRequest::fontStack, GlyphRangeRequest::rangeStart))
        if (required.size > limits.maxGlyphRangesPerBatch) {
            throw SafetyLimitException(
                message = "Label candidates require more glyph ranges than the configured limit",
                limitName = "maxGlyphRangesPerBatch",
                limit = limits.maxGlyphRangesPerBatch.toLong(),
                observed = required.size.toLong(),
                stage = PipelineStage.RESOURCE_ACQUISITION,
                affectedTiles = tiles.sortedWith(TILE_ORDER),
            )
        }

        return LabelCandidatePlan(
            pending = pending.sortedWith(PENDING_ORDER),
            requiredRanges = required,
            complexScript = complexScript,
            featureSkips = featureSkips,
            style = style,
            contentDigests = resources.values.map { it.contentDigest }.distinct().sorted(),
            requestedTiles = tiles.sortedWith(TILE_ORDER),
            limits = limits,
        )
    }

    /**
     * The empty batch a style with no resolvable `glyphs` template yields. Label preparation is
     * opt-in, so a style that never declared glyphs must not fail a caller that asks for labels -
     * it reports why and returns nothing, per ADR 0026's degrade-rather-than-fail rule.
     */
    fun emptyBatch(
        style: CompiledPreparedStyle,
        tiles: List<TileId>,
        limits: ResourceLimits,
    ): LabelCandidateBatch {
        val atlas = GlyphAtlasPacker.pack(emptyList(), limits)
        return LabelCandidateBatch(
            candidates = emptyList(),
            layerStyles = emptyList(),
            atlas = LabelGlyphAtlas(
                // Defensive copy, the convention ValidatedMvtTile already follows for its
                // bytes: this type's equals and hashCode are computed over the array's
                // contents, so handing out a reference to the packer's own buffer would let
                // a mutation change an already-published atlas's hash.
                pngBytes = atlas.pngBytes.copyOf(),
                width = atlas.width,
                height = atlas.height,
                contentKey = atlas.contentKey,
                entries = atlas.entries,
            ),
            contentKey = "rentile-label-candidates-1\n${style.digest}\n\n".sha256Hex(),
            diagnostics = style.diagnostics + glyphRangeUnavailable(tiles),
        )
    }

    internal fun glyphRangeUnavailable(tiles: List<TileId>): RenderDiagnostic = RenderDiagnostic(
        code = DiagnosticCode.GLYPH_RANGE_UNAVAILABLE,
        severity = DiagnosticSeverity.INFO,
        stage = PipelineStage.RESOURCE_ACQUISITION,
        message = "The prepared style resolves no glyphs template, so it has no label candidates",
        affectedTiles = tiles.sortedWith(TILE_ORDER),
    )

    /** Everything one feature's property evaluation produces, or nothing if it produced none. */
    private class ResolvedLabel(
        val fontStack: String,
        val textStyle: LabelTextStyle,
        val scalars: LabelScalars,
        val layerStyle: LabelLayerStyle,
    )

    /**
     * Paint for one layer at one requested output zoom, evaluated with no feature context. The
     * corpus has no feature-driven `text-color` or `text-halo-color` in any of its 265 place-name
     * layers, which is why colour lives on the layer record rather than on each candidate.
     */
    private fun resolveLayerStyle(
        program: CompiledLabelTextProgram,
        layerId: String,
        zoom: Int,
    ): LabelLayerStyle {
        val context = StyleEvaluationContext(zoom = zoom.toDouble())
        return LabelLayerStyle(
            layerId = layerId,
            zoom = zoom,
            priority = program.layerOrder,
            color = program.color.evaluate(context).asColor("text-color").packedArgb(),
            haloColor = program.haloColor.evaluate(context).asColor("text-halo-color").packedArgb(),
        )
    }

    private data class LabelScalars(
        val sortKey: Double,
        val opacity: Double,
        val haloWidth: Double,
        val haloBlur: Double,
    )

    private fun evaluateScalars(
        program: CompiledLabelTextProgram,
        context: StyleEvaluationContext,
        tile: TileId,
    ): LabelScalars {
        val opacity = program.opacity.evaluate(context).asNumber("text-opacity", tile)
        val haloWidth = program.haloWidth.evaluate(context).asNumber("text-halo-width", tile)
        val haloBlur = program.haloBlur.evaluate(context).asNumber("text-halo-blur", tile)
        if (opacity !in 0.0..1.0) {
            throw RasterizationException(
                message = "text-opacity did not evaluate to a value between zero and one",
                affectedTiles = listOf(tile),
            )
        }
        if (haloWidth < 0.0 || haloBlur < 0.0) {
            throw RasterizationException(
                message = "A text halo value did not evaluate to a non-negative number",
                affectedTiles = listOf(tile),
            )
        }
        return LabelScalars(
            sortKey = when (val value = program.sortKey?.evaluate(context)) {
                null, StyleValue.Null -> 0.0
                else -> value.asNumber("symbol-sort-key", tile)
            },
            opacity = opacity,
            haloWidth = haloWidth,
            haloBlur = haloBlur,
        )
    }

    private fun textStyleFor(
        program: CompiledLabelTextProgram,
        context: StyleEvaluationContext,
        fontStack: String,
        sizePx: Double,
        tile: TileId,
    ): LabelTextStyle {
        val offset = program.offset.evaluate(context).asNumberPair("text-offset", tile)
        return LabelTextStyle(
            fontStackDigest = fontStack.sha256Hex(),
            sizePx = sizePx,
            anchor = program.anchor,
            offsetEm = offset,
            justify = program.justify,
            maxWidthEm = program.maxWidth.evaluate(context).asNumber("text-max-width", tile),
            letterSpacingEm = program.letterSpacing.evaluate(context).asNumber("text-letter-spacing", tile),
            lineHeightEm = program.lineHeight.evaluate(context).asNumber("text-line-height", tile),
            paddingPx = program.padding,
        )
    }

    /**
     * Resolves the final text for one feature: evaluate, expand legacy `{token}` references,
     * apply `text-transform`, trim. Null when nothing is left to lay out.
     */
    private fun evaluateText(
        program: CompiledLabelTextProgram,
        context: StyleEvaluationContext,
        feature: DecodedVectorFeature,
    ): String? {
        val raw = when (val value = program.text.evaluate(context)) {
            is StyleValue.StringValue -> value.value
            is StyleValue.NumberValue -> value.value.toString().removeSuffix(".0")
            is StyleValue.BooleanValue -> value.value.toString()
            // Null, an array, an object or an image: no text this profile knows how to lay out.
            else -> return null
        }
        val expanded = raw.withExpandedFeatureTokens(feature.properties)
        val transformed = when (program.transform) {
            TextTransform.NONE -> expanded
            TextTransform.UPPERCASE -> expanded.uppercase()
            TextTransform.LOWERCASE -> expanded.lowercase()
        }
        return transformed.trim().takeIf(String::isNotEmpty)
    }

    /**
     * The font stack a glyph endpoint is asked for: the evaluated `text-font` names joined by
     * commas, exactly as `{fontstack}` expects them. A `text-font` that evaluates to anything but
     * a non-empty list of names leaves the feature with no glyph source at all, so it raises like
     * any other unusable text property and the caller skips that one feature.
     */
    private fun fontStackOf(
        program: CompiledLabelTextProgram,
        context: StyleEvaluationContext,
        tile: TileId,
    ): String {
        val value = program.font.evaluate(context) as? StyleValue.ArrayValue
        val names = value?.values
            ?.mapNotNull { (it as? StyleValue.StringValue)?.value?.takeIf(String::isNotEmpty) }
            ?.takeIf { it.isNotEmpty() }
            ?: throw RasterizationException(
                message = "text-font did not evaluate to a non-empty list of font names",
                affectedTiles = listOf(tile),
            )
        return names.joinToString(",")
    }

    /**
     * The sprite this label's own style layer pairs with it, or null.
     *
     * Null is the normal answer and never a placeholder: a layer with no icon, an `icon-image`
     * that resolves to no sprite in the atlas `prepare` already built, or a style with no sprite
     * at all all yield null, because inventing a marker would draw something the style never
     * asked for. An icon layer sitting beside label text is always a repaired layer - the
     * compatibility profile excludes text-coupled icons and retains only text-independent ones -
     * so ADR 0026 governs: a property that will not evaluate loses the icon, not the label.
     */
    private fun iconRefFor(
        style: CompiledPreparedStyle,
        layerOrder: Int,
        tile: TileId,
        feature: DecodedVectorFeature,
        context: StyleEvaluationContext,
        iconImageNameOf: (StyleValue, DecodedVectorFeature) -> String?,
    ): LabelIconRef? {
        val atlas = style.spriteAtlas ?: return null
        val iconLayer = style.drawLayers
            .filterIsInstance<IconDrawLayer>()
            .firstOrNull { it.layerOrder == layerOrder && it.isActiveAt(tile.z) } ?: return null
        val iconContext = context.copy(imageAvailable = atlas.entries::containsKey)
        val imageName = iconImageNameOf(iconLayer.image.evaluate(iconContext), feature) ?: return null
        val entry = atlas.entries[imageName] ?: return null
        return try {
            val size = iconLayer.size.evaluate(iconContext).asNumber("icon-size", tile)
            if (size <= 0.0) return null
            val offset = iconLayer.offset.evaluate(iconContext).asNumberPair("icon-offset", tile)
            val translate = iconLayer.translate.evaluate(iconContext).asNumberPair("icon-translate", tile)
            // The same decomposition placeIcons places its own markers from, out of the same
            // helper, so what a consumer draws and what Rentile's icon pass draws cannot drift.
            val anchoring = spriteAnchoring(
                entry = entry,
                anchor = iconLayer.anchor,
                size = size,
                offsetX = offset.first,
                offsetY = offset.second,
                translateX = translate.first,
                translateY = translate.second,
            )
            LabelIconRef(
                imageName = imageName,
                width = anchoring.width,
                height = anchoring.height,
                offsetX = anchoring.offsetX,
                offsetY = anchoring.offsetY,
                anchorOffsetX = anchoring.anchorShiftX,
                anchorOffsetY = anchoring.anchorShiftY,
                translateX = anchoring.translateX,
                translateY = anchoring.translateY,
            )
        } catch (_: RasterizationException) {
            null
        }
    }

    private val TILE_ORDER: Comparator<TileId> = LABEL_TILE_ORDER

    private val PENDING_ORDER: Comparator<PendingLabel> = compareBy(
        { it.program.layerOrder },
        { it.requestedTile.z }, { it.requestedTile.x }, { it.requestedTile.y },
        { it.sourceTile.z }, { it.sourceTile.x }, { it.sourceTile.y },
        { it.featureIndex },
        { it.anchorIndex },
    )
}

private fun StyleValue.asNumber(property: String, tile: TileId): Double =
    (this as? StyleValue.NumberValue)?.value?.takeIf(Double::isFinite)
        ?: throw RasterizationException(
            message = "$property did not evaluate to a finite number",
            affectedTiles = listOf(tile),
        )

private fun StyleValue.asNumberPair(property: String, tile: TileId): Pair<Double, Double> {
    val values = (this as? StyleValue.ArrayValue)?.values
        ?.takeIf { it.size == 2 }
        ?.map { (it as? StyleValue.NumberValue)?.value?.takeIf(Double::isFinite) }
        ?: throw RasterizationException(
            message = "$property did not evaluate to a pair of numbers",
            affectedTiles = listOf(tile),
        )
    val first = values[0]
    val second = values[1]
    if (first == null || second == null) {
        throw RasterizationException(
            message = "$property did not evaluate to a pair of numbers",
            affectedTiles = listOf(tile),
        )
    }
    return first to second
}

private fun StyleValue.asColor(property: String): CompiledColor = when (this) {
    is StyleValue.ColorValue -> value
    is StyleValue.StringValue -> parseCssColor(value)
    else -> null
} ?: throw RasterizationException(message = "$property did not evaluate to a supported color")

/** Packs a compiled colour into the 0xAARRGGBB integer a consumer uploads as a uniform. */
private fun CompiledColor.packedArgb(): Int =
    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
