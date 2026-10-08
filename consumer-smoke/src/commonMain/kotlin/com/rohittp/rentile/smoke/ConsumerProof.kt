package com.rohittp.rentile.smoke

import com.rohittp.rentile.BasemapRasterizer
import com.rohittp.rentile.CompatibilityPolicy
import com.rohittp.rentile.DiagnosticCode
import com.rohittp.rentile.IconTextFit
import com.rohittp.rentile.LabelCandidate
import com.rohittp.rentile.LabelCandidateOptions
import com.rohittp.rentile.LabelGlyphAtlasPolicy
import com.rohittp.rentile.LabelGlyphPacking
import com.rohittp.rentile.LabelIconAnchor
import com.rohittp.rentile.LabelLayerStyle
import com.rohittp.rentile.LabelPlacement
import com.rohittp.rentile.LabelSymbolSize
import com.rohittp.rentile.RawResourceStore
import com.rohittp.rentile.RenderOptions
import com.rohittp.rentile.RentileConfiguration
import com.rohittp.rentile.ResourceTransport
import com.rohittp.rentile.SpriteAtlas
import com.rohittp.rentile.SpriteContentBox
import com.rohittp.rentile.SpriteImageEntry
import com.rohittp.rentile.SpriteStretchRange
import com.rohittp.rentile.StyleInput
import com.rohittp.rentile.SymbolAlignment
import com.rohittp.rentile.SymbolOverlap
import com.rohittp.rentile.SymbolSizeKind
import com.rohittp.rentile.SymbolZOrder
import com.rohittp.rentile.TerrainDemEncoding
import com.rohittp.rentile.TileId
import com.rohittp.rentile.ValidatedDemTile
import com.rohittp.rentile.ExtrusionLimits

fun proveAggregateDependency(): Pair<TileId, RenderOptions> =
    TileId(z = 0, x = 0, y = 0) to RenderOptions()

/** Compile the host-extrusion contract from the published aggregate on every consumer target. */
suspend fun proveHostExtrusionsApi(
    rasterizer: BasemapRasterizer, input: StyleInput, tiles: List<TileId>, cameraZoom: Double,
): Boolean {
    val style = rasterizer.prepare(input, CompatibilityPolicy.RentileV1HostSymbolsAndExtrusions)
    val descriptors = rasterizer.extrusionLayerDescriptors(style)
    val requestKey = rasterizer.extrusionCandidateRequestKey(style, tiles)
    val batch = rasterizer.acquireExtrusionCandidates(style, tiles, ExtrusionLimits())
    return requestKey.isNotEmpty() && batch.contentKey.isNotEmpty() &&
        batch.estimatedRetainedBytes >= 0 && descriptors.size == batch.layerStyles.size &&
        batch.candidates.all { candidate ->
            val layer = batch.layerStyles[candidate.layerStyleIndex]
            val geometry = candidate.geometry
            val paint = candidate.paintAtZoom(cameraZoom)
            candidate.extent > 0 && candidate.sourceTile.z <= layer.descriptor.sourceMaximumZoom &&
                candidate.sourceId == layer.descriptor.sourceId && candidate.featureIndex >= 0 &&
                geometry.polygonCount > 0 && geometry.ringEnd(0) > geometry.ringStart(0) &&
                geometry.polygonEnd(0) > geometry.polygonStart(0) &&
                geometry.copyCoordinates().size == geometry.vertexCount * 2 &&
                geometry.copyRingOffsets().size == geometry.ringCount + 1 &&
                geometry.copyPolygonOffsets().size == geometry.polygonCount + 1 &&
                geometry.primitiveByteCount > 0 && layer.opacityAtZoom(cameraZoom) in 0.0..1.0 &&
                (paint == null || paint.heightMetres >= paint.baseMetres)
        }
}

