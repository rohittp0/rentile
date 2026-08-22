# Report the glyph closure before fetching it

**Date:** 2026-08-22
**Status:** approved design, not yet planned
**Origin:** a handoff request from RenG (`com.rohittp.reng`), which consumes Rentile behind a
resource firewall that preregisters an exact URL for every resource the engine may fetch and fails
closed on anything else.

## Problem

RenG derives every basemap URL from the style document before acquisition begins, reproducing
Rentile's composition byte-for-byte to do it. Glyph ranges are the one resource class where that
fails, and they fail for a structural reason rather than an incidental one: the
`(fontStack, rangeStart)` set falls out of text inside decoded vector tiles, and decoding them is
Rentile's job, behind the firewall. RenG cannot know the URLs, so it cannot preregister them, so
every glyph fetch fails closed and no label can ever be drawn.

Rentile already computes exactly this list, internally, on the path in question:
`LabelCandidateAssembler.kt:487` sorts and de-duplicates it, and
`DefaultBasemapRasterizer.kt:574` consumes it. This design makes it observable between those two
points, without letting the two disagree.

The workarounds RenG measured are worse than the API. Pattern-matching one resource class carves
an exception into the single invariant its firewall rests on, and does so for the one class whose
URLs are influenced by tile content. Preregistering the whole space costs 17 x 256 = 4352 routes
for the worst style in its corpus, recurring per frame. A two-phase `CACHE_ONLY` probe does not
work, because `CACHE_ONLY` throws rather than reporting what it would have needed.

## Verified before designing

Against the code, not against the request.

- **The closure is exact and complete.** `GlyphResourceAcquirer.acquire` has exactly one call site
  (`DefaultBasemapRasterizer.kt:574`) and `resolveUrl` exactly one caller
  (`GlyphResourceAcquirer.kt:64`). The acquirer performs no fallback-stack, notdef, or
  measurement fetch of its own. `plan.requiredRanges` is the fetch list, not an estimate of it.
- **`text-font` is resolved before the closure.** `fontStackOf` (`LabelCandidateAssembler.kt:666`)
  evaluates `program.font` against the per-feature `StyleEvaluationContext`, and the closure stores
  the comma-joined resolved stack. A data-driven `match` over `literal` branches lands as its
  resolved branches, never as an expression.
- **Dedup is batch-wide.** `ranges` is a `Set` accumulated across every layer and every tile,
  sorted at `:487`. The claim in the `Api.kt:467` KDoc holds at the closure boundary too.
- **`ResourceAccessMode` never enters planning.** It decides which bytes you get and whether
  acquisition succeeds; given the same tile bytes the closure is identical.
- **The request's own sketch violates its own determinism constraint.**
  `acquireLabelCandidates` acquires and decodes its own vector tiles
  (`DefaultBasemapRasterizer.kt:545-560`). A stateless `glyphClosureFor(style, tiles, options)`
  would acquire them a second time, and under `NORMAL` revalidation or `RELOAD` the second
  acquisition can legitimately return different MVT bytes - an under-approximating closure, which
  is exactly the intermittent total outage the request calls fatal. There is also no
  decoded-resource cache: `VectorResourceAcquirer` re-decodes from raw bytes every time, so the
  sketch pays a full second MVT decode of the viewport per frame.
- **The glyphs template is a secret.** It is held as a `ProtectedResourceUrl` inside a
  `SecretContext` (`SecretContext.kt:8`, `StyleCompiler.kt:146`) because it can carry the provider
  credential, its `toString` is redacted, and it is cleared on rasterizer close. No public API
  re-emits it. The current corpus happens to carry no query string on glyph URLs
  (2026-08-18 measurement), but the design must not depend on that.
- **A plan is cheap to hold.** The internal `LabelCandidatePlan`
  (`LabelCandidateAssembler.kt:135`) retains `pending`, `requiredRanges`, the diagnostic tallies,
  `contentDigests` and `requestedTiles`. It does **not** retain the decoded `VectorResource`s;
  they are dropped when `plan()` returns. Heap cost is proportional to surviving label count, not
  to tile bytes.
