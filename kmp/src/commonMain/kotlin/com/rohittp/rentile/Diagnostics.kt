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
     * invalid-property count is `skippedFeatures` minus `skippedMissingSprite`. `layerIndex`
     * locates the layer in the compiled style.
     *
     * All of these counts describe **this tile only**. `skippedFeatures == candidateFeatures`
     * means the layer drew none of the features that wanted an icon on this tile; it does not
     * mean the style is broken. The cause is frequently tile-dependent - a data-driven
     * `icon-image` resolving to a sprite name the atlas lacks for the features on this tile is the
     * common case - so the same layer can lose everything here and draw normally on the next tile.
     * Alerting on equal counts alone will fire on healthy tiles; compare across tiles first.
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
     * typed failure, `statusCode` when the failure was an HTTP response, and two redacted digests
     * identifying what was lost: `sourceIdDigest`, which is stable across tiles and says which
     * source it was, and `resourceId`, which is per-sample and therefore distinguishes two sources
     * failing identically on the same tile. Neither is a URL.
     */
    ICON_LAYER_SKIPPED_SOURCE_UNAVAILABLE,

    /**
     * A label candidate's text resolved to a script that requires bidirectional reordering or
     * contextual joining, which this renderer's glyph-metrics-only layout cannot perform. The
     * feature was excluded from its layer's label candidates rather than laid out incorrectly.
     *
     * Reported once per layer per acquisition, never per feature: a dense tile carries thousands
     * of features in one script, and repeating an identical diagnostic for each adds no
     * information while burying everything else a caller's sink receives.
     *
     * `details` carries `layerIndex` and `layerIdDigest`, the same identity pair used by the other
     * layer-level label diagnostics, plus `excludedFeatures`: how many labels this one entry
     * stands for across every requested tile in the batch. `affectedTiles` names those tiles.
     *
     * A style that branches on `is-supported-script` to select a fallback text field (typically
     * `name:latin`) usually keeps its labels without ever reaching this code - the text field is
     * evaluated first, and this fires only when the string that evaluation produced still resolves
     * to an unsupported script.
     *
     * Always INFO. Nothing failed: label-candidate acquisition continues, and these features are
     * simply absent from the result.
     */
    COMPLEX_SCRIPT_LABEL_EXCLUDED,

    /**
     * A place-name label layer carried a construct this compatibility profile cannot compile -
     * its `filter`, a text layout property, a text paint property, or an expression inside any of
     * those - and that layer contributes no label candidates, rather than failing style
     * preparation.
     *
     * Only the label candidates are lost. The layer keeps its [LabelLayerDescriptor] and its raw
     * MVT still acquires, so a consumer using `labelLayerDescriptors`/`acquireLabelTiles` - an API
     * that predates label candidates - sees no change at all.
     *
     * `details` carries `layerIndex` and `layerIdDigest`, the same identity pair used by other
     * layer-exclusion diagnostics, locating which layer was excluded in the compiled style.
     *
     * Always INFO. Nothing failed: preparation continues without that layer's label candidates.
     */
    UNSUPPORTED_TEXT_CONSTRUCT,

    /**
     * Label candidates were requested for a prepared style whose `glyphs` URL template could not
     * be resolved, so an empty label-candidate batch was returned instead of failing. A style
     * that declares no `glyphs` key has no text to lay out - a legitimate style, not an error.
     *
     * Always INFO. Nothing failed: an empty batch is returned rather than the operation throwing.
     */
    GLYPH_RANGE_UNAVAILABLE,

    /**
     * A label layer declared `symbol-placement: line`, which this profile's label-candidate
     * layout does not implement, so that layer contributes no label candidates rather than being
     * laid out as point-anchored text.
     *
     * Only the label candidates are lost. As with [UNSUPPORTED_TEXT_CONSTRUCT], the layer keeps
     * its [LabelLayerDescriptor] and its raw MVT still acquires, so a consumer using
     * `labelLayerDescriptors`/`acquireLabelTiles` sees no change.
     *
     * `details` carries `layerIndex` and `layerIdDigest`, the same identity pair used by other
     * layer-exclusion diagnostics, locating which layer was excluded in the compiled style.
     *
     * Always INFO. Nothing failed: preparation continues without that layer's label candidates.
     */
    LINE_PLACEMENT_LABEL_EXCLUDED,

    /**
     * A place-name label layer produced no candidate for one or more of the features that
     * qualified for one, because a text property would not evaluate to a usable value, and the
     * batch was returned without them rather than failing.
     *
     * Reported once per layer per acquisition, whatever the cause and however many features were
     * affected. The name is deliberately neutral: this fires for any text layout or paint property
     * that evaluates to something unusable - a `text-size` that is not a finite number, a
     * `text-offset` that is not a pair of numbers, a `text-opacity` outside zero to one, a
     * negative halo value - and the emitting code cannot attribute it to one of those without
     * asserting a cause it does not know.
     *
     * `details` carries `layerIndex` and `layerIdDigest`, the same identity pair used by the other
     * layer-level label diagnostics, plus two counts: `candidateFeatures`, how many features
     * reached property evaluation, and `skippedFeatures`, how many of those produced no candidate.
     * The counts exist so that losing one feature is distinguishable from losing every one, which
     * a single boolean flag could not express.
     *
     * Both counts describe **this batch only**, across every requested tile in it.
     * `skippedFeatures == candidateFeatures` means this layer contributed nothing here; it does
     * not mean the style is broken, because the condition is frequently data-dependent and the
     * same layer can lose everything for one tile set and behave normally for the next.
     * `affectedTiles` names the requested tiles the skipped features came from.
     *
     * Always INFO. Nothing failed: acquisition continues and these features are simply absent from
     * the result.
     */
    LABEL_FEATURE_SKIPPED,
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
