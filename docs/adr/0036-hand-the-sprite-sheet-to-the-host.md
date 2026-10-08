# Hand the sprite sheet to the host

[ADR 0024](0024-label-placement-belongs-to-the-consumer.md) left sprite imagery with the consumer:
a Paired Icon names its sprite through `LabelIconRef.imageName`, and "sprite pixels and SDF/RGBA
metadata remain consumer-owned". [ADR 0013](0013-use-one-explicit-output-pixel-size.md) kept
sprite-entry pixel ratios as internal atlas metadata. Neither rule recorded a technical bar. They
drew the line of ownership for the first consumer as it then was, which resolved icons through a
map SDK that fetched its own sprite.

That consumer now draws every base-map label and point-of-interest icon itself, in screen space and
at device resolution. To do so it needs the sheet, the entry geometry, the SDF flag and the
provider's `@2x` sheet. Left consumer-owned, all of that is a second implementation of work
Rentile already does on the preparation path: resolving the style's `sprite` against its base URI,
keeping the credential in that URL out of logs and keys, fetching through the host's transport
while bypassing Rentile's raw store and its revalidation, and parsing and validating the sheet's
JSON. A second copy would drift from the first, and the first is the one every `LabelIconRef` size
is computed from.

[ADR 0029](0029-expose-decoded-dem-texels.md) is the precedent: it handed over a decode Rentile
already performed rather than widening Rentile's remit. This does the same for the sprite sheet.
Placement, collision and drawing remain the consumer's, exactly as ADR 0024 says.

## Decision

**`BasemapRasterizer.acquireSpriteAtlas(style, pixelRatio = 1, resourceAccess = NORMAL)` returns
the style's sheet as a `SpriteAtlas`, or null when the style declares no `sprite`.** A `SpriteAtlas`
carries the provider's PNG bytes verbatim, the sheet's dimensions, the ratio that was asked for, a
content key, and every entry by name as a `SpriteImageEntry`: position, size, the entry's own
`pixelRatio`, `sdf`, and its `stretchX`, `stretchY` and `content`. A `sprite` that is declared but
cannot be acquired, the multi-sprite array form or a relative reference with no base URI, throws
`StylePreparationException`. Null means "no sprite", and must not also mean "a sprite Rentile
cannot fetch".

**Ratio `1` is the sheet the style was prepared with.** When preparation resolved a sheet, ratio `1`
returns it without another request, whatever `resourceAccess` says. That sheet is the one every
`LabelIconRef.width` and `height` was derived from. Re-acquiring it could hand back a revision the
store refreshed in the background since, whose entries need not agree with the candidates.
Preparation resolves a sheet only for a style with an icon or pattern layer, so for any other style
ratio `1` acquires the sheet on demand.

**Ratio `2` is the provider's `@2x` sheet, acquired through the same path.** `<sprite>@2x.json` and
`<sprite>@2x.png` are built by the same suffix insertion that builds `.json` and `.png`, before any
query, so a credential in the sprite URL survives. They go through the same raw store,
stale-while-revalidate acquirer, shape checks, byte limits and single flight. The flight key
carries the ratio and the access mode, so neither can join a flight whose answer it would have to
reject. The request is all-or-error: a missing or malformed `@2x` sheet raises its typed failure and
never falls back to the 1x sheet, which would quietly halve the texels the host asked for. Only `1`
and `2` are accepted, because those are the sheets providers publish.

**The access mode is honoured,** for the reason `GlyphResourceAcquirer` honours it: the public
signature offers one, and a documented cache-only that still reached the network would break
offline export. `CACHE_ONLY` serves the stored entry and schedules no refresh. `RELOAD` fetches and
replaces. `CACHE_SUBSTITUTE_THEN_NETWORK` is `NORMAL`. Preparation still takes no access mode and
is unchanged.

**Candidate geometry and every label key stay free of the ratio.** A `LabelIconRef` is sized in
style pixels at ratio one from the prepared 1x sheet and already includes `icon-size`, so a host
draws `entries[imageName]` from whichever sheet it holds into a quad of `width * r` by `height * r`
device pixels, `r` being its own device ratio. A 2x sheet only doubles the texels per style pixel.
This is ADR 0030's reason for keeping output size out of the label keys, applied to sprite ratio:
a candidate does not depend on it, so no key carries it. The Label Candidate Batch still transports
no sheet. A host acquires one per style and ratio, not one per batch.