- **`assemble` mutates plan state.** `featureSkips` is a plan field (`:142`) holding mutable
  tallies, and `:178-179` does `it.noGlyphs += 1; it.tiles += label.requestedTile` during layout.
  A second successful assembly over one plan would emit `LABEL_FEATURE_SKIPPED` with
  `skippedNoGlyphs` inflated, breaking ADR 0026's invariant that the loss counts are subsets of
  `candidateFeatures`. Retry after a *failed* glyph fetch is already safe, because
  `throwAcquisitionFailures` runs before `assemble`.
- **`ProtectedResourceUrl.canonicalUrl` is the redacted template** (`SecretContext.kt:31`), and
  `withRedactedAuthenticationQuery` (`ContentIdentity.kt:37`) replaces authentication query
  *values* while preserving names and everything else. So two copies of one template that differ
  only by credential compare equal, and a relative-versus-resolved difference compares unequal.
- **`docs/error-model.md` is six exception families behind.** It lists 11; `Exceptions.kt`
  declares 17. `BatchRenderException`, which `render()` throws, is among the missing.

## Locked decisions

1. **A two-phase handle, not a stateless second call.** The closure and the acquisition read the
   same frozen `requiredRanges` list, so they cannot diverge for any reason, including upstream
   tile churn. This mirrors the existing `prepareBatch` -> `render(batch)` grain. ADR 0028.
2. **The handle is a Label Candidate Plan**, pairing with **Label Candidate Batch** so the domain
   relationship is legible from the names. The existing internal `LabelCandidatePlan` is renamed
   `LabelAssembly`, which describes what it actually holds.
3. **Rentile composes the URLs; the caller supplies the template.** No credential is emitted, and
   percent-encoding, `{range}` formatting and substitution stay in one implementation shared with
   the fetch path. The template the caller passes is verified against Rentile's own.
4. **Exact, not over-approximated.** The request offered to accept a superset; it does not need
   one, and a superset would weaken the regression guard in the Coverage section.
5. **The plan is reusable, and `assemble` becomes pure.** Handles are reusable in this codebase -
   `render(batch, tiles)` takes a tile subset precisely so one batch can be rendered from
   repeatedly - so the mutation found above is removed rather than specified around.
6. **The one-shot signature survives unchanged.** `acquireLabelCandidates(style, tiles, mode)`
   keeps today's behaviour byte-for-byte, reimplemented over the two-phase pair.
7. **No lease machinery.** Per the memory finding above, a plan needs an owner identity and a
   closed flag, not `DefaultPreparedBatch`'s lease counting and `releaseResourcesIfUnused`.

## Public API

Added to `Api.kt`, beside `LabelCandidateBatch`.

```kotlin
/** One Glyph Range a [LabelCandidatePlan] will acquire. Identity only: no URL, no credential. */
public data class GlyphRangeRef(
    /**
     * Matches [LabelGlyphAtlasEntry.fontStackDigest], so a ref correlates with the atlas entries
     * it eventually produces. The raw font stack is deliberately absent: `text-font` may be
     * data-driven, so it can carry decoded feature-property bytes.
     */
    public val fontStackDigest: String,
    /** The `{range}` block start. */
    public val rangeStart: Int,
)

/**
 * A frozen Glyph Closure for one tile set, held between Label Tile acquisition and Glyph Range
 * acquisition. Not a Prepared Batch; see CONTEXT.md.
 *
 * Reusable: acquiring from one plan repeatedly yields identical batches.
 */
public interface LabelCandidatePlan : AutoCloseable {
    /** The de-duplicated tile set the plan was computed over, in (z, x, y) order. */
    public val tiles: List<TileId>

    /**
     * Exactly the Glyph Ranges [BasemapRasterizer.acquireLabelCandidates] will request from this
     * plan - not a superset and not an estimate. Sorted by resolved font stack then [rangeStart],
     * de-duplicated across every layer and tile in the batch, and stable across runs.
     */
    public val glyphClosure: List<GlyphRangeRef>

    /**
     * The URL of every entry in [glyphClosure], in the same order, composed by Rentile's own
     * substitution so a caller never re-derives it.
     *
     * [template] is the caller's copy of the style's `glyphs` value, resolved against the style's
     * base URI. Rentile holds its own copy but will not emit it, because that copy can carry the
     * provider credential. Instead it verifies the two agree and throws
     * [GlyphTemplateMismatchException] when they do not, so a relative reference passed
     * unresolved, a stale template, or another style's template fails here rather than as labels
     * that silently stop drawing.
     *
     * Returns an empty list, without verifying [template], when the style resolves no `glyphs`
     * template - such a plan will fetch nothing.
     */
    public fun glyphUrls(template: String): List<String>

    public val diagnostics: List<RenderDiagnostic>

    /** Idempotent, non-blocking, and non-throwing. */
    override fun close()
}
```

