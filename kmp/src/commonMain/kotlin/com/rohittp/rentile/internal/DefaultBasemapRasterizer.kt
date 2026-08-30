package com.rohittp.rentile.internal

import com.rohittp.rentile.BasemapRasterizer
import com.rohittp.rentile.BatchRenderException
import com.rohittp.rentile.CompatibilityPolicy
import com.rohittp.rentile.DemTexels
import com.rohittp.rentile.DiagnosticCode
import com.rohittp.rentile.DiagnosticSeverity
import com.rohittp.rentile.ForeignLabelCandidatePlanException
import com.rohittp.rentile.ForeignPreparedBatchException
import com.rohittp.rentile.ForeignPreparedStyleException
import com.rohittp.rentile.GlyphRangeRef
import com.rohittp.rentile.GlyphTemplateMismatchException
import com.rohittp.rentile.InvalidTileIdException
import com.rohittp.rentile.LabelCandidateBatch
import com.rohittp.rentile.LabelCandidatePlan
import com.rohittp.rentile.LabelCandidatePlanClosedException
import com.rohittp.rentile.LabelLayerDescriptor
import com.rohittp.rentile.MetricName
import com.rohittp.rentile.PipelineStage
import com.rohittp.rentile.PngEncodingException
import com.rohittp.rentile.PreparedBatch
import com.rohittp.rentile.PreparedBatchClosedException
import com.rohittp.rentile.PreparedStyle
import com.rohittp.rentile.RasterizationException
import com.rohittp.rentile.RasterizerClosedException
import com.rohittp.rentile.RenderBatch
import com.rohittp.rentile.RenderDiagnostic
import com.rohittp.rentile.RenderOptions
import com.rohittp.rentile.RenderedTile
import com.rohittp.rentile.ExactRecoveryResult
import com.rohittp.rentile.ResourceSubstitution
import com.rohittp.rentile.ResourceLimits
import com.rohittp.rentile.RentileConfiguration
import com.rohittp.rentile.RentileMetric
import com.rohittp.rentile.RentileException
import com.rohittp.rentile.ResourceAccessMode
import com.rohittp.rentile.ResourceAcquisitionException
import com.rohittp.rentile.ResourceClass
import com.rohittp.rentile.ResourceDecodeException
import com.rohittp.rentile.SafetyLimitException
import com.rohittp.rentile.StyleInput
import com.rohittp.rentile.StylePreparationException
import com.rohittp.rentile.TerrainDemEncoding
import com.rohittp.rentile.TerrainSourceDescriptor
import com.rohittp.rentile.TileId
import com.rohittp.rentile.TileNotInPreparedBatchException
import com.rohittp.rentile.TileSubstitutionException
import com.rohittp.rentile.TileSubstitutionLimitException
import com.rohittp.rentile.TileSubstitutionPolicy
import com.rohittp.rentile.TileSubstitutionStrategy
import com.rohittp.rentile.TransportRequest
import com.rohittp.rentile.ValidatedDemTile
import com.rohittp.rentile.ValidatedMvtTile
import com.rohittp.rentile.internal.metadata.TileJsonResourceAcquirer
import com.rohittp.rentile.internal.geojson.GeoJsonResourceAcquirer
import com.rohittp.rentile.internal.glyph.AcquiredGlyphRange
import com.rohittp.rentile.internal.glyph.GlyphResourceAcquirer
import com.rohittp.rentile.internal.glyph.LABEL_TILE_ORDER
import com.rohittp.rentile.internal.glyph.LabelAssembly
import com.rohittp.rentile.internal.glyph.LabelCandidateAssembler
import com.rohittp.rentile.internal.mvt.DecodedVectorFeature
import com.rohittp.rentile.internal.mvt.DecodedVectorGeometry
import com.rohittp.rentile.internal.mvt.VectorResource
import com.rohittp.rentile.internal.mvt.VectorResourceAcquirer
import com.rohittp.rentile.internal.mvt.VectorTileSample
import com.rohittp.rentile.internal.mvt.ancestor as vectorAncestor
import com.rohittp.rentile.internal.mvt.composeVectorChildren
import com.rohittp.rentile.internal.mvt.immediateChildren as immediateVectorChildren
import com.rohittp.rentile.internal.mvt.sampleFor
import com.rohittp.rentile.internal.mvt.vectorAncestorSubstitute
import com.rohittp.rentile.internal.raster.RasterResource
import com.rohittp.rentile.internal.raster.RasterResourceAcquirer
import com.rohittp.rentile.internal.raster.RasterSample
import com.rohittp.rentile.internal.raster.ancestor as rasterAncestor
import com.rohittp.rentile.internal.raster.composeRasterChildren
import com.rohittp.rentile.internal.raster.immediateChildren as immediateRasterChildren
import com.rohittp.rentile.internal.raster.rasterAncestorSubstitute
import com.rohittp.rentile.internal.raster.sampleFor
import com.rohittp.rentile.internal.raster.neighbor
import com.rohittp.rentile.internal.sprite.SpriteResourceAcquirer
import com.rohittp.rentile.internal.sprite.CompiledSpriteAtlas
import com.rohittp.rentile.internal.sprite.SpriteAtlasEntry
import com.rohittp.rentile.internal.style.BackgroundDrawLayer
import com.rohittp.rentile.internal.style.CompiledDrawLayer
import com.rohittp.rentile.internal.style.CompiledColor
import com.rohittp.rentile.internal.style.CompiledPreparedStyle
import com.rohittp.rentile.internal.style.CompiledLineCap
import com.rohittp.rentile.internal.style.CompiledLineJoin
import com.rohittp.rentile.internal.style.DemEncoding
import com.rohittp.rentile.internal.style.FillDrawLayer
import com.rohittp.rentile.internal.style.IconDrawLayer
import com.rohittp.rentile.internal.style.HillshadeDrawLayer
import com.rohittp.rentile.internal.style.LineDrawLayer
import com.rohittp.rentile.internal.style.RasterDrawLayer
import com.rohittp.rentile.internal.style.RasterResampling
import com.rohittp.rentile.internal.style.StyleEvaluationContext
import com.rohittp.rentile.internal.style.SymbolPlacement
import com.rohittp.rentile.internal.style.StyleCompiler
import com.rohittp.rentile.internal.style.StyleValue
import com.rohittp.rentile.internal.style.parseCssColor
import com.rohittp.rentile.internal.style.iconAnchorOrNull
import com.rohittp.rentile.internal.style.spriteAnchoring
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.skia.Color
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorMatrix
import org.jetbrains.skia.ClipMode
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.PathEffect
import org.jetbrains.skia.Path
import org.jetbrains.skia.PathFillMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

internal fun createBasemapRasterizer(configuration: RentileConfiguration): BasemapRasterizer =
    DefaultBasemapRasterizer(configuration)

private const val EARTH_CIRCUMFERENCE_METERS = 40_075_016.68557849
private const val MAX_ANCESTOR_DISTANCE = 2

/**
 * Bump this whenever a change to label evaluation, text layout or glyph-atlas packing would
 * alter observable [LabelCandidateBatch] output for a style/tile set whose
 * [DefaultBasemapRasterizer.labelCandidateRequestKey] would otherwise stay the same.
 *
 * This is the *only* signal [labelCandidateRequestKey] gives a consumer that its previously
 * cached candidates are stale: the key is deliberately computed before any acquisition, from
 * style and tile identity alone, so it cannot detect a semantics change on its own. Ship a
 * layout or packing change without bumping this and a consumer's cache looks valid forever — it
 * will keep serving stale label geometry and never learn otherwise.
 */
private const val LABEL_SEMANTICS_VERSION = "label-candidates-2"
private val SUBSTITUTABLE_RESOURCE_CLASSES = setOf(
    ResourceClass.VECTOR_TILE,
    ResourceClass.RASTER_TILE,
    ResourceClass.DEM_TILE,
)

