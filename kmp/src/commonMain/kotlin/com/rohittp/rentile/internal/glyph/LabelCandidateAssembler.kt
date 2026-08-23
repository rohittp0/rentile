package com.rohittp.rentile.internal.glyph

import com.rohittp.rentile.DiagnosticCode
import com.rohittp.rentile.DiagnosticSeverity
import com.rohittp.rentile.LabelCandidate
import com.rohittp.rentile.LabelCandidateBatch
import com.rohittp.rentile.LabelGlyphAtlas
import com.rohittp.rentile.LabelIconRef
import com.rohittp.rentile.LabelIconAnchor
import com.rohittp.rentile.LabelLinePoint
import com.rohittp.rentile.LabelPlacement
import com.rohittp.rentile.LabelLayerStyle
import com.rohittp.rentile.IconTextFit
import com.rohittp.rentile.PipelineStage
import com.rohittp.rentile.RasterizationException
import com.rohittp.rentile.RenderDiagnostic
import com.rohittp.rentile.ResourceLimits
import com.rohittp.rentile.SafetyLimitException
import com.rohittp.rentile.SymbolAlignment
import com.rohittp.rentile.SymbolOverlap
import com.rohittp.rentile.SymbolZOrder
import com.rohittp.rentile.TileId
import com.rohittp.rentile.internal.mvt.DecodedVectorFeature
import com.rohittp.rentile.internal.mvt.DecodedVectorGeometry
import com.rohittp.rentile.internal.mvt.VectorResource
import com.rohittp.rentile.internal.mvt.VectorCoordinate
import com.rohittp.rentile.internal.mvt.VectorRing
import com.rohittp.rentile.internal.sha256Hex
import com.rohittp.rentile.internal.style.CompiledColor
import com.rohittp.rentile.internal.style.CompiledLabelTextProgram
import com.rohittp.rentile.internal.style.CompiledPreparedStyle
import com.rohittp.rentile.internal.style.IconAnchor
import com.rohittp.rentile.internal.style.StyleEvaluationContext
import com.rohittp.rentile.internal.style.StyleValue
import com.rohittp.rentile.internal.style.TextJustify
import com.rohittp.rentile.internal.style.iconAnchorOrNull
import com.rohittp.rentile.internal.style.mercatorLatitude
import com.rohittp.rentile.internal.style.parseCssColor
import com.rohittp.rentile.internal.style.spriteAnchoring
import com.rohittp.rentile.internal.withExpandedFeatureTokens
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.PI
import kotlin.math.sqrt

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
        message = "A text-bearing symbol layer produced no candidate for one or more of its features",
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

/** One layer's layout-time losses for a single assembly, kept out of the shared tallies. */
private class LayoutLoss {
    var noGlyphs: Int = 0
    val tiles: MutableSet<TileId> = mutableSetOf()

    fun record(tile: TileId) {
        noGlyphs += 1
        tiles += tile
    }
}

// A member function would have to be declared at least as visible as LabelFeatureSkips itself
// (effectively internal), which Kotlin forbids from taking a private-in-file LayoutLoss parameter.
// A private top-level extension matches LayoutLoss's own visibility instead, while staying
// callable from LabelAssembly further down in this same file.
/** A copy carrying [loss] folded in, so the receiver is never mutated by an assembly. */
private fun LabelFeatureSkips.mergedWith(loss: LayoutLoss?): LabelFeatureSkips {
    if (loss == null) return this
    val merged = LabelFeatureSkips(layerId)
    merged.candidates = candidates
    merged.skipped = skipped
    merged.nonPointGeometry = nonPointGeometry
    merged.noGlyphs = noGlyphs + loss.noGlyphs
    merged.tiles += tiles
    merged.tiles += loss.tiles
    return merged
}

/** Per-layer tally behind `COMPLEX_SCRIPT_LABEL_EXCLUDED`, in the same unit as [LabelFeatureSkips]. */
internal class ComplexScriptExclusion(val layerId: String) {
    var features: Int = 0
    val tiles: MutableSet<TileId> = mutableSetOf()

    fun toDiagnostic(layerOrder: Int): RenderDiagnostic = RenderDiagnostic(
        code = DiagnosticCode.COMPLEX_SCRIPT_LABEL_EXCLUDED,
        severity = DiagnosticSeverity.INFO,
        stage = PipelineStage.RESOURCE_DECODING,
        message = "A text-bearing symbol layer's text resolves to a script this profile cannot lay out",
        details = mapOf(
            "layerIndex" to layerOrder.toString(),
            "layerIdDigest" to layerId.sha256Hex(),
            "excludedFeatures" to features.toString(),
        ),
        affectedTiles = tiles.sortedWith(LABEL_TILE_ORDER),
    )
}