On `BasemapRasterizer`, beside the existing label methods:

```kotlin
/**
 * Acquires this tile set's Label Tiles, decodes and evaluates them, and freezes the Glyph Ranges
 * the batch will need - without acquiring any of them.
 *
 * A caller that must know its glyph URLs before they are fetched reads them from
 * [LabelCandidatePlan.glyphUrls] and then passes that same plan to [acquireLabelCandidates]. The
 * two read one frozen list, so the closure cannot under-approximate the acquisition that follows.
 */
public suspend fun planLabelCandidates(
    style: PreparedStyle,
    tiles: List<TileId>,
    resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
): LabelCandidatePlan

/** Acquires the plan's Glyph Closure and assembles the batch. Reuses the plan's access mode. */
public suspend fun acquireLabelCandidates(plan: LabelCandidatePlan): LabelCandidateBatch
```

`acquireLabelCandidates(style, tiles, resourceAccess)` remains, reimplemented as plan, acquire,
close-in-`finally` - the shape `render(style, tiles, ...)` already uses over `prepareBatch` and
`render(batch)` at `DefaultBasemapRasterizer.kt:660-673`.

Consumer shape:

```kotlin
rasterizer.planLabelCandidates(style, tiles).use { plan ->
    registry.preregister(plan.glyphUrls(glyphsTemplate))
    val batch = rasterizer.acquireLabelCandidates(plan)
}
```

## One composer, not two

`resolveUrl` and its `private` `percentEncodeFontStack` stay the single implementation of glyph URL
composition; `glyphUrls` calls `resolveUrl` per closure entry with the caller's template. Nothing
about the composition is re-stated in `Api.kt`, exposed as a token, or left to the caller as
arithmetic. A second copy of that encoding - in Rentile or in a consumer - is the failure this API
exists to prevent, and it fails closed and silently.

The template check is the other half. `ProtectedResourceUrl.canonicalUrl` is already the redacted
template, so `glyphUrls` compares `template.withRedactedAuthenticationQuery()` against it and
throws on mismatch. Neither template appears in the exception; a mismatch is reported as a fact,
not by echoing credential-bearing strings.

## Assembly becomes pure

`LabelCandidatePlan.assemble` currently mutates the tallies it was constructed with
(`LabelCandidateAssembler.kt:178-179`), so a second assembly over one plan double-counts
`skippedNoGlyphs`. Layout losses move into a local map inside `assemble`, merged with the
immutable tallies when diagnostics are built. That makes `assemble` a pure function of its inputs
and the public plan safely reusable, which is what `PreparedBatch` already promises for its own
handle.

The internal `LabelCandidatePlan` is renamed `LabelAssembly` to free the name for the public type.

## Lifecycle

`private class DefaultLabelCandidatePlan(owner, style, tiles, resourceAccess, assembly)` implements
`LabelCandidatePlan` with an owner identity and an `AtomicBoolean` closed flag.
`acquireLabelCandidates(plan)` resolves ownership through a new `requireOwnedLabelCandidatePlan`,
calls `ensureOpen()`, and reads the internal assembly into a local before doing anything else, so a
concurrent `close()` cannot pull state out from under an acquisition in flight.

Three new exceptions in `Exceptions.kt`, matching the per-handle pattern already there at
`:134-165`:

- `ForeignLabelCandidatePlanException` - a plan handle was passed to a rasterizer that did not
  create it.