/** Compile-time proof that the published aggregate exposes the complete 0.6 label contract. */
fun proveExpandedLabelApi(candidate: LabelCandidate, style: LabelLayerStyle): Boolean {
    val placementIsKnown = when (candidate.placement) {
        LabelPlacement.POINT -> candidate.line.isEmpty()
        LabelPlacement.LINE, LabelPlacement.LINE_CENTER -> candidate.line.isNotEmpty()
    }
    val overlapIsKnown = when (candidate.overlap) {
        SymbolOverlap.NEVER, SymbolOverlap.ALWAYS, SymbolOverlap.COOPERATIVE -> true
    }
    val orderIsKnown = when (candidate.zOrder) {
        SymbolZOrder.AUTO, SymbolZOrder.SOURCE, SymbolZOrder.VIEWPORT_Y -> true
    }
    val iconIsKnown = candidate.icon?.let { icon ->
        icon.translateAlignment in SymbolAlignment.entries &&
            icon.anchor in LabelIconAnchor.entries &&
            icon.rotationAlignment in SymbolAlignment.entries &&
            icon.pitchAlignment in SymbolAlignment.entries &&
            icon.textFit in IconTextFit.entries &&
            icon.textFitPadding.size == 4
    } ?: true
    return placementIsKnown && overlapIsKnown && orderIsKnown && iconIsKnown &&
        candidate.rotationAlignment in SymbolAlignment.entries &&
        candidate.pitchAlignment in SymbolAlignment.entries &&
        candidate.maxAngleDegrees >= 0.0 && candidate.color != candidate.haloColor && style.priority >= 0
}

/**
 * Compile-time proof that the published aggregate lets a consumer read elevation out of a DEM tile
 * with no image decoder of its own, which is the whole of the 0.7 terrain contract.
 *
 * This is also the worked example: index the texel, then apply the tile's own encoding. The
 * channels are packed values, never metres, and the encoded [ValidatedDemTile.bytes] remains what a
 * caller hashes for cache identity.
 */
fun proveDecodedDemTexelApi(tile: ValidatedDemTile, x: Int, y: Int): Double {
    val texels = tile.texels
    require(x in 0 until texels.width && y in 0 until texels.height)
    require(texels.rgba.size == texels.width * texels.height * 4)
    require(tile.bytes.isNotEmpty())

    // Rows run top-down and are tightly packed at width * 4 bytes, with no padding.
    val offset = (y * texels.width + x) * 4
    val red = texels.rgba[offset].toInt() and 0xff
    val green = texels.rgba[offset + 1].toInt() and 0xff
    val blue = texels.rgba[offset + 2].toInt() and 0xff

    // Unpremultiplied, so these three channels are usable as-is however opaque the alpha is.
    return when (tile.encoding) {
        TerrainDemEncoding.MAPBOX -> -10_000.0 + (red * 65_536 + green * 256 + blue) * 0.1
        TerrainDemEncoding.TERRARIUM -> red * 256.0 + green + blue / 256.0 - 32_768.0
    }
}

/**
 * Compile-time proof of the 0.12 label contract: a host that owns every symbol layer, reads each
 * candidate's identity and camera-zoom size, chooses its label language without re-preparing the
 * style, bounds its glyph atlas, and draws icons from the style's own sprite sheet.
 */
fun configureHostOwnedSymbols(transport: ResourceTransport, store: RawResourceStore): RentileConfiguration =
    RentileConfiguration(
        transport = transport,
        rawResourceStore = store,
        // What a GL host with a 4096 texture ceiling passes: only the glyphs its candidates draw.
        labelGlyphAtlas = LabelGlyphAtlasPolicy(packing = LabelGlyphPacking.REFERENCED_GLYPHS, maxDimensionPx = 4096),
    )

