# Label Candidates for host-owned label rendering

**Date:** 2026-08-18
**Status:** approved design, not yet planned
**Origin:** a handoff request from RenG (`com.rohittp.reng`), a Kotlin Multiplatform 3D renderer that draws worlds on Rentile basemap tiles and needs place-name labels it can position itself.

## Problem

Rentile draws no map text. That is deliberate — `StyleCompiler.classifySymbol` excludes text-only symbol layers, strips optional text from icon layers, and excludes text-coupled icon layers entirely — and ADR 0024 records why it must stay that way for a pitched-camera consumer.

Rentile already offers an escape hatch: `labelLayerDescriptors(PreparedStyle)` and `acquireLabelTiles(...)` hand back sanitized layer JSON and validated MVT bytes. A consumer using it must write its own protobuf reader and its own style-expression evaluator, duplicating two things Rentile already does well. RenG will not do that; it has one production dependency and a policy against a protobuf runtime.

This design moves the seam without moving the boundary: Rentile decodes, evaluates and lays out; the consumer still decides where every label lands.

## Locked decisions

1. **Scope stays place names.** `PLACE_NAME_SOURCE_LAYERS` continues to gate which layers qualify. Road, point-of-interest, water, terrain and protected-area naming is out, as `CompiledStyle.kt` already documents. Widening is a future decision, recorded in `CONTEXT.md`.
2. **Layout comes from glyph metrics**, not platform text shaping. ADR 0025.
3. **The `TEXT_COUPLED_ICON_LAYER_EXCLUDED` icon restoration ships first**, as its own release, so a rendered-output change is attributable to one small diff.
4. **The API mirrors the existing auxiliary acquisitions** — `acquireLabelTiles` and `acquireTerrainTiles` — rather than introducing a prepared-batch lifecycle.
5. **Candidates carry geometry and per-feature scalars; colours live on a per-layer record.**

## Evidence

Measured against all 34 rolling-corpus styles on 2026-08-18.

- **Every style has a `glyphs` template** on `api.maptiler.com` carrying both `{fontstack}` and `{range}`. Only 2 of 34 lack a `sprite`; none lack glyphs. The central assumption is universal, not merely plausible.
- Those glyph URLs carry **no query string** in this corpus, so no credential decoration is required here — acquisition still routes through `withRedactedAuthenticationQuery` because another provider's would.
- **33 of 34 styles** contain place-name text layers: **265 layers** in total.
- Property drivenness across those 265 layers:

  | Property | constant | zoom-driven | feature-driven |
  |---|---:|---:|---:|
  | `text-color` | 226 | 30 | 0 |
  | `text-halo-color` | 224 | 11 | 0 |
  | `text-halo-width` | 222 | 11 | 2 |
  | `text-size` | 33 | 158 | 72 |
  | `text-opacity` | 23 | 0 | 49 |
  | `symbol-sort-key` | 0 | 0 | 206 |

  Colours are never feature-driven; sort key always is. That determines the split in decision 5.
- **`text-field` expression operators actually used:** `get`, `coalesce`, legacy `{token}`, `concat`, `case`, `all`, `has`, `is-supported-script`, `step`, `zoom`. Rentile's `StyleExpression` already implements all but `concat` and `is-supported-script`. `format`, `to-string`, `upcase` and `downcase` do not appear.
- **62 distinct font stacks**, every one ending in a Noto Sans fallback.
- Adding Tokyo and Cairo coverage cases **destabilises nothing**: a full corpus run with them added passed 34/34 styles, with all 136 new-case tile rows rendered. A baseline run the same day failed 6 styles on transient sprite-transport exceptions that did not recur; CI is green.

## Public API

Added to `Api.kt` and to `BasemapRasterizer`, beside the terrain pair.

