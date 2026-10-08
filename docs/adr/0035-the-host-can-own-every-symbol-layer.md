# The host can own every symbol layer

> Supersedes, for `rentile-v1-host-symbols` only, the rule in [ADR 0024](0024-label-placement-belongs-to-the-consumer.md)
> that the Label closure is the visible *text-bearing* vector symbol layers, and the paragraph of
> [ADR 0026](0026-repaired-layers-degrade-and-author-intended-layers-fail.md) on the Label Candidate
> path, which assumes an icon either rides in the Output Tile or pairs with text. Both remain
> exactly as written for `rentile-v1`, which stays the default.

Under `rentile-v1` an icon reaches the screen one of two ways. An icon-only layer, and a text-and-icon
layer whose icon does not depend on its text, is drawn into the Output Tile; the second has its text
removed there and is also carried as a Paired Icon on its Label Candidate. An icon that does depend
on its text travels only as a Paired Icon. That split was the right default for a consumer that
draws no symbols of its own. It is the wrong one for a consumer that does.

Travel Animator draws labels in screen space and has to match what its previous Mapbox renderer
drew: every symbol layer - place names, POI icons and their names, road shields, river and water
names. Against that target the split fails in three ways that no amount of host work can repair:

- **A baked icon is ground texture.** It shrinks toward the horizon under a pitched camera, shears
  with bearing and is resampled with the tile - the reasons ADR 0024 gives for never baking text
  apply to icons unchanged. Mapbox draws them upright, constant-size and crisp.
- **A baked icon has lost its text.** The repaired layer's text survives only as a Label Candidate
  whose icon the host must then *not* draw, or it draws twice; and an icon-only POI layer has no
  candidate at all, so the host cannot collide its own labels against it.
- **An icon whose text is lost is lost too.** A candidate needs text, so a feature whose name is
  empty, in a script the profile cannot lay out, or missing from the glyph atlas yields nothing -
  where Mapbox still draws the marker.

## Decision

**A second compatibility profile, `CompatibilityPolicy.RentileV1HostSymbols`
(`rentile-v1-host-symbols`), hands every symbol layer to the host.** It is opt-in; `Default` remains
`RentileV1`, and nothing about `RentileV1` changes. Under it:

- **Output Tiles carry no symbol layer.** No icon draw layer is compiled, no symbol layer requires
  the sprite, and a vector source read only by symbol layers is never fetched for a tile. Background,
  fill and line patterns are unaffected. Every visible symbol layer reports `SYMBOL_LAYER_HOST_OWNED`.
- **Every visible vector symbol layer with meaningful text or a meaningful `icon-image` is a label
  layer.** A feature with an icon and no text yields an *icon-only candidate*: no glyphs, a zero-area
  box at the anchor, null `text` and `textSize`, and `textOptional = true`.
- **A lost text leaves its icon.** Empty text, an unsupported script, an unusable text property,
  missing glyphs, a text construct the profile cannot compile, and a style with no `glyphs` template
  at all each leave the icon-only candidate behind, counted as `textLostIconRetained`.
- **Nothing a symbol needs fails preparation.** An unresolvable sprite skips the icons it would
  have supplied (`ICON_FEATURE_SKIPPED`), and an unresolvable source excludes the layer
  (`LABEL_SOURCE_UNAVAILABLE`).

### Why a profile and not a flag on label acquisition

What is in the Output Tile is decided at preparation and is part of the tile's identity; label
acquisition cannot reach back into it. A profile is already the unit that decides what Rentile draws,
already folded into `PreparedStyle.digest` and so into every output and label key, and already
closed, so a host cannot ask for a combination nobody has tested. A rasterization flag on the style
input would have been all of that again under another name.

### Why the icon-only candidate's text half is inert rather than absent

`LabelCandidate` is a data class every consumer constructs, destructures and collides. Making its
text fields nullable would break every consumer to serve one profile. Instead the text half of an
icon-only candidate holds fixed values chosen so a host that forgets to check `glyphs` is harmless:
permission to overlap and to be ignored by placement, so it neither blocks nor is blocked; no
padding and a zero-area box; transparent, zero-opacity paint. The icon is placed and collided by
its own `LabelIconRef`, exactly as a Paired Icon already is.

### Why an uncompilable text construct keeps the icon

Under `rentile-v1` an unsupported text construct costs the layer its candidates and nothing else,
because the icon is in the tile. Here nothing else would draw it, so the layer is compiled a second
time with its text properties removed and contributes icon-only candidates, while
`UNSUPPORTED_TEXT_CONSTRUCT` still reports the text loss.

## Consequences

- **`rentile-v1` is byte-identical.** Its tiles, raw pixels, style digests, output keys, label
  candidates and diagnostics were compared against a pre-change probe and did not move, apart from
  the label keys, which advance for every profile because `LabelCandidate` gained fields in the
  same release (below).
- **One loss is accepted.** A symbol layer whose source is GeoJSON, or which names no
  `source-layer`, has no Label Tiles, so under this profile nothing draws it. It reports
  `SYMBOL_LAYER_HOST_OWNED` with `labelLayer = false` rather than disappearing silently, and the
  corpus gate counts such layers as an observation.
- **The corpus gate covers both profiles.** The host profile is derived from the committed
  `rentile-v1` Coverage Manifest - same styles, cases and thresholds - rather than committed beside
  it, so the rolling catalog cannot drift between two files. See `compatibility/README.md`.
- **The host now owns sprites for every icon.** Rentile still names images and does not publish a
  sprite atlas through the Label Candidate Batch; that is unchanged from ADR 0024.

## Related label-side decisions in the same release

Three further changes serve the same host and are recorded here because each constrains the others.

**Candidate identity.** `LabelCandidate` appends `featureId` (the MVT id as its 64 bits, null when
absent) and `text` (the evaluated text after expansion, `text-transform` and trimming). A line or
polygon feature is anchored separately in every tile it crosses, so position cannot identify it
across tiles; the feature id and text can.

**Size at the camera's fractional zoom.** Rentile evaluates `text-size` and `icon-size` at the
tile's integer zoom. `LabelCandidate.textSize` and `LabelIconRef.size` carry a `LabelSymbolSize`
from which `sizeAt(zoom)` reproduces Mapbox GL exactly: Mapbox's constant/source/camera/composite
classification, the pair of curve stops covering `[z, z + 1]`, the sizes there, and the curve's
interpolation, clamped. This is Mapbox GL JS `src/symbol/symbol_size.ts` and gl-native's
`SymbolSizeBinder`, which agree. It is **not** MapLibre GL JS after #8175 (August 2026), which
samples a camera curve at every stop and caps it at the layout size; the host replaces a Mapbox
renderer, so Mapbox is the reference. One shape serves all four kinds, per candidate, so a host never
branches on kind to be exact; camera sizes repeat per candidate, which is cheap.

**A label-side `text-field` override.** `LabelCandidateOptions.textFieldOverride` applies one
`text-field` expression to every label layer at acquisition - the host's label-language setting,
which its Mapbox renderer implemented by setting `text-field` on every symbol layer. Rewriting the
style JSON instead would change the prepared-style digest and re-key every Output Tile for a change
that touches no pixel of one. The override is therefore folded into the label request and content
keys only, compiled once per prepared style and override, and never into the style digest. Under
this profile it gives an icon-only layer text, as Mapbox would.

All three change `LabelCandidate`'s public fields, so the candidate request and content semantics
markers advance to `label-candidates-3` and `rentile-label-candidates-3` for every profile.