/** Every label of [tiles] in English where the tiles have it, drawn at [cameraZoom]; true when consistent. */
suspend fun proveHostOwnedSymbolsApi(
    rasterizer: BasemapRasterizer,
    style: StyleInput,
    tiles: List<TileId>,
    cameraZoom: Double,
): Boolean {
    val prepared = rasterizer.prepare(style, CompatibilityPolicy.RentileV1HostSymbols)
    val english = LabelCandidateOptions(textFieldOverride = """["coalesce",["get","name:en"],["get","name"]]""")
    val requestKey = rasterizer.labelCandidateRequestKey(prepared, tiles, english)
    val plan = rasterizer.planLabelCandidates(prepared, tiles, english)
    val batch = try {
        rasterizer.acquireLabelCandidates(plan)
    } finally {
        plan.close()
    }
    val hostOwned = prepared.diagnostics.filter { it.code == DiagnosticCode.SYMBOL_LAYER_HOST_OWNED }

    // The 1x sheet is the one every LabelIconRef was sized from; @2x covers the same style pixels.
    val sheet: SpriteAtlas? = rasterizer.acquireSpriteAtlas(prepared, pixelRatio = 2)
    val iconsResolve = batch.candidates.mapNotNull { it.icon }.all { icon ->
        val entry: SpriteImageEntry? = sheet?.entries?.get(icon.imageName)
        entry == null || (entry.pixelRatio > 0.0 && entry.width > 0 && proveSpriteEntry(entry) && iconScale(icon.size, cameraZoom) >= 0.0)
    }
    val identities = batch.candidates.map { candidateIdentity(it, batch.layerStyles) }.toSet()
    val candidatesAreConsistent = identities.size <= batch.candidates.size && batch.candidates.all { candidate ->
        proveCandidateIdentity(candidate) &&
            candidate.glyphs.all { it.entryIndex in batch.atlas.entries.indices } &&
            (candidate.textSize?.let { iconScale(it, cameraZoom) >= 0.0 } ?: candidate.glyphs.isEmpty())
    }
    return requestKey.isNotEmpty() && hostOwned.all { it.details.containsKey("labelLayer") } &&
        iconsResolve && candidatesAreConsistent
}

/** An icon-only candidate has no glyphs, no text and no text size, and always an icon. */
fun proveCandidateIdentity(candidate: LabelCandidate): Boolean {
    val text: String? = candidate.text
    return if (text == null) {
        candidate.glyphs.isEmpty() && candidate.textSize == null && candidate.icon != null
    } else {
        text.isNotEmpty()
    }
}

/**
 * What a host de-duplicates a line or polygon feature's candidates by across tiles: its layer, its
 * MVT feature id as the unsigned 64 bits it is, and its text.
 */
fun candidateIdentity(candidate: LabelCandidate, styles: List<LabelLayerStyle>): Triple<String, ULong?, String?> =
    Triple(styles[candidate.layerStyleIndex].layerId, candidate.featureId?.toULong(), candidate.text)

/** The factor a host applies to a symbol's tile-zoom geometry to draw it at [cameraZoom]. */
fun iconScale(size: LabelSymbolSize, cameraZoom: Double): Double {
    val kindIsKnown = when (size.kind) {
        SymbolSizeKind.CONSTANT, SymbolSizeKind.SOURCE -> size.sizeAt(cameraZoom) == size.tileZoomSize
        SymbolSizeKind.CAMERA, SymbolSizeKind.COMPOSITE -> size.lowerZoom <= size.upperZoom
    }
    if (!kindIsKnown || size.tileZoomSize == 0.0) return 0.0
    return size.sizeAt(cameraZoom) / size.tileZoomSize
}

/** A sheet entry's stretch and content metadata, for a host that nine-slices `icon-text-fit` icons. */
fun proveSpriteEntry(entry: SpriteImageEntry): Boolean {
    val stretches: List<SpriteStretchRange> = entry.stretchX.orEmpty() + entry.stretchY.orEmpty()
    val content: SpriteContentBox? = entry.content
    // An SDF entry is sampled from its alpha channel alone, with the edge at 192/255.
    val sampledChannels = if (entry.sdf) 1 else 4
    return stretches.all { it.from <= it.to } &&
        (content == null || (content.left <= content.right && content.top <= content.bottom)) &&
        sampledChannels > 0
}