```kotlin
/** One block of 256 codepoints of one font stack, as SDF bitmaps packed into the batch atlas. */
public data class LabelGlyphEntry(
    public val fontStackDigest: String,
    public val codepoint: Int,
    public val x: Int, public val y: Int,
    public val width: Int, public val height: Int,
    public val left: Int, public val top: Int,
    public val advance: Int,
)

/** One texture the consumer uploads once per distinct [contentKey]. */
public data class LabelGlyphAtlas(
    public val pngBytes: ByteArray,
    public val width: Int,
    public val height: Int,
    public val contentKey: String,
    public val entries: List<LabelGlyphEntry>,
)

/** A glyph quad in label-local coordinates, referencing [LabelGlyphAtlas.entries] by index. */
public data class LabelGlyphQuad(
    public val entryIndex: Int,
    public val x: Double, public val y: Double,
    public val scale: Double,
)

/** Label-local bounds, before any projection, for the consumer's screen-space collision. */
public data class LabelBox(
    public val left: Double, public val top: Double,
    public val right: Double, public val bottom: Double,
)

/**
 * Paint resolved for one layer at one requested output zoom with no feature context.
 * One entry per (layer, zoom) pair present in the batch; [LabelCandidate.layerStyleIndex]
 * selects the entry matching that candidate's own `requestedTile.z`.
 */
public data class LabelLayerStyle(
    public val layerId: String,
    public val zoom: Int,
    public val priority: Int,
    public val color: Int,
    public val haloColor: Int,
)

/** The sprite the style pairs with this label, indexing the atlas Rentile already builds. */
public data class LabelIconRef(
    public val imageName: String,
    public val width: Double, public val height: Double,
    public val offsetX: Double, public val offsetY: Double,
)

/**
 * One Label decoded, evaluated and laid out, but not positioned on screen and not
 * resolved against any other Label. Nothing here is in screen coordinates.
 */
public data class LabelCandidate(
    public val layerStyleIndex: Int,
    public val requestedTile: TileId,
    public val sourceTile: TileId,
    public val longitude: Double,
    public val latitude: Double,
    public val glyphs: List<LabelGlyphQuad>,
    public val boundingBox: LabelBox,
    public val icon: LabelIconRef?,
    public val allowOverlap: Boolean,
    public val ignorePlacement: Boolean,
    public val padding: Double,
    public val sortKey: Double,
    public val opacity: Double,
    public val haloWidth: Double,
    public val haloBlur: Double,
)

/** The immutable result of one Label acquisition. Not a Prepared Batch; see CONTEXT.md. */
public data class LabelCandidateBatch(
    public val candidates: List<LabelCandidate>,
    public val layerStyles: List<LabelLayerStyle>,
    public val atlas: LabelGlyphAtlas,
    public val contentKey: String,
    public val diagnostics: List<RenderDiagnostic>,
)
```

On `BasemapRasterizer`:

```kotlin
/** Identity for a Label acquisition, available before any network. Omits the glyph closure. */
public fun labelCandidateRequestKey(style: PreparedStyle, tiles: List<TileId>): String

/** All-or-error validated Label acquisition. Tile substitution is deliberately not applied. */
public suspend fun acquireLabelCandidates(
    style: PreparedStyle,
    tiles: List<TileId>,
    resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
): LabelCandidateBatch
```

`ByteArray` members follow the existing `ValidatedMvtTile` convention: defensive copies, and `toString` that does not dump bytes.

### Three keys, three questions

Each answers something the others cannot, following the request-key/content-key precedent `outputRequestKey` already documents.

| Key | Available | Answers |
|---|---|---|
| `labelCandidateRequestKey` | before any network | must I fetch at all? |
| `LabelCandidateBatch.contentKey` | after acquisition | are my cached candidates still valid? |
| `LabelGlyphAtlas.contentKey` | after acquisition | must I re-upload the texture? |

The request key covers style identity, tile identities and a label-semantics version, and explicitly omits credentials, validators and the glyph closure — because which Glyph Ranges a tile set needs is not knowable until its features are decoded. The batch content key covers the resolved glyph-range digests and the MVT content digests.

## Pipeline

