package com.rohittp.rentile

public enum class DiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

public enum class PipelineStage {
    STYLE_PREPARATION,
    RESOURCE_PLANNING,
    RESOURCE_ACQUISITION,
    RESOURCE_DECODING,
    RASTERIZATION,
    PNG_ENCODING,
    RESOURCE_STORAGE,
    LIFECYCLE,
}

/** Stable public diagnostic codes. */
public enum class DiagnosticCode {
    TEXT_ONLY_LAYER_EXCLUDED,
    TEXT_COMPONENT_REMOVED_ICON_RETAINED,
    TEXT_COUPLED_ICON_LAYER_EXCLUDED,
    EMPTY_ICON_IMAGE_NO_DRAW,
    HIDDEN_LAYER_NO_DRAW,
    EXTRUSION_FLATTENED,
    RESOURCE_CACHE_HIT,
    RESOURCE_CACHE_MISS,
    TILE_RESOURCE_SUBSTITUTED,
    TILE_EXACT_RECOVERY_FAILED,
    RESOURCE_REVALIDATED,
    RASTER_PASSTHROUGH_USED,
    ROOT_BEHAVIOR_EXCLUDED,
    UNSUPPORTED_RETAINED_CONSTRUCT,
    ICON_FEATURE_SKIPPED_INVALID_PROPERTY,
}

/** Sanitized diagnostic. [details] must never contain secrets or signed URLs. */
public data class RenderDiagnostic(
    public val code: DiagnosticCode,
    public val severity: DiagnosticSeverity,
    public val stage: PipelineStage,
    public val message: String,
    public val details: Map<String, String> = emptyMap(),
    public val affectedTiles: List<TileId> = emptyList(),
)

public fun interface DiagnosticSink {
    public fun record(diagnostic: RenderDiagnostic)

    public companion object {
        public val None: DiagnosticSink = DiagnosticSink { }
    }
}

public enum class MetricName {
    RESOURCE_REQUEST,
    RESOURCE_WIRE_BYTES,
    RESOURCE_DECODED_BYTES,
    RAW_CACHE_HIT,
    RAW_CACHE_MISS,
    SINGLE_FLIGHT_JOIN,
    TILE_RESOURCE_SUBSTITUTED,
    TILE_EXACT_RECOVERED,
    TILE_RENDERED,
    PNG_ENCODED_BYTES,
}

public data class RentileMetric(
    public val name: MetricName,
    public val value: Long = 1L,
    public val resourceClass: ResourceClass? = null,
    public val tags: Map<String, String> = emptyMap(),
)

public fun interface MetricsSink {
    public fun record(metric: RentileMetric)

    public companion object {
        public val None: MetricsSink = MetricsSink { }
    }
}
