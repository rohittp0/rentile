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
     * A style-requested icon could not be emitted for one or more features, while the rest of the
     * Output Tile or Label Candidate Batch was returned rather than failing.
     *
     * This covers both repaired Output Tile icon layers and icons coupled to retained label text.
     * A feature is skipped either because an icon construct/property cannot produce a usable icon
     * (`icon-size: "big"`, a negative `icon-halo-width`, an unsupported icon property, or a
     * data-driven `icon-offset` that is not a numeric pair) or because it names an icon absent from
     * the sprite atlas. `details` carries the discriminator: `candidateFeatures` is how many
     * features requested an icon, `skippedFeatures` how many emitted none, and
     * `skippedMissingSprite` how many of *those* named a missing sprite. The invalid-or-unsupported
     * property count is therefore `skippedFeatures` minus `skippedMissingSprite`; `layerIndex`
     * locates the layer in the compiled style.
     *
     * For Output Tiles this is reported once per layer per tile and all counts describe that tile.
     * For Label Candidates it is reported once per layer per requested batch: the counts aggregate
     * every requested tile, and `affectedTiles` identifies the tiles which lost an icon. In either
     * pipeline, `skippedFeatures == candidateFeatures` means only that this sample emitted none of
     * the requested icons; it does not by itself mean the whole style is broken. Causes are often
     * feature- or tile-dependent, so compare multiple representative samples before alerting.
     *
     * Always a WARNING. The containing result still succeeded.
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
     * A text-bearing vector symbol layer carried a construct this compatibility profile cannot compile -
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
     * A visible text-bearing vector symbol layer outside the place-name descriptor set supported
     * before 0.6.0 could not resolve or compile its vector source, so that newly admitted label
     * layer was excluded without making the previously valid style fail preparation.
     *
     * `details` carries `layerIndex`, `layerIdDigest`, and the stable Rentile error enum name in
     * `causeCode`; it never contains a source URL. Legacy place-name source failures remain strict
     * because those descriptors were already reachable before 0.6.0.
     *
     * Always INFO. No pre-existing capability failed.
     */
    LABEL_SOURCE_UNAVAILABLE,

    /**
     * Label candidates were requested for a prepared style whose `glyphs` URL template could not
     * be resolved, so an empty label-candidate batch was returned instead of failing. A style
     * that declares no `glyphs` key has no text to lay out - a legitimate style, not an error.
     *
     * Always INFO. Nothing failed: an empty batch is returned rather than the operation throwing.
     */
    GLYPH_RANGE_UNAVAILABLE,

    /**
     * Legacy compatibility code for a label layer whose line placement could not be compiled.
     * Version 0.6.0 implements `line` and `line-center`; this enum entry remains stable for old
     * diagnostics and for future failures that must preserve the same public classification.
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
     * A text-bearing vector symbol layer produced no candidate for one or more of the features that wanted
     * one, and the batch was returned without them rather than failing.
     *
     * Reported once per layer per acquisition, whatever the cause and however many features were
     * affected. The name is deliberately neutral because three unrelated conditions reach it, and
     * `details` is what tells them apart rather than the code or the message.
     *
     * `details` carries `layerIndex` and `layerIdDigest`, the same identity pair used by the other
     * layer-level label diagnostics, and four counts:
     *
     * - `candidateFeatures` - how many labels this layer wanted, the denominator for the rest.
     * - `skippedFeatures` - a text layout or paint property evaluated to something unusable: a
     *   `text-size` that is not a finite number, a `text-offset` that is not a pair of numbers, a
     *   `text-opacity` outside zero to one, a negative halo value, a `text-font` that is not a
     *   list of names. Which one is not reported, because attributing it would assert a cause the
     *   emitting code does not know.
     * - `skippedNoGlyphs` - the label's text resolved, but the acquired glyph atlas covers none of
     *   its codepoints, so it laid out to nothing.
     * - `skippedNonPointGeometry` - the feature geometry is empty, degenerate, or incompatible
     *   with the resolved point/line placement, so no geographic anchor can be produced.
     *
     * The three are counted apart rather than summed, because a consumer seeing no labels needs to
     * know which of them happened; they are strict subsets of `candidateFeatures` and share its
     * unit, one label meaning one anchor of one feature on one requested tile. A label whose
     * `text-size` resolves to zero or less is not among them and is not in `candidateFeatures`
     * either: that is a style asking for no visible text at this zoom, not a loss, and reporting
     * it would make every style that hides its labels by zoom look broken.
     *
     * Every count describes **this batch only**, across every requested tile in it. The three
     * summing to `candidateFeatures` means this layer contributed nothing here; it does not mean
     * the style is broken, because these conditions are data-dependent and the same layer can lose
     * everything for one tile set and behave normally for the next. Note that the denominator also
     * includes labels excluded by [COMPLEX_SCRIPT_LABEL_EXCLUDED], which reports its own count
     * separately. `affectedTiles` names the requested tiles the losses came from.
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