New `internal/glyph/GlyphResourceAcquirer.kt`, modelled on `SpriteResourceAcquirer`: `SingleFlight` keyed on the redacted URL digest, going through the injected `ResourceTransport` and `RawResourceStore` under a new `ResourceClass.GLYPH_RANGE`.

One structural difference from sprites, and it is the reason labels cannot join `prepareBatch`. Sprites resolve during `prepare`, because a style names exactly one sprite. Glyphs cannot: the required codepoints depend on decoded feature properties. So `prepare` gains only the resolved, credential-stripped `glyphs` template and the compiled text program per label layer; acquisition drives the glyph fetch.

`acquireLabelCandidates` then:

1. Validates tiles against the policy and resolves label sources via the existing `sampleFor` path, exactly as `acquireLabelTiles` does.
2. Acquires and decodes MVT through `vectorAcquirer`.
3. Evaluates each qualifying feature's text program, producing the final string, font stack, size and layout properties.
4. Collects the required `(fontStack, range)` set, then acquires those Glyph Ranges concurrently under the work coordinator.
5. Lays out each label, packs the atlas, and assembles the batch.

New proto at `kmp/src/commonMain/proto/glyphs.proto`, generated by the Wire plugin already configured for that `srcDir`. Schema is the standard `glyphs`/`fontstack`/`glyph` triple: id, SDF bitmap, width, height, left, top, advance.

## Text compilation

`text-field` is currently never compiled — `hasMeaningfulText` only checks that it is non-empty. Compiling it requires:

- **`concat`** and **`is-supported-script`** added to `StyleExpression`. Nothing else; the corpus uses no other missing operator.
- **Legacy `{token}` expansion**, reusing the `ICON_TOKEN` logic that already serves `icon-image`. 82 corpus layers need it.
- **Layout properties**: `text-font`, `text-size`, `text-anchor`, `text-offset`, `text-justify`, `text-max-width`, `text-letter-spacing`, `text-line-height`, `text-transform`, `text-padding`, `text-allow-overlap`, `text-overlap`, `text-ignore-placement`, `symbol-sort-key`, and paint's `text-color`, `text-opacity`, `text-translate` and `text-halo-*`.

Anything outside that set keeps Rentile's established behaviour: exclude the layer with a diagnostic rather than guess. That is enforced by an explicit allowlist mirroring `compileIconLayer`'s, added on 2026-08-19 after reading the known properties and ignoring the rest was found to produce wrong output rather than no output — `text-variable-anchor` with `text-radial-offset` yielded a label centred with no offset, and `text-writing-mode: ["vertical"]` yielded horizontal CJK.

Three properties resolved to something other than exclusion when the corpus was measured against that allowlist:

- **`text-overlap` is honoured** as the modern spelling of `text-allow-overlap`, and wins when both appear. `always` allows; `never` and `cooperative` do not, because cooperative is a negotiation between two colliding symbols during placement and placement belongs to the consumer here.
- **`text-keep-upright` is allowed and ignored**, because it governs orientation along a line and line placement is excluded outright.
- **`text-translate` is carried** on `LabelCandidate` as `translateX`/`translateY`, in pixels and unscaled by `text-size` — unlike the em-based `text-offset`. `text-translate-anchor` stays excluded: Rentile has no camera to resolve `viewport` against.

`symbol-avoid-edges`, `symbol-spacing` and `symbol-z-order` are allowed and not honoured, being placement and draw-order concerns ADR 0024 assigns to the consumer.

The governing rule is that every property is honoured, ignored for a stated reason, or excluded with a diagnostic — never silently dropped.

## Layout and script handling

Layout accumulates advances, breaks lines at `text-max-width` on word boundaries, applies `text-letter-spacing` and `text-line-height`, justifies per `text-justify`, and anchors per `text-anchor` and `text-offset`. Glyph scale is `text-size / 24`, the SDF em size. No `Typeface`, no `FontMgr`, no platform text stack, so geometry is identical on all published targets.