- `LabelCandidatePlanClosedException` - a caller attempted acquisition from a plan after closing it.
- `GlyphTemplateMismatchException` - the template passed to `glyphUrls` is not the one this style
  resolves.

Rasterizer close is already handled: `planLabelCandidates` and `acquireLabelCandidates` both run
inside `operation { }`, which throws `RasterizerClosedException` once `closing` is set, and
`glyphsTemplate.resolve()` throws the same once the `SecretContext` is cleared.

## Determinism

The guarantee is **coupling**, not determinism across calls. The request asked for a closure that
is "deterministic for the same style, tiles and options"; that property is neither achievable nor
sufficient, because tile content can change upstream between any two calls and a stateless closure
would then disagree with the acquisition that follows it. Binding the closure to a plan makes
divergence structurally impossible rather than merely unlikely.

Within one plan, everything downstream is already deterministic: `requiredRanges` is sorted and
de-duplicated, atlas packing canonicalises on `(fontStackDigest, codepoint)`, and the batch content
key sorts the digests it folds in.

Public ordering is `requiredRanges` order, whose sort key is the raw font stack - which the public
type deliberately does not expose. Callers get a stable order they cannot re-derive, and
`glyphUrls` returns its list in that same order so the two can be zipped. That is adequate for
preregistration and is documented rather than repaired; changing the internal sort to
`(fontStackDigest, rangeStart)` would churn a working path for no gain, since nothing downstream
depends on the order.

## Limits and failure

`SafetyLimitException` for `maxGlyphRangesPerBatch` is thrown inside `plan()`, so it now surfaces
from `planLabelCandidates` - before the caller preregisters anything, rather than after. Same type,
same `limitName`, same `stage`. The stage is deliberately not re-labelled: a public diagnostic
field quietly changing meaning is worse than a slightly loose label.

`CACHE_ONLY` with a missing vector tile likewise now throws at plan time, which is the same
improvement.

`GLYPH_RANGE_UNAVAILABLE` becomes a planning fact. A style with no `glyphs` template yields a plan
with an empty `glyphClosure` and the diagnostic recorded to the sink once, and
`acquireLabelCandidates(plan)` returns today's `emptyBatch` unchanged. The per-layer tallies
(`LABEL_FEATURE_SKIPPED`, `COMPLEX_SCRIPT_LABEL_EXCLUDED`) stay in `assemble`, because
`skippedNoGlyphs` is not knowable until layout runs.

So `LabelCandidatePlan.diagnostics` is the style's diagnostics plus `GLYPH_RANGE_UNAVAILABLE` where
applicable, and `LabelCandidateBatch.diagnostics` is byte-identical to today for both the one-shot
and two-phase paths.

## Coverage

The load-bearing test is the request's own definition of done, and it is mechanisable: drive a full
acquisition through a recording transport and assert that the set of `GLYPH_RANGE` URLs requested
equals exactly the set composed from `plan.glyphClosure`. Equality, not containment - a superset
closure would pass a containment check while hiding a route that is never fetched, and an
under-approximation is what fails closed in production. This is the guard that stops a future
fallback-stack or notdef fetch from silently breaking a consumer's firewall.

Around it:

- **Encoding fidelity.** A data-driven `text-font` resolving to stacks containing a space, `#`,
  `?`, `../` and non-Latin bytes; assert `glyphUrls` reproduces `resolveUrl` exactly for each.
- **Template mismatch.** A relative `glyphs` reference passed unresolved, a template from another
  style, and a template differing only by credential value: the first two throw
  `GlyphTemplateMismatchException`, the third does not.
- **Reuse purity.** Acquire twice from one plan; assert both batches are equal, including every
  diagnostic count. This is the regression guard for the `assemble` mutation.
- **Dedup and order.** Two tiles sharing a range yield one entry; order is stable across runs.
- **Digest correlation.** Over a fixture whose every requested range decodes to at least one
  glyph, each `GlyphRangeRef.fontStackDigest` appears in the resulting `LabelGlyphAtlas.entries`.
  Scoped to a fixture deliberately: a provider may legitimately serve an empty range, which would
  make the same assertion flaky against the live corpus.