/** Per-layer tally for an icon a text-bearing symbol requested but could not emit. */
internal class LabelIconSkips(val layerId: String) {
    var candidates: Int = 0
    var skipped: Int = 0
    var missingSprite: Int = 0
    val tiles: MutableSet<TileId> = mutableSetOf()

    val reportable: Boolean get() = skipped > 0

    fun toDiagnostic(layerOrder: Int): RenderDiagnostic = RenderDiagnostic(
        code = DiagnosticCode.ICON_FEATURE_SKIPPED,
        severity = DiagnosticSeverity.WARNING,
        stage = PipelineStage.RESOURCE_DECODING,
        message = "A label-coupled icon could not be emitted for one or more label candidates",
        details = mapOf(
            "layerIndex" to layerOrder.toString(),
            "layerIdDigest" to layerId.sha256Hex(),
            "candidateFeatures" to candidates.toString(),
            "skippedFeatures" to skipped.toString(),
            "skippedMissingSprite" to missingSprite.toString(),
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

internal data class GeometryAnchor(
    val point: VectorCoordinate,
    val line: List<VectorCoordinate> = emptyList(),
    val rotationDegrees: Double = 0.0,
)

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
    val placement: LabelPlacement,
    val line: List<LabelLinePoint>,
    val rotationDegrees: Double,
    val symbolSpacing: Double,
    val keepUpright: Boolean,
    val avoidEdges: Boolean,
    val zOrder: SymbolZOrder,
    val textRotationDegrees: Double,
    val maxAngleDegrees: Double,
    val rotationAlignment: SymbolAlignment,
    val pitchAlignment: SymbolAlignment,
    val textOptional: Boolean,
    val text: String,
    val textStyle: LabelTextStyle,
    val icon: LabelIconRef?,
    val overlap: SymbolOverlap,
    val ignorePlacement: Boolean,
    val padding: Double,
    val sortKey: Double,
    val color: Int,
    val haloColor: Int,
    val opacity: Double,
    val haloWidth: Double,
    val haloBlur: Double,
    val translate: Pair<Double, Double>,
    val translateAlignment: SymbolAlignment,
)

/**
 * Everything one Label acquisition decided before its glyphs exist: the labels that survived, the
 * Glyph Ranges they need, and the diagnostics that decision produced.
 */
internal class LabelAssembly internal constructor(
    private val pending: List<PendingLabel>,
    val requiredRanges: List<GlyphRangeRequest>,
    private val complexScript: Map<Int, ComplexScriptExclusion>,
    private val featureSkips: Map<Int, LabelFeatureSkips>,
    private val iconSkips: Map<Int, LabelIconSkips>,
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
        // rebuilding it inside layOut cost up to 256 x 256 map operations for every label.
        val whitespace = LabelLayout.whitespaceAdvances(ranges)
        val layerStyles = mutableListOf<LabelLayerStyle>()
        val layerStyleIndex = mutableMapOf<Pair<String, Int>, Int>()
        val candidates = mutableListOf<LabelCandidate>()
        // Layout losses are accumulated locally rather than into featureSkips, which this
        // assembly does not own alone: a reusable LabelCandidatePlan can assemble more than
        // once, and mutating the shared tallies would double-count skippedNoGlyphs on the
        // second pass, breaking ADR 0026's rule that the three loss counts are subsets of
        // candidateFeatures.
        val layoutLosses = mutableMapOf<Int, LayoutLoss>()

        for ((index, label) in pending.withIndex()) {
            if (index and CANCELLATION_CHECK_MASK == 0) currentCoroutineContext().ensureActive()
            val laidOut = LabelLayout.layOut(label.text, atlas, whitespace, label.textStyle)
            if (laidOut == null) {
                // The label wanted glyphs the acquired atlas does not cover, so it lays out to
                // nothing. Counted rather than dropped in silence: 0.2.0 shipped an icon layer
                // that emitted nothing at all when every feature named a sprite the atlas lacked,
                // and a whole-layer loss reported as no signal whatsoever was a real defect.
                if (featureSkips.containsKey(label.program.layerOrder)) {
                    layoutLosses.getOrPut(label.program.layerOrder) { LayoutLoss() }
                        .record(label.requestedTile)
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
                placement = label.placement,
                line = label.line,
                rotationDegrees = label.rotationDegrees,
                symbolSpacing = label.symbolSpacing,
                keepUpright = label.keepUpright,
                avoidEdges = label.avoidEdges,
                zOrder = label.zOrder,
                textRotationDegrees = label.textRotationDegrees,
                maxAngleDegrees = label.maxAngleDegrees,
                rotationAlignment = label.rotationAlignment,
                pitchAlignment = label.pitchAlignment,
                textOptional = label.textOptional,
                glyphs = laidOut.quads,
                boundingBox = laidOut.box,
                icon = label.icon,
                overlap = label.overlap,
                ignorePlacement = label.ignorePlacement,
                padding = label.padding,
                sortKey = label.sortKey,
                color = label.color,
                haloColor = label.haloColor,
                opacity = label.opacity,
                haloWidth = label.haloWidth,
                haloBlur = label.haloBlur,
                translateX = label.translate.first,
                translateY = label.translate.second,
                translateAlignment = label.translateAlignment,
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
            // PreparedBatch: UNSUPPORTED_TEXT_CONSTRUCT and any retained legacy/future
            // LINE_PLACEMENT_LABEL_EXCLUDED diagnostic explain why a layer visible through
            // labelLayerDescriptors contributed no candidates. Without these, a caller reading
            // only this batch could not distinguish an excluded layer from an empty one.
            diagnostics = style.diagnostics + labelDiagnostics(layoutLosses).onEach(record),
        )
    }

    /** One entry per layer per code, in layer order, so the list is identical between runs. */
    private fun labelDiagnostics(layoutLosses: Map<Int, LayoutLoss>): List<RenderDiagnostic> =
        (complexScript.keys + featureSkips.keys + iconSkips.keys).sorted().flatMap { layerOrder ->
            val merged = featureSkips[layerOrder]?.mergedWith(layoutLosses[layerOrder])
            listOfNotNull(
                complexScript[layerOrder]?.toDiagnostic(layerOrder),
                merged?.takeIf { it.reportable }?.toDiagnostic(layerOrder),
                iconSkips[layerOrder]?.takeIf { it.reportable }?.toDiagnostic(layerOrder),
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
        append("rentile-label-candidates-2\n")
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
 * [LabelAssembly.assemble] finishes. Glyph needs are not knowable before features are
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
    ): LabelAssembly {
        val pending = mutableListOf<PendingLabel>()
        val ranges = mutableSetOf<GlyphRangeRequest>()
        val complexScript = mutableMapOf<Int, ComplexScriptExclusion>()
        val featureSkips = mutableMapOf<Int, LabelFeatureSkips>()
        val iconSkips = mutableMapOf<Int, LabelIconSkips>()
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

                    val placement = try {
                        placementOf(program.placement.evaluate(context), tile)
                    } catch (_: RasterizationException) {
                        skips.candidates += 1
                        skips.skipped += 1
                        skips.tiles += tile
                        continue
                    }
                    val anchors = geometryAnchors(feature.geometry, placement)
                    if (anchors.isEmpty()) {
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
                    val inside = anchors.withIndex().filter { (_, anchor) ->
                        val localX = anchor.point.x.toLong() * sample.childScale - sample.childX.toLong() * extent
                        val localY = anchor.point.y.toLong() * sample.childScale - sample.childY.toLong() * extent
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
                                resolveLayerStyle(program.layerOrder, layer.descriptor.id, tile.z)
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
                    val resolvedIcon = iconRefFor(style, program.layerOrder, tile, feature, context, iconImageNameOf)
                    if (resolvedIcon.requested) {
                        val tally = iconSkips.getOrPut(program.layerOrder) {
                            LabelIconSkips(layer.descriptor.id)
                        }
                        tally.candidates += inside.size
                        if (resolvedIcon.skipped) {
                            tally.skipped += inside.size
                            if (resolvedIcon.missingSprite) tally.missingSprite += inside.size
                            tally.tiles += tile
                        }
                    }

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
                    for ((anchorIndex, anchor) in inside) {
                        pending += PendingLabel(
                            program = program,
                            layerId = layer.descriptor.id,
                            layerStyle = resolved.layerStyle,
                            requestedTile = tile,
                            sourceTile = sourceTile,
                            featureIndex = featureIndex,
                            anchorIndex = anchorIndex,
                            longitude = (sourceTile.x + anchor.point.x.toDouble() / extent) /
                                dimension * 360.0 - 180.0,
                            latitude = mercatorLatitude(sourceTile.y + anchor.point.y.toDouble() / extent, dimension),
                            placement = placement,
                            line = anchor.line.map { point ->
                                LabelLinePoint(
                                    longitude = (sourceTile.x + point.x.toDouble() / extent) / dimension * 360.0 - 180.0,
                                    latitude = mercatorLatitude(sourceTile.y + point.y.toDouble() / extent, dimension),
                                )
                            },
                            rotationDegrees = anchor.rotationDegrees,
                            symbolSpacing = scalars.symbolSpacing,
                            keepUpright = scalars.keepUpright,
                            avoidEdges = scalars.avoidEdges,
                            zOrder = scalars.zOrder,
                            textRotationDegrees = scalars.textRotationDegrees,
                            maxAngleDegrees = scalars.maxAngleDegrees,
                            rotationAlignment = scalars.rotationAlignment,
                            pitchAlignment = scalars.pitchAlignment,
                            textOptional = scalars.textOptional,
                            text = text,
                            textStyle = textStyle,
                            icon = resolvedIcon.ref,
                            overlap = scalars.overlap,
                            ignorePlacement = scalars.ignorePlacement,
                            padding = scalars.padding,
                            sortKey = scalars.sortKey,
                            color = scalars.color,
                            haloColor = scalars.haloColor,
                            opacity = scalars.opacity,
                            haloWidth = scalars.haloWidth,
                            haloBlur = scalars.haloBlur,
                            translate = scalars.translate,
                            translateAlignment = scalars.translateAlignment,
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

        return LabelAssembly(
            pending = pending.sortedWith(PENDING_ORDER),
            requiredRanges = required,
            complexScript = complexScript,
            featureSkips = featureSkips,
            iconSkips = iconSkips,
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
            contentKey = "rentile-label-candidates-2\n${style.digest}\n\n".sha256Hex(),
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

    private fun resolveLayerStyle(
        layerOrder: Int,
        layerId: String,
        zoom: Int,
    ): LabelLayerStyle {
        return LabelLayerStyle(
            layerId = layerId,
            zoom = zoom,
            priority = layerOrder,
        )
    }

    private data class LabelScalars(
        val sortKey: Double,
        val symbolSpacing: Double,
        val padding: Double,
        val overlap: SymbolOverlap,
        val ignorePlacement: Boolean,
        val keepUpright: Boolean,
        val avoidEdges: Boolean,
        val zOrder: SymbolZOrder,
        val textRotationDegrees: Double,
        val maxAngleDegrees: Double,
        val rotationAlignment: SymbolAlignment,
        val pitchAlignment: SymbolAlignment,
        val textOptional: Boolean,
        val color: Int,
        val haloColor: Int,
        val opacity: Double,
        val haloWidth: Double,
        val haloBlur: Double,
        val translate: Pair<Double, Double>,
        val translateAlignment: SymbolAlignment,
    )

    private fun evaluateScalars(
        program: CompiledLabelTextProgram,
        context: StyleEvaluationContext,
        tile: TileId,
    ): LabelScalars {
        val opacity = program.opacity.evaluate(context).asNumber("text-opacity", tile)
        val haloWidth = program.haloWidth.evaluate(context).asNumber("text-halo-width", tile)
        val haloBlur = program.haloBlur.evaluate(context).asNumber("text-halo-blur", tile)
        val padding = program.padding.evaluate(context).asNumber("text-padding", tile)
        val symbolSpacing = program.spacing.evaluate(context).asNumber("symbol-spacing", tile)
        val textRotationDegrees = program.rotate.evaluate(context).asNumber("text-rotate", tile)
        val maxAngleDegrees = program.maxAngle.evaluate(context).asNumber("text-max-angle", tile)
        if (opacity !in 0.0..1.0) {
            throw RasterizationException(
                message = "text-opacity did not evaluate to a value between zero and one",
                affectedTiles = listOf(tile),
            )
        }
        if (
            haloWidth < 0.0 || haloBlur < 0.0 || padding < 0.0 || symbolSpacing <= 0.0 ||
            maxAngleDegrees !in 0.0..180.0
        ) {
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
            symbolSpacing = symbolSpacing,
            padding = padding,
            overlap = overlapOf(program.overlap.evaluate(context), "text-overlap", tile),
            ignorePlacement = program.ignorePlacement.evaluate(context).asBoolean("text-ignore-placement", tile),
            keepUpright = program.keepUpright.evaluate(context).asBoolean("text-keep-upright", tile),
            avoidEdges = program.avoidEdges.evaluate(context).asBoolean("symbol-avoid-edges", tile),
            zOrder = zOrderOf(program.zOrder.evaluate(context), tile),
            textRotationDegrees = textRotationDegrees,
            maxAngleDegrees = maxAngleDegrees,
            rotationAlignment = alignmentOf(
                program.rotationAlignment.evaluate(context), "text-rotation-alignment", tile,
            ),
            pitchAlignment = alignmentOf(program.pitchAlignment.evaluate(context), "text-pitch-alignment", tile),
            textOptional = program.optional.evaluate(context).asBoolean("text-optional", tile),
            color = program.color.evaluate(context).asColor("text-color").packedArgb(),
            haloColor = program.haloColor.evaluate(context).asColor("text-halo-color").packedArgb(),
            opacity = opacity,
            haloWidth = haloWidth,
            haloBlur = haloBlur,
            // Pixels, not ems: text-translate is not scaled by text-size, so it is carried through
            // untouched for the consumer to add to the projected anchor.
            translate = program.translate.evaluate(context).asNumberPair("text-translate", tile),
            translateAlignment = alignmentOf(
                program.translateAnchor.evaluate(context), "text-translate-anchor", tile, autoAllowed = false,
            ),
        )
    }

    private fun textStyleFor(
        program: CompiledLabelTextProgram,
        context: StyleEvaluationContext,
        fontStack: String,
        sizePx: Double,
        tile: TileId,
    ): LabelTextStyle {
        val anchor = iconAnchorOrNull(program.anchor.evaluate(context).asString("text-anchor", tile))
            ?: throw RasterizationException(
                "text-anchor did not evaluate to a supported value",
                affectedTiles = listOf(tile),
            )
        // MapLibre clamps a negative radial distance to zero. A positive radial value replaces
        // text-offset rather than adding to it; zero (including a clamped negative value) retains
        // text-offset. Do not evaluate that fallback when radial placement wins, so an unusable
        // feature value in an irrelevant text-offset cannot suppress an otherwise valid label.
        val radialOffset = program.radialOffset.evaluate(context)
            .asNumber("text-radial-offset", tile)
            .coerceAtLeast(0.0)
        val offset = if (radialOffset == 0.0) {
            program.offset.evaluate(context).asNumberPair("text-offset", tile)
        } else {
            anchor.radialOffsetEm(radialOffset)
        }
        return LabelTextStyle(
            fontStackDigest = fontStack.sha256Hex(),
            sizePx = sizePx,
            anchor = anchor,
            offsetEm = offset,
            justify = textJustifyOf(program.justify.evaluate(context), anchor, tile),
            maxWidthEm = program.maxWidth.evaluate(context).asNumber("text-max-width", tile),
            letterSpacingEm = program.letterSpacing.evaluate(context).asNumber("text-letter-spacing", tile),
            lineHeightEm = program.lineHeight.evaluate(context).asNumber("text-line-height", tile),
            paddingPx = program.padding.evaluate(context).asNumber("text-padding", tile),
        )
    }

    /**
     * Converts a fixed anchor's outward radial distance into the inward block displacement this
     * layout applies in screen coordinates (positive x right, positive y down). Diagonals split
     * the authored distance equally over the two axes while preserving its Euclidean length.
     */
    private fun IconAnchor.radialOffsetEm(distanceEm: Double): Pair<Double, Double> {
        val diagonal = distanceEm / sqrt(2.0)
        return when (this) {
            IconAnchor.CENTER -> 0.0 to 0.0
            IconAnchor.TOP -> 0.0 to distanceEm
            IconAnchor.BOTTOM -> 0.0 to -distanceEm
            IconAnchor.LEFT -> distanceEm to 0.0
            IconAnchor.RIGHT -> -distanceEm to 0.0
            IconAnchor.TOP_LEFT -> diagonal to diagonal
            IconAnchor.TOP_RIGHT -> -diagonal to diagonal
            IconAnchor.BOTTOM_LEFT -> diagonal to -diagonal
            IconAnchor.BOTTOM_RIGHT -> -diagonal to -diagonal
        }
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
        val transformed = when (program.transform.evaluate(context).asStringOrNull()) {
            "none" -> expanded
            "uppercase" -> expanded.uppercase()
            "lowercase" -> expanded.lowercase()
            else -> return null
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
     * A layer with no icon produces an unrequested resolution. A requested icon that cannot be
     * resolved or evaluated produces a skipped resolution, which is tallied into
     * [DiagnosticCode.ICON_FEATURE_SKIPPED]; the label itself remains available under ADR 0026.
     */
    private data class IconResolution(
        val ref: LabelIconRef? = null,
        val requested: Boolean = false,
        val skipped: Boolean = false,
        val missingSprite: Boolean = false,
    )

    private fun iconRefFor(
        style: CompiledPreparedStyle,
        layerOrder: Int,
        tile: TileId,
        feature: DecodedVectorFeature,
        context: StyleEvaluationContext,
        iconImageNameOf: (StyleValue, DecodedVectorFeature) -> String?,
    ): IconResolution {
        val labelLayer = style.labelLayers.firstOrNull { it.textProgram?.layerOrder == layerOrder }
        val textProgram = labelLayer?.textProgram ?: return IconResolution()
        if (textProgram.iconRequestedButUnsupported) {
            return IconResolution(requested = true, skipped = true)
        }
        val iconLayer = textProgram.icon ?: return IconResolution()
        val atlas = style.spriteAtlas
            ?: return IconResolution(requested = true, skipped = true, missingSprite = true)
        return try {
            val iconContext = context.copy(imageAvailable = atlas.entries::containsKey)
            val imageName = iconImageNameOf(iconLayer.image.evaluate(iconContext), feature)
                ?: return IconResolution(requested = true, skipped = true, missingSprite = true)
            val entry = atlas.entries[imageName]
                ?: return IconResolution(requested = true, skipped = true, missingSprite = true)
            val size = iconLayer.size.evaluate(iconContext).asNumber("icon-size", tile)
            if (size <= 0.0) return IconResolution(requested = false)
            val opacity = iconLayer.opacity.evaluate(iconContext).asNumber("icon-opacity", tile)
            val haloWidth = iconLayer.haloWidth.evaluate(iconContext).asNumber("icon-halo-width", tile)
            val haloBlur = iconLayer.haloBlur.evaluate(iconContext).asNumber("icon-halo-blur", tile)
            val padding = iconLayer.padding.evaluate(iconContext).asNumber("icon-padding", tile)
            if (opacity !in 0.0..1.0 || haloWidth < 0.0 || haloBlur < 0.0 || padding < 0.0) {
                throw RasterizationException("An icon paint or collision value is outside its valid range", affectedTiles = listOf(tile))
            }
            val offset = iconLayer.offset.evaluate(iconContext).asNumberPair("icon-offset", tile)
            val translate = iconLayer.translate.evaluate(iconContext).asNumberPair("icon-translate", tile)
            val anchor = iconAnchorOrNull(iconLayer.anchor.evaluate(iconContext).asString("icon-anchor", tile))
                ?: throw RasterizationException("icon-anchor did not evaluate to a supported value", affectedTiles = listOf(tile))
            // The same decomposition placeIcons places its own markers from, out of the same
            // helper, so what a consumer draws and what Rentile's icon pass draws cannot drift.
            val anchoring = spriteAnchoring(
                entry = entry,
                anchor = anchor,
                size = size,
                offsetX = offset.first,
                offsetY = offset.second,
                translateX = translate.first,
                translateY = translate.second,
            )
            IconResolution(ref = LabelIconRef(
                imageName = imageName,
                width = anchoring.width,
                height = anchoring.height,
                anchor = anchor.toLabelIconAnchor(),
                offsetX = anchoring.offsetX,
                offsetY = anchoring.offsetY,
                translateX = anchoring.translateX,
                translateY = anchoring.translateY,
                translateAlignment = alignmentOf(iconLayer.translateAnchor.evaluate(iconContext), "icon-translate-anchor", tile, autoAllowed = false),
                color = iconLayer.color.evaluate(iconContext).asColor("icon-color").packedArgb(),
                opacity = opacity,
                haloColor = iconLayer.haloColor.evaluate(iconContext).asColor("icon-halo-color").packedArgb(),
                haloWidth = haloWidth,
                haloBlur = haloBlur,
                rotationDegrees = iconLayer.rotate.evaluate(iconContext).asNumber("icon-rotate", tile),
                padding = padding,
                optional = iconLayer.optional.evaluate(iconContext).asBoolean("icon-optional", tile),
                overlap = overlapOf(iconLayer.overlap.evaluate(iconContext), "icon-overlap", tile),
                ignorePlacement = iconLayer.ignorePlacement.evaluate(iconContext).asBoolean("icon-ignore-placement", tile),
                rotationAlignment = alignmentOf(iconLayer.rotationAlignment.evaluate(iconContext), "icon-rotation-alignment", tile),
                pitchAlignment = alignmentOf(iconLayer.pitchAlignment.evaluate(iconContext), "icon-pitch-alignment", tile),
                keepUpright = iconLayer.keepUpright.evaluate(iconContext).asBoolean("icon-keep-upright", tile),
                avoidEdges = iconLayer.avoidEdges.evaluate(iconContext).asBoolean("symbol-avoid-edges", tile),
                textFit = textFitOf(iconLayer.textFit.evaluate(iconContext), tile),
                textFitPadding = iconLayer.textFitPadding.evaluate(iconContext).asNumberList("icon-text-fit-padding", tile, 4),
            ), requested = true)
        } catch (_: RasterizationException) {
            IconResolution(requested = true, skipped = true)
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

private fun IconAnchor.toLabelIconAnchor(): LabelIconAnchor = when (this) {
    IconAnchor.CENTER -> LabelIconAnchor.CENTER
    IconAnchor.LEFT -> LabelIconAnchor.LEFT
    IconAnchor.RIGHT -> LabelIconAnchor.RIGHT
    IconAnchor.TOP -> LabelIconAnchor.TOP
    IconAnchor.BOTTOM -> LabelIconAnchor.BOTTOM
    IconAnchor.TOP_LEFT -> LabelIconAnchor.TOP_LEFT
    IconAnchor.TOP_RIGHT -> LabelIconAnchor.TOP_RIGHT
    IconAnchor.BOTTOM_LEFT -> LabelIconAnchor.BOTTOM_LEFT
    IconAnchor.BOTTOM_RIGHT -> LabelIconAnchor.BOTTOM_RIGHT
}

private fun StyleValue.asNumber(property: String, tile: TileId): Double =
    (this as? StyleValue.NumberValue)?.value?.takeIf(Double::isFinite)
        ?: throw RasterizationException(
            message = "$property did not evaluate to a finite number",
            affectedTiles = listOf(tile),
        )

private fun StyleValue.asString(property: String, tile: TileId): String =
    (this as? StyleValue.StringValue)?.value
        ?: throw RasterizationException(
            message = "$property did not evaluate to a string",
            affectedTiles = listOf(tile),
        )

private fun StyleValue.asStringOrNull(): String? = (this as? StyleValue.StringValue)?.value

private fun StyleValue.asBoolean(property: String, tile: TileId): Boolean =
    (this as? StyleValue.BooleanValue)?.value
        ?: throw RasterizationException(
            message = "$property did not evaluate to a boolean",
            affectedTiles = listOf(tile),
        )

private fun StyleValue.asNumberList(property: String, tile: TileId, count: Int): List<Double> {
    val values = (this as? StyleValue.ArrayValue)?.values?.takeIf { it.size == count }
        ?: throw RasterizationException(
            message = "$property did not evaluate to $count numbers",
            affectedTiles = listOf(tile),
        )
    return values.map { value ->
        (value as? StyleValue.NumberValue)?.value?.takeIf(Double::isFinite)
            ?: throw RasterizationException(
                message = "$property did not evaluate to $count finite numbers",
                affectedTiles = listOf(tile),
            )
    }
}

private fun placementOf(value: StyleValue, tile: TileId): LabelPlacement = when (value.asString("symbol-placement", tile)) {
    "point" -> LabelPlacement.POINT
    "line" -> LabelPlacement.LINE
    "line-center" -> LabelPlacement.LINE_CENTER
    else -> throw RasterizationException("symbol-placement did not evaluate to a supported value", affectedTiles = listOf(tile))
}

private fun overlapOf(value: StyleValue, property: String, tile: TileId): SymbolOverlap = when (value) {
    is StyleValue.BooleanValue -> if (value.value) SymbolOverlap.ALWAYS else SymbolOverlap.NEVER
    is StyleValue.StringValue -> when (value.value) {
        "always" -> SymbolOverlap.ALWAYS
        "never" -> SymbolOverlap.NEVER
        "cooperative" -> SymbolOverlap.COOPERATIVE
        else -> throw RasterizationException("$property did not evaluate to a supported value", affectedTiles = listOf(tile))
    }
    else -> throw RasterizationException("$property did not evaluate to an overlap value", affectedTiles = listOf(tile))
}

private fun alignmentOf(
    value: StyleValue,
    property: String,
    tile: TileId,
    autoAllowed: Boolean = true,
): SymbolAlignment = when (value.asString(property, tile)) {
    "map" -> SymbolAlignment.MAP
    "viewport" -> SymbolAlignment.VIEWPORT
    "auto" -> if (autoAllowed) SymbolAlignment.AUTO else SymbolAlignment.MAP
    else -> throw RasterizationException("$property did not evaluate to a supported value", affectedTiles = listOf(tile))
}

private fun textFitOf(value: StyleValue, tile: TileId): IconTextFit = when (value.asString("icon-text-fit", tile)) {
    "none" -> IconTextFit.NONE
    "width" -> IconTextFit.WIDTH
    "height" -> IconTextFit.HEIGHT
    "both" -> IconTextFit.BOTH
    else -> throw RasterizationException("icon-text-fit did not evaluate to a supported value", affectedTiles = listOf(tile))
}

private fun zOrderOf(value: StyleValue, tile: TileId): SymbolZOrder = when (value.asString("symbol-z-order", tile)) {
    "auto" -> SymbolZOrder.AUTO
    "source" -> SymbolZOrder.SOURCE
    "viewport-y" -> SymbolZOrder.VIEWPORT_Y
    else -> throw RasterizationException("symbol-z-order did not evaluate to a supported value", affectedTiles = listOf(tile))
}

internal fun textJustifyOf(value: StyleValue, anchor: IconAnchor, tile: TileId): TextJustify =
    when (value.asString("text-justify", tile)) {
        "left" -> TextJustify.LEFT
        "center" -> TextJustify.CENTER
        "right" -> TextJustify.RIGHT
        "auto" -> when (anchor) {
            IconAnchor.LEFT, IconAnchor.TOP_LEFT, IconAnchor.BOTTOM_LEFT -> TextJustify.LEFT
            IconAnchor.RIGHT, IconAnchor.TOP_RIGHT, IconAnchor.BOTTOM_RIGHT -> TextJustify.RIGHT
            IconAnchor.CENTER, IconAnchor.TOP, IconAnchor.BOTTOM -> TextJustify.CENTER
        }
        else -> throw RasterizationException(
            "text-justify did not evaluate to a supported value",
            affectedTiles = listOf(tile),
        )
    }

internal fun geometryAnchors(geometry: DecodedVectorGeometry, placement: LabelPlacement): List<GeometryAnchor> =
    when (placement) {
        LabelPlacement.POINT -> when (geometry) {
            is DecodedVectorGeometry.Points -> geometry.points.map(::GeometryAnchor)
            is DecodedVectorGeometry.Lines -> geometry.lines.mapNotNull(::lineAnchor)
            is DecodedVectorGeometry.Polygons -> polygonPointAnchors(geometry.rings)
        }
        LabelPlacement.LINE, LabelPlacement.LINE_CENTER -> when (geometry) {
            is DecodedVectorGeometry.Lines -> geometry.lines.mapNotNull(::lineAnchor)
            else -> emptyList()
        }
    }

/**
 * Returns one point-placement anchor per MVT polygon component.
 *
 * MVT's screen-coordinate winding is load-bearing here: exterior rings have positive signed area,
 * and any following negative-area rings are holes belonging to that exterior until the next
 * positive ring. A hole therefore constrains its exterior's interior point but never creates a
 * label of its own.
 *
 * A vertex average is not a polygon centroid and can sit outside a concave shape. Instead, each
 * component is intersected with a horizontal scanline halfway through its widest vertex-free Y
 * band. The widest filled interval on that line is strictly inside the even-odd-filled component
 * and avoids its holes. One scan rather than one per vertex band keeps this O(n log n) for the
 * detailed water and land polygons that make polygon labels matter. A degenerate component with
 * no integral interior coordinate produces no anchor and is counted by `LABEL_FEATURE_SKIPPED`
 * rather than being silently placed on an arbitrary boundary vertex.
 */
private fun polygonPointAnchors(rings: List<VectorRing>): List<GeometryAnchor> {
    data class Component(
        val exterior: VectorRing,
        val holes: MutableList<VectorRing> = mutableListOf(),
    )

    val components = mutableListOf<Component>()
    for (ring in rings) {
        if (ring.signedAreaTwice > 0.0) {
            components += Component(ring)
        } else {
            components.lastOrNull()?.holes?.add(ring)
        }
    }
    return components.mapNotNull { component ->
        val componentRings = listOf(component.exterior) + component.holes
        interiorPoint(componentRings)?.let(::GeometryAnchor)
    }
}

private data class InteriorPointCandidate(
    val point: VectorCoordinate,
    val clearance: Double,
)

/** Finds a deterministic integral point strictly inside the even-odd fill of [rings]. */
private fun interiorPoint(
    rings: List<VectorRing>,
): VectorCoordinate? {
    val vertexYs = rings.flatMap { ring -> ring.points.map(VectorCoordinate::y) }.distinct().sorted()
    val band = (0 until vertexYs.lastIndex)
        .map { index -> vertexYs[index] to vertexYs[index + 1] }
        .filter { (low, high) -> high - low >= 2 }
        .maxByOrNull { (low, high) -> high - low }
        ?: return null
    val lowY = band.first
    val highY = band.second
    val y = lowY + (highY - lowY) / 2
    val intersections = rings.flatMap { ring -> scanlineIntersections(ring.points, y.toDouble()) }.sorted()
    var best: InteriorPointCandidate? = null
    var index = 0
    while (index + 1 < intersections.size) {
        val left = intersections[index]
        val right = intersections[index + 1]
        val firstInteriorX = floor(left).toInt() + 1
        val lastInteriorX = ceil(right).toInt() - 1
        if (firstInteriorX <= lastInteriorX) {
            val x = ((left + right) / 2.0).toInt().coerceIn(firstInteriorX, lastInteriorX)
            val clearance = minOf(x - left, right - x)
            if (best == null || clearance > best.clearance) {
                best = InteriorPointCandidate(VectorCoordinate(x, y), clearance)
            }
        }
        index += 2
    }
    return best?.point
}

/** Half-open edge crossing keeps a scanline through a vertex from counting that vertex twice. */
private fun scanlineIntersections(points: List<VectorCoordinate>, y: Double): List<Double> {
    if (points.size < 3) return emptyList()
    return buildList {
        for (index in points.indices) {
            val start = points[index]
            val end = points[(index + 1) % points.size]
            val crosses = (start.y <= y && end.y > y) || (end.y <= y && start.y > y)
            if (crosses) {
                add(start.x + (y - start.y) * (end.x - start.x).toDouble() / (end.y - start.y))
            }
        }
    }
}

private fun lineAnchor(points: List<VectorCoordinate>): GeometryAnchor? {
    if (points.size < 2) return null
    val lengths = points.zipWithNext { a, b -> hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) }
    val total = lengths.sum()
    if (total <= 0.0) return null
    var remaining = total / 2.0
    for (index in lengths.indices) {
        val length = lengths[index]
        if (remaining <= length) {
            val start = points[index]
            val end = points[index + 1]
            val fraction = if (length == 0.0) 0.0 else remaining / length
            return GeometryAnchor(
                point = VectorCoordinate(
                    x = (start.x + (end.x - start.x) * fraction).toInt(),
                    y = (start.y + (end.y - start.y) * fraction).toInt(),
                ),
                line = points,
                rotationDegrees = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble()) * 180.0 / PI,
            )
        }
        remaining -= length
    }
    return null
}

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