Scripts requiring bidirectional reordering or contextual joining cannot be laid out this way. Two mechanisms handle them, in order:

1. **`is-supported-script` returns false** for those scripts. Eleven corpus layers already branch on it and will select their own fallback — typically `name:latin` — so the label survives with style-authored text. This is why implementing the operator matters more than its rarity suggests.
2. **Exclusion with `COMPLEX_SCRIPT_LABEL_EXCLUDED`** for text that still resolves to such a script. Excluded rather than laid out wrongly, per ADR 0025.

Under place-name scope every qualifying source layer is point geometry, so `symbol-placement: line` never applies and line layout is not implemented. A place layer declaring line placement is excluded with `LINE_PLACEMENT_LABEL_EXCLUDED`.

## Determinism

Two ways the same feature can appear more than once. One is spurious and is eliminated exactly; the other is intended and is kept. Both are governed by one rule, not two.

- **Tile buffers and overzoom** are both a question of whether an anchor falls inside the *requested* tile's own window of the source tile: `point * childScale - (childX, childY) * extent` inside `[0, extent)`, the same mapping `VectorTileSample.sourceCoordinateToOutputPixels` uses to place pixels. A candidate is emitted only when its anchor falls inside that window. When `childScale == 1` — every non-overzoom case — the window is the whole source tile, so the rule reduces exactly to the anchor falling inside `[0, extent)` of the source tile, which is what removes buffer duplicates: exactly, not by proximity. When several requested tiles share one source tile (overzoom), the same window instead selects the single child whose bounds contain the anchor, so the feature is attributed to exactly one requested tile per zoom rather than to every child sharing that source.
- **Repetition across requested zooms** is intended, not a duplicate: zoom-driven paint resolves at `requestedTile.z`, so the same feature legitimately yields differently styled candidates at different requested zooms. Candidates are emitted per *requested* tile, carrying both identities exactly as `ValidatedMvtTile` does, and `acquireLabelTiles` already returns one tile per requested tile when they share a source.

Attribution must not depend on which other tiles the caller asked for in the same call: a key derived from the group of requested tiles sharing a source would hand the label to whichever tile sorted first, so panning a viewport would move a label between tiles and a consumer holding per-tile lists across frames would draw it twice. It would also wrongly collapse world copies of the same tile, since `sampleFor` canonicalises `x` while `validateTile` bounds only `z` and `y` — `TileId(1,-1,0)` and `TileId(1,1,0)` share a source tile and are both legitimate, distinct requests.

Ordering is (style layer order, requested tile, source tile, feature index). Atlas packing is deterministic shelf packing ordered by (font-stack digest, codepoint), so `LabelGlyphAtlas.contentKey` depends only on the glyph set and not on tile order. Two runs over the same style and tiles produce identical candidates in identical order — required, because consumer-side collision would otherwise flicker between frames.

## Limits and failure

`ResourceLimits` gains:

- `maxGlyphRangeBytes` — default 1 MiB; a range is small.
- `maxGlyphRangesPerBatch` — the ceiling that matters. Latin viewports touch two or three ranges per stack; CJK place names can pull dozens, across 62 distinct corpus font stacks. Without it a Tokyo viewport quietly fans out into a hundred requests.

Exceeding either raises `SafetyLimitException` with the named limit, matching `SpriteResourceAcquirer`. Acquisition is all-or-error with no tile substitution, raising `ResourceAcquisitionException` carrying `ResourceClass.GLYPH_RANGE`. `CancellationException` propagates unwrapped.

A style with no `glyphs` key yields an empty batch and a diagnostic, not a failure. Existing consumers that never call the new API see no behaviour change, no new configuration and no new network traffic.

