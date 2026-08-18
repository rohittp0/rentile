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

    /**
     * A repaired icon layer - one retained because its text was removed and its icon is
     * independent of that text - could not draw one or more of its features, and the tile was
     * returned without them rather than failing.
     *
     * Reported once per layer per tile, whatever the cause, deliberately: the name stays neutral
     * because two different things reach it. A feature is skipped either because an icon property
     * would not evaluate to a usable value (`icon-size: "big"`, a negative `icon-halo-width`, a
     * data-driven `icon-offset` that is not a numeric pair) or because it named an icon the sprite
     * atlas does not contain. `details` carries the discriminator: `candidateFeatures` is how many
     * features wanted an icon, `skippedFeatures` how many of those drew nothing, and
     * `skippedMissingSprite` how many of *those* were a missing sprite name - so the
     * invalid-property count is `skippedFeatures` minus `skippedMissingSprite`, and
     * `skippedFeatures == candidateFeatures` means the layer lost everything, which is a
     * whole-layer authoring error rather than bad data on one feature. `layerIndex` locates the
     * layer in the compiled style.
     *
     * Always a WARNING. Nothing failed: preparation succeeded and the tile rendered.
     */
    ICON_FEATURE_SKIPPED,

    /**
     * A vector source reachable only through repaired icon layers could not be acquired, so those
     * layers drew nothing for this tile and the tile was returned anyway.
     *
     * Distinct from [ICON_FEATURE_SKIPPED] because the resource never arrived at all, rather than
     * a feature in it being undrawable. Such a source was never fetched before this compatibility
     * profile retained those layers, so a failure on it must not fail the batch - it also never
     * consumes tile substitution. `details` carries `resourceClass`, the `causeCode` naming the
     * typed failure, and `statusCode` when the failure was an HTTP response.
     */
    ICON_LAYER_SKIPPED_SOURCE_UNAVAILABLE,
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