- **No-template style.** Empty closure, diagnostic recorded once, `emptyBatch` returned.
- **Over-limit style.** `SafetyLimitException` from `planLabelCandidates`.
- **Ownership.** Foreign plan, closed plan, closed rasterizer.
- **One-shot equivalence.** `acquireLabelCandidates(style, tiles, mode)` and the two-phase path
  produce the same `contentKey` and the same candidate list.

The corpus gate already records the distinct glyph-range count per style
(`compatibility/README.md:19`), so extending it to assert closure exactness across the rolling
corpus is nearly free and proves the property against 34 real styles rather than fixtures.

## Documentation

Already written, during the grill:

- **ADR 0028** - freeze the glyph closure in a label candidate plan.
- **CONTEXT.md** gained **Label Tile**, **Glyph Closure** and **Label Candidate Plan**, and the
  flagged ambiguity was rewritten rather than deleted: its conclusion holds, and only its middle
  clause was too strong. **Source Tile** now avoids **Label Tile** reciprocally.

Still to write, during implementation:

- **docs/error-model.md** gains this design's three exceptions, and backfills the six that have
  shipped undocumented - `ForeignPreparedBatchException`, `InvalidTileIdException`,
  `TileNotInPreparedBatchException`, `TileSubstitutionLimitException`, `TileSubstitutionException`
  and `BatchRenderException`. The doc presents itself as enumerating the families, so a consumer
  building error handling from it today does not know `render()` throws `BatchRenderException`.
- **ADR 0024**'s closing paragraph needs the same qualification CONTEXT.md's ambiguity got.
- The published site (`docs/rendering.html`, `docs/kmp.html`, `docs/index.html`) documents no label
  API today and needs no change.

## Release

Additive to `Api.kt`, but adding abstract members to the public `BasemapRasterizer` interface is
source-breaking for anyone implementing it, such as a consumer's test double. The label APIs
themselves arrived the same way and took a deliberate minor bump - `f865320` cut `0.3.0` - so this
asks for `VERSION_NAME=0.5.0` rather than the automatic patch advance described in CONTEXT.md's
Distribution section.

## Out of scope

- Drawing labels. ADR 0024 stands.
- Any change to glyph URL composition, atlas packing, or `LabelCandidate`.
- A public sprite atlas; RenG resolves `LabelIconRef.imageName` itself.
- Exposing the glyphs template, a composed glyph URL, or the raw font stack.
- Folding labels into `prepareBatch`.

## Open risks

- **RenG must hold the plan across its preregistration step.** If its firewall registry is bound to
  a preparation invocation that cannot span the two calls, the handle shape is wrong for it and the
  callback variant considered during design becomes the fallback. This is the one assumption about
  RenG's side that this design cannot verify from here, and it should be confirmed before
  implementation rather than after.
- **Holding a plan holds labels resident.** Cheap per the memory finding, but a consumer that plans
  many viewports without closing them accumulates `PendingLabel` lists. `AutoCloseable` and the
  `use { }` idiom in the KDoc are the mitigation; no limit is imposed, consistent with how
  `PreparedBatch` is treated.
- **Ordering is stable but not re-derivable** from the exposed type. Accepted, documented; the
  `glyphUrls` list is order-aligned with `glyphClosure`, which is what callers actually need.
- **The template check assumes the caller resolved relative references the same way Rentile did.**
  It proves the two strings agree, not that either is right. A caller that resolves a relative
  `glyphs` against a different base URI than the style's own gets a clean mismatch error rather
  than a wrong URL, which is the intended outcome.

## References

- `docs/superpowers/specs/2026-08-18-label-candidates-design.md` - the design this extends.
- ADR 0008 - freeze the resource closure before drawing.
- ADR 0024 - label placement belongs to the consumer.
- ADR 0025 - lay out labels from glyph metrics.
- ADR 0026 - repaired layers degrade and author-intended layers fail.
- `CONTEXT.md` - `Resource Closure`, `Glyph Range`, `Label Candidate Batch`, and the flagged
  ambiguity this design amends.