New diagnostic codes: `COMPLEX_SCRIPT_LABEL_EXCLUDED`, `UNSUPPORTED_TEXT_CONSTRUCT`, `GLYPH_RANGE_UNAVAILABLE`, `LINE_PLACEMENT_LABEL_EXCLUDED`, and `LABEL_FEATURE_SKIPPED`. The fifth was added under ADR 0026's degrade posture — repaired, newly-reachable work degrades with a diagnostic rather than failing the whole batch — and reports, once per layer per acquisition, the per-cause counts of labels a layer wanted but did not get: an unusable layout or paint value, an acquired glyph atlas that covers none of the label's codepoints, or non-point geometry.

## Coverage

`rentile-v1-coverage.json` gains a `label-candidate` capability and two cases, `tokyo-cjk-dense` and `cairo-rtl`, each a z14 and a z16 tile. Both are already proven to render across all 34 styles.

The corpus gate extends narrowly. It acquires candidates for three cases per style — the `new-york-zoom-ladder` Latin case, plus `tokyo-cjk-dense` and `cairo-rtl` — not for every case at every zoom, because label correctness varies by geography and script rather than by zoom, and the publish gate is already a 28-minute job. It asserts that acquisition succeeds, that Tokyo stays inside the range ceiling, and that Cairo produces either a style-authored Latin fallback or the complex-script diagnostic, never garbage.

`CONTEXT.md`'s **Profile-Complete Rendering** has been updated to cover Label preparation, so the glossary does not go stale against the gate.

Unit coverage: determinism over repeated runs, buffer-duplicate rejection, the script gate in both directions, `is-supported-script` selecting a fallback, request-key credential-freedom, and both limit ceilings.

## Release sequence

Two releases, in order.

1. **Icon restoration — shipped as `0.2.0` on 2026-08-19.** Retaining the icon turned out to require far more than restoring it: these layers had never been compiled, so making them reachable exposed unsupported constructs, an unfetched sprite, an unfetched vector source, and an unfetched render-time tileset, each of which had to degrade rather than fail a style that previously worked. It became a minor rather than a patch because the degraded paths need two new public `DiagnosticCode` entries. See [ADR 0026](../../adr/0026-repaired-layers-degrade-and-author-intended-layers-fail.md).
2. **Label Candidates.** Purely additive. `VERSION_NAME` in the root `gradle.properties` must be set above every published version to cut a minor; the default patch-advance will not do it (ADR 0023). With `0.2.0` published, that is **`0.3.0`** — not the `0.1.6` the originating RenG handoff assumed.

## Documentation

- ADR 0024 — label placement belongs to the consumer.
- ADR 0025 — lay out labels from glyph metrics.
- ADR 0026 — repaired layers degrade and author-intended layers fail (written during release 1).
- `CONTEXT.md` — `Label`, `Label Candidate`, `Label Candidate Batch`, `Glyph Range`; `Raw Resource` extended to admit glyph bytes; `Profile-Complete Rendering` extended to preparation; three flagged ambiguities.
- `Api.kt:306` — the `labelLayerDescriptors` KDoc claims "visible text-symbol layers" but returns only place-name layers. Correct it.
- `instructions.md` — the "do not route labels through Rentile" guidance predates the shipped place-name seam. Reconcile it.

## Out of scope

- Drawing text into an Output Tile. ADR 0024.
- Screen-space collision, occlusion, depth and fade. The consumer's, by construction.
- Changing `acquireLabelTiles`. It stays for consumers that want raw MVT.
- Line-placed text, and any source layer outside `PLACE_NAME_SOURCE_LAYERS`.

## Open risks

- **Glyph atlas size for dense CJK viewports** is bounded by `maxGlyphRangesPerBatch`, but the right default is unmeasured. Measure a Tokyo z14 viewport during implementation and set the default from that, not from a guess.
- **62 distinct font stacks** means a style switching stacks by zoom multiplies range fetches. The single-flight key is per URL, so stacks shared across styles are fetched once per process; verify that holds in the corpus gate.
- **`text-halo-width` and `text-halo-blur` are feature-driven in 2 of 265 layers.** They sit on the candidate rather than the layer record, which costs two doubles per candidate to avoid excluding two layers. Revisit if candidate volume proves to be the memory constraint.
