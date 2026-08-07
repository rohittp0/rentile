package com.rohittp.rentile.internal

import com.rohittp.rentile.BasemapRasterizer
import com.rohittp.rentile.BatchRenderException
import com.rohittp.rentile.CompatibilityPolicy
import com.rohittp.rentile.DiagnosticCode
import com.rohittp.rentile.DiagnosticSeverity
import com.rohittp.rentile.ForeignPreparedBatchException
import com.rohittp.rentile.ForeignPreparedStyleException
import com.rohittp.rentile.InvalidTileIdException
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
import com.rohittp.rentile.TileId
import com.rohittp.rentile.TileNotInPreparedBatchException
import com.rohittp.rentile.TransportRequest
import com.rohittp.rentile.internal.metadata.TileJsonResourceAcquirer
import com.rohittp.rentile.internal.geojson.GeoJsonResourceAcquirer
import com.rohittp.rentile.internal.mvt.DecodedVectorFeature
import com.rohittp.rentile.internal.mvt.DecodedVectorGeometry
import com.rohittp.rentile.internal.mvt.VectorResource
import com.rohittp.rentile.internal.mvt.VectorResourceAcquirer
import com.rohittp.rentile.internal.mvt.sampleFor
import com.rohittp.rentile.internal.raster.RasterResource
import com.rohittp.rentile.internal.raster.RasterResourceAcquirer
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
import com.rohittp.rentile.internal.style.IconAnchor
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
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

    override suspend fun prepareBatch(
        style: PreparedStyle,
        tiles: List<TileId>,
        options: RenderOptions,
        resourceAccess: ResourceAccessMode,
    ): PreparedBatch = operation {
        val compiledStyle = requireOwnedStyle(style)
        val stableTiles = tiles.toList()
        stableTiles.forEach { validateTile(it, compiledStyle.policy) }
        val duplicate = stableTiles.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        if (duplicate != null) throw InvalidTileIdException(duplicate, "Prepared batch contains a duplicate tile")
        val preparedResources = supervisorScope {
            val raster = async { acquireOutcome { acquireRasterResources(compiledStyle, stableTiles, resourceAccess) } }
            val vector = async { acquireOutcome { acquireVectorResources(compiledStyle, stableTiles, resourceAccess) } }
            val rasterOutcome = raster.await()
            val vectorOutcome = vector.await()
            throwAcquisitionFailures(listOf(rasterOutcome, vectorOutcome))
            PreparedResources(
                raster = (rasterOutcome as AcquisitionOutcome.Success).value,
                vector = (vectorOutcome as AcquisitionOutcome.Success).value,
            )
        }
        val resourceDiagnostics = (
            preparedResources.raster.values.flatten().flatMap { it.diagnostics } +
                preparedResources.vector.values.flatten().flatMap { it.diagnostics }
            ).distinct()
        val contentKeys = stableTiles.associateWith { tile ->
            buildString {
                append("rentile-output-2\n")
                append(compiledStyle.digest)
                append('\n')
                append(tile.z)
                append('/')
                append(canonicalX(tile))
                append('/')
                append(tile.y)
                append('\n')
                append(options.outputSizePx)
                for (resource in preparedResources.raster[tile].orEmpty().sortedBy { it.sample.identity }) {
                    append('\n')
                    append("raster:")
                    append(resource.sample.identity)
                    append(':')
                    append(resource.contentDigest)
                }
                for (resource in preparedResources.vector[tile].orEmpty().sortedBy { it.sample.identity }) {
                    append('\n')
                    append("vector:")
                    append(resource.sample.identity)
                    append(':')
                    append(resource.contentDigest)
                }
            }.sha256Hex()
        }
        DefaultPreparedBatch(
            owner = owner,
            style = compiledStyle,
            tiles = stableTiles,
            contentKeys = contentKeys,
            diagnostics = compiledStyle.diagnostics + resourceDiagnostics,
            options = options,
            preparedResources = preparedResources,
        )
    }

    override suspend fun render(batch: PreparedBatch, tiles: List<TileId>): RenderBatch = operation {
        val prepared = requireOwnedBatch(batch)
        val lease = prepared.acquireRenderLease()
        try {
            val requested = tiles.toList()
            requested.forEach { tile ->
                if (tile !in prepared.contentKeys) throw TileNotInPreparedBatchException(tile)
            }
            val tileResults = requested.map { tile ->
                currentCoroutineContext().ensureActive()
                renderPermits.withPermit { renderTile(prepared, lease.resources, tile) }
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
                    contentKey = prepared.contentKeys.getValue(result.tile),
                    diagnostics = prepared.diagnostics + result.diagnostics,
                )
            }
            RenderBatch(rendered, prepared.diagnostics + tileResults.flatMap { it.diagnostics })
        } finally {
            lease.close()
        }
    }

    override suspend fun render(
        style: PreparedStyle,
        tiles: List<TileId>,
        options: RenderOptions,
        resourceAccess: ResourceAccessMode,
    ): RenderBatch {
        val batch = prepareBatch(style, tiles, options, resourceAccess)
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

    private suspend fun acquireRasterResources(
        style: CompiledPreparedStyle,
        tiles: List<TileId>,
        accessMode: ResourceAccessMode,
    ): Map<TileId, List<RasterResource>> = supervisorScope {
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
        throwAcquisitionFailures(outcomes.values.toList())
        val acquiredByIdentity = outcomes.mapValues { (_, outcome) ->
            (outcome as AcquisitionOutcome.Success).value
        }

        samplesByTile.mapValues { (_, samples) ->
            samples.map { sample ->
                val acquired = acquiredByIdentity.getValue(sample.identity)
                if (acquired.sample == sample) acquired else acquired.copy(sample = sample)
            }
        }
    }

    private suspend fun acquireVectorResources(
        style: CompiledPreparedStyle,
        tiles: List<TileId>,
        accessMode: ResourceAccessMode,
    ): Map<TileId, List<VectorResource>> = supervisorScope {
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
        throwAcquisitionFailures(outcomes.values.toList())
        val acquiredByIdentity = outcomes.mapValues { (_, outcome) ->
            (outcome as AcquisitionOutcome.Success).value
        }

        samplesByTile.mapValues { (_, samples) ->
            samples.map { sample ->
                val acquired = acquiredByIdentity.getValue(sample.identity)
                if (acquired.sample == sample) acquired else acquired.copy(sample = sample)
            }
        }
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

        val png = renderCompositedTile(
            style = batch.style,
            layers = activeLayers,
            rasterResources = rasterResources,
            vectorResources = vectorResources,
            sizePx = batch.options.outputSizePx,
            tile = tile,
        )
        return TileRender(tile, png, emptyList())
    }

    private fun renderCompositedTile(
        style: CompiledPreparedStyle,
        layers: List<CompiledDrawLayer>,
        rasterResources: List<RasterResource>,
        vectorResources: List<VectorResource>,
        sizePx: Int,
        tile: TileId,
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
                            val pathEffect = dashValues?.let { values ->
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
                                PathEffect.makeDash(intervals, phase)
                            }
                            paint.pathEffect = pathEffect
                            try {
                                surface.canvas.drawPath(path, paint)
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
    ): Map<Int, List<PlacedIcon>> {
        val accepted = mutableMapOf<Int, MutableList<PlacedIcon>>()
        val collisionBoxes = mutableListOf<CollisionBox>()
        for (layer in layers.sortedByDescending(IconDrawLayer::layerOrder)) {
            val resource = resources.singleOrNull { it.sample.source.idDigest == layer.source.idDigest } ?: continue
            val sourceLayer = resource.tile.layers.singleOrNull { it.name == layer.sourceLayer } ?: continue
            val candidates = mutableListOf<IconCandidate>()
            for ((featureIndex, feature) in sourceLayer.features.withIndex()) {
                val baseContext = featureContext(tile, feature).copy(imageAvailable = atlas.entries::containsKey)
                if (!layer.filter.matches(baseContext)) continue
                val imageName = evaluateIconImageName(layer.image.evaluate(baseContext), feature) ?: continue
                val sprite = sprites.image(imageName) ?: continue
                val size = evaluatedNumber(layer.size.evaluate(baseContext), "icon-size", tile)
                val opacity = evaluatedOpacity(layer.opacity.evaluate(baseContext), "icon-opacity", tile)
                val haloWidth = evaluatedNumber(layer.haloWidth.evaluate(baseContext), "icon-halo-width", tile)
                val haloBlur = evaluatedNumber(layer.haloBlur.evaluate(baseContext), "icon-halo-blur", tile)
                val rotate = evaluatedNumber(layer.rotate.evaluate(baseContext), "icon-rotate", tile)
                val spacing = evaluatedNumber(layer.spacing.evaluate(baseContext), "symbol-spacing", tile)
                if (size <= 0.0) continue
                if (haloWidth < 0.0 || haloBlur < 0.0 || spacing <= 0.0) {
                    throw RasterizationException(
                        message = "Retained icon halo or spacing values are outside their valid range",
                        affectedTiles = listOf(tile),
                    )
                }
                val offset = evaluatedNumberArray(layer.offset.evaluate(baseContext), "icon-offset", tile, 2)
                val translate = evaluatedNumberArray(layer.translate.evaluate(baseContext), "icon-translate", tile, 2)
                val logicalWidth = sprite.entry.width / sprite.entry.pixelRatio * size
                val logicalHeight = sprite.entry.height / sprite.entry.pixelRatio * size
                val anchorShift = iconAnchorShift(layer.anchor, logicalWidth, logicalHeight)
                val anchors = iconAnchors(
                    geometry = feature.geometry,
                    placement = layer.placement,
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
                    val centerX = anchor.x + anchorShift.first + offset[0] * size + translate[0]
                    val centerY = anchor.y + anchorShift.second + offset[1] * size + translate[1]
                    if (centerX !in 0.0..<sizePx.toDouble() || centerY !in 0.0..<sizePx.toDouble()) return@forEachIndexed
                    val padding = layer.padding + haloWidth
                    val box = CollisionBox(
                        left = centerX - logicalWidth / 2.0 - padding,
                        top = centerY - logicalHeight / 2.0 - padding,
                        right = centerX + logicalWidth / 2.0 + padding,
                        bottom = centerY + logicalHeight / 2.0 + padding,
                    )
                    if (layer.avoidEdges && !box.isInside(sizePx.toDouble())) return@forEachIndexed
                    candidates += IconCandidate(
                        stableOrder = featureIndex.toLong() * 1_000_000L + anchorIndex,
                        sortKey = sortKey,
                        box = box,
                        icon = PlacedIcon(
                            sprite = sprite,
                            centerX = centerX,
                            centerY = centerY,
                            width = logicalWidth,
                            height = logicalHeight,
                            rotationDegrees = rotate + anchor.rotationDegrees,
                            opacity = opacity,
                            color = evaluatedColor(layer.color.evaluate(baseContext), "icon-color", tile),
                            haloColor = evaluatedColor(layer.haloColor.evaluate(baseContext), "icon-halo-color", tile),
                            haloWidth = haloWidth,
                            haloBlur = haloBlur,
                        ),
                    )
                }
            }
            for (candidate in candidates.sortedWith(compareBy(IconCandidate::sortKey, IconCandidate::stableOrder))) {
                if (!layer.allowOverlap && collisionBoxes.any { it.intersects(candidate.box) }) continue
                collisionBoxes += candidate.box
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
        }
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
        if ('{' !in raw) return raw.takeIf(String::isNotEmpty)
        return ICON_TOKEN.replace(raw) { match ->
            when (val property = feature.properties[match.groupValues[1]]) {
                is StyleValue.StringValue -> property.value
                is StyleValue.NumberValue -> property.value.toString().removeSuffix(".0")
                is StyleValue.BooleanValue -> property.value.toString()
                else -> ""
            }
        }.takeIf(String::isNotEmpty)
    }

    private fun iconAnchorShift(anchor: IconAnchor, width: Double, height: Double): Pair<Double, Double> = when (anchor) {
        IconAnchor.CENTER -> 0.0 to 0.0
        IconAnchor.LEFT -> width / 2.0 to 0.0
        IconAnchor.RIGHT -> -width / 2.0 to 0.0
        IconAnchor.TOP -> 0.0 to height / 2.0
        IconAnchor.BOTTOM -> 0.0 to -height / 2.0
        IconAnchor.TOP_LEFT -> width / 2.0 to height / 2.0
        IconAnchor.TOP_RIGHT -> -width / 2.0 to height / 2.0
        IconAnchor.BOTTOM_LEFT -> width / 2.0 to -height / 2.0
        IconAnchor.BOTTOM_RIGHT -> -width / 2.0 to -height / 2.0
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
    val sortKey: Double,
    val box: CollisionBox,
    val icon: PlacedIcon,
)

private data class CollisionBox(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    fun intersects(other: CollisionBox): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun isInside(size: Double): Boolean = left >= 0.0 && top >= 0.0 && right <= size && bottom <= size
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

private val ICON_TOKEN = Regex("\\{([^{}]+)}")

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

private data class PreparedResources(
    val raster: Map<TileId, List<RasterResource>>,
    val vector: Map<TileId, List<VectorResource>>,
) {
    companion object {
        val Empty: PreparedResources = PreparedResources(emptyMap(), emptyMap())
    }
}

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
    override val contentKeys: Map<TileId, String>,
    override val diagnostics: List<RenderDiagnostic>,
    val options: RenderOptions,
    preparedResources: PreparedResources,
) : PreparedBatch {
    private val closed = AtomicBoolean(false)
    private val activeLeases = AtomicInt(0)
    private val resources = AtomicReference(preparedResources)

    override fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) {
            releaseResourcesIfUnused()
        }
    }

    fun ensureOpen() {
        if (closed.load()) throw PreparedBatchClosedException()
    }

    fun acquireRenderLease(): PreparedBatchLease {
        ensureOpen()
        activeLeases.fetchAndAdd(1)
        if (closed.load()) {
            releaseLease()
            throw PreparedBatchClosedException()
        }
        return PreparedBatchLease(resources.load(), ::releaseLease)
    }

    private fun releaseLease() {
        val previous = activeLeases.fetchAndAdd(-1)
        check(previous > 0) { "Prepared batch lease count underflow" }
        if (previous == 1) releaseResourcesIfUnused()
    }

    private fun releaseResourcesIfUnused() {
        if (closed.load() && activeLeases.load() == 0) {
            resources.store(PreparedResources.Empty)
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class PreparedBatchLease(
    val resources: PreparedResources,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) release()
    }
}