**`stretchX`, `stretchY` and `content` are parsed, not rejected.** Before this, any entry carrying
one failed the whole sheet. A style whose icon or pattern layer required the sprite did not prepare
at all. A style whose label layers only desired it lost every paired icon to
`ICON_FEATURE_SKIPPED` and every repaired icon layer to `TEXT_COUPLED_ICON_LAYER_EXCLUDED`. That
punished a whole sheet for metadata most of its entries do not carry. The metadata is now checked
as strictly as every other entry field, using MapLibre's own image validation: each stretch span is
an ordered, non-overlapping `[from, to]` inside the image, and a content box is four edges inside
it. A bad value still fails the sheet. A host applying `icon-text-fit` reads the values from
`SpriteImageEntry`.

**The Output Tile path ignores that metadata, and ignoring it is correct rather than degraded.** It
draws every icon and every pattern at the image's own aspect: `icon-size` scales uniformly, and an
icon whose size depends on its text is excluded from the PNG by
[ADR 0026](0026-repaired-layers-degrade-and-author-intended-layers-fail.md). Under the style
specification, stretch zones and a content box act only when `icon-text-fit` resizes an icon, which
never happens on that path. `SpriteAtlasAcquisitionTest` renders one icon tile twice, from a sheet
without the metadata and from the same sheet with it on the drawn entry, and the PNGs are
byte-identical.

## The encoding a host samples

The PNG is the provider's, byte for byte, and Rentile does not re-encode it. Its colour type, gamma
chunks and compression are the provider's, and PNG defines alpha as straight, so a host decodes it
to unpremultiplied RGBA. Rentile decodes nothing for the host. The image is always PNG, because
acquisition checks the signature. The motive ADR 0029 had for handing over decoded pixels, a
container hosts cannot be expected to decode, does not arise. And a decoded 2x sheet of 2048 px
would be 16 MiB held for a host that may prefer to let its GPU path decode.

An `sdf: true` entry keeps its signed distance in the alpha channel and nowhere else. MapLibre's SDF
shader, shared by icons and glyphs, puts the edge at alpha `0.75`, which is `192/255`, and moves a
halo's outer edge by `1/8` of the alpha range per unit of `halo-width / icon-size` (`SDF_PX = 8`).
The colour channels carry nothing. In MapTiler's SDF sheets they are black: measured on 2026-10-07
over the Outdoor sheet at both ratios, every SDF pixel with non-zero alpha had RGB `0,0,0`.
Rentile's glyph atlas uses the same alpha encoding with its colour channels fixed at white. A host
that samples alpha alone therefore has one rule for both textures and is indifferent to the
difference.

Each entry carries its own `pixelRatio`, and that is the value a host divides by.
`SpriteAtlas.pixelRatio` only records which sheet was asked for. On 2026-10-07, 7 of the 32 catalog
styles that declare a sprite served their 1x sheet in answer to `@2x`, entries at ratio `1`
included.

`SpriteAtlas.contentKey` answers "must I re-upload this texture?". It covers the JSON and PNG bytes
through the same content digest the style digest folds in, together with a parsing-semantics marker
(`rentile-sprite-atlas-1`). It omits the requested ratio, because identical bytes are one texture
whichever request returned them.

## Consequences

- **This supersedes part of ADR 0024's paired-icon paragraph.** Sprite pixels and SDF/RGBA metadata
  are no longer consumer-owned resources Rentile declines to publish. They are published through
  `acquireSpriteAtlas`. The rest of that ADR stands: the batch carries no sheet, and placement,
  collision, fit and drawing are the consumer's.
- **This supersedes ADR 0013's "sprite-entry pixel ratios remain internal atlas metadata".** They are
  public on `SpriteImageEntry`. Nothing about `outputSizePx` changes.
- **No style that prepared or rendered before fails now, and no key serves stale output.** ADR 0026's
  governing rule holds. The only newly accepted inputs are sheets that failed before. A style whose
  *desired* sheet carries the metadata used to prepare without it. It now resolves the sheet, draws
  its repaired icons and emits its paired icons. Its style digest moves too, because the digest
  folds in the prepared sheet's content digest, which used to be empty for it. So every Output Tile
  and label key of exactly those styles changes by itself, and no renderer or label marker needed
  bumping. The rolling corpus is not among them. On 2026-10-07 none of the 32 sprite-declaring
  catalog styles had a 1x or `@2x` entry with `stretchX`, `stretchY` or `content`.
- **`BasemapRasterizer` gains a member with a default body** that throws
  `UnsupportedOperationException`, so an implementer written before it, such as a consumer's test
  fake, keeps compiling. Returning null by default would have claimed that every such fake's style
  had no sprite.
- **A host holding a 2x sheet holds a second texture.** For the catalog's largest `@2x` sheet,
  2048x2048, that is 16 MiB once decoded. The cost is the host's, and is incurred only by a host
  that asks.
