package com.rohittp.rentile

/** Stable machine-readable error codes. Human exception messages are not contracts. */
public enum class RentileErrorCode {
    STYLE_PREPARATION_FAILED,
    RESOURCE_ACQUISITION_FAILED,
    RESOURCE_DECODE_FAILED,
    RASTERIZATION_FAILED,
    PNG_ENCODING_FAILED,
    RESOURCE_STORE_FAILED,
    SAFETY_LIMIT_EXCEEDED,
    PREPARED_BATCH_CLOSED,
    RASTERIZER_CLOSED,
    FOREIGN_PREPARED_STYLE,
    FOREIGN_PREPARED_BATCH,
    INVALID_TILE_ID,
    TILE_NOT_IN_PREPARED_BATCH,
    BATCH_RENDER_FAILED,
}

public sealed class RentileException(
    public val code: RentileErrorCode,
    public val stage: PipelineStage,
    message: String,
    public val diagnostics: List<RenderDiagnostic> = emptyList(),
    public val affectedTiles: List<TileId> = emptyList(),
    cause: Throwable? = null,
) : Exception(message, cause)

public class StylePreparationException(
    message: String,
    diagnostics: List<RenderDiagnostic> = emptyList(),
    cause: Throwable? = null,
) : RentileException(
    code = RentileErrorCode.STYLE_PREPARATION_FAILED,
    stage = PipelineStage.STYLE_PREPARATION,
    message = message,
    diagnostics = diagnostics,
    cause = cause,
)

public class ResourceAcquisitionException(
    message: String,
    public val resourceClass: ResourceClass,
    public val sanitizedResourceId: String,
    public val statusCode: Int? = null,
    public val retryAfterMillis: Long? = null,
    diagnostics: List<RenderDiagnostic> = emptyList(),
    affectedTiles: List<TileId> = emptyList(),
    cause: Throwable? = null,
) : RentileException(
    code = RentileErrorCode.RESOURCE_ACQUISITION_FAILED,
    stage = PipelineStage.RESOURCE_ACQUISITION,
    message = message,
    diagnostics = diagnostics,
    affectedTiles = affectedTiles,
    cause = cause,
)

public class ResourceDecodeException(
    message: String,
    public val resourceClass: ResourceClass,
    public val sanitizedResourceId: String,
    diagnostics: List<RenderDiagnostic> = emptyList(),
    affectedTiles: List<TileId> = emptyList(),
    cause: Throwable? = null,
) : RentileException(
    code = RentileErrorCode.RESOURCE_DECODE_FAILED,
    stage = PipelineStage.RESOURCE_DECODING,
    message = message,
    diagnostics = diagnostics,
    affectedTiles = affectedTiles,
    cause = cause,
)

public class RasterizationException(
    message: String,
    diagnostics: List<RenderDiagnostic> = emptyList(),
    affectedTiles: List<TileId> = emptyList(),
    cause: Throwable? = null,
) : RentileException(
    code = RentileErrorCode.RASTERIZATION_FAILED,
    stage = PipelineStage.RASTERIZATION,
    message = message,
    diagnostics = diagnostics,
    affectedTiles = affectedTiles,
    cause = cause,
)

public class PngEncodingException(
    message: String,
    diagnostics: List<RenderDiagnostic> = emptyList(),
    affectedTiles: List<TileId> = emptyList(),
    cause: Throwable? = null,
) : RentileException(
    code = RentileErrorCode.PNG_ENCODING_FAILED,
    stage = PipelineStage.PNG_ENCODING,
    message = message,
    diagnostics = diagnostics,
    affectedTiles = affectedTiles,
    cause = cause,
)

public class ResourceStoreException(
    message: String,
    diagnostics: List<RenderDiagnostic> = emptyList(),
    cause: Throwable? = null,
) : RentileException(
    code = RentileErrorCode.RESOURCE_STORE_FAILED,
    stage = PipelineStage.RESOURCE_STORAGE,
    message = message,
    diagnostics = diagnostics,
    cause = cause,
)

public class SafetyLimitException(
    message: String,
    public val limitName: String,
    public val limit: Long,
    public val observed: Long,
    stage: PipelineStage,
    diagnostics: List<RenderDiagnostic> = emptyList(),
    affectedTiles: List<TileId> = emptyList(),
) : RentileException(
    code = RentileErrorCode.SAFETY_LIMIT_EXCEEDED,
    stage = stage,
    message = message,
    diagnostics = diagnostics,
    affectedTiles = affectedTiles,
)

public class PreparedBatchClosedException(
    message: String = "Prepared batch is closed",
) : RentileException(
    code = RentileErrorCode.PREPARED_BATCH_CLOSED,
    stage = PipelineStage.LIFECYCLE,
    message = message,
)

public class RasterizerClosedException(
    message: String = "Rasterizer is closed",
) : RentileException(
    code = RentileErrorCode.RASTERIZER_CLOSED,
    stage = PipelineStage.LIFECYCLE,
    message = message,
)

public class ForeignPreparedStyleException(
    message: String = "Prepared style belongs to another rasterizer",
) : RentileException(
    code = RentileErrorCode.FOREIGN_PREPARED_STYLE,
    stage = PipelineStage.LIFECYCLE,
    message = message,
)

public class ForeignPreparedBatchException(
    message: String = "Prepared batch belongs to another rasterizer",
) : RentileException(
    code = RentileErrorCode.FOREIGN_PREPARED_BATCH,
    stage = PipelineStage.LIFECYCLE,
    message = message,
)

public class InvalidTileIdException(
    public val tile: TileId,
    message: String = "Tile identity is outside the supported XYZ range",
) : RentileException(
    code = RentileErrorCode.INVALID_TILE_ID,
    stage = PipelineStage.RESOURCE_PLANNING,
    message = message,
    affectedTiles = listOf(tile),
)

public class TileNotInPreparedBatchException(
    public val tile: TileId,
    message: String = "Requested tile is not part of the prepared batch",
) : RentileException(
    code = RentileErrorCode.TILE_NOT_IN_PREPARED_BATCH,
    stage = PipelineStage.LIFECYCLE,
    message = message,
    affectedTiles = listOf(tile),
)

public class BatchRenderException(
    message: String,
    public val primaryFailure: RentileException,
    public val concurrentFailures: List<RentileException> = emptyList(),
    diagnostics: List<RenderDiagnostic> = primaryFailure.diagnostics,
    affectedTiles: List<TileId> = primaryFailure.affectedTiles,
) : RentileException(
    code = RentileErrorCode.BATCH_RENDER_FAILED,
    stage = primaryFailure.stage,
    message = message,
    diagnostics = diagnostics,
    affectedTiles = affectedTiles,
    cause = primaryFailure,
)
