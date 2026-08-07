package com.rohittp.rentile.internal

import com.rohittp.rentile.BasemapRasterizer
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
import com.rohittp.rentile.RasterizerClosedException
import com.rohittp.rentile.RenderBatch
import com.rohittp.rentile.RenderDiagnostic
import com.rohittp.rentile.RenderOptions
import com.rohittp.rentile.RenderedTile
import com.rohittp.rentile.RentileConfiguration
import com.rohittp.rentile.RentileMetric
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
import com.rohittp.rentile.internal.raster.RasterResource
import com.rohittp.rentile.internal.raster.RasterResourceAcquirer
import com.rohittp.rentile.internal.raster.sampleFor
import com.rohittp.rentile.internal.style.BackgroundDrawLayer
import com.rohittp.rentile.internal.style.CompiledDrawLayer
import com.rohittp.rentile.internal.style.CompiledPreparedStyle
import com.rohittp.rentile.internal.style.RasterDrawLayer
import com.rohittp.rentile.internal.style.RasterResampling
import com.rohittp.rentile.internal.style.StyleCompiler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.roundToInt

internal fun createBasemapRasterizer(configuration: RentileConfiguration): BasemapRasterizer =
    DefaultBasemapRasterizer(configuration)

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
    private val compiler = StyleCompiler(owner)
    private val rasterAcquirer = RasterResourceAcquirer(configuration, scope)
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
        stableTiles.forEach(::validateTile)
        val duplicate = stableTiles.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        if (duplicate != null) throw InvalidTileIdException(duplicate, "Prepared batch contains a duplicate tile")
        val rasterResources = acquireRasterResources(compiledStyle, stableTiles, resourceAccess)
        val resourceDiagnostics = rasterResources.values.flatten().flatMap { it.diagnostics }.distinct()
        val contentKeys = stableTiles.associateWith { tile ->
            buildString {
                append("rentile-output-1\n")
                append(compiledStyle.digest)
                append('\n')
                append(tile.z)
                append('/')
                append(canonicalX(tile))
                append('/')
                append(tile.y)
                append('\n')
                append(options.outputSizePx)
                for (resource in rasterResources[tile].orEmpty().sortedBy { it.sample.identity }) {
                    append('\n')
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
            rasterResources = rasterResources,
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

    private fun validateTile(tile: TileId) {
        if (tile.z !in 0..30) throw InvalidTileIdException(tile)
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
    ): Map<TileId, List<RasterResource>> = coroutineScope {
        val samplesByTile = tiles.associateWith { tile ->
            style.drawLayers
                .filterIsInstance<RasterDrawLayer>()
                .filter { it.isActiveAt(tile.z) }
                .mapNotNull { it.source.sampleFor(tile) }
                .distinctBy { it.source.idDigest }
        }
        val representatives = samplesByTile.values.flatten().associateBy { it.identity }
        val pending = representatives.mapValues { (_, sample) ->
            async { rasterAcquirer.acquire(sample, accessMode) }
        }
        pending.values.awaitAll()
        val acquiredByIdentity = pending.mapValues { (_, deferred) -> deferred.await() }

        samplesByTile.mapValues { (_, samples) ->
            samples.map { sample ->
                val acquired = acquiredByIdentity.getValue(sample.identity)
                if (acquired.sample == sample) acquired else acquired.copy(sample = sample)
            }
        }
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
        resourcesByTile: Map<TileId, List<RasterResource>>,
        tile: TileId,
    ): TileRender {
        val activeLayers = batch.style.drawLayers.filter { layer ->
            layer !is RasterDrawLayer || layer.isActiveAt(tile.z)
        }
        val resources = resourcesByTile[tile].orEmpty()
        val passThroughLayer = activeLayers.singleOrNull() as? RasterDrawLayer
        if (passThroughLayer != null && passThroughLayer.opacity == 1f) {
            val resource = resources.singleOrNull { it.sample.source.idDigest == passThroughLayer.source.idDigest }
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

        val png = renderCompositedTile(activeLayers, resources, batch.options.outputSizePx, tile)
        return TileRender(tile, png, emptyList())
    }

    private fun renderCompositedTile(
        layers: List<CompiledDrawLayer>,
        resources: List<RasterResource>,
        sizePx: Int,
        tile: TileId,
    ): ByteArray {
        val surface = Surface.makeRasterN32Premul(sizePx, sizePx)
        try {
            surface.canvas.clear(Color.TRANSPARENT)
            for (layer in layers) {
                when (layer) {
                    is BackgroundDrawLayer -> drawBackground(surface, layer)
                    is RasterDrawLayer -> {
                        val resource = resources.singleOrNull { it.sample.source.idDigest == layer.source.idDigest }
                            ?: continue
                        drawRaster(surface, layer, resource, sizePx, tile)
                    }
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
        } catch (error: Throwable) {
            throw PngEncodingException("PNG rendering or encoding failed", affectedTiles = listOf(tile), cause = error)
        } finally {
            surface.close()
        }
    }

    private fun drawBackground(surface: Surface, layer: BackgroundDrawLayer) {
        val background = layer.background
        val alpha = (background.color.alpha * background.opacity).roundToInt().coerceIn(0, 255)
        val paint = Paint().apply {
            color = Color.makeARGB(alpha, background.color.red, background.color.green, background.color.blue)
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
            val paint = Paint().apply { alpha = (layer.opacity * 255f).roundToInt().coerceIn(0, 255) }
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
            }
        } finally {
            image.close()
        }
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
    rasterResources: Map<TileId, List<RasterResource>>,
) : PreparedBatch {
    private val closed = AtomicBoolean(false)
    private val activeLeases = AtomicInt(0)
    private val resources = AtomicReference(rasterResources)

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
            resources.store(emptyMap())
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class PreparedBatchLease(
    val resources: Map<TileId, List<RasterResource>>,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) release()
    }
}