@OptIn(ExperimentalAtomicApi::class)
private class DefaultBasemapRasterizer(
    private val configuration: RentileConfiguration,
) : BasemapRasterizer {
    private val owner = Any()
    private val closing = AtomicBoolean(false)
    private val rootJob: Job = SupervisorJob()
    private val scope = CoroutineScope(rootJob + Dispatchers.Default)
    private val closed = CompletableDeferred<Unit>()
    private val secretContexts = AtomicReference<List<SecretContext>>(emptyList())
    private val resourceWorkCoordinator = ResourceWorkCoordinator(configuration.executionPolicy)
    private val tileJsonAcquirer = TileJsonResourceAcquirer(configuration, scope, resourceWorkCoordinator)
    private val spriteAcquirer = SpriteResourceAcquirer(configuration, scope, resourceWorkCoordinator)
    private val geoJsonAcquirer = GeoJsonResourceAcquirer(configuration, scope, resourceWorkCoordinator)
    private val compiler = StyleCompiler(
        owner,
        tileJsonAcquirer::acquire,
        spriteAcquirer::acquire,
        geoJsonAcquirer::acquire,
    )
    private val rasterAcquirer = RasterResourceAcquirer(configuration, scope, resourceWorkCoordinator)
    private val vectorAcquirer = VectorResourceAcquirer(configuration, scope, resourceWorkCoordinator)
    // Same three collaborators the sprite acquirer takes, including the rasterizer's own
    // supervisor scope: SingleFlight launches each range as a direct child of it, so one range
    // failing must not cancel its siblings and turn a single missing block into a whole-batch
    // failure. Like the sprite acquirer it owns nothing beyond that scope, so cancelling
    // rootJob in close() is all the teardown it needs.
    private val glyphAcquirer = GlyphResourceAcquirer(configuration, scope, resourceWorkCoordinator)
    private val renderPermits = Semaphore(configuration.executionPolicy.maxConcurrentMetatileWorkers)

    init {
        rootJob.invokeOnCompletion {
            secretContexts.exchange(emptyList()).forEach(SecretContext::clear)
            closed.complete(Unit)
        }
    }

    override suspend fun prepare(style: StyleInput, policy: CompatibilityPolicy): PreparedStyle = operation {
        val acquired = acquireStyle(style)
        currentCoroutineContext().ensureActive()
        try {
            compiler.compile(acquired.bytes, policy, acquired.baseUri).also { prepared ->
                trackSecretContext(prepared.secretContext)
                prepared.diagnostics.forEach(::recordDiagnosticSafely)
            }
        } catch (error: StylePreparationException) {
            error.diagnostics.forEach(::recordDiagnosticSafely)
            throw error
        }
    }

    override fun outputRequestKey(
        style: PreparedStyle,
        tile: TileId,
        options: RenderOptions,
    ): String {
        val compiled = requireOwnedStyle(style)
        validateTile(tile, compiled.policy)
        return buildString {
            append("rentile-output-request-2\n")
            append(compiled.digest)
            append('\n')
            append(tile.z)
            append('/')
            append(canonicalX(tile))
            append('/')
            append(tile.y)
            append('\n')
            append(options.outputSizePx)
        }.sha256Hex()
    }

    override suspend fun prepareBatch(
        style: PreparedStyle,
        tiles: List<TileId>,
        options: RenderOptions,
        resourceAccess: ResourceAccessMode,
        substitutionPolicy: TileSubstitutionPolicy,
    ): PreparedBatch = operation {
        val compiledStyle = requireOwnedStyle(style)
        val stableTiles = tiles.toList()
        stableTiles.forEach { validateTile(it, compiledStyle.policy) }
        val duplicate = stableTiles.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        if (duplicate != null) throw InvalidTileIdException(duplicate, "Prepared batch contains a duplicate tile")
        val preparedResources = prepareBatchResources(
            style = compiledStyle,
            tiles = stableTiles,
            resourceAccess = resourceAccess,
            substitutionPolicy = substitutionPolicy,
        )
        val state = buildPreparedBatchState(compiledStyle, stableTiles, options, preparedResources)
        state.diagnostics.filter { it.code == DiagnosticCode.TILE_RESOURCE_SUBSTITUTED }
            .forEach(configuration.diagnosticSink::recordSafely)
        state.substitutions.values.flatten().forEach { substitution ->
            configuration.metricsSink.recordSafely(
                RentileMetric(
                    name = MetricName.TILE_RESOURCE_SUBSTITUTED,
                    resourceClass = substitution.resourceClass,
                    tags = buildMap {
                        put("strategy", substitution.strategy.name)
                        substitution.ancestorZoomDistance?.let { put("ancestorZoomDistance", it.toString()) }
                    },
                ),
            )
        }
        DefaultPreparedBatch(
            owner = owner,
            style = compiledStyle,
            tiles = stableTiles,
            options = options,
            initialState = state,
        )
    }

    private suspend fun prepareBatchResources(
        style: CompiledPreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode,
        substitutionPolicy: TileSubstitutionPolicy,
    ): PreparedResources = if (
        resourceAccess == ResourceAccessMode.CACHE_SUBSTITUTE_THEN_NETWORK
    ) {
        prepareCacheSubstituteThenNetwork(style, tiles, substitutionPolicy)
    } else {
        prepareResources(style, tiles, resourceAccess, substitutionPolicy)
    }

    private suspend fun prepareResources(
        style: CompiledPreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode,
        substitutionPolicy: TileSubstitutionPolicy,
    ): PreparedResources {
        val (rasterPlan, vectorPlan) = planResources(style, tiles, resourceAccess)
        validateSubstitutionAllowance(rasterPlan, vectorPlan, substitutionPolicy)
        return resolveResources(rasterPlan, vectorPlan, resourceAccess)
    }

    /**
     * Gives the caller a drawable cached result without making exact transport latency part of
     * initial preparation. The substitution allowance is assigned deterministically in caller
     * tile order; once it is exhausted, remaining misses use the existing NORMAL path.
     */
    private suspend fun prepareCacheSubstituteThenNetwork(
        style: CompiledPreparedStyle,
        tiles: List<TileId>,
        substitutionPolicy: TileSubstitutionPolicy,
    ): PreparedResources {
        val (cachedRaster, cachedVector) = planResources(
            style,
            tiles,
            ResourceAccessMode.CACHE_ONLY,
        )
        val cacheFailures = cachedRaster.failures() + cachedVector.failures()
        val ineligible = cacheFailures.filterNot { isSubstitutionEligible(it.error) }
        if (ineligible.isNotEmpty()) {
            throwAcquisitionFailures(ineligible.map { AcquisitionOutcome.Failure(it.error) })
        }

        val missedTiles = cacheFailures.mapTo(mutableSetOf()) { it.tile }
        if (missedTiles.isEmpty()) {
            return resolveResources(cachedRaster, cachedVector, ResourceAccessMode.CACHE_ONLY)
        }

        val prepared = mutableListOf<PreparedResources>()
        val exactCacheHits = tiles.filterNot(missedTiles::contains)
        if (exactCacheHits.isNotEmpty()) {
            prepared += resolveResources(
                cachedRaster.onlyTiles(exactCacheHits),
                cachedVector.onlyTiles(exactCacheHits),
                ResourceAccessMode.CACHE_ONLY,
            )
        }

        var remainingSubstitutedTiles = substitutionPolicy.maximumSubstitutedTiles
        val networkTiles = mutableListOf<TileId>()
        tiles.filter(missedTiles::contains).forEach { tile ->
            if (remainingSubstitutedTiles == 0) {
                networkTiles += tile
                return@forEach
            }
            val raster = cachedRaster.onlyTiles(listOf(tile))
            val vector = cachedVector.onlyTiles(listOf(tile))
            val cachedSubstitute = try {
                validateSubstitutionAllowance(
                    raster,
                    vector,
                    TileSubstitutionPolicy(maximumSubstitutedTiles = 1),
                )
                resolveResources(raster, vector, ResourceAccessMode.CACHE_ONLY)
            } catch (error: CancellationException) {
                throw error
            } catch (_: TileSubstitutionException) {
                null
            }
            if (cachedSubstitute == null) {
                networkTiles += tile
            } else {
                prepared += cachedSubstitute
                remainingSubstitutedTiles -= 1
            }
        }

        if (networkTiles.isNotEmpty()) {
            prepared += prepareResources(
                style = style,
                tiles = networkTiles,
                resourceAccess = ResourceAccessMode.NORMAL,
                substitutionPolicy = TileSubstitutionPolicy(remainingSubstitutedTiles),
            )
        }
        return mergePreparedResources(tiles, prepared)
    }

    private suspend fun planResources(
        style: CompiledPreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode,
    ): Pair<RasterAcquisitionPlan, VectorAcquisitionPlan> = supervisorScope {
        val raster = async { planRasterResources(style, tiles, resourceAccess) }
        val vector = async { planVectorResources(style, tiles, resourceAccess) }
        raster.await() to vector.await()
    }

    private suspend fun resolveResources(
        rasterPlan: RasterAcquisitionPlan,
        vectorPlan: VectorAcquisitionPlan,
        resourceAccess: ResourceAccessMode,
    ): PreparedResources = supervisorScope {
        val raster = async { resolveRasterResources(rasterPlan, resourceAccess) }
        val vector = async { resolveVectorResources(vectorPlan, resourceAccess) }
        val resolvedVector = vector.await()
        PreparedResources(raster.await(), resolvedVector.resources, resolvedVector.diagnostics)
    }

    private fun mergePreparedResources(
        tiles: List<TileId>,
        parts: List<PreparedResources>,
    ): PreparedResources = PreparedResources(
        raster = tiles.associateWith { tile ->
            parts.firstNotNullOfOrNull { it.raster[tile] }.orEmpty()
        },
        vector = tiles.associateWith { tile ->
            parts.firstNotNullOfOrNull { it.vector[tile] }.orEmpty()
        },
        acquisitionDiagnostics = parts.flatMap { it.acquisitionDiagnostics },
    )

    override suspend fun retryExact(batch: PreparedBatch): ExactRecoveryResult = operation {
        val prepared = requireOwnedBatch(batch)
        prepared.withRecoveryLock {
            prepared.ensureOpen()
            val before = prepared.snapshot()
            if (before.substitutions.isEmpty()) {
                return@withRecoveryLock ExactRecoveryResult(emptySet(), emptySet())
            }
            val recoveredRaster = recoverRasterResources(before.resources.raster)
            val recoveredVector = recoverVectorResources(before.resources.vector)
            currentCoroutineContext().ensureActive()
            prepared.ensureOpen()
            val after = buildPreparedBatchState(
                prepared.style,
                prepared.tiles,
                prepared.options,
                PreparedResources(
                    recoveredRaster.resources,
                    recoveredVector.resources,
                    before.resources.acquisitionDiagnostics,
                ),
            )
            prepared.replaceState(after)
            (recoveredRaster.upgradedTiles + recoveredVector.upgradedTiles).forEach {
                configuration.metricsSink.recordSafely(RentileMetric(MetricName.TILE_EXACT_RECOVERED))
            }
            ExactRecoveryResult(
                upgradedTiles = recoveredRaster.upgradedTiles + recoveredVector.upgradedTiles,
                remainingSubstitutedTiles = after.substitutions.keys,
                diagnostics = (recoveredRaster.diagnostics + recoveredVector.diagnostics).distinct(),
            )
        }
    }

    override fun labelLayerDescriptors(style: PreparedStyle): List<LabelLayerDescriptor> =
        requireOwnedStyle(style).labelLayers.map { it.descriptor }

    override suspend fun acquireLabelTiles(
        style: PreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode,
    ): List<ValidatedMvtTile> = operation {
        val compiledStyle = requireOwnedStyle(style)
        val stableTiles = tiles.toList()
        stableTiles.forEach { validateTile(it, compiledStyle.policy) }
        val samples = compiledStyle.labelLayers
            .map { it.source }
            .distinctBy { it.idDigest }
            .flatMap { source -> stableTiles.mapNotNull { tile -> source.sampleFor(tile) } }
            .distinctBy { it.identity to it.outputTile }
        val outcomes = supervisorScope {
            samples.map { sample ->
                async { sample to acquireOutcome { vectorAcquirer.acquire(sample, resourceAccess) } }
            }.awaitAll()
        }
        throwAcquisitionFailures(outcomes.map { it.second })
        outcomes.map { (sample, outcome) ->
            val resource = (outcome as AcquisitionOutcome.Success).value
            val bytes = resource.encodedBytes ?: throw ResourceDecodeException(
                message = "Label resource is not encoded MVT",
                resourceClass = ResourceClass.VECTOR_TILE,
                sanitizedResourceId = sample.source.idDigest,
                affectedTiles = listOf(sample.outputTile),
            )
            ValidatedMvtTile(
                requestedTile = sample.outputTile,
                sourceTile = TileId(sample.sourceZ, sample.sourceX, sample.sourceY),
                sourceId = sample.source.idDigest,
                bytes = bytes.copyOf(),
                contentDigest = resource.contentDigest,
            )
        }
    }

    override fun labelCandidateRequestKey(style: PreparedStyle, tiles: List<TileId>): String {
        val compiled = requireOwnedStyle(style)
        val stableTiles = tiles.toList()
        stableTiles.forEach { validateTile(it, compiled.policy) }
        // Sorted and de-duplicated, so a caller that reorders its viewport tiles between frames or
        // repeats one lands on the same key rather than refetching for nothing.
        //
        // x is NOT canonicalized, and that is the difference from an earlier version of this key.
        // acquireLabelCandidates emits requestedTile verbatim, so TileId(1,-1,0) and TileId(1,1,0)
        // - world copies of one another, both legitimate inputs, since validateTile bounds only z
        // and y - produce different batches, each tagged for its own copy. Folding them onto one
        // key handed a consumer panning across the antimeridian the other copy's candidates, whose
        // requestedTile matches no tile it is drawing, so it drew nothing on one side of the world.
        // A key must alias only what the operation it guards actually treats as identical.
        val stableTileList = stableTiles
            .map { Triple(it.z, it.x, it.y) }
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
            .joinToString(",") { (z, x, y) -> "$z/$x/$y" }
        // compiled.digest is credential-free: it is computed over the style JSON passed through
        // redactedForIdentity(), which strips authentication query values wherever they appear -
        // the glyphs template's included, since that is where its credential lives. It also folds
        // in compiled.policy.id. So nothing else from the style is needed here, and nothing that
        // is here can leak a credential.
        return listOf(
            LABEL_SEMANTICS_VERSION,
            compiled.digest,
            stableTileList,
        ).joinToString("|").sha256Hex()
    }

    override suspend fun planLabelCandidates(
        style: PreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode,
    ): LabelCandidatePlan = operation {
        val compiledStyle = requireOwnedStyle(style)
        val stableTiles = tiles.toList()
        stableTiles.forEach { validateTile(it, compiledStyle.policy) }
        // A style with no glyphs template has no text to lay out. That is a legitimate style, and
        // this API is opt-in, so it reports and plans an empty closure rather than failing
        // (ADR 0026). The diagnostic is recorded here rather than at acquisition because it is a
        // planning fact: nothing about the tiles can change the answer.
        if (compiledStyle.glyphsTemplate == null) {
            recordDiagnosticSafely(LabelCandidateAssembler.glyphRangeUnavailable(stableTiles))
            return@operation DefaultLabelCandidatePlan(
                owner = owner,
                style = compiledStyle,
                tiles = stableTiles.distinct().sortedWith(LABEL_TILE_ORDER),
                callerTiles = stableTiles,
                resourceAccess = resourceAccess,
                assembly = null,
                limits = configuration.resourceLimits,
            )
        }

        val samples = compiledStyle.labelLayers
            .map { it.source }
            .distinctBy { it.idDigest }
            .flatMap { source -> stableTiles.mapNotNull { tile -> source.sampleFor(tile) } }
            .distinctBy { it.identity to it.outputTile }
        val tileOutcomes = supervisorScope {
            samples.map { sample ->
                async { sample to acquireOutcome { vectorAcquirer.acquire(sample, resourceAccess) } }
            }.awaitAll()
        }
        throwAcquisitionFailures(tileOutcomes.map { it.second })
        val resources = tileOutcomes.associate { (sample, outcome) ->
            (sample.source.idDigest to sample.outputTile) to (outcome as AcquisitionOutcome.Success).value
        }

        val assembly = LabelCandidateAssembler.plan(
            style = compiledStyle,
            tiles = stableTiles.distinct(),
            resources = resources,
            limits = configuration.resourceLimits,
            iconImageNameOf = ::evaluateIconImageName,
        )
        DefaultLabelCandidatePlan(
            owner = owner,
            style = compiledStyle,
            tiles = stableTiles.distinct().sortedWith(LABEL_TILE_ORDER),
            callerTiles = stableTiles,
            resourceAccess = resourceAccess,
            assembly = assembly,
            limits = configuration.resourceLimits,
        )
    }

    override suspend fun acquireLabelCandidates(plan: LabelCandidatePlan): LabelCandidateBatch = operation {
        val owned = requireOwnedLabelCandidatePlan(plan)
        // Read once, into a local, before any suspension: a concurrent close() must not be able
        // to pull state out from under an acquisition already in flight.
        val state = owned.stateForAcquisition()
        val assembly = state.assembly
            ?: return@operation LabelCandidateAssembler.emptyBatch(state.style, state.callerTiles, state.limits)

        val glyphsTemplate = state.style.glyphsTemplate
            ?: return@operation LabelCandidateAssembler.emptyBatch(state.style, state.callerTiles, state.limits)
        val rangeOutcomes = supervisorScope {
            assembly.requiredRanges.map { request ->
                async {
                    acquireOutcome {
                        glyphAcquirer.acquire(
                            glyphsTemplate.resolve(),
                            request.fontStack,
                            request.rangeStart,
                            state.resourceAccess,
                        )
                    }
                }
            }.awaitAll()
        }
        throwAcquisitionFailures(rangeOutcomes)
        val ranges = rangeOutcomes.map { (it as AcquisitionOutcome.Success<AcquiredGlyphRange>).value }

        assembly.assemble(ranges, ::recordDiagnosticSafely)
    }

    override suspend fun acquireLabelCandidates(
        style: PreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode,
    ): LabelCandidateBatch {
        val plan = planLabelCandidates(style, tiles, resourceAccess)
        return try {
            acquireLabelCandidates(plan)
        } finally {
            plan.close()
        }
    }

    override fun terrainSourceDescriptor(style: PreparedStyle): TerrainSourceDescriptor? =
        requireOwnedStyle(style).terrainSource?.let { source ->
            TerrainSourceDescriptor(
                sourceId = source.idDigest,
                encoding = source.demEncoding.toPublicEncoding(),
                minimumZoom = source.minZoom,
                maximumZoom = source.maxZoom,
                tileSizePx = source.tileSize,
            )
        }

    override fun groundRadianceDescriptor(style: PreparedStyle) =
        requireOwnedStyle(style).groundRadiance

    override suspend fun acquireTerrainTiles(
        style: PreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode,
    ): List<ValidatedDemTile> = operation {
        val compiledStyle = requireOwnedStyle(style)
        val source = compiledStyle.terrainSource ?: return@operation emptyList()
        val stableTiles = tiles.toList()
        stableTiles.forEach { validateTile(it, compiledStyle.policy) }
        val samples = stableTiles.mapNotNull { tile -> source.sampleFor(tile) }.distinctBy { it.identity to it.outputTile }
        val outcomes = supervisorScope {
            samples.map { sample ->
                async {
                    sample to acquireOutcome {
                        rasterAcquirer.acquire(sample, resourceAccess, retainPixels = true)
                    }
                }
            }.awaitAll()
        }
        throwAcquisitionFailures(outcomes.map { it.second })
        outcomes.map { (sample, outcome) ->
            val resource = (outcome as AcquisitionOutcome.Success).value
            // retainPixels = true above, so this is unreachable rather than merely unlikely; it
            // stays a typed failure because an NPE here would cross the public boundary.
            val rgba = resource.rgba ?: throw ResourceDecodeException(
                message = "Validated DEM tile carries no decoded pixels",
                resourceClass = ResourceClass.DEM_TILE,
                sanitizedResourceId = sample.identity.sha256Hex(),
                affectedTiles = listOf(sample.outputTile),
            )
            ValidatedDemTile(
                requestedTile = sample.outputTile,
                sourceTile = TileId(sample.sourceZ, sample.sourceX, sample.sourceY),
                sourceId = source.idDigest,
                encoding = source.demEncoding.toPublicEncoding(),
                bytes = resource.bytes.copyOf(),
                contentDigest = resource.contentDigest,
                // Copied for the same reason bytes is: one single-flight result is shared by every
                // joiner, so two requested tiles backed by one source tile would otherwise hand
                // consumers the same mutable array.
                texels = DemTexels(resource.width, resource.height, rgba.copyOf()),
            )
        }
    }

    override suspend fun render(batch: PreparedBatch, tiles: List<TileId>): RenderBatch = operation {
        val prepared = requireOwnedBatch(batch)
        val lease = prepared.acquireRenderLease()
        try {
            val requested = tiles.toList()
            requested.forEach { tile ->
                if (tile !in lease.state.contentKeys) throw TileNotInPreparedBatchException(tile)
            }
            val tileResults = requested.map { tile ->
                currentCoroutineContext().ensureActive()
                renderPermits.withPermit { renderTile(prepared, lease.state.resources, tile) }
            }
            val rendered = tileResults.map { result ->
                configuration.metricsSink.recordSafely(
                    RentileMetric(MetricName.TILE_RENDERED, resourceClass = null),
                )
                configuration.metricsSink.recordSafely(
                    RentileMetric(MetricName.PNG_ENCODED_BYTES, value = result.png.size.toLong()),
                )
                RenderedTile(
                    id = result.tile,
                    pngBytes = result.png,
                    contentKey = lease.state.contentKeys.getValue(result.tile),
                    diagnostics = lease.state.diagnostics + result.diagnostics,
                )
            }
            RenderBatch(rendered, lease.state.diagnostics + tileResults.flatMap { it.diagnostics })
        } finally {
            lease.close()
        }
    }

    override suspend fun render(
        style: PreparedStyle,
        tiles: List<TileId>,
        options: RenderOptions,
        resourceAccess: ResourceAccessMode,
        substitutionPolicy: TileSubstitutionPolicy,
    ): RenderBatch {
        val batch = prepareBatch(style, tiles, options, resourceAccess, substitutionPolicy)
        return try {
            render(batch)
        } finally {
            batch.close()
        }
    }

    override fun close() {
        if (closing.compareAndSet(expectedValue = false, newValue = true)) {
            rootJob.cancel(CancellationException("Rentile rasterizer closed"))
        }
    }

    override suspend fun awaitClosed() {
        closed.await()
    }

    private suspend fun acquireStyle(input: StyleInput): AcquiredStyle {
        val acquired = when (input) {
            is StyleInput.InlineJson -> AcquiredStyle(input.json.encodeToByteArray(), input.baseUri)
            is StyleInput.Prefetched -> AcquiredStyle(input.bytes.copyOf(), input.baseUri)
            is StyleInput.Remote -> AcquiredStyle(acquireRemoteStyle(input.url), input.url)
        }
        val limit = configuration.resourceLimits.maxStyleBytes
        if (acquired.bytes.size.toLong() > limit) {
            throw SafetyLimitException(
                message = "Style response exceeds the configured byte limit",
                limitName = "maxStyleBytes",
                limit = limit,
                observed = acquired.bytes.size.toLong(),
                stage = PipelineStage.STYLE_PREPARATION,
            )
        }
        return acquired
    }

    private suspend fun acquireRemoteStyle(url: String): ByteArray {
        val sanitizedId = url.withRedactedAuthenticationQuery().sha256Hex()
        configuration.metricsSink.recordSafely(RentileMetric(MetricName.RESOURCE_REQUEST, resourceClass = ResourceClass.STYLE))
        val response = try {
            configuration.transport.execute(
                TransportRequest(
                    url = url,
                    resourceClass = ResourceClass.STYLE,
                    maxResponseBytes = configuration.resourceLimits.maxStyleBytes,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw ResourceAcquisitionException(
                message = "Style transport failed",
                resourceClass = ResourceClass.STYLE,
                sanitizedResourceId = sanitizedId,
            )
        }
        if (response.statusCode !in 200..299) {
            throw ResourceAcquisitionException(
                message = "Style transport returned a non-success status",
                resourceClass = ResourceClass.STYLE,
                sanitizedResourceId = sanitizedId,
                statusCode = response.statusCode,
                retryAfterMillis = response.metadata.retryAfterMillis,
            )
        }
        val body = response.body
        configuration.metricsSink.recordSafely(
            RentileMetric(
                name = MetricName.RESOURCE_WIRE_BYTES,
                value = response.metadata.wireByteCount ?: body.size.toLong(),
                resourceClass = ResourceClass.STYLE,
            ),
        )
        return body
    }

    private fun requireOwnedStyle(style: PreparedStyle): CompiledPreparedStyle {
        val prepared = style as? CompiledPreparedStyle ?: throw ForeignPreparedStyleException()
        if (prepared.owner !== owner) throw ForeignPreparedStyleException()
        return prepared
    }

    private fun requireOwnedBatch(batch: PreparedBatch): DefaultPreparedBatch {
        val prepared = batch as? DefaultPreparedBatch ?: throw ForeignPreparedBatchException()
        if (prepared.owner !== owner) throw ForeignPreparedBatchException()
        return prepared
    }

    private fun requireOwnedLabelCandidatePlan(plan: LabelCandidatePlan): DefaultLabelCandidatePlan {
        val owned = plan as? DefaultLabelCandidatePlan ?: throw ForeignLabelCandidatePlanException()
        if (owned.owner !== owner) throw ForeignLabelCandidatePlanException()
        return owned
    }

    private fun validateTile(tile: TileId, policy: CompatibilityPolicy) {
        if (tile.z !in policy.minimumOutputZoom..policy.maximumOutputZoom) {
            throw InvalidTileIdException(tile, "Tile zoom is outside the compatibility profile's output range")
        }
        val dimension = 1L shl tile.z
        if (tile.y.toLong() !in 0 until dimension) throw InvalidTileIdException(tile)
    }

    private fun canonicalX(tile: TileId): Long {
        val dimension = 1L shl tile.z
        val remainder = tile.x.toLong() % dimension
        return if (remainder < 0) remainder + dimension else remainder
    }

    private fun DemEncoding?.toPublicEncoding(): TerrainDemEncoding = when (this) {
        DemEncoding.TERRARIUM -> TerrainDemEncoding.TERRARIUM
        DemEncoding.MAPBOX, null -> TerrainDemEncoding.MAPBOX
    }

    private suspend fun planRasterResources(
        style: CompiledPreparedStyle,
        tiles: List<TileId>,
        accessMode: ResourceAccessMode,
    ): RasterAcquisitionPlan = supervisorScope {
        val samplesByTile = tiles.associateWith { tile ->
            style.drawLayers
                .filter { it is RasterDrawLayer || it is HillshadeDrawLayer }
                .filter { it.isActiveAt(tile.z) }
                .flatMap { layer ->
                    when (layer) {
                        is RasterDrawLayer -> listOfNotNull(layer.source.sampleFor(tile))
                        is HillshadeDrawLayer -> layer.source.sampleFor(tile)?.let { center ->
                            (-1..1).flatMap { deltaY ->
                                (-1..1).mapNotNull { deltaX -> center.neighbor(deltaX, deltaY) }
                            }
                        }.orEmpty()
                        else -> emptyList()
                    }
                }
                .distinctBy { it.identity }
        }
        val representatives = samplesByTile.values.flatten()
            .associateBy { it.identity }
            .entries
            .sortedBy { it.key }
            .associate { it.toPair() }
        val pending = representatives.mapValues { (_, sample) ->
            async { acquireOutcome { rasterAcquirer.acquire(sample, accessMode) } }
        }
        val outcomes = pending.mapValues { (_, deferred) -> deferred.await() }
        RasterAcquisitionPlan(samplesByTile, outcomes)
    }

    private suspend fun planVectorResources(
        style: CompiledPreparedStyle,
        tiles: List<TileId>,
        accessMode: ResourceAccessMode,
    ): VectorAcquisitionPlan = supervisorScope {
        val samplesByTile = tiles.associateWith { tile ->
            style.drawLayers
                .filter { it is FillDrawLayer || it is LineDrawLayer || it is IconDrawLayer }
                .filter { it.isActiveAt(tile.z) }
                .map { layer ->
                    when (layer) {
                        is FillDrawLayer -> layer.source
                        is LineDrawLayer -> layer.source
                        is IconDrawLayer -> layer.source
                        else -> error("unreachable")
                    }
                }
                .mapNotNull { it.sampleFor(tile) }
                .distinctBy { it.source.idDigest }
        }
        val representatives = samplesByTile.values.flatten()
            .associateBy { it.identity }
            .entries
            .sortedBy { it.key }
            .associate { it.toPair() }
        val pending = representatives.mapValues { (_, sample) ->
            async { acquireOutcome { vectorAcquirer.acquire(sample, accessMode) } }
        }
        val outcomes = pending.mapValues { (_, deferred) -> deferred.await() }
        // Sampling above is zoom-scoped, so the classification has to be too - see
        // bestEffortVectorSourceDigests. One entry per distinct zoom in the batch, not per tile:
        // the answer depends only on which layers are active.
        val bestEffortByZoom = tiles.map { it.z }.distinct()
            .associateWith { zoom -> bestEffortVectorSourceDigests(style, zoom) }
        VectorAcquisitionPlan(samplesByTile, outcomes, bestEffortByZoom)
    }

    /**
     * Vector sources reachable *only* through repaired icon layers - layers this compatibility
     * profile retained because their text was removed and their icon is independent of it.
     *
     * Such a source was fetched exactly never before this profile retained those layers: no fill,
     * line or author-intended icon layer referenced it, and being an auxiliary *label* source is
     * not enough, since `render` never acquires those. Adding it to the per-tile fetch set turns
     * one 404 on an empty POI tile - the commonest tile-server behaviour there is - into a failed
     * batch, because the default TileSubstitutionPolicy.Disabled makes any acquisition failure
     * fatal at planning time. Under ResourceAccessMode.CACHE_ONLY every tile fails instead, since
     * nothing ever warmed a tileset the renderer never used, which breaks offline export outright.
     *
     * So these sources are best-effort: if acquiring one fails, the layers that need it are simply
     * not drawn for that tile. [placeIcons] already tolerates a missing resource, so nothing
     * downstream needs to change - the gap was only that planning threw first.
     *
     * The set is computed from the compiled draw layers rather than guessed at render time, and a
     * source shared with *any* fill, line, raster, hillshade or author-intended icon layer is
     * excluded from it, keeping today's strict behaviour exactly for everything else.
     *
     * It is scoped to [zoom] because sampling is: [planVectorResources] only samples layers
     * passing `isActiveAt`, so only those layers can cause the fetch whose failure is being
     * classified. A zoom-agnostic answer under-reaches - a source required solely by, say, a
     * `minzoom: 14` fill would count as required at z=10, where that fill draws nothing and never
     * asked for the tile, and the repaired POI layer sharing it would lose its best-effort
     * treatment. On `main` neither layer fetched anything at z=10 and the tile rendered, so that
     * is the same invariant leaking, just in the direction that fails loudly instead of quietly.
     */
    private fun bestEffortVectorSourceDigests(style: CompiledPreparedStyle, zoom: Int): Set<String> {
        val repaired = mutableSetOf<String>()
        val required = mutableSetOf<String>()
        for (layer in style.drawLayers) {
            if (!layer.isActiveAt(zoom)) continue
            // Exhaustive on purpose: no `else`. A new CompiledDrawLayer kind must be a compile
            // error here, because the failure mode of forgetting one is a source that silently
            // stops being subtracted - the exact silent-loss shape this whole path exists to
            // avoid. The sibling `when` in planVectorResources states the same intent with
            // error("unreachable").
            when (layer) {
                is IconDrawLayer ->
                    if (layer.retainedIndependentOfText) repaired += layer.source.idDigest
                    else required += layer.source.idDigest
                is FillDrawLayer -> required += layer.source.idDigest
                is LineDrawLayer -> required += layer.source.idDigest
                is RasterDrawLayer -> required += layer.source.idDigest
                is HillshadeDrawLayer -> required += layer.source.idDigest
                is BackgroundDrawLayer -> Unit
            }
        }
        return repaired - required
    }

    private fun validateSubstitutionAllowance(
        raster: RasterAcquisitionPlan,
        vector: VectorAcquisitionPlan,
        policy: TileSubstitutionPolicy,
    ) {
        val failures = raster.failures() + vector.failures()
        if (failures.isEmpty()) return
        val ineligible = failures.filterNot { isSubstitutionEligible(it.error) }
        if (ineligible.isNotEmpty() || policy.maximumSubstitutedTiles == 0) {
            throwAcquisitionFailures(
                (if (ineligible.isNotEmpty()) ineligible else failures).map { AcquisitionOutcome.Failure(it.error) },
            )
        }
        val affectedTiles = failures.map { it.tile }.distinct()
        if (affectedTiles.size > policy.maximumSubstitutedTiles) {
            throw TileSubstitutionLimitException(
                maximumSubstitutedTiles = policy.maximumSubstitutedTiles,
                requiredSubstitutedTiles = affectedTiles.size,
                primaryFailure = failures.first().error as ResourceAcquisitionException,
                affectedTiles = affectedTiles,
            )
        }
    }

    private fun isSubstitutionEligible(error: Throwable): Boolean {
        val failure = error as? ResourceAcquisitionException ?: return false
        if (failure.resourceClass !in SUBSTITUTABLE_RESOURCE_CLASSES) return false
        val status = failure.statusCode ?: return true
        return status == 404 || status == 408 || status == 429 || status >= 500
    }

    private suspend fun resolveRasterResources(
        plan: RasterAcquisitionPlan,
        accessMode: ResourceAccessMode,
    ): Map<TileId, List<RasterResource>> = supervisorScope {
        plan.samplesByTile.map { (tile, samples) ->
            tile to samples.map { sample ->
                async {
                    when (val outcome = plan.outcomesByIdentity.getValue(sample.identity)) {
                        is AcquisitionOutcome.Success -> outcome.value.forExactSample(sample)
                        is AcquisitionOutcome.Failure -> substituteRaster(
                            requested = sample,
                            primaryFailure = outcome.error as ResourceAcquisitionException,
                            accessMode = accessMode,
                        )
                    }
                }
            }.awaitAll()
        }.toMap()
    }

    private suspend fun resolveVectorResources(
        plan: VectorAcquisitionPlan,
        accessMode: ResourceAccessMode,
    ): VectorResolution = supervisorScope {
        val resolved = plan.samplesByTile.map { (tile, samples) ->
            tile to samples.map { sample ->
                async {
                    when (val outcome = plan.outcomesByIdentity.getValue(sample.identity)) {
                        is AcquisitionOutcome.Success -> VectorResolutionItem(outcome.value.forExactSample(sample))
                        is AcquisitionOutcome.Failure ->
                            // A best-effort source never consumes substitution: it must not burn
                            // the budget and then throw TileSubstitutionException, it just skips.
                            if (plan.isBestEffort(tile, sample, outcome.error)) {
                                VectorResolutionItem(
                                    resource = null,
                                    diagnostic = iconLayerSourceUnavailableDiagnostic(tile, sample, outcome.error),
                                )
                            } else {
                                VectorResolutionItem(
                                    substituteVector(
                                        requested = sample,
                                        primaryFailure = outcome.error as ResourceAcquisitionException,
                                        accessMode = accessMode,
                                    ),
                                )
                            }
                    }
                }
            }.awaitAll()
        }.toMap()
        VectorResolution(
            resources = resolved.mapValues { (_, items) -> items.mapNotNull { it.resource } },
            diagnostics = resolved.values.flatten().mapNotNull { it.diagnostic },
        )
    }

    /**
     * Reported when a vector source reachable only through repaired icon layers could not be
     * acquired, so those layers draw nothing for this tile and the tile is returned anyway. It
     * goes to the sink *and* into the batch state, so it reaches `RenderedTile.diagnostics` for a
     * caller that configured no sink - which is the only way this is observable at all, since the
     * alternative it replaces was a thrown batch failure nobody could miss.
     *
     * It names the source two ways, both already-redacted digests and neither a URL.
     * `sourceIdDigest` is stable across tiles and answers *which* source was lost;
     * `resourceId` is per-sample, in the same form `RASTER_PASSTHROUGH_USED` uses, and is what
     * stops the `.distinct()` in `buildPreparedBatchState` collapsing two different icon sources
     * failing identically on one tile into a single diagnostic that undercounts the loss.
     */
    private fun iconLayerSourceUnavailableDiagnostic(
        tile: TileId,
        sample: VectorTileSample,
        error: Throwable,
    ): RenderDiagnostic {
        val statusCode = (error as? ResourceAcquisitionException)?.statusCode
        return RenderDiagnostic(
            code = DiagnosticCode.ICON_LAYER_SKIPPED_SOURCE_UNAVAILABLE,
            severity = DiagnosticSeverity.WARNING,
            stage = PipelineStage.RESOURCE_ACQUISITION,
            message = "A vector source reachable only through repaired icon layers is unavailable, " +
                "so those layers are skipped for this tile",
            details = buildMap {
                put("resourceClass", ResourceClass.VECTOR_TILE.name)
                put("sourceIdDigest", sample.source.idDigest)
                put("resourceId", sample.identity.sha256Hex())
                (error as? RentileException)?.let { put("causeCode", it.code.name) }
                statusCode?.let { put("statusCode", it.toString()) }
            },
            affectedTiles = listOf(tile),
        ).also(configuration.diagnosticSink::recordSafely)
    }

    private suspend fun substituteRaster(
        requested: RasterSample,
        primaryFailure: ResourceAcquisitionException,
        accessMode: ResourceAccessMode,
    ): RasterResource = supervisorScope {
        val attempted = mutableListOf<TileSubstitutionStrategy>()
        val failures = mutableListOf<RentileException>()
        val children = requested.immediateRasterChildren()
        if (children.isNotEmpty()) {
            attempted += TileSubstitutionStrategy.IMMEDIATE_CHILDREN
            val outcomes = children.map { child ->
                async { acquireOutcome { rasterAcquirer.acquire(child, accessMode) } }
            }.awaitAll()
            if (outcomes.all { it is AcquisitionOutcome.Success }) {
                return@supervisorScope try {
                    composeRasterChildren(requested, outcomes.map { (it as AcquisitionOutcome.Success).value })
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    failures += ResourceDecodeException(
                        message = "Raster child substitutes could not be composed",
                        resourceClass = requested.source.resourceClass,
                        sanitizedResourceId = primaryFailure.sanitizedResourceId,
                        affectedTiles = listOf(requested.outputTile),
                    )
                    null
                } ?: acquireRasterAncestor(requested, primaryFailure, accessMode, attempted, failures)
            }
            failures += outcomes.filterIsInstance<AcquisitionOutcome.Failure>()
                .map { it.error.asSubstitutionFailure(primaryFailure, requested.outputTile) }
        }
        acquireRasterAncestor(requested, primaryFailure, accessMode, attempted, failures)
    }

    private suspend fun acquireRasterAncestor(
        requested: RasterSample,
        primaryFailure: ResourceAcquisitionException,
        accessMode: ResourceAccessMode,
        attempted: MutableList<TileSubstitutionStrategy>,
        failures: MutableList<RentileException>,
    ): RasterResource {
        for (distance in 1..MAX_ANCESTOR_DISTANCE) {
            val ancestor = requested.rasterAncestor(distance) ?: continue
            if (TileSubstitutionStrategy.ANCESTOR !in attempted) attempted += TileSubstitutionStrategy.ANCESTOR
            when (val outcome = acquireOutcome { rasterAcquirer.acquire(ancestor, accessMode) }) {
                is AcquisitionOutcome.Success -> return rasterAncestorSubstitute(requested, outcome.value, distance)
                is AcquisitionOutcome.Failure -> failures +=
                    outcome.error.asSubstitutionFailure(primaryFailure, requested.outputTile)
            }
        }
        throw TileSubstitutionException(
            tile = requested.outputTile,
            resourceClass = requested.source.resourceClass,
            sanitizedResourceId = primaryFailure.sanitizedResourceId,
            attemptedStrategies = attempted,
            primaryFailure = primaryFailure,
            substitutionFailures = failures,
        )
    }

    private suspend fun substituteVector(
        requested: VectorTileSample,
        primaryFailure: ResourceAcquisitionException,
        accessMode: ResourceAccessMode,
    ): VectorResource = supervisorScope {
        val attempted = mutableListOf<TileSubstitutionStrategy>()
        val failures = mutableListOf<RentileException>()
        val children = requested.immediateVectorChildren()
        if (children.isNotEmpty()) {
            attempted += TileSubstitutionStrategy.IMMEDIATE_CHILDREN
            val outcomes = children.map { child ->
                async { acquireOutcome { vectorAcquirer.acquire(child, accessMode) } }
            }.awaitAll()
            if (outcomes.all { it is AcquisitionOutcome.Success }) {
                return@supervisorScope try {
                    composeVectorChildren(requested, outcomes.map { (it as AcquisitionOutcome.Success).value })
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    failures += ResourceDecodeException(
                        message = "Vector child substitutes could not be composed",
                        resourceClass = ResourceClass.VECTOR_TILE,
                        sanitizedResourceId = primaryFailure.sanitizedResourceId,
                        affectedTiles = listOf(requested.outputTile),
                    )
                    null
                } ?: acquireVectorAncestor(requested, primaryFailure, accessMode, attempted, failures)
            }
            failures += outcomes.filterIsInstance<AcquisitionOutcome.Failure>()
                .map { it.error.asSubstitutionFailure(primaryFailure, requested.outputTile) }
        }
        acquireVectorAncestor(requested, primaryFailure, accessMode, attempted, failures)
    }

    private suspend fun acquireVectorAncestor(
        requested: VectorTileSample,
        primaryFailure: ResourceAcquisitionException,
        accessMode: ResourceAccessMode,
        attempted: MutableList<TileSubstitutionStrategy>,
        failures: MutableList<RentileException>,
    ): VectorResource {
        for (distance in 1..MAX_ANCESTOR_DISTANCE) {
            val ancestor = requested.vectorAncestor(distance) ?: continue
            if (TileSubstitutionStrategy.ANCESTOR !in attempted) attempted += TileSubstitutionStrategy.ANCESTOR
            when (val outcome = acquireOutcome { vectorAcquirer.acquire(ancestor, accessMode) }) {
                is AcquisitionOutcome.Success -> return vectorAncestorSubstitute(requested, outcome.value, distance)
                is AcquisitionOutcome.Failure -> failures +=
                    outcome.error.asSubstitutionFailure(primaryFailure, requested.outputTile)
            }
        }
        throw TileSubstitutionException(
            tile = requested.outputTile,
            resourceClass = ResourceClass.VECTOR_TILE,
            sanitizedResourceId = primaryFailure.sanitizedResourceId,
            attemptedStrategies = attempted,
            primaryFailure = primaryFailure,
            substitutionFailures = failures,
        )
    }

    private fun Throwable.asSubstitutionFailure(
        primary: ResourceAcquisitionException,
        tile: TileId,
    ): RentileException = this as? RentileException ?: ResourceAcquisitionException(
        message = "Substitute tile acquisition failed",
        resourceClass = primary.resourceClass,
        sanitizedResourceId = primary.sanitizedResourceId,
        affectedTiles = listOf(tile),
    )

    private fun RasterResource.forExactSample(sample: RasterSample): RasterResource =
        if (this.sample == sample && exactSample == sample && substitution == null) this else copy(
            sample = sample,
            exactSample = sample,
            substitution = null,
        )

    private fun VectorResource.forExactSample(sample: VectorTileSample): VectorResource =
        if (this.sample == sample && exactSample == sample && substitution == null) this else copy(
            sample = sample,
            exactSample = sample,
            substitution = null,
        )

    private suspend fun recoverRasterResources(
        resources: Map<TileId, List<RasterResource>>,
    ): ResourceRecovery<RasterResource> = supervisorScope {
        val recovered = resources.map { (tile, tileResources) ->
            tile to tileResources.map { resource ->
                async {
                    if (resource.substitution == null) return@async RecoveryItem(resource)
                    when (val outcome = acquireOutcome {
                        rasterAcquirer.acquire(resource.exactSample, ResourceAccessMode.NORMAL)
                    }) {
                        is AcquisitionOutcome.Success -> RecoveryItem(
                            resource = outcome.value.forExactSample(resource.exactSample),
                            upgraded = true,
                        )
                        is AcquisitionOutcome.Failure -> RecoveryItem(
                            resource = resource,
                            diagnostic = exactRecoveryFailureDiagnostic(
                                tile,
                                resource.exactSample.source.resourceClass,
                                outcome.error,
                            ),
                        )
                    }
                }
            }.awaitAll()
        }.toMap()
        ResourceRecovery(
            resources = recovered.mapValues { (_, items) -> items.map { it.resource } },
            upgradedTiles = recovered.filterValues { items -> items.any { it.upgraded } }.keys,
            diagnostics = recovered.values.flatten().mapNotNull { it.diagnostic },
        )
    }

    private suspend fun recoverVectorResources(
        resources: Map<TileId, List<VectorResource>>,
    ): ResourceRecovery<VectorResource> = supervisorScope {
        val recovered = resources.map { (tile, tileResources) ->
            tile to tileResources.map { resource ->
                async {
                    if (resource.substitution == null) return@async RecoveryItem(resource)
                    when (val outcome = acquireOutcome {
                        vectorAcquirer.acquire(resource.exactSample, ResourceAccessMode.NORMAL)
                    }) {
                        is AcquisitionOutcome.Success -> RecoveryItem(
                            resource = outcome.value.forExactSample(resource.exactSample),
                            upgraded = true,
                        )
                        is AcquisitionOutcome.Failure -> RecoveryItem(
                            resource = resource,
                            diagnostic = exactRecoveryFailureDiagnostic(tile, ResourceClass.VECTOR_TILE, outcome.error),
                        )
                    }
                }
            }.awaitAll()
        }.toMap()
        ResourceRecovery(
            resources = recovered.mapValues { (_, items) -> items.map { it.resource } },
            upgradedTiles = recovered.filterValues { items -> items.any { it.upgraded } }.keys,
            diagnostics = recovered.values.flatten().mapNotNull { it.diagnostic },
        )
    }

    private fun exactRecoveryFailureDiagnostic(
        tile: TileId,
        resourceClass: ResourceClass,
        error: Throwable,
    ): RenderDiagnostic {
        val statusCode = (error as? ResourceAcquisitionException)?.statusCode
        return RenderDiagnostic(
            code = DiagnosticCode.TILE_EXACT_RECOVERY_FAILED,
            severity = DiagnosticSeverity.WARNING,
            stage = PipelineStage.RESOURCE_ACQUISITION,
            message = "An exact tile resource remains unavailable",
            details = buildMap {
                put("resourceClass", resourceClass.name)
                statusCode?.let { put("statusCode", it.toString()) }
            },
            affectedTiles = listOf(tile),
        ).also(configuration.diagnosticSink::recordSafely)
    }

    private fun buildPreparedBatchState(
        style: CompiledPreparedStyle,
        tiles: List<TileId>,
        options: RenderOptions,
        resources: PreparedResources,
    ): PreparedBatchState {
        val resourceDiagnostics = (
            resources.raster.values.flatten().flatMap { it.diagnostics } +
                resources.vector.values.flatten().flatMap { it.diagnostics } +
                resources.acquisitionDiagnostics
            ).distinct()
        val substitutions = tiles.mapNotNull { tile ->
            val tileSubstitutions = (
                resources.raster[tile].orEmpty().mapNotNull { it.substitution } +
                    resources.vector[tile].orEmpty().mapNotNull { it.substitution }
                ).distinct()
            if (tileSubstitutions.isEmpty()) null else tile to tileSubstitutions
        }.toMap()
        val contentKeys = tiles.associateWith { tile ->
            buildString {
                append("rentile-output-3\n")
                append(style.digest)
                append('\n')
                append(tile.z)
                append('/')
                append(canonicalX(tile))
                append('/')
                append(tile.y)
                append('\n')
                append(options.outputSizePx)
                for (resource in resources.raster[tile].orEmpty().sortedBy { it.sample.identity }) {
                    append("\nraster:")
                    append(resource.sample.identity)
                    append(':')
                    append(resource.contentDigest)
                }
                for (resource in resources.vector[tile].orEmpty().sortedBy { it.sample.identity }) {
                    append("\nvector:")
                    append(resource.sample.identity)
                    append(':')
                    append(resource.contentDigest)
                }
            }.sha256Hex()
        }
        return PreparedBatchState(
            resources = resources,
            contentKeys = contentKeys,
            diagnostics = style.diagnostics + resourceDiagnostics,
            substitutions = substitutions,
        )
    }

    private suspend fun <T> acquireOutcome(block: suspend () -> T): AcquisitionOutcome<T> = try {
        AcquisitionOutcome.Success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        AcquisitionOutcome.Failure(error)
    }

    private fun throwAcquisitionFailures(outcomes: List<AcquisitionOutcome<*>>) {
        val failures = outcomes.filterIsInstance<AcquisitionOutcome.Failure>().map { it.error }
        if (failures.isEmpty()) return
        val unexpected = failures.firstOrNull { it !is RentileException }
        if (unexpected != null) throw unexpected
        val typed = failures.filterIsInstance<RentileException>()
        if (typed.size == 1) throw typed.single()
        throw BatchRenderException(
            message = "Multiple resource acquisitions failed",
            primaryFailure = typed.first(),
            concurrentFailures = typed.drop(1),
            diagnostics = typed.flatMap { it.diagnostics },
            affectedTiles = typed.flatMap { it.affectedTiles }.distinct(),
        )
    }

    private suspend fun <T> operation(block: suspend () -> T): T {
        if (closing.load()) throw RasterizerClosedException()
        currentCoroutineContext().ensureActive()
        val worker = scope.async { block() }
        val callerJob = currentCoroutineContext().job
        val cancellationLink = callerJob.invokeOnCompletion { cause ->
            if (cause is CancellationException) worker.cancel(cause)
        }
        return try {
            worker.await()
        } catch (error: CancellationException) {
            worker.cancel(error)
            throw error
        } finally {
            cancellationLink.dispose()
        }
    }

    private fun renderTile(
        batch: DefaultPreparedBatch,
        resources: PreparedResources,
        tile: TileId,
    ): TileRender {
        val activeLayers = batch.style.drawLayers.filter { it.isActiveAt(tile.z) }
        val rasterResources = resources.raster[tile].orEmpty()
        val vectorResources = resources.vector[tile].orEmpty()
        val passThroughLayer = activeLayers.singleOrNull() as? RasterDrawLayer
        if (passThroughLayer != null && evaluateRasterPaint(passThroughLayer, tile).isIdentity) {
            val resource = rasterResources.singleOrNull { it.sample.source.idDigest == passThroughLayer.source.idDigest }
            if (
                resource != null &&
                resource.sample.childScale == 1 &&
                resource.width == batch.options.outputSizePx &&
                resource.height == batch.options.outputSizePx &&
                resource.bytes.isPng()
            ) {
                val diagnostic = RenderDiagnostic(
                    code = DiagnosticCode.RASTER_PASSTHROUGH_USED,
                    severity = DiagnosticSeverity.INFO,
                    stage = PipelineStage.PNG_ENCODING,
                    message = "A validated PNG raster tile was returned without redrawing",
                    details = mapOf("resourceId" to resource.sample.identity.sha256Hex()),
                    affectedTiles = listOf(tile),
                )
                configuration.diagnosticSink.recordSafely(diagnostic)
                return TileRender(tile, resource.bytes.copyOf(), listOf(diagnostic))
            }
        }

        // Render-stage diagnostics reach the caller two ways, and both matter. The sink is
        // optional - DiagnosticSink.None is the default - so anything only routed there is
        // invisible to a caller that never configured one. TileRender.diagnostics is the
        // always-available per-tile list that becomes RenderedTile.diagnostics, the way
        // RASTER_PASSTHROUGH_USED above reaches both.
        val renderDiagnostics = mutableListOf<RenderDiagnostic>()
        val png = renderCompositedTile(
            style = batch.style,
            layers = activeLayers,
            rasterResources = rasterResources,
            vectorResources = vectorResources,
            sizePx = batch.options.outputSizePx,
            tile = tile,
            diagnostics = renderDiagnostics,
        )
        return TileRender(tile, png, renderDiagnostics.toList())
    }

    private fun renderCompositedTile(
        style: CompiledPreparedStyle,
        layers: List<CompiledDrawLayer>,
        rasterResources: List<RasterResource>,
        vectorResources: List<VectorResource>,
        sizePx: Int,
        tile: TileId,
        diagnostics: MutableList<RenderDiagnostic>,
    ): ByteArray {
        val surface = Surface.makeRasterN32Premul(sizePx, sizePx)
        val spriteContext = style.spriteAtlas?.let(::SpriteRenderContext)
        try {
            surface.canvas.clear(Color.TRANSPARENT)
            val placedIcons = if (spriteContext == null) {
                emptyMap()
            } else {
                placeIcons(
                    layers = layers.filterIsInstance<IconDrawLayer>(),
                    resources = vectorResources,
                    sprites = spriteContext,
                    atlas = style.spriteAtlas,
                    sizePx = sizePx,
                    tile = tile,
                    diagnostics = diagnostics,
                )
            }
            for (layer in layers) {
                when (layer) {
                    is BackgroundDrawLayer -> drawBackground(surface, layer, tile, spriteContext, sizePx)
                    is RasterDrawLayer -> {
                        val resource = rasterResources.singleOrNull { it.sample.source.idDigest == layer.source.idDigest }
                            ?: continue
                        drawRaster(surface, layer, resource, sizePx, tile)
                    }
                    is HillshadeDrawLayer -> {
                        val centerSample = layer.source.sampleFor(tile) ?: continue
                        val resources = rasterResources.filter { it.sample.source.idDigest == layer.source.idDigest }
                        val center = resources.singleOrNull {
                            it.sample.sourceZ == centerSample.sourceZ &&
                                it.sample.sourceX == centerSample.sourceX &&
                                it.sample.sourceY == centerSample.sourceY
                        } ?: continue
                        drawHillshade(surface, layer, center, resources, sizePx, tile)
                    }
                    is FillDrawLayer -> {
                        val resource = vectorResources.singleOrNull { it.sample.source.idDigest == layer.source.idDigest }
                            ?: continue
                        drawFill(surface, layer, resource, sizePx, tile, spriteContext)
                    }
                    is LineDrawLayer -> {
                        val resource = vectorResources.singleOrNull { it.sample.source.idDigest == layer.source.idDigest }
                            ?: continue
                        drawLine(surface, layer, resource, sizePx, tile, spriteContext)
                    }
                    is IconDrawLayer -> drawIcons(surface, placedIcons[layer.layerOrder].orEmpty())
                }
            }
            val image = surface.makeImageSnapshot()
            try {
                val data = image.encodeToData(EncodedImageFormat.PNG)
                    ?: throw PngEncodingException("Skia could not encode PNG", affectedTiles = listOf(tile))
                try {
                    return data.bytes
                } finally {
                    data.close()
                }
            } finally {
                image.close()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: PngEncodingException) {
            throw error
        } catch (error: RasterizationException) {
            throw error
        } catch (error: Throwable) {
            throw PngEncodingException("PNG rendering or encoding failed", affectedTiles = listOf(tile), cause = error)
        } finally {
            spriteContext?.close()
            surface.close()
        }
    }

    private fun drawBackground(
        surface: Surface,
        layer: BackgroundDrawLayer,
        tile: TileId,
        sprites: SpriteRenderContext?,
        sizePx: Int,
    ) {
        val background = layer.background
        val context = StyleEvaluationContext(zoom = tile.z.toDouble())
        val color = when (val value = background.color.evaluate(context)) {
            is StyleValue.ColorValue -> value.value
            is StyleValue.StringValue -> parseCssColor(value.value)
            else -> null
        } ?: throw RasterizationException(
            message = "background-color did not evaluate to a supported color",
            affectedTiles = listOf(tile),
        )
        val opacity = (background.opacity.evaluate(context) as? StyleValue.NumberValue)?.value
            ?.takeIf { it.isFinite() && it in 0.0..1.0 }
            ?: throw RasterizationException(
                message = "background-opacity did not evaluate to a value between zero and one",
                affectedTiles = listOf(tile),
            )
        val patternName = background.pattern?.evaluate(context)?.let { evaluatePatternName(it, "background-pattern", tile) }
        if (patternName != null) {
            val sprite = sprites?.image(patternName) ?: return
            drawRepeatedPattern(
                surface = surface,
                clipPath = null,
                sprite = sprite,
                opacity = opacity,
                tile = tile,
                sizePx = sizePx,
            )
            return
        }
        val alpha = (color.alpha * opacity).roundToInt().coerceIn(0, 255)
        val paint = Paint().apply {
            this.color = Color.makeARGB(alpha, color.red, color.green, color.blue)
        }
        try {
            surface.canvas.drawRect(Rect.makeWH(surface.width.toFloat(), surface.height.toFloat()), paint)
        } finally {
            paint.close()
        }
    }

    private fun drawRaster(
        surface: Surface,
        layer: RasterDrawLayer,
        resource: RasterResource,
        sizePx: Int,
        tile: TileId,
    ) {
        val rasterPaint = evaluateRasterPaint(layer, tile)
        val image = try {
            Image.makeFromEncoded(resource.bytes)
        } catch (error: Throwable) {
            throw ResourceDecodeException(
                message = "Prepared raster tile cannot be decoded",
                resourceClass = ResourceClass.RASTER_TILE,
                sanitizedResourceId = resource.sample.identity.sha256Hex(),
                affectedTiles = listOf(tile),
                cause = error,
            )
        }
        try {
            val sample = resource.sample
            val sourceLeft = image.width.toFloat() * sample.childX / sample.childScale
            val sourceTop = image.height.toFloat() * sample.childY / sample.childScale
            val sourceRight = image.width.toFloat() * (sample.childX + 1) / sample.childScale
            val sourceBottom = image.height.toFloat() * (sample.childY + 1) / sample.childScale
            val colorFilter = rasterPaint.colorFilter()
            val paint = Paint().apply {
                alpha = (rasterPaint.opacity * 255.0).roundToInt().coerceIn(0, 255)
                this.colorFilter = colorFilter
            }
            try {
                val sampling = when (layer.resampling) {
                    RasterResampling.LINEAR -> SamplingMode.LINEAR
                    RasterResampling.NEAREST -> SamplingMode.DEFAULT
                }
                surface.canvas.drawImageRect(
                    image,
                    Rect.makeLTRB(sourceLeft, sourceTop, sourceRight, sourceBottom),
                    Rect.makeWH(sizePx.toFloat(), sizePx.toFloat()),
                    sampling,
                    paint,
                    true,
                )
            } finally {
                paint.close()
                colorFilter?.close()
            }
        } finally {
            image.close()
        }
    }

    private fun drawHillshade(
        surface: Surface,
        layer: HillshadeDrawLayer,
        center: RasterResource,
        resources: List<RasterResource>,
        sizePx: Int,
        tile: TileId,
    ) {
        val context = StyleEvaluationContext(zoom = tile.z.toDouble())
        val exaggeration = evaluatedNumber(layer.exaggeration.evaluate(context), "hillshade-exaggeration", tile)
        if (exaggeration < 0.0) {
            throw RasterizationException(
                message = "hillshade-exaggeration evaluated to a negative value",
                affectedTiles = listOf(tile),
            )
        }
        val accent = evaluatedColor(layer.accentColor.evaluate(context), "hillshade-accent-color", tile)
        val highlight = evaluatedColor(layer.highlightColor.evaluate(context), "hillshade-highlight-color", tile)
        val shadow = evaluatedColor(layer.shadowColor.evaluate(context), "hillshade-shadow-color", tile)
        val encoding = layer.source.demEncoding ?: DemEncoding.MAPBOX

        val decoded = mutableMapOf<Pair<Int, Int>, Bitmap>()
        try {
            for (resource in resources) {
                val image = try {
                    Image.makeFromEncoded(resource.bytes)
                } catch (error: Throwable) {
                    throw ResourceDecodeException(
                        message = "Prepared DEM tile cannot be decoded",
                        resourceClass = ResourceClass.DEM_TILE,
                        sanitizedResourceId = resource.sample.identity.sha256Hex(),
                        affectedTiles = listOf(tile),
                        cause = error,
                    )
                }
                try {
                    val bitmap = Bitmap()
                    if (!bitmap.allocN32Pixels(image.width, image.height, false) || !image.readPixels(bitmap)) {
                        bitmap.close()
                        throw ResourceDecodeException(
                            message = "Prepared DEM pixels cannot be read",
                            resourceClass = ResourceClass.DEM_TILE,
                            sanitizedResourceId = resource.sample.identity.sha256Hex(),
                            affectedTiles = listOf(tile),
                        )
                    }
                    decoded[resource.sample.sourceX to resource.sample.sourceY]?.close()
                    decoded[resource.sample.sourceX to resource.sample.sourceY] = bitmap
                } finally {
                    image.close()
                }
            }

            val centerBitmap = decoded[center.sample.sourceX to center.sample.sourceY] ?: return
            val sourceWidth = centerBitmap.width
            val sourceHeight = centerBitmap.height
            val sourceDimension = 1L shl center.sample.sourceZ

            fun heightAt(sourcePixelX: Int, sourcePixelY: Int): Double {
                val deltaX = floor(sourcePixelX.toDouble() / sourceWidth).toInt()
                val deltaY = floor(sourcePixelY.toDouble() / sourceHeight).toInt()
                val sourceX = floorMod(center.sample.sourceX.toLong() + deltaX, sourceDimension).toInt()
                val sourceY = center.sample.sourceY + deltaY
                val bitmap = decoded[sourceX to sourceY] ?: centerBitmap
                val localX = floorMod(sourcePixelX.toLong(), sourceWidth.toLong()).toInt()
                val localY = sourcePixelY.coerceIn(0, sourceHeight - 1)
                    .let { value -> if (bitmap === centerBitmap && sourceY != center.sample.sourceY) value else floorMod(sourcePixelY.toLong(), sourceHeight.toLong()).toInt() }
                val color = bitmap.getColor(localX.coerceIn(0, bitmap.width - 1), localY.coerceIn(0, bitmap.height - 1))
                val red = color ushr 16 and 0xff
                val green = color ushr 8 and 0xff
                val blue = color and 0xff
                return when (encoding) {
                    DemEncoding.MAPBOX -> -10_000.0 + (red * 65_536 + green * 256 + blue) * 0.1
                    DemEncoding.TERRARIUM -> red * 256.0 + green + blue / 256.0 - 32_768.0
                }
            }

            val rgba = ByteArray(sizePx * sizePx * 4)
            val lightAltitude = PI / 4.0
            val lightAzimuth = 335.0 * PI / 180.0
            val flatIllumination = sin(lightAltitude)
            for (outputY in 0 until sizePx) {
                val sourceY = (
                    (center.sample.childY + (outputY + 0.5) / sizePx) * sourceHeight / center.sample.childScale - 0.5
                    ).roundToInt()
                val globalSourceY = center.sample.sourceY.toDouble() * sourceHeight + sourceY
                val worldY = (globalSourceY + 0.5) / (sourceDimension * sourceHeight)
                val latitude = atan(sinh(PI * (1.0 - 2.0 * worldY)))
                val metersPerPixel = max(
                    0.01,
                    EARTH_CIRCUMFERENCE_METERS * cos(latitude) / (sourceDimension * sourceWidth),
                )
                for (outputX in 0 until sizePx) {
                    val sourceX = (
                        (center.sample.childX + (outputX + 0.5) / sizePx) * sourceWidth / center.sample.childScale - 0.5
                        ).roundToInt()
                    val left = heightAt(sourceX - 1, sourceY)
                    val right = heightAt(sourceX + 1, sourceY)
                    val up = heightAt(sourceX, sourceY - 1)
                    val down = heightAt(sourceX, sourceY + 1)
                    val gradientX = (right - left) / (2.0 * metersPerPixel)
                    val gradientY = (down - up) / (2.0 * metersPerPixel)
                    val slope = atan(exaggeration * hypot(gradientX, gradientY))
                    val aspect = atan2(gradientY, -gradientX)
                    val illumination = sin(lightAltitude) * cos(slope) +
                        cos(lightAltitude) * sin(slope) * cos(lightAzimuth - aspect)
                    val delta = illumination - flatIllumination
                    val accentAlpha = (sin(slope) * 0.28).coerceIn(0.0, 1.0)
                    val shadeColor: CompiledColor
                    val shadeAlpha: Double
                    if (delta >= 0.0) {
                        shadeColor = highlight
                        shadeAlpha = (delta / (1.0 - flatIllumination)).coerceIn(0.0, 1.0)
                    } else {
                        shadeColor = shadow
                        shadeAlpha = (-delta / (1.0 + flatIllumination)).coerceIn(0.0, 1.0)
                    }
                    val color = compositeHillshade(accent, accentAlpha, shadeColor, shadeAlpha)
                    val offset = (outputY * sizePx + outputX) * 4
                    rgba[offset] = color.red.toByte()
                    rgba[offset + 1] = color.green.toByte()
                    rgba[offset + 2] = color.blue.toByte()
                    rgba[offset + 3] = color.alpha.toByte()
                }
            }
            val imageInfo = ImageInfo(sizePx, sizePx, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
            val hillshade = Image.makeRaster(imageInfo, rgba, sizePx * 4)
            try {
                surface.canvas.drawImageRect(
                    hillshade,
                    Rect.makeWH(sizePx.toFloat(), sizePx.toFloat()),
                    Rect.makeWH(sizePx.toFloat(), sizePx.toFloat()),
                    SamplingMode.DEFAULT,
                    null,
                    true,
                )
            } finally {
                hillshade.close()
            }
        } finally {
            decoded.values.forEach(Bitmap::close)
        }
    }

    private fun compositeHillshade(
        base: CompiledColor,
        baseStrength: Double,
        overlay: CompiledColor,
        overlayStrength: Double,
    ): CompiledColor {
        val baseAlpha = baseStrength * base.alpha / 255.0
        val overlayAlpha = overlayStrength * overlay.alpha / 255.0
        val outputAlpha = overlayAlpha + baseAlpha * (1.0 - overlayAlpha)
        if (outputAlpha <= 0.0) return CompiledColor(0, 0, 0, 0)
        fun channel(baseChannel: Int, overlayChannel: Int): Int = (
            (overlayChannel * overlayAlpha + baseChannel * baseAlpha * (1.0 - overlayAlpha)) / outputAlpha
            ).roundToInt().coerceIn(0, 255)
        return CompiledColor(
            red = channel(base.red, overlay.red),
            green = channel(base.green, overlay.green),
            blue = channel(base.blue, overlay.blue),
            alpha = (outputAlpha * 255.0).roundToInt().coerceIn(0, 255),
        )
    }

    private fun floorMod(value: Long, divisor: Long): Long {
        val remainder = value % divisor
        return if (remainder < 0) remainder + divisor else remainder
    }

    private fun evaluateRasterPaint(layer: RasterDrawLayer, tile: TileId): EvaluatedRasterPaint {
        val context = StyleEvaluationContext(zoom = tile.z.toDouble())
        val opacity = evaluatedNumber(layer.opacity.evaluate(context), "raster-opacity", tile)
        val brightnessMinimum = evaluatedNumber(
            layer.brightnessMinimum.evaluate(context),
            "raster-brightness-min",
            tile,
        )
        val brightnessMaximum = evaluatedNumber(
            layer.brightnessMaximum.evaluate(context),
            "raster-brightness-max",
            tile,
        )
        val contrast = evaluatedNumber(layer.contrast.evaluate(context), "raster-contrast", tile)
        val hueRotate = evaluatedNumber(layer.hueRotate.evaluate(context), "raster-hue-rotate", tile)
        val saturation = evaluatedNumber(layer.saturation.evaluate(context), "raster-saturation", tile)
        if (opacity !in 0.0..1.0) rasterPaintRangeFailure("raster-opacity", tile)
        if (brightnessMinimum !in 0.0..1.0) rasterPaintRangeFailure("raster-brightness-min", tile)
        if (brightnessMaximum !in 0.0..1.0) rasterPaintRangeFailure("raster-brightness-max", tile)
        if (brightnessMinimum > brightnessMaximum) {
            throw RasterizationException(
                message = "raster brightness minimum exceeds its maximum",
                affectedTiles = listOf(tile),
            )
        }
        if (contrast !in -1.0..1.0) rasterPaintRangeFailure("raster-contrast", tile)
        if (saturation !in -1.0..1.0) rasterPaintRangeFailure("raster-saturation", tile)
        return EvaluatedRasterPaint(
            opacity = opacity,
            brightnessMinimum = brightnessMinimum,
            brightnessMaximum = brightnessMaximum,
            contrast = contrast,
            hueRotate = hueRotate,
            saturation = saturation,
        )
    }

    private fun rasterPaintRangeFailure(property: String, tile: TileId): Nothing = throw RasterizationException(
        message = "$property evaluated outside its supported range",
        affectedTiles = listOf(tile),
    )

    private fun drawFill(
        surface: Surface,
        layer: FillDrawLayer,
        resource: VectorResource,
        sizePx: Int,
        tile: TileId,
        sprites: SpriteRenderContext?,
    ) {
        val sourceLayer = resource.tile.layers.singleOrNull { it.name == layer.sourceLayer } ?: return
        val fillPaint = Paint().apply {
            mode = PaintMode.FILL
            isAntiAlias = layer.antialias
        }
        val outlinePaint = layer.outlineColor?.let {
            Paint().apply {
                mode = PaintMode.STROKE
                strokeWidth = 1f
                isAntiAlias = layer.antialias
            }
        }
        try {
            for (feature in sourceLayer.features) {
                val geometry = feature.geometry as? DecodedVectorGeometry.Polygons ?: continue
                val context = featureContext(tile, feature)
                if (!layer.filter.matches(context)) continue
                val opacity = evaluatedOpacity(layer.opacity.evaluate(context), "fill-opacity", tile)
                val translation = evaluatedNumberArray(layer.translate.evaluate(context), "fill-translate", tile, 2)
                val patternName = layer.pattern?.evaluate(context)?.let { evaluatePatternName(it, "fill-pattern", tile) }
                if (patternName == null) {
                    fillPaint.color = paintColor(evaluatedColor(layer.color.evaluate(context), "fill-color", tile), opacity)
                }
                val pathBuilder = PathBuilder(PathFillMode.EVEN_ODD)
                try {
                    for (ring in geometry.rings) {
                        val first = ring.points.firstOrNull() ?: continue
                        val firstPixel = resource.sample.sourceCoordinateToOutputPixels(first, sourceLayer.extent, sizePx)
                        pathBuilder.moveTo(
                            (firstPixel.x + translation[0]).toFloat(),
                            (firstPixel.y + translation[1]).toFloat(),
                        )
                        for (point in ring.points.drop(1)) {
                            val pixel = resource.sample.sourceCoordinateToOutputPixels(point, sourceLayer.extent, sizePx)
                            pathBuilder.lineTo(
                                (pixel.x + translation[0]).toFloat(),
                                (pixel.y + translation[1]).toFloat(),
                            )
                        }
                        pathBuilder.closePath()
                    }
                    val path = pathBuilder.detach()
                    try {
                        if (patternName == null) {
                            surface.canvas.drawPath(path, fillPaint)
                        } else {
                            val sprite = sprites?.image(patternName) ?: continue
                            drawRepeatedPattern(surface, path, sprite, opacity, tile, sizePx)
                        }
                        if (outlinePaint != null) {
                            outlinePaint.color = paintColor(evaluatedColor(
                                layer.outlineColor.evaluate(context),
                                "fill-outline-color",
                                tile,
                            ), opacity)
                            surface.canvas.drawPath(path, outlinePaint)
                        }
                    } finally {
                        path.close()
                    }
                } finally {
                    pathBuilder.close()
                }
            }
        } finally {
            outlinePaint?.close()
            fillPaint.close()
        }
    }

    private fun drawLine(
        surface: Surface,
        layer: LineDrawLayer,
        resource: VectorResource,
        sizePx: Int,
        tile: TileId,
        sprites: SpriteRenderContext?,
    ) {
        val sourceLayer = resource.tile.layers.singleOrNull { it.name == layer.sourceLayer } ?: return
        val features = sourceLayer.features.withIndex()
            .filter { (_, feature) ->
                feature.geometry is DecodedVectorGeometry.Lines && layer.filter.matches(featureContext(tile, feature))
            }
            .sortedWith(compareBy<IndexedValue<DecodedVectorFeature>>(
                { indexed ->
                    layer.sortKey?.evaluate(featureContext(tile, indexed.value))
                        ?.let { it as? StyleValue.NumberValue }
                        ?.value ?: 0.0
                },
                IndexedValue<DecodedVectorFeature>::index,
            ))
        val paint = Paint().apply {
            mode = PaintMode.STROKE
            strokeCap = when (layer.cap) {
                CompiledLineCap.BUTT -> PaintStrokeCap.BUTT
                CompiledLineCap.ROUND -> PaintStrokeCap.ROUND
                CompiledLineCap.SQUARE -> PaintStrokeCap.SQUARE
            }
            strokeJoin = when (layer.join) {
                CompiledLineJoin.BEVEL -> PaintStrokeJoin.BEVEL
                CompiledLineJoin.MITER -> PaintStrokeJoin.MITER
                CompiledLineJoin.ROUND -> PaintStrokeJoin.ROUND
            }
            isAntiAlias = true
        }
        try {
            for ((_, feature) in features) {
                val context = featureContext(tile, feature)
                val width = evaluatedNumber(layer.width.evaluate(context), "line-width", tile)
                if (width < 0.0) {
                    throw RasterizationException(
                        message = "line-width evaluated to a negative value",
                        affectedTiles = listOf(tile),
                    )
                }
                if (width == 0.0) continue
                val gapWidth = evaluatedNumber(layer.gapWidth.evaluate(context), "line-gap-width", tile)
                val offset = evaluatedNumber(layer.offset.evaluate(context), "line-offset", tile)
                val blur = evaluatedNumber(layer.blur.evaluate(context), "line-blur", tile)
                val miterLimit = evaluatedNumber(layer.miterLimit.evaluate(context), "line-miter-limit", tile)
                if (gapWidth < 0.0 || blur < 0.0 || miterLimit < 0.0) {
                    throw RasterizationException(
                        message = "line width, gap, blur, and miter values must not be negative",
                        affectedTiles = listOf(tile),
                    )
                }
                paint.strokeMiter = miterLimit.toFloat()
                val opacity = evaluatedOpacity(layer.opacity.evaluate(context), "line-opacity", tile)
                val patternName = layer.pattern?.evaluate(context)?.let { evaluatePatternName(it, "line-pattern", tile) }
                val patternShader = patternName?.let { name ->
                    val sprite = sprites?.image(name) ?: continue
                    sprite.image.makeShader(
                        FilterTileMode.REPEAT,
                        FilterTileMode.REPEAT,
                        SamplingMode.DEFAULT,
                        Matrix33.makeTranslate(
                            (canonicalX(tile) * sizePx).toFloat(),
                            (tile.y.toLong() * sizePx).toFloat(),
                        ),
                    )
                }
                if (patternShader == null) {
                    paint.color = paintColor(evaluatedColor(layer.color.evaluate(context), "line-color", tile), opacity)
                } else {
                    paint.shader = patternShader
                    paint.alpha = (opacity * 255.0).roundToInt().coerceIn(0, 255)
                }
                val translation = evaluatedNumberArray(layer.translate.evaluate(context), "line-translate", tile, 2)
                val dashValues = layer.dashArray?.evaluate(context)?.let { value ->
                    evaluatedNumberArray(value, "line-dasharray", tile).also { values ->
                        if (values.size < 2 || values.any { it < 0.0 } || values.sum() <= 0.0) {
                            throw RasterizationException(
                                message = "line-dasharray must contain at least two non-negative values with a positive sum",
                                affectedTiles = listOf(tile),
                            )
                        }
                    }
                }
                val maskFilter = blur.takeIf { it > 0.0 }?.let {
                    MaskFilter.makeBlur(FilterBlurMode.NORMAL, it.toFloat(), false)
                }
                paint.maskFilter = maskFilter
                val geometry = feature.geometry as DecodedVectorGeometry.Lines
                try {
                    for (line in geometry.lines) {
                        val pixels = line.map { point ->
                            val pixel = resource.sample.sourceCoordinateToOutputPixels(point, sourceLayer.extent, sizePx)
                            RenderPoint(pixel.x + translation[0], pixel.y + translation[1])
                        }
                        if (pixels.size < 2) continue
                        val strokes = if (gapWidth > 0.0) {
                            val center = gapWidth / 2.0 + width / 2.0
                            listOf(offset - center, offset + center)
                        } else {
                            listOf(offset)
                        }
                        paint.strokeWidth = width.toFloat()
                        for (strokeOffset in strokes) {
                            val strokePoints = offsetPolyline(pixels, strokeOffset)
                            val path = buildLinePath(strokePoints)
                            val dashStroke = dashValues?.let { values ->
                                val intervals = (if (values.size % 2 == 0) values else values + values)
                                    .map { value -> (value * width).toFloat() }
                                    .toFloatArray()
                                val period = intervals.sum()
                                val first = strokePoints.first()
                                val second = strokePoints[1]
                                val dx = second.x - first.x
                                val dy = second.y - first.y
                                val length = hypot(dx, dy)
                                val worldX = canonicalX(tile) * sizePx + first.x
                                val worldY = tile.y.toDouble() * sizePx + first.y
                                val phase = if (length > 0.0 && period > 0f) {
                                    ((worldX * dx / length + worldY * dy / length) % period + period).toFloat() % period
                                } else {
                                    0f
                                }
                                DashStroke(intervals, phase)
                            }
                            val pathEffect = dashStroke?.makePathEffect()
                            paint.pathEffect = pathEffect
                            try {
                                drawLinePath(
                                    surface = surface,
                                    path = path,
                                    paint = paint,
                                    points = strokePoints,
                                    roundLimit = layer.roundLimit.toDouble(),
                                    blur = blur,
                                    dashStroke = dashStroke,
                                )
                            } finally {
                                paint.pathEffect = null
                                pathEffect?.close()
                                path.close()
                            }
                        }
                    }
                } finally {
                    paint.maskFilter = null
                    maskFilter?.close()
                    paint.shader = null
                    patternShader?.close()
                }
            }
        } finally {
            paint.close()
        }
    }

    private fun placeIcons(
        layers: List<IconDrawLayer>,
        resources: List<VectorResource>,
        sprites: SpriteRenderContext,
        atlas: CompiledSpriteAtlas,
        sizePx: Int,
        tile: TileId,
        diagnostics: MutableList<RenderDiagnostic>,
    ): Map<Int, List<PlacedIcon>> {
        val accepted = mutableMapOf<Int, MutableList<PlacedIcon>>()
        val collisionBoxes = mutableListOf<IconCollision>()
        for (layer in layers.sortedByDescending(IconDrawLayer::layerOrder)) {
            val resource = resources.singleOrNull { it.sample.source.idDigest == layer.source.idDigest } ?: continue
            val sourceLayer = resource.tile.layers.singleOrNull { it.name == layer.sourceLayer } ?: continue
            val candidates = mutableListOf<IconCandidate>()
            // Counted, not flagged: a layer that skipped every candidate feature it had is an
            // authoring error in the style, while a layer that skipped some of them is bad data on
            // those features. Only the count can tell the two apart. See the escalation below.
            var candidateFeatures = 0
            var skippedFeatures = 0
            var skippedMissingSprite = 0
            for ((featureIndex, feature) in sourceLayer.features.withIndex()) {
                val baseContext = featureContext(tile, feature).copy(imageAvailable = atlas.entries::containsKey)
                if (!layer.filter.matches(baseContext)) continue
                val imageName = evaluateIconImageName(layer.image.evaluate(baseContext), feature) ?: continue
                // A feature that named an icon is a candidate from here on. Counting it only after
                // the atlas lookup made a repaired layer whose every feature names a sprite the
                // atlas lacks report zero candidates, zero skips, and no diagnostic at all - a
                // whole-layer loss reported as nothing, in exactly the area this counting closes.
                candidateFeatures++
                val sprite = sprites.image(imageName)
                if (sprite == null) {
                    skippedFeatures++
                    skippedMissingSprite++
                    continue
                }
                // A repaired layer (retained only because its text was removed and its icon does
                // not depend on it) was never validated as a retained construct before this
                // compatibility profile grew that feature: one feature's data-driven property
                // failing to evaluate must not turn "this icon is missing" into "the whole tile
                // fails to render". An author-intended icon-only layer keeps the original
                // fail-loudly behaviour, exactly as it always has.
                try {
                    val size = evaluatedNumber(layer.size.evaluate(baseContext), "icon-size", tile)
                    val opacity = evaluatedOpacity(layer.opacity.evaluate(baseContext), "icon-opacity", tile)
                    val haloWidth = evaluatedNumber(layer.haloWidth.evaluate(baseContext), "icon-halo-width", tile)
                    val haloBlur = evaluatedNumber(layer.haloBlur.evaluate(baseContext), "icon-halo-blur", tile)
                    val rotate = evaluatedNumber(layer.rotate.evaluate(baseContext), "icon-rotate", tile)
                    val spacing = evaluatedNumber(layer.spacing.evaluate(baseContext), "symbol-spacing", tile)
                    val padding = evaluatedNumber(layer.padding.evaluate(baseContext), "icon-padding", tile)
                    val placement = when ((layer.placement.evaluate(baseContext) as? StyleValue.StringValue)?.value) {
                        "point" -> SymbolPlacement.POINT
                        "line" -> SymbolPlacement.LINE
                        "line-center" -> SymbolPlacement.LINE_CENTER
                        else -> throw RasterizationException(
                            message = "symbol-placement did not evaluate to a supported value",
                            affectedTiles = listOf(tile),
                        )
                    }
                    if (size <= 0.0) continue
                    if (haloWidth < 0.0 || haloBlur < 0.0 || spacing <= 0.0 || padding < 0.0) {
                        throw RasterizationException(
                            message = "Retained icon halo or spacing values are outside their valid range",
                            affectedTiles = listOf(tile),
                        )
                    }
                    val offset = evaluatedNumberArray(layer.offset.evaluate(baseContext), "icon-offset", tile, 2)
                    val translate = evaluatedNumberArray(layer.translate.evaluate(baseContext), "icon-translate", tile, 2)
                    val anchor = iconAnchorOrNull(
                        evaluatedString(layer.anchor.evaluate(baseContext), "icon-anchor", tile),
                    ) ?: throw RasterizationException(
                        message = "icon-anchor did not evaluate to a supported value",
                        affectedTiles = listOf(tile),
                    )
                    // Output Tiles are north-up and unpitched, so map and viewport translation and
                    // pitch frames are geometrically identical here. They are still evaluated and
                    // validated: an expression must never be silently replaced by the default.
                    evaluatedAlignment(
                        layer.translateAnchor.evaluate(baseContext), "icon-translate-anchor", tile, autoAllowed = false,
                    )
                    val rotationAlignment = evaluatedAlignment(
                        layer.rotationAlignment.evaluate(baseContext), "icon-rotation-alignment", tile,
                    )
                    evaluatedAlignment(layer.pitchAlignment.evaluate(baseContext), "icon-pitch-alignment", tile)
                    val keepUpright = evaluatedBoolean(
                        layer.keepUpright.evaluate(baseContext), "icon-keep-upright", tile,
                    )
                    val ignorePlacement = evaluatedBoolean(
                        layer.ignorePlacement.evaluate(baseContext), "icon-ignore-placement", tile,
                    )
                    val textOverlap = layer.textOverlap?.let { property ->
                        evaluatedIconOverlap(property.evaluate(baseContext), tile)
                    }
                    val textIgnorePlacement = layer.textIgnorePlacement?.let { property ->
                        evaluatedBoolean(property.evaluate(baseContext), "text-ignore-placement", tile)
                    }
                    val avoidEdges = evaluatedBoolean(
                        layer.avoidEdges.evaluate(baseContext), "symbol-avoid-edges", tile,
                    )
                    val overlap = evaluatedIconOverlap(layer.overlap.evaluate(baseContext), tile)
                    val zOrder = evaluatedSymbolZOrder(layer.zOrder.evaluate(baseContext), tile)
                    val anchoring = spriteAnchoring(
                        entry = sprite.entry,
                        anchor = anchor,
                        size = size,
                        offsetX = offset[0],
                        offsetY = offset[1],
                        translateX = translate[0],
                        translateY = translate[1],
                    )
                    val logicalWidth = anchoring.width
                    val logicalHeight = anchoring.height
                    val anchors = iconAnchors(
                        geometry = feature.geometry,
                        placement = placement,
                        resource = resource,
                        extent = sourceLayer.extent,
                        sizePx = sizePx,
                        spacing = spacing,
                    )
                    val sortKey = when (val value = layer.sortKey?.evaluate(baseContext)) {
                        null, StyleValue.Null -> 0.0
                        else -> evaluatedNumber(value, "symbol-sort-key", tile)
                    }
                    anchors.forEachIndexed { anchorIndex, anchor ->
                        val effectiveRotationAlignment = when (rotationAlignment) {
                            IconAlignment.AUTO -> if (placement == SymbolPlacement.POINT) {
                                IconAlignment.VIEWPORT
                            } else {
                                IconAlignment.MAP
                            }
                            else -> rotationAlignment
                        }
                        val rotationDegrees = uprightRotation(
                            rotate + if (effectiveRotationAlignment == IconAlignment.VIEWPORT) {
                                0.0
                            } else {
                                anchor.rotationDegrees
                            },
                            keepUpright && placement != SymbolPlacement.POINT &&
                                effectiveRotationAlignment == IconAlignment.MAP,
                        )
                        val rotationRadians = rotationDegrees * PI / 180.0
                        val rotationCosine = cos(rotationRadians)
                        val rotationSine = sin(rotationRadians)
                        // icon-anchor and icon-offset are icon-local: they rotate with the image.
                        // icon-translate remains a separate displacement in the selected map or
                        // viewport frame (the two frames coincide for a north-up Output Tile).
                        val localShiftX = anchoring.anchorShiftX + anchoring.offsetX
                        val localShiftY = anchoring.anchorShiftY + anchoring.offsetY
                        val centerX = anchor.x +
                            localShiftX * rotationCosine - localShiftY * rotationSine +
                            anchoring.translateX
                        val centerY = anchor.y +
                            localShiftX * rotationSine + localShiftY * rotationCosine +
                            anchoring.translateY
                        if (centerX !in 0.0..<sizePx.toDouble() || centerY !in 0.0..<sizePx.toDouble()) return@forEachIndexed
                        val collisionShape = OrientedCollisionBox(
                            centerX = centerX,
                            centerY = centerY,
                            halfWidth = logicalWidth / 2.0 + padding,
                            halfHeight = logicalHeight / 2.0 + padding,
                            cosine = rotationCosine,
                            sine = rotationSine,
                        )
                        if (avoidEdges && !collisionShape.isInside(sizePx.toDouble())) return@forEachIndexed
                        val eitherHalfAllowsOverlap = overlap != IconOverlap.NEVER ||
                            textOverlap?.let { it != IconOverlap.NEVER } == true
                        val usesViewportY = when (zOrder) {
                            IconZOrder.VIEWPORT_Y -> eitherHalfAllowsOverlap || !ignorePlacement ||
                                textIgnorePlacement == false
                            IconZOrder.AUTO -> layer.sortKey == null &&
                                (eitherHalfAllowsOverlap || !ignorePlacement || textIgnorePlacement == false)
                            IconZOrder.SOURCE -> false
                        }
                        val primaryOrder = when {
                            zOrder != IconZOrder.VIEWPORT_Y && layer.sortKey != null -> sortKey
                            // viewport-y orders the projected symbol anchor, not the icon's centre
                            // after icon-anchor, icon-offset and icon-translate displacements.
                            usesViewportY -> anchor.y
                            else -> featureIndex.toDouble() * 1_000_000.0 + anchorIndex
                        }
                        candidates += IconCandidate(
                            stableOrder = featureIndex.toLong() * 1_000_000L + anchorIndex,
                            primaryOrder = primaryOrder,
                            collisionShape = collisionShape,
                            overlap = overlap,
                            ignorePlacement = ignorePlacement,
                            icon = PlacedIcon(
                                sprite = sprite,
                                centerX = centerX,
                                centerY = centerY,
                                width = logicalWidth,
                                height = logicalHeight,
                                rotationDegrees = rotationDegrees,
                                opacity = opacity,
                                color = evaluatedColor(layer.color.evaluate(baseContext), "icon-color", tile),
                                haloColor = evaluatedColor(layer.haloColor.evaluate(baseContext), "icon-halo-color", tile),
                                haloWidth = haloWidth,
                                haloBlur = haloBlur,
                            ),
                        )
                    }
                } catch (error: RasterizationException) {
                    if (!layer.retainedIndependentOfText) throw error
                    skippedFeatures++
                }
            }
            // An author-intended icon layer keeps its long-standing silence about a sprite name
            // the atlas lacks; only a repaired layer reports, and only it can reach the catch that
            // increments skippedFeatures for an invalid property.
            if (layer.retainedIndependentOfText && skippedFeatures > 0) {
                // A constant invalid value - icon-halo-width: -1, symbol-spacing: 0,
                // icon-size: "big" - throws identically for every feature, so a layer carrying one
                // draws nothing at all while preparation reports success. Losing every candidate
                // gets its own message and the skipped-versus-candidate counts, so a caller can
                // tell that from losing one feature and see how much was lost.
                //
                // Both messages state only what happened on this tile, because that is all this
                // code knows. everyCandidateSkipped is computed per tile, and a layer that loses
                // every candidate here can draw fine on the next one - a data-driven icon-image
                // resolving to a name the atlas lacks for this tile's features is the common way
                // that happens. Calling it a whole-layer authoring error would be a conclusion the
                // counts do not support; the counts are there for the reader to draw their own.
                //
                // It is deliberately still a WARNING, and deliberately never a thrown exception.
                // In Rentile, ERROR means the operation failed - StyleCompiler's ERROR diagnostics
                // fail preparation - and nothing failed here: preparation succeeded, the tile
                // rendered, and it is being returned with some icons absent. Failing the tile
                // would also be exactly the all-or-error behaviour this per-feature catch exists to
                // remove, since these layers were not drawn at all before this compatibility
                // profile retained them and a style that rendered fine icon-less must keep
                // rendering. The distinction lives in the message and the counts, not in severity.
                val everyCandidateSkipped = skippedFeatures == candidateFeatures
                val diagnostic = RenderDiagnostic(
                    code = DiagnosticCode.ICON_FEATURE_SKIPPED,
                    severity = DiagnosticSeverity.WARNING,
                    stage = PipelineStage.RASTERIZATION,
                    message = if (everyCandidateSkipped) {
                        "A repaired icon layer drew none of the features that wanted an icon on " +
                            "this tile"
                    } else {
                        "A repaired icon layer skipped one or more features it could not draw " +
                            "rather than failing the tile"
                    },
                    details = mapOf(
                        "layerIndex" to layer.layerOrder.toString(),
                        "candidateFeatures" to candidateFeatures.toString(),
                        "skippedFeatures" to skippedFeatures.toString(),
                        // How many of those skips were a sprite name the atlas lacks rather than a
                        // property that would not evaluate, so the code's name stays readable.
                        "skippedMissingSprite" to skippedMissingSprite.toString(),
                    ),
                    affectedTiles = listOf(tile),
                )
                recordDiagnosticSafely(diagnostic)
                diagnostics += diagnostic
            }
            for (candidate in candidates.sortedWith(compareBy(IconCandidate::primaryOrder, IconCandidate::stableOrder))) {
                val collisions = collisionBoxes.filter { it.shape.intersects(candidate.collisionShape) }
                val canPlace = when (candidate.overlap) {
                    IconOverlap.ALWAYS -> true
                    IconOverlap.NEVER -> collisions.isEmpty()
                    IconOverlap.COOPERATIVE -> collisions.all { it.overlap != IconOverlap.NEVER }
                }
                if (!canPlace) continue
                if (!candidate.ignorePlacement) {
                    collisionBoxes += IconCollision(candidate.collisionShape, candidate.overlap)
                }
                accepted.getOrPut(layer.layerOrder, ::mutableListOf) += candidate.icon
            }
        }
        return accepted
    }

    private fun iconAnchors(
        geometry: DecodedVectorGeometry,
        placement: SymbolPlacement,
        resource: VectorResource,
        extent: Int,
        sizePx: Int,
        spacing: Double,
    ): List<IconPlacementAnchor> {
        fun pixel(point: com.rohittp.rentile.internal.mvt.VectorCoordinate): RenderPoint {
            val output = resource.sample.sourceCoordinateToOutputPixels(point, extent, sizePx)
            return RenderPoint(output.x, output.y)
        }
        return when (placement) {
            SymbolPlacement.POINT -> when (geometry) {
                is DecodedVectorGeometry.Points -> geometry.points.map { point ->
                    val position = pixel(point)
                    IconPlacementAnchor(position.x, position.y, 0.0)
                }
                is DecodedVectorGeometry.Lines -> geometry.lines.mapNotNull { line ->
                    line.takeIf { it.isNotEmpty() }?.map(::pixel)?.let(::midpointAnchor)
                }
                is DecodedVectorGeometry.Polygons -> geometry.rings.firstOrNull()?.points?.map(::pixel)?.let { points ->
                    if (points.isEmpty()) null else IconPlacementAnchor(
                        points.sumOf(RenderPoint::x) / points.size,
                        points.sumOf(RenderPoint::y) / points.size,
                        0.0,
                    )
                }?.let(::listOf).orEmpty()
            }
            SymbolPlacement.LINE -> when (geometry) {
                is DecodedVectorGeometry.Lines -> geometry.lines.flatMap { line -> repeatedLineAnchors(line.map(::pixel), spacing) }
                else -> emptyList()
            }
            SymbolPlacement.LINE_CENTER -> when (geometry) {
                is DecodedVectorGeometry.Lines -> geometry.lines.mapNotNull { line ->
                    line.takeIf { it.size >= 2 }?.map(::pixel)?.let(::midpointAnchor)
                }
                else -> emptyList()
            }
        }
    }

    private fun uprightRotation(rotation: Double, keepUpright: Boolean): Double {
        if (!keepUpright) return rotation
        var normalized = ((rotation + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        if (normalized > 90.0) normalized -= 180.0
        if (normalized < -90.0) normalized += 180.0
        return normalized
    }

    private fun drawIcons(surface: Surface, icons: List<PlacedIcon>) {
        val canvas = surface.canvas
        for (icon in icons) {
            val saveCount = canvas.save()
            try {
                canvas.translate(icon.centerX.toFloat(), icon.centerY.toFloat())
                canvas.rotate(icon.rotationDegrees.toFloat())
                val destination = Rect.makeLTRB(
                    (-icon.width / 2.0).toFloat(),
                    (-icon.height / 2.0).toFloat(),
                    (icon.width / 2.0).toFloat(),
                    (icon.height / 2.0).toFloat(),
                )
                if (icon.sprite.entry.sdf && (icon.haloWidth > 0.0 || icon.haloBlur > 0.0)) {
                    val haloFilter = ColorFilter.makeBlend(
                        paintColor(icon.haloColor, 1.0),
                        BlendMode.SRC_IN,
                    )
                    val blur = icon.haloBlur.takeIf { it > 0.0 }?.let {
                        MaskFilter.makeBlur(FilterBlurMode.NORMAL, it.toFloat(), false)
                    }
                    val haloPaint = Paint().apply {
                        alpha = (icon.opacity * 255.0).roundToInt().coerceIn(0, 255)
                        colorFilter = haloFilter
                        maskFilter = blur
                    }
                    try {
                        canvas.drawImageRect(
                            icon.sprite.image,
                            Rect.makeWH(icon.sprite.image.width.toFloat(), icon.sprite.image.height.toFloat()),
                            Rect.makeLTRB(
                                (destination.left - icon.haloWidth).toFloat(),
                                (destination.top - icon.haloWidth).toFloat(),
                                (destination.right + icon.haloWidth).toFloat(),
                                (destination.bottom + icon.haloWidth).toFloat(),
                            ),
                            SamplingMode.DEFAULT,
                            haloPaint,
                            true,
                        )
                    } finally {
                        haloPaint.close()
                        blur?.close()
                        haloFilter.close()
                    }
                }
                val tint = if (icon.sprite.entry.sdf) {
                    ColorFilter.makeBlend(paintColor(icon.color, 1.0), BlendMode.SRC_IN)
                } else {
                    null
                }
                val paint = Paint().apply {
                    alpha = (icon.opacity * 255.0).roundToInt().coerceIn(0, 255)
                    colorFilter = tint
                }
                try {
                    canvas.drawImageRect(
                        icon.sprite.image,
                        Rect.makeWH(icon.sprite.image.width.toFloat(), icon.sprite.image.height.toFloat()),
                        destination,
                        SamplingMode.DEFAULT,
                        paint,
                        true,
                    )
                } finally {
                    paint.close()
                    tint?.close()
                }
            } finally {
                canvas.restoreToCount(saveCount)
            }
        }
    }

    private fun evaluateIconImageName(value: StyleValue, feature: DecodedVectorFeature): String? {
        val raw = when (value) {
            is StyleValue.ImageValue -> value.name
            is StyleValue.StringValue -> value.value
            else -> return null
        }
        return raw.withExpandedFeatureTokens(feature.properties).takeIf(String::isNotEmpty)
    }

    private fun featureContext(tile: TileId, feature: DecodedVectorFeature): StyleEvaluationContext =
        StyleEvaluationContext(
            zoom = tile.z.toDouble(),
            geometryType = feature.geometryType,
            featureId = feature.id?.let { StyleValue.NumberValue(it.toDouble()) } ?: StyleValue.Null,
            properties = feature.properties,
        )

    private fun evaluatedColor(
        value: StyleValue,
        property: String,
        tile: TileId,
    ): CompiledColor {
        val color = when (value) {
            is StyleValue.ColorValue -> value.value
            is StyleValue.StringValue -> parseCssColor(value.value)
            else -> null
        } ?: throw RasterizationException(
            message = "$property did not evaluate to a supported color",
            affectedTiles = listOf(tile),
        )
        return color
    }

    private fun evaluatedOpacity(value: StyleValue, property: String, tile: TileId): Double {
        val opacity = evaluatedNumber(value, property, tile)
        if (opacity !in 0.0..1.0) {
            throw RasterizationException(
                message = "$property did not evaluate to a value between zero and one",
                affectedTiles = listOf(tile),
            )
        }
        return opacity
    }

    private fun evaluatePatternName(value: StyleValue, property: String, tile: TileId): String? =
        when (value) {
            is StyleValue.StringValue -> value.value
            is StyleValue.ImageValue -> value.name
            else -> throw RasterizationException(
                message = "$property did not evaluate to an image name",
                affectedTiles = listOf(tile),
            )
        }.takeIf(String::isNotEmpty)

    private fun drawRepeatedPattern(
        surface: Surface,
        clipPath: Path?,
        sprite: SpriteRenderImage,
        opacity: Double,
        tile: TileId,
        sizePx: Int,
    ) {
        val logicalWidth = sprite.entry.width / sprite.entry.pixelRatio
        val logicalHeight = sprite.entry.height / sprite.entry.pixelRatio
        if (!logicalWidth.isFinite() || !logicalHeight.isFinite() || logicalWidth <= 0.0 || logicalHeight <= 0.0) {
            throw RasterizationException(
                message = "Sprite pattern has invalid logical dimensions",
                affectedTiles = listOf(tile),
            )
        }
        val canvas = surface.canvas
        val saveCount = canvas.save()
        val paint = Paint().apply { alpha = (opacity * 255.0).roundToInt().coerceIn(0, 255) }
        try {
            clipPath?.let { canvas.clipPath(it, ClipMode.INTERSECT, true) }
            val globalX = canonicalX(tile) * sizePx.toDouble()
            val globalY = tile.y.toDouble() * sizePx
            var y = -positiveModulo(globalY, logicalHeight)
            while (y < sizePx) {
                var x = -positiveModulo(globalX, logicalWidth)
                while (x < sizePx) {
                    canvas.drawImageRect(
                        sprite.image,
                        Rect.makeWH(sprite.image.width.toFloat(), sprite.image.height.toFloat()),
                        Rect.makeLTRB(
                            x.toFloat(),
                            y.toFloat(),
                            (x + logicalWidth).toFloat(),
                            (y + logicalHeight).toFloat(),
                        ),
                        SamplingMode.DEFAULT,
                        paint,
                        true,
                    )
                    x += logicalWidth
                }
                y += logicalHeight
            }
        } finally {
            paint.close()
            canvas.restoreToCount(saveCount)
        }
    }

    private fun evaluatedNumberArray(
        value: StyleValue,
        property: String,
        tile: TileId,
        requiredSize: Int? = null,
    ): List<Double> {
        val array = (value as? StyleValue.ArrayValue)?.values?.map { item ->
            (item as? StyleValue.NumberValue)?.value?.takeIf(Double::isFinite)
                ?: throw RasterizationException(
                    message = "$property did not evaluate to a numeric array",
                    affectedTiles = listOf(tile),
                )
        } ?: throw RasterizationException(
            message = "$property did not evaluate to an array",
            affectedTiles = listOf(tile),
        )
        if (requiredSize != null && array.size != requiredSize) {
            throw RasterizationException(
                message = "$property did not evaluate to exactly $requiredSize numbers",
                affectedTiles = listOf(tile),
            )
        }
        return array
    }

    private fun paintColor(color: CompiledColor, opacity: Double): Int = Color.makeARGB(
        (color.alpha * opacity).roundToInt().coerceIn(0, 255),
        color.red,
        color.green,
        color.blue,
    )

    private fun evaluatedNumber(value: StyleValue, property: String, tile: TileId): Double =
        (value as? StyleValue.NumberValue)?.value?.takeIf(Double::isFinite)
            ?: throw RasterizationException(
                message = "$property did not evaluate to a finite number",
                affectedTiles = listOf(tile),
            )

    private fun evaluatedString(value: StyleValue, property: String, tile: TileId): String =
        (value as? StyleValue.StringValue)?.value
            ?: throw RasterizationException(
                message = "$property did not evaluate to a string",
                affectedTiles = listOf(tile),
            )

    private fun evaluatedBoolean(value: StyleValue, property: String, tile: TileId): Boolean =
        (value as? StyleValue.BooleanValue)?.value
            ?: throw RasterizationException(
                message = "$property did not evaluate to a boolean",
                affectedTiles = listOf(tile),
            )

    private fun evaluatedAlignment(
        value: StyleValue,
        property: String,
        tile: TileId,
        autoAllowed: Boolean = true,
    ): IconAlignment = when (evaluatedString(value, property, tile)) {
        "map" -> IconAlignment.MAP
        "viewport" -> IconAlignment.VIEWPORT
        "auto" -> if (autoAllowed) IconAlignment.AUTO else throw RasterizationException(
            message = "$property did not evaluate to a supported value",
            affectedTiles = listOf(tile),
        )
        else -> throw RasterizationException(
            message = "$property did not evaluate to a supported value",
            affectedTiles = listOf(tile),
        )
    }

    private fun evaluatedIconOverlap(value: StyleValue, tile: TileId): IconOverlap = when (value) {
        is StyleValue.BooleanValue -> if (value.value) IconOverlap.ALWAYS else IconOverlap.NEVER
        is StyleValue.StringValue -> when (value.value) {
            "always" -> IconOverlap.ALWAYS
            "never" -> IconOverlap.NEVER
            "cooperative" -> IconOverlap.COOPERATIVE
            else -> throw RasterizationException(
                message = "icon-overlap did not evaluate to a supported value",
                affectedTiles = listOf(tile),
            )
        }
        else -> throw RasterizationException(
            message = "icon-overlap did not evaluate to an overlap value",
            affectedTiles = listOf(tile),
        )
    }

    private fun evaluatedSymbolZOrder(value: StyleValue, tile: TileId): IconZOrder =
        when (evaluatedString(value, "symbol-z-order", tile)) {
            "auto" -> IconZOrder.AUTO
            "source" -> IconZOrder.SOURCE
            "viewport-y" -> IconZOrder.VIEWPORT_Y
            else -> throw RasterizationException(
                message = "symbol-z-order did not evaluate to a supported value",
                affectedTiles = listOf(tile),
            )
        }

    private fun recordDiagnosticSafely(diagnostic: RenderDiagnostic) {
        configuration.diagnosticSink.recordSafely(diagnostic)
    }

    private fun trackSecretContext(context: SecretContext) {
        while (true) {
            val current = secretContexts.load()
            if (secretContexts.compareAndSet(current, current + context)) return
        }
    }
}

private data class TileRender(
    val tile: TileId,
    val png: ByteArray,
    val diagnostics: List<RenderDiagnostic>,
)

private data class AcquiredStyle(
    val bytes: ByteArray,
    val baseUri: String?,
)

private data class RenderPoint(
    val x: Double,
    val y: Double,
)

private data class IconPlacementAnchor(
    val x: Double,
    val y: Double,
    val rotationDegrees: Double,
)

private data class PlacedIcon(
    val sprite: SpriteRenderImage,
    val centerX: Double,
    val centerY: Double,
    val width: Double,
    val height: Double,
    val rotationDegrees: Double,
    val opacity: Double,
    val color: CompiledColor,
    val haloColor: CompiledColor,
    val haloWidth: Double,
    val haloBlur: Double,
)

private data class IconCandidate(
    val stableOrder: Long,
    val primaryOrder: Double,
    val collisionShape: OrientedCollisionBox,
    val overlap: IconOverlap,
    val ignorePlacement: Boolean,
    val icon: PlacedIcon,
)

private enum class IconOverlap {
    NEVER,
    ALWAYS,
    COOPERATIVE,
}

private enum class IconAlignment {
    MAP,
    VIEWPORT,
    AUTO,
}

private enum class IconZOrder {
    AUTO,
    SOURCE,
    VIEWPORT_Y,
}

private data class IconCollision(
    val shape: OrientedCollisionBox,
    val overlap: IconOverlap,
)

private data class OrientedCollisionBox(
    val centerX: Double,
    val centerY: Double,
    val halfWidth: Double,
    val halfHeight: Double,
    val cosine: Double,
    val sine: Double,
) {
    private val axisX: CollisionAxis get() = CollisionAxis(cosine, sine)
    private val axisY: CollisionAxis get() = CollisionAxis(-sine, cosine)

    private fun projectionRadius(axis: CollisionAxis): Double =
        halfWidth * abs(axisX.dot(axis)) + halfHeight * abs(axisY.dot(axis))

    fun intersects(other: OrientedCollisionBox): Boolean {
        val delta = CollisionAxis(other.centerX - centerX, other.centerY - centerY)
        return listOf(axisX, axisY, other.axisX, other.axisY).all { axis ->
            abs(delta.dot(axis)) < projectionRadius(axis) + other.projectionRadius(axis)
        }
    }

    private val axisAlignedHalfWidth: Double
        get() = abs(cosine) * halfWidth + abs(sine) * halfHeight

    private val axisAlignedHalfHeight: Double
        get() = abs(sine) * halfWidth + abs(cosine) * halfHeight

    fun isInside(size: Double): Boolean =
        centerX - axisAlignedHalfWidth >= 0.0 && centerY - axisAlignedHalfHeight >= 0.0 &&
            centerX + axisAlignedHalfWidth <= size && centerY + axisAlignedHalfHeight <= size
}

private data class CollisionAxis(val x: Double, val y: Double) {
    fun dot(other: CollisionAxis): Double = x * other.x + y * other.y
}

private fun midpointAnchor(points: List<RenderPoint>): IconPlacementAnchor? {
    if (points.isEmpty()) return null
    if (points.size == 1) return IconPlacementAnchor(points[0].x, points[0].y, 0.0)
    val lengths = points.zipWithNext { first, second -> hypot(second.x - first.x, second.y - first.y) }
    val target = lengths.sum() / 2.0
    var traversed = 0.0
    for (index in lengths.indices) {
        val length = lengths[index]
        if (traversed + length >= target && length > 0.0) {
            val progress = (target - traversed) / length
            val first = points[index]
            val second = points[index + 1]
            return IconPlacementAnchor(
                x = first.x + (second.x - first.x) * progress,
                y = first.y + (second.y - first.y) * progress,
                rotationDegrees = atan2(second.y - first.y, second.x - first.x) * 180.0 / PI,
            )
        }
        traversed += length
    }
    return points.last().let { IconPlacementAnchor(it.x, it.y, 0.0) }
}

private fun repeatedLineAnchors(points: List<RenderPoint>, spacing: Double): List<IconPlacementAnchor> {
    if (points.size < 2) return emptyList()
    val lengths = points.zipWithNext { first, second -> hypot(second.x - first.x, second.y - first.y) }
    val total = lengths.sum()
    if (total <= 0.0) return emptyList()
    val anchors = mutableListOf<IconPlacementAnchor>()
    var target = if (total < spacing) total / 2.0 else spacing / 2.0
    var segmentStart = 0.0
    var segmentIndex = 0
    while (target <= total && segmentIndex < lengths.size) {
        while (segmentIndex < lengths.size - 1 && segmentStart + lengths[segmentIndex] < target) {
            segmentStart += lengths[segmentIndex]
            segmentIndex += 1
        }
        val length = lengths[segmentIndex]
        if (length > 0.0) {
            val first = points[segmentIndex]
            val second = points[segmentIndex + 1]
            val progress = ((target - segmentStart) / length).coerceIn(0.0, 1.0)
            anchors += IconPlacementAnchor(
                x = first.x + (second.x - first.x) * progress,
                y = first.y + (second.y - first.y) * progress,
                rotationDegrees = atan2(second.y - first.y, second.x - first.x) * 180.0 / PI,
            )
        }
        target += spacing
    }
    return anchors
}

private val FEATURE_TOKEN = Regex("\\{([^{}]+)\\}")

/**
 * Expands legacy Mapbox `{property}` tokens against a decoded feature's properties.
 *
 * Both `icon-image` and `text-field` accept this pre-expression shorthand, and 82 rolling-corpus
 * label layers still use it for text. One expansion rule serving both is deliberate: the two
 * previously shared nothing, and a second hand-written copy of the same regex and the same
 * property-to-string coercion is precisely the duplication that let 0.2.0 ship a fix applied to
 * one copy and not the other. A token naming a property the feature does not carry expands to the
 * empty string, matching Mapbox GL's behaviour.
 */
internal fun String.withExpandedFeatureTokens(properties: Map<String, StyleValue>): String {
    if ('{' !in this) return this
    return FEATURE_TOKEN.replace(this) { match ->
        when (val property = properties[match.groupValues[1]]) {
            is StyleValue.StringValue -> property.value
            is StyleValue.NumberValue -> property.value.toString().removeSuffix(".0")
            is StyleValue.BooleanValue -> property.value.toString()
            else -> ""
        }
    }
}

private data class SpriteRenderImage(
    val entry: SpriteAtlasEntry,
    val image: Image,
)

private class SpriteRenderContext(
    private val atlas: CompiledSpriteAtlas,
) : AutoCloseable {
    private val atlasImage = try {
        Image.makeFromEncoded(atlas.pngBytes)
    } catch (error: Throwable) {
        throw ResourceDecodeException(
            message = "Prepared sprite atlas image cannot be decoded",
            resourceClass = ResourceClass.SPRITE_IMAGE,
            sanitizedResourceId = atlas.contentDigest,
            cause = error,
        )
    }
    private val images = mutableMapOf<String, SpriteRenderImage>()

    fun image(name: String): SpriteRenderImage? {
        images[name]?.let { return it }
        val entry = atlas.entries[name] ?: return null
        val surface = Surface.makeRasterN32Premul(entry.width, entry.height)
        try {
            val paint = Paint()
            try {
                surface.canvas.drawImageRect(
                    atlasImage,
                    Rect.makeLTRB(
                        entry.x.toFloat(),
                        entry.y.toFloat(),
                        (entry.x + entry.width).toFloat(),
                        (entry.y + entry.height).toFloat(),
                    ),
                    Rect.makeWH(entry.width.toFloat(), entry.height.toFloat()),
                    SamplingMode.DEFAULT,
                    paint,
                    true,
                )
            } finally {
                paint.close()
            }
            return SpriteRenderImage(entry, surface.makeImageSnapshot()).also { images[name] = it }
        } finally {
            surface.close()
        }
    }

    override fun close() {
        images.values.forEach { it.image.close() }
        images.clear()
        atlasImage.close()
    }
}

private fun positiveModulo(value: Double, divisor: Double): Double {
    val remainder = value % divisor
    return if (remainder < 0.0) remainder + divisor else remainder
}

private fun offsetPolyline(points: List<RenderPoint>, offset: Double): List<RenderPoint> {
    if (offset == 0.0 || points.size < 2) return points
    return points.indices.map { index ->
        val previous = points[maxOf(0, index - 1)]
        val current = points[index]
        val next = points[minOf(points.lastIndex, index + 1)]
        val before = unitNormal(previous, current)
        val after = unitNormal(current, next)
        val normalX = before.first + after.first
        val normalY = before.second + after.second
        val normalLength = hypot(normalX, normalY)
        val selected = when {
            normalLength > 1e-9 -> normalX / normalLength to normalY / normalLength
            hypot(after.first, after.second) > 0.0 -> after
            else -> before
        }
        RenderPoint(current.x + selected.first * offset, current.y + selected.second * offset)
    }
}

private fun unitNormal(from: RenderPoint, to: RenderPoint): Pair<Double, Double> {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = hypot(dx, dy)
    return if (length > 1e-9) -dy / length to dx / length else 0.0 to 0.0
}

private fun buildLinePath(points: List<RenderPoint>) = PathBuilder().let { builder ->
    try {
        val first = points.first()
        builder.moveTo(first.x.toFloat(), first.y.toFloat())
        points.drop(1).forEach { point -> builder.lineTo(point.x.toFloat(), point.y.toFloat()) }
        builder.detach()
    } finally {
        builder.close()
    }
}

private data class DashStroke(
    val intervals: FloatArray,
    val phase: Float,
) {
    val period: Float = intervals.sum()

    fun makePathEffect(distanceFromOriginalStart: Double = 0.0): PathEffect {
        val localPhase = if (period > 0f) {
            ((phase + distanceFromOriginalStart) % period + period).toFloat() % period
        } else {
            0f
        }
        return PathEffect.makeDash(intervals, localPhase)
    }
}

/**
 * Draws one line path while honoring MapLibre's `line-round-limit`: a round join whose
 * geometric miter ratio is below the limit is rendered as a miter join. Skia exposes one join
 * for an entire path, so the round path and one local three-point miter path per selected join are
 * first composed on a transparent temporary layer, then that layer is blended onto the Output Tile
 * once. A whole miter-path redraw is not local enough: with short segments or a wide/blurred stroke,
 * an adjacent sharp miter can enter the selected join's circular clip. The local contour has only
 * the selected vertex as a join, and its dash phase is advanced to the contour's original path
 * distance. This also avoids whole-path boolean operations, whose cost becomes pathological on
 * detailed road geometries.
 *
 * Within the temporary layer, `BlendMode.SRC` is load-bearing: using normal source-over would apply
 * translucent line opacity twice at converted joins, while using SRC directly on the Output Tile
 * would erase the opaque background beneath a translucent line.
 */
private fun drawLinePath(
    surface: Surface,
    path: Path,
    paint: Paint,
    points: List<RenderPoint>,
    roundLimit: Double,
    blur: Double,
    dashStroke: DashStroke?,
) {
    if (paint.strokeJoin != PaintStrokeJoin.ROUND) {
        surface.canvas.drawPath(path, paint)
        return
    }
    val replacements = selectedRoundJoinReplacements(
        points,
        paint.strokeWidth.toDouble() / 2.0,
        roundLimit,
        blur,
    )
    if (replacements.isEmpty()) {
        surface.canvas.drawPath(path, paint)
        return
    }

    val originalJoin = paint.strokeJoin
    val originalMiter = paint.strokeMiter
    val originalCap = paint.strokeCap
    val originalBlendMode = paint.blendMode
    val originalPathEffect = paint.pathEffect
    val canvas = surface.canvas
    val layerSaveCount = canvas.saveLayer(
        0f,
        0f,
        surface.width.toFloat(),
        surface.height.toFloat(),
        null,
    )
    try {
        canvas.drawPath(path, paint)
        paint.strokeJoin = PaintStrokeJoin.MITER
        paint.strokeCap = PaintStrokeCap.BUTT
        // `line-miter-limit` governs authored miter joins. It must not veto a round join that
        // `line-round-limit` explicitly selected for conversion: every selected geometric ratio
        // is strictly below roundLimit, so this ceiling is sufficient for every local pass.
        paint.strokeMiter = maxOf(originalMiter, roundLimit.toFloat())
        paint.blendMode = BlendMode.SRC
        for (replacement in replacements) {
            val localPath = buildLinePath(listOf(replacement.previous, replacement.current, replacement.next))
            val mask = PathBuilder().let { builder ->
                try {
                    builder.addCircle(
                        replacement.current.x.toFloat(),
                        replacement.current.y.toFloat(),
                        replacement.maskRadius.toFloat(),
                    )
                    builder.detach()
                } finally {
                    builder.close()
                }
            }
            val localPathEffect = dashStroke?.makePathEffect(replacement.distanceAtPrevious)
            val clipSaveCount = canvas.save()
            try {
                canvas.clipPath(mask, ClipMode.INTERSECT, true)
                paint.pathEffect = localPathEffect
                canvas.drawPath(localPath, paint)
            } finally {
                paint.pathEffect = originalPathEffect
                canvas.restoreToCount(clipSaveCount)
                localPathEffect?.close()
                mask.close()
                localPath.close()
            }
        }
    } finally {
        canvas.restoreToCount(layerSaveCount)
        paint.pathEffect = originalPathEffect
        paint.blendMode = originalBlendMode
        paint.strokeMiter = originalMiter
        paint.strokeCap = originalCap
        paint.strokeJoin = originalJoin
    }
}

private data class RoundJoinReplacement(
    val previous: RenderPoint,
    val current: RenderPoint,
    val next: RenderPoint,
    val maskRadius: Double,
    val distanceAtPrevious: Double,
)

private fun selectedRoundJoinReplacements(
    points: List<RenderPoint>,
    halfWidth: Double,
    roundLimit: Double,
    blur: Double,
): List<RoundJoinReplacement> {
    if (points.size < 3 || halfWidth <= 0.0 || roundLimit <= 1.0) return emptyList()
    val distanceAtPoint = DoubleArray(points.size)
    for (index in 1 until points.size) {
        distanceAtPoint[index] = distanceAtPoint[index - 1] + hypot(
            points[index].x - points[index - 1].x,
            points[index].y - points[index - 1].y,
        )
    }
    return (1 until points.lastIndex).mapNotNull { index ->
        val ratio = roundJoinMiterRatio(points[index - 1], points[index], points[index + 1])
            ?: return@mapNotNull null
        if (ratio >= roundLimit) return@mapNotNull null
        RoundJoinReplacement(
            previous = points[index - 1],
            current = points[index],
            next = points[index + 1],
            // Skia clips after the mask filter. Three sigmas keeps the local miter pass's blur
            // away from the clip edge, avoiding a hard circular seam around the selected join.
            maskRadius = halfWidth * ratio + blur * 3.0 + 0.01,
            distanceAtPrevious = distanceAtPoint[index - 1],
        )
    }
}

private fun roundJoinMiterRatio(
    previous: RenderPoint,
    current: RenderPoint,
    next: RenderPoint,
): Double? {
    val incomingX = current.x - previous.x
    val incomingY = current.y - previous.y
    val outgoingX = next.x - current.x
    val outgoingY = next.y - current.y
    val incomingLength = hypot(incomingX, incomingY)
    val outgoingLength = hypot(outgoingX, outgoingY)
    if (incomingLength <= 1e-9 || outgoingLength <= 1e-9) return null

    val unitIncomingX = incomingX / incomingLength
    val unitIncomingY = incomingY / incomingLength
    val unitOutgoingX = outgoingX / outgoingLength
    val unitOutgoingY = outgoingY / outgoingLength
    val cross = unitIncomingX * unitOutgoingY - unitIncomingY * unitOutgoingX
    if (cross in -1e-9..1e-9) return null
    val dot = (unitIncomingX * unitOutgoingX + unitIncomingY * unitOutgoingY).coerceIn(-1.0, 1.0)
    val cosineHalfAngleSquared = (1.0 + dot) / 2.0
    if (cosineHalfAngleSquared <= 1e-12) return null
    return 1.0 / sqrt(cosineHalfAngleSquared)
}

private data class EvaluatedRasterPaint(
    val opacity: Double,
    val brightnessMinimum: Double,
    val brightnessMaximum: Double,
    val contrast: Double,
    val hueRotate: Double,
    val saturation: Double,
) {
    val isIdentity: Boolean
        get() = opacity == 1.0 &&
            brightnessMinimum == 0.0 &&
            brightnessMaximum == 1.0 &&
            contrast == 0.0 &&
            hueRotate % 360.0 == 0.0 &&
            saturation == 0.0

    fun colorFilter(): ColorFilter? {
        if (
            brightnessMinimum == 0.0 &&
            brightnessMaximum == 1.0 &&
            contrast == 0.0 &&
            hueRotate % 360.0 == 0.0 &&
            saturation == 0.0
        ) {
            return null
        }
        var matrix = identityColorMatrix()
        if (hueRotate % 360.0 != 0.0) matrix = composeColorMatrices(hueColorMatrix(hueRotate), matrix)
        if (saturation != 0.0) matrix = composeColorMatrices(saturationColorMatrix(saturation), matrix)
        if (contrast != 0.0) matrix = composeColorMatrices(contrastColorMatrix(contrast), matrix)
        if (brightnessMinimum != 0.0 || brightnessMaximum != 1.0) {
            matrix = composeColorMatrices(brightnessColorMatrix(brightnessMinimum, brightnessMaximum), matrix)
        }
        return ColorFilter.makeMatrix(ColorMatrix(matrix))
    }
}

private fun identityColorMatrix(): FloatArray = floatArrayOf(
    1f, 0f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f, 0f,
    0f, 0f, 1f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
)

private fun brightnessColorMatrix(minimum: Double, maximum: Double): FloatArray {
    val scale = (maximum - minimum).toFloat()
    val offset = minimum.toFloat()
    return floatArrayOf(
        scale, 0f, 0f, 0f, offset,
        0f, scale, 0f, 0f, offset,
        0f, 0f, scale, 0f, offset,
        0f, 0f, 0f, 1f, 0f,
    )
}

private fun contrastColorMatrix(contrast: Double): FloatArray {
    val factor = if (contrast > 0.0) 1.0 / (1.0 - contrast.coerceAtMost(0.999)) else 1.0 + contrast
    val offset = (0.5 * (1.0 - factor)).toFloat()
    val scale = factor.toFloat()
    return floatArrayOf(
        scale, 0f, 0f, 0f, offset,
        0f, scale, 0f, 0f, offset,
        0f, 0f, scale, 0f, offset,
        0f, 0f, 0f, 1f, 0f,
    )
}

private fun saturationColorMatrix(saturation: Double): FloatArray {
    val adjustment = if (saturation > 0.0) {
        1.0 - 1.0 / (1.001 - saturation)
    } else {
        -saturation
    }
    val average = (adjustment / 3.0).toFloat()
    val scale = (1.0 - adjustment).toFloat()
    return floatArrayOf(
        average + scale, average, average, 0f, 0f,
        average, average + scale, average, 0f, 0f,
        average, average, average + scale, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

private fun hueColorMatrix(degrees: Double): FloatArray {
    val radians = degrees * PI / 180.0
    val cosine = cos(radians)
    val sine = sin(radians)
    val first = ((2.0 * cosine + 1.0) / 3.0).toFloat()
    val second = ((-sqrt(3.0) * sine - cosine + 1.0) / 3.0).toFloat()
    val third = ((sqrt(3.0) * sine - cosine + 1.0) / 3.0).toFloat()
    return floatArrayOf(
        first, second, third, 0f, 0f,
        third, first, second, 0f, 0f,
        second, third, first, 0f, 0f,
        0f,
        0f,
        0f,
        1f,
        0f,
    )
}

private fun composeColorMatrices(outer: FloatArray, inner: FloatArray): FloatArray = FloatArray(20).also { result ->
    for (row in 0 until 4) {
        for (column in 0 until 4) {
            var value = 0f
            for (component in 0 until 4) {
                value += outer[row * 5 + component] * inner[component * 5 + column]
            }
            result[row * 5 + column] = value
        }
        var offset = outer[row * 5 + 4]
        for (component in 0 until 4) {
            offset += outer[row * 5 + component] * inner[component * 5 + 4]
        }
        result[row * 5 + 4] = offset
    }
}

private sealed interface AcquisitionOutcome<out T> {
    data class Success<T>(val value: T) : AcquisitionOutcome<T>

    data class Failure(val error: Throwable) : AcquisitionOutcome<Nothing>
}

private data class TileAcquisitionFailure(
    val tile: TileId,
    val error: Throwable,
)

private data class RasterAcquisitionPlan(
    val samplesByTile: Map<TileId, List<RasterSample>>,
    val outcomesByIdentity: Map<String, AcquisitionOutcome<RasterResource>>,
) {
    fun onlyTiles(tiles: Collection<TileId>): RasterAcquisitionPlan = copy(
        samplesByTile = samplesByTile.filterKeys(tiles::contains),
    )

    fun failures(): List<TileAcquisitionFailure> = samplesByTile.flatMap { (tile, samples) ->
        samples.mapNotNull { sample ->
            (outcomesByIdentity.getValue(sample.identity) as? AcquisitionOutcome.Failure)?.let {
                TileAcquisitionFailure(tile, it.error)
            }
        }
    }
}

private data class VectorAcquisitionPlan(
    val samplesByTile: Map<TileId, List<VectorTileSample>>,
    val outcomesByIdentity: Map<String, AcquisitionOutcome<VectorResource>>,
    /**
     * Sources reachable only through repaired icon layers, per zoom; see
     * `bestEffortVectorSourceDigests`. Keyed by zoom because which layers are active decides the
     * answer, and only active layers cause a fetch in the first place.
     */
    val bestEffortSourceDigestsByZoom: Map<Int, Set<String>> = emptyMap(),
) {
    fun onlyTiles(tiles: Collection<TileId>): VectorAcquisitionPlan = copy(
        samplesByTile = samplesByTile.filterKeys(tiles::contains),
        bestEffortSourceDigestsByZoom = bestEffortSourceDigestsByZoom.filterKeys { zoom ->
            tiles.any { it.z == zoom }
        },
    )

    /**
     * A best-effort failure is not a batch failure. Excluding it here is what keeps it out of
     * `validateSubstitutionAllowance` - so it neither fails the batch under the default
     * TileSubstitutionPolicy.Disabled nor counts against the substitution budget.
     *
     * Only a failure to *obtain or understand* the resource degrades. The boundary is narrower
     * than the whole [RentileException] hierarchy on purpose, and narrower than the compile-time
     * sibling in `resolveOptionalSpriteAtlas`, because a different set of failures is reachable
     * here. `ResourceStoreException` is thrown by the vector acquirer for a cache read, write or
     * eviction that failed: that is the caller's own store misbehaving, not a tile that is
     * missing, and turning it into a per-tile WARNING would hide a broken cache behind absent
     * icons. `RasterizationException` and `PngEncodingException` belong to later stages, the
     * lifecycle and identity failures are control flow, and an unexpected non-Rentile Throwable is
     * a bug - all of them keep surfacing.
     */
    fun isBestEffort(tile: TileId, sample: VectorTileSample, error: Throwable): Boolean {
        val degradable = error is ResourceAcquisitionException ||
            error is ResourceDecodeException ||
            error is SafetyLimitException
        return degradable && sample.source.idDigest in bestEffortSourceDigestsByZoom[tile.z].orEmpty()
    }

    fun failures(): List<TileAcquisitionFailure> = samplesByTile.flatMap { (tile, samples) ->
        samples.mapNotNull { sample ->
            (outcomesByIdentity.getValue(sample.identity) as? AcquisitionOutcome.Failure)
                ?.takeUnless { isBestEffort(tile, sample, it.error) }
                ?.let { TileAcquisitionFailure(tile, it.error) }
        }
    }
}

private data class VectorResolutionItem(
    val resource: VectorResource?,
    val diagnostic: RenderDiagnostic? = null,
)

private data class VectorResolution(
    val resources: Map<TileId, List<VectorResource>>,
    val diagnostics: List<RenderDiagnostic>,
)

private data class RecoveryItem<T>(
    val resource: T,
    val upgraded: Boolean = false,
    val diagnostic: RenderDiagnostic? = null,
)

private data class ResourceRecovery<T>(
    val resources: Map<TileId, List<T>>,
    val upgradedTiles: Set<TileId>,
    val diagnostics: List<RenderDiagnostic>,
)

private data class PreparedResources(
    val raster: Map<TileId, List<RasterResource>>,
    val vector: Map<TileId, List<VectorResource>>,
    /**
     * Diagnostics produced while resolving resources that have no surviving resource to hang
     * themselves on - today, a best-effort vector source that was skipped.
     *
     * A skip leaves no entry in [vector] at all, and `recoverVectorResources` only revisits a
     * resource that carries a substitution, so `retryExact` cannot recover one: the icons stay
     * absent for the batch's lifetime even after the network returns. These are carried on the
     * resources so that rebuilding batch state does not *lose* them, which is a weaker promise
     * than recovering them. Re-acquiring a skipped source means preparing the batch again.
     *
     * No default: a construction site that forgets this field should not compile, because the
     * failure mode of forgetting it is a diagnostic that silently stops reaching the caller.
     */
    val acquisitionDiagnostics: List<RenderDiagnostic>,
) {
    companion object {
        val Empty: PreparedResources = PreparedResources(emptyMap(), emptyMap(), emptyList())
    }
}

private data class PreparedBatchState(
    val resources: PreparedResources,
    val contentKeys: Map<TileId, String>,
    val diagnostics: List<RenderDiagnostic>,
    val substitutions: Map<TileId, List<ResourceSubstitution>>,
)

private fun ByteArray.isPng(): Boolean =
    size >= 8 &&
        this[0] == 0x89.toByte() &&
        this[1] == 0x50.toByte() &&
        this[2] == 0x4e.toByte() &&
        this[3] == 0x47.toByte() &&
        this[4] == 0x0d.toByte() &&
        this[5] == 0x0a.toByte() &&
        this[6] == 0x1a.toByte() &&
        this[7] == 0x0a.toByte()

@OptIn(ExperimentalAtomicApi::class)
private class DefaultPreparedBatch(
    val owner: Any,
    val style: CompiledPreparedStyle,
    override val tiles: List<TileId>,
    val options: RenderOptions,
    initialState: PreparedBatchState,
) : PreparedBatch {
    private val closed = AtomicBoolean(false)
    private val activeLeases = AtomicInt(0)
    private val state = AtomicReference(initialState)
    private val recoveryMutex = Mutex()

    override val contentKeys: Map<TileId, String>
        get() = state.load().contentKeys
    override val diagnostics: List<RenderDiagnostic>
        get() = state.load().diagnostics
    override val substitutions: Map<TileId, List<ResourceSubstitution>>
        get() = state.load().substitutions

    override fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) {
            releaseResourcesIfUnused()
        }
    }

    fun ensureOpen() {
        if (closed.load()) throw PreparedBatchClosedException()
    }

    fun snapshot(): PreparedBatchState = state.load()

    fun replaceState(replacement: PreparedBatchState) {
        ensureOpen()
        state.store(replacement)
    }

    suspend fun <T> withRecoveryLock(block: suspend () -> T): T = recoveryMutex.withLock { block() }

    fun acquireRenderLease(): PreparedBatchLease {
        ensureOpen()
        activeLeases.fetchAndAdd(1)
        if (closed.load()) {
            releaseLease()
            throw PreparedBatchClosedException()
        }
        return PreparedBatchLease(state.load(), ::releaseLease)
    }

    private fun releaseLease() {
        val previous = activeLeases.fetchAndAdd(-1)
        check(previous > 0) { "Prepared batch lease count underflow" }
        if (previous == 1) releaseResourcesIfUnused()
    }

    private fun releaseResourcesIfUnused() {
        if (closed.load() && activeLeases.load() == 0) {
            val current = state.load()
            state.store(current.copy(resources = PreparedResources.Empty))
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class PreparedBatchLease(
    val state: PreparedBatchState,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) release()
    }
}

/**
 * Everything one acquisition reads off a plan.
 *
 * [callerTiles] is the caller's raw, unfiltered tile list - not [tiles], which is de-duplicated
 * and sorted for the public [LabelCandidatePlan.tiles] property. `emptyBatch` must be handed
 * [callerTiles] so the diagnostic it embeds in the returned batch matches the one already
 * recorded to the sink during planning, and matches what the one-shot path emitted before this
 * plan existed: [LabelCandidateAssembler.glyphRangeUnavailable] sorts but does not de-duplicate,
 * so a caller-supplied duplicate tile must survive into `affectedTiles` exactly as it did before.
 */
private class LabelCandidatePlanState(
    val style: CompiledPreparedStyle,
    val tiles: List<TileId>,
    val callerTiles: List<TileId>,
    val resourceAccess: ResourceAccessMode,
    val assembly: LabelAssembly?,
    val limits: ResourceLimits,
)

@OptIn(ExperimentalAtomicApi::class)
private class DefaultLabelCandidatePlan(
    val owner: Any,
    style: CompiledPreparedStyle,
    override val tiles: List<TileId>,
    callerTiles: List<TileId>,
    resourceAccess: ResourceAccessMode,
    assembly: LabelAssembly?,
    limits: ResourceLimits,
) : LabelCandidatePlan {
    // Null once closed, so close() can drop the LabelAssembly - and every PendingLabel it holds -
    // rather than keeping it reachable for as long as the caller holds this plan. AutoCloseable
    // implies release, and a reusable plan is exactly the object a caller is likely to hold onto.
    private val state = AtomicReference<LabelCandidatePlanState?>(
        LabelCandidatePlanState(style, tiles, callerTiles, resourceAccess, assembly, limits),
    )

    // Computed once at construction, not read through `state`, so both keep working after
    // close() clears it: neither needs the assembly or the style beyond this point.
    override val glyphClosure: List<GlyphRangeRef> =
        assembly?.requiredRanges.orEmpty().map { request ->
            GlyphRangeRef(
                fontStackDigest = request.fontStack.sha256Hex(),
                rangeStart = request.rangeStart,
            )
        }

    override val diagnostics: List<RenderDiagnostic> =
        if (assembly == null) {
            style.diagnostics + LabelCandidateAssembler.glyphRangeUnavailable(callerTiles)
        } else {
            style.diagnostics
        }

    override fun glyphUrls(template: String): List<String> {
        val current = requireOpenState()
        val resolved = current.style.glyphsTemplate ?: return emptyList()
        val assembly = current.assembly ?: return emptyList()
        // Compared in redacted form, so two copies of one template that differ only by credential
        // agree, and neither side has to reveal its own. A relative reference passed unresolved
        // does not agree, which is the case worth catching: it would compose URLs that look right
        // and are never the ones fetched. Because the comparison is redacted, the authentication
        // value actually substituted into the returned URLs is the caller's own [template], never
        // verified against what acquisition will use - see this method's KDoc.
        if (template.withRedactedAuthenticationQuery() != resolved.canonicalUrl) {
            throw GlyphTemplateMismatchException()
        }
        return assembly.requiredRanges.map { request ->
            GlyphResourceAcquirer.resolveUrl(template, request.fontStack, request.rangeStart)
        }
    }

    override fun close() {
        state.store(null)
    }

    fun stateForAcquisition(): LabelCandidatePlanState = requireOpenState()

    private fun requireOpenState(): LabelCandidatePlanState =
        state.load() ?: throw LabelCandidatePlanClosedException()
}
