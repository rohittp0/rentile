# Describe style metadata for hosts that never hold the style

A host that prepares `StyleInput.Remote` never sees the style document; it learns about a style
only through Rentile's descriptors (`labelLayerDescriptors`, `terrainSourceDescriptor`,
`groundRadianceDescriptor`, `hasRasterImagery`, ...). A host deciding whether to request Material
Coverage (ADR 0042), or reading a root renderer flag, needs the style's `metadata` before its first
render. `BasemapRasterizer.styleMetadataDescriptor(style)` returns it from the `PreparedStyle`, at
the same point and in the same form as the other descriptors.

```kotlin
data class StyleMetadataDescriptor(val rootMetadataJson: String?, val layers: List<LayerMetadataDescriptor>)
data class LayerMetadataDescriptor(
    val id: String, val styleLayerIndex: Int, val type: String, val metadataJson: String,
    val minimumZoom: Double, val maximumZoom: Double, val materialCoverageEligible: Boolean,
)
```

## A descriptor, not a query

Rentile's style lookups are descriptors: one network-free call returns facts in style order and
the host applies its own policy (`labelLayerDescriptors`, `extrusionLayerDescriptors`). A
"which layers declare key K with value V" query would answer one question per call, would have to
fix how non-string values and nested objects compare, and could not return root metadata. The list
returns every fact once. The host's question ("can coverage for K = V be non-empty at zoom z?") is
answered by one documented rule over the list: an eligible layer whose metadata object maps K to the
string V and whose `minimumZoom <= z < maximumZoom`.

## Which layers are listed, and eligibility

The list holds exactly the layers Rentile draws into the Output Tile under the prepared
compatibility profile that declare a `metadata` key, in style order. It is recorded while compiling
the draw layers, from the same compiled layer Material Coverage later reads, so the two cannot
disagree. A hidden layer, a layer the profile excludes or repairs away, a Label layer and a
host-owned extrusion are not drawn and are never listed: coverage never reads them either. Invalid
retained constructs fail preparation outright, so there is no "invalid layer" to report.

`materialCoverageEligible` is the coverage pass's own predicate (`takesPartInMaterialCoverage`:
fills, flattened fill-extrusion footprints and lines). Background, raster, hillshade, color-relief
and icon layers are listed with their metadata, because they are drawn and a host may want it, but
are never eligible: a water-tagged background reports `false`, matching the coverage rule that a
background never contributes. The descriptor is therefore a necessary condition for a non-empty
mask, never a false positive about which layers are selected. It cannot promise a non-empty mask:
the tile may hold no matching geometry, its source may not cover the tile, and later layers may hide
the material (ADR 0042). `StyleMetadataDescriptorTest` pins prediction and rendered result
agreeing at several zooms and requests.

The descriptor is per `PreparedStyle`, not per `RenderOptions`: `includeHillshade = false` does
not remove hillshade layers from it, and the OUTPUT_ZOOM raster minzoom rule concerns raster layers,
which are never eligible.

## Representation

JSON values cross the public API as JSON text, as `LabelLayerDescriptor.layerJson` already does.
`kotlinx-serialization-json` is an ordinary `implementation` dependency, not part of Rentile's ABI
(ADR 0003 keeps such pure-Kotlin libraries unshaded so consumers resolve compatible versions
naturally). Exposing `JsonElement` would turn it into an `api` dependency whose types appear in
Rentile's signatures, binding consumers to it. Text keeps the ABI to `String`, and every consumer
already has a JSON parser.

Unlike `layerJson`, which is canonical because it feeds identities, metadata is information: it is
written compactly in the author's key order, and layers keep declaration order. String values have
authentication query values (`key`, `token`, `access_token`, ...) redacted exactly as the style
digest redacts them, so a credential a style author put in metadata cannot leave through it.

## Cost

Preparation keeps only references to the root `metadata` element and to each drawn layer's
`metadata` element; nothing is serialised, copied or hashed until the descriptor is asked for. The
style digest, request and content keys, pixels and the rolling corpus are unchanged (corpus A/B
against 0.20.0 byte-identical).

The interface member has a default that throws `UnsupportedOperationException`, as the host
extrusion members do, so other implementations stay source compatible. Minor version 0.21.0.

## Rejected

- **Raw `JsonElement` / `JsonObject` in the API**: see Representation.
- **A `Map<String, String>` of flattened metadata**: loses nesting and non-string values, and
  invents a flattening rule the style specification does not have.
- **Listing every authored layer with a "drawn" flag**: hidden and host-owned layers would then
  carry metadata no Rentile output reflects, which is exactly the false positive the host must not
  see. A host that needs Label layers already has `labelLayerDescriptors`.
