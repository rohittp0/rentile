package com.rohittp.rentile.internal.style

import com.rohittp.rentile.CompatibilityPolicy
import com.rohittp.rentile.LabelLayerDescriptor
import com.rohittp.rentile.GroundRadianceDescriptor
import com.rohittp.rentile.PreparedStyle
import com.rohittp.rentile.RenderDiagnostic
import com.rohittp.rentile.ResourceClass
import com.rohittp.rentile.internal.ProtectedResourceUrl
import com.rohittp.rentile.internal.SecretContext
import com.rohittp.rentile.internal.sprite.CompiledSpriteAtlas
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

internal data class CompiledColor(
    val red: Int,
    val green: Int,
    val blue: Int,
    val alpha: Int,
)

internal data class CompiledBackgroundLayer(
    val color: CompiledStyleProperty,
    val opacity: CompiledStyleProperty,
    val pattern: CompiledStyleProperty?,
)

internal enum class TileScheme {
    XYZ,
    TMS,
}

internal enum class RasterResampling {
    LINEAR,
    NEAREST,
}

internal enum class TranslateAnchor {
    MAP,
    VIEWPORT,
}

internal data class SourceBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

internal fun SourceBounds.intersects(tile: com.rohittp.rentile.TileId): Boolean {
    val dimension = 1L shl tile.z
    val canonicalX = tile.x.toLong().let { value ->
        val remainder = value % dimension
        if (remainder < 0) remainder + dimension else remainder
    }
    val tileWest = canonicalX.toDouble() / dimension * 360.0 - 180.0
    val tileEast = (canonicalX + 1L).toDouble() / dimension * 360.0 - 180.0
    val tileNorth = mercatorLatitude(tile.y.toDouble(), dimension.toDouble())
    val tileSouth = mercatorLatitude(tile.y + 1.0, dimension.toDouble())
    val latitudeOverlaps = tileNorth >= south && tileSouth <= north
    val longitudeOverlaps = if (west <= east) {
        tileEast >= west && tileWest <= east
    } else {
        tileEast >= west || tileWest <= east
    }
    return latitudeOverlaps && longitudeOverlaps
}

private fun mercatorLatitude(y: Double, dimension: Double): Double {
    val mercator = PI * (1.0 - 2.0 * y / dimension)
    return atan(sinh(mercator)) * 180.0 / PI
}

internal data class CompiledRasterSource(
    val idDigest: String,
    val metadataDigest: String? = null,
    val tileTemplates: List<ProtectedResourceUrl>,
    val tileSize: Int,
    val scheme: TileScheme,
    val minZoom: Int,
    val maxZoom: Int,
    val bounds: SourceBounds? = null,
    val resourceClass: ResourceClass = ResourceClass.RASTER_TILE,
    val demEncoding: DemEncoding? = null,
)

internal enum class DemEncoding {
    MAPBOX,
    TERRARIUM,
}

internal data class CompiledVectorSource(
    val idDigest: String,
    val metadataDigest: String? = null,
    val tileTemplates: List<ProtectedResourceUrl>,
    val scheme: TileScheme,
    val minZoom: Int,
    val maxZoom: Int,
    val bounds: SourceBounds? = null,
    val geoJson: CompiledGeoJsonData? = null,
)

internal data class CompiledGeoJsonData(
    val contentDigest: String,
    val features: List<CompiledGeoJsonLineFeature>,
)

internal data class CompiledGeoJsonLineFeature(
    val properties: Map<String, StyleValue>,
    val lines: List<List<GeoJsonPosition>>,
)

internal data class GeoJsonPosition(
    val longitude: Double,
    val latitude: Double,
)

internal const val GEO_JSON_SOURCE_LAYER: String = "__rentile_geojson__"

/**
 * The vector source-layers that carry geographic place names, across both tile schemas the
 * rolling style corpus serves.
 *
 * OpenMapTiles v3 aggregates every settlement, admin area and island name into one `place`
 * layer whose `class` property discriminates them (`continent`, `country`, `state`,
 * `province`, `city`, `town`, `village`, `hamlet`, `suburb`, `neighbourhood`, `island`,
 * `islet` are all in use across the corpus). MapTiler Planet v4 splits that same content
 * into one layer per class family, so the v4 names below are the exact counterpart of v3's
 * single layer -- nothing more. Point-of-interest, road, water, terrain and protected-area
 * naming stays out: a host that asked for place names never drew those under v3, and
 * admitting them here would change what a style renders rather than repair it.
 */
internal val PLACE_NAME_SOURCE_LAYERS: Set<String> = setOf(
    // OpenMapTiles v3
    "place",
    // MapTiler Planet v4
    "continent_label",
    "country_label",
    "country_disputed_label",
    "state_label",
    "city_label",
    "town_label",
    "place_label",
    "island_label",
    "archipelago_label",
)

internal sealed interface CompiledDrawLayer {
    val minZoom: Double
    val maxZoom: Double

    fun isActiveAt(zoom: Int): Boolean = zoom >= minZoom && zoom < maxZoom
}

internal data class BackgroundDrawLayer(
    val background: CompiledBackgroundLayer,
    override val minZoom: Double,
    override val maxZoom: Double,
) : CompiledDrawLayer

internal data class RasterDrawLayer(
    val source: CompiledRasterSource,
    val opacity: CompiledStyleProperty,
    val brightnessMinimum: CompiledStyleProperty,
    val brightnessMaximum: CompiledStyleProperty,
    val contrast: CompiledStyleProperty,
    val hueRotate: CompiledStyleProperty,
    val saturation: CompiledStyleProperty,
    val resampling: RasterResampling,
    override val minZoom: Double,
    override val maxZoom: Double,
) : CompiledDrawLayer {
    override fun isActiveAt(zoom: Int): Boolean =
        zoom >= minZoom && zoom < maxZoom && zoom >= source.minZoom
}

internal data class HillshadeDrawLayer(
    val source: CompiledRasterSource,
    val accentColor: CompiledStyleProperty,
    val exaggeration: CompiledStyleProperty,
    val highlightColor: CompiledStyleProperty,
    val shadowColor: CompiledStyleProperty,
    override val minZoom: Double,
    override val maxZoom: Double,
) : CompiledDrawLayer {
    override fun isActiveAt(zoom: Int): Boolean =
        zoom >= minZoom && zoom < maxZoom && zoom >= source.minZoom
}

internal sealed interface VectorDrawLayer : CompiledDrawLayer {
    val source: CompiledVectorSource
    val sourceLayer: String
    val filter: CompiledStyleFilter
}

internal data class FillDrawLayer(
    override val source: CompiledVectorSource,
    override val sourceLayer: String,
    override val filter: CompiledStyleFilter,
    val antialias: Boolean,
    val color: CompiledStyleProperty,
    val opacity: CompiledStyleProperty,
    val outlineColor: CompiledStyleProperty?,
    val pattern: CompiledStyleProperty?,
    val translate: CompiledStyleProperty,
    val translateAnchor: TranslateAnchor,
    override val minZoom: Double,
    override val maxZoom: Double,
) : VectorDrawLayer

internal enum class CompiledLineCap {
    BUTT,
    ROUND,
    SQUARE,
}

internal enum class CompiledLineJoin {
    BEVEL,
    MITER,
    ROUND,
}

internal enum class SymbolPlacement {
    POINT,
    LINE,
}

internal enum class IconAnchor {
    CENTER,
    LEFT,
    RIGHT,
    TOP,
    BOTTOM,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

internal enum class TextTransform {
    NONE,
    UPPERCASE,
    LOWERCASE,
}

internal enum class TextJustify {
    LEFT,
    CENTER,
    RIGHT,
}

internal data class LineDrawLayer(
    override val source: CompiledVectorSource,
    override val sourceLayer: String,
    override val filter: CompiledStyleFilter,
    val cap: CompiledLineCap,
    val join: CompiledLineJoin,
    val miterLimit: CompiledStyleProperty,
    val roundLimit: Float,
    val sortKey: CompiledStyleProperty?,
    val color: CompiledStyleProperty,
    val opacity: CompiledStyleProperty,
    val width: CompiledStyleProperty,
    val blur: CompiledStyleProperty,
    val dashArray: CompiledStyleProperty?,
    val gapWidth: CompiledStyleProperty,
    val offset: CompiledStyleProperty,
    val pattern: CompiledStyleProperty?,
    val translate: CompiledStyleProperty,
    override val minZoom: Double,
    override val maxZoom: Double,
) : VectorDrawLayer

internal data class IconDrawLayer(
    override val source: CompiledVectorSource,
    override val sourceLayer: String,
    override val filter: CompiledStyleFilter,
    val layerOrder: Int,
    val placement: SymbolPlacement,
    val image: CompiledStyleProperty,
    val size: CompiledStyleProperty,
    val opacity: CompiledStyleProperty,
    val color: CompiledStyleProperty,
    val haloColor: CompiledStyleProperty,
    val haloWidth: CompiledStyleProperty,
    val haloBlur: CompiledStyleProperty,
    val rotate: CompiledStyleProperty,
    val padding: Double,
    val offset: CompiledStyleProperty,
    val translate: CompiledStyleProperty,
    val anchor: IconAnchor,
    val sortKey: CompiledStyleProperty?,
    val spacing: CompiledStyleProperty,
    val allowOverlap: Boolean,
    val avoidEdges: Boolean,
    /**
     * True when this layer was retained only because its text was removed and its icon is
     * independent of that text - meaning it was never validated as a retained construct before
     * this compatibility profile grew that feature. Such a layer degrades per-feature at render
     * time instead of failing the whole tile: a single feature whose data-driven property fails
     * to evaluate is skipped rather than aborting every other layer's draw. An author-intended
     * icon-only layer (`false`) keeps the original fail-loudly behaviour, since it always existed
     * as the layer's sole purpose.
     */
    val retainedIndependentOfText: Boolean = false,
    override val minZoom: Double,
    override val maxZoom: Double,
) : VectorDrawLayer

/**
 * A place-name label layer's compiled text program: everything needed to lay out label
 * candidates for it. Kept as a separate, nullable type from [CompiledLabelLayer] rather than a
 * set of fields on it, so that a construct this compatibility profile cannot compile - an
 * unsupported filter, layout property, or paint property - can leave a layer's raw-MVT
 * [CompiledLabelLayer.descriptor] and [CompiledLabelLayer.source] untouched. Compiling this program
 * is new work no consumer's preparation ever depended on before this profile grew label
 * candidates, so per ADR 0026, a rejected construct degrades this - the new capability - to
 * `null` rather than removing the descriptor a pre-existing, non-opted-in consumer already relies
 * on for raw MVT via `labelLayerDescriptors`/`acquireLabelTiles`.
 */
internal data class CompiledLabelTextProgram(
    val layerOrder: Int,
    val filter: CompiledStyleFilter,
    val text: CompiledStyleProperty,
    val font: CompiledStyleProperty,
    val size: CompiledStyleProperty,
    val anchor: IconAnchor,
    val offset: CompiledStyleProperty,
    val justify: TextJustify,
    val maxWidth: CompiledStyleProperty,
    val letterSpacing: CompiledStyleProperty,
    val lineHeight: CompiledStyleProperty,
    val transform: TextTransform,
    val padding: Double,
    val allowOverlap: Boolean,
    val ignorePlacement: Boolean,
    val sortKey: CompiledStyleProperty?,
    val color: CompiledStyleProperty,
    val haloColor: CompiledStyleProperty,
    val haloWidth: CompiledStyleProperty,
    val haloBlur: CompiledStyleProperty,
    val opacity: CompiledStyleProperty,
    val minZoom: Double,
    val maxZoom: Double,
)

internal data class CompiledLabelLayer(
    val descriptor: LabelLayerDescriptor,
    val source: CompiledVectorSource,
    /**
     * Null when this layer's text program could not be compiled (an unsupported filter, layout,
     * or paint construct) or was excluded outright (`symbol-placement: line`). [descriptor] and
     * [source] are unaffected either way - they are populated unconditionally, exactly as they
     * were before this compatibility profile compiled any text program at all - so a consumer
     * that never calls the label-candidate API sees no behaviour change. A null program simply
     * yields no label candidates for this layer; the diagnostic explaining why is already on
     * `CompiledPreparedStyle.diagnostics`.
     */
    val textProgram: CompiledLabelTextProgram?,
)

internal class CompiledPreparedStyle(
    val owner: Any,
    override val digest: String,
    override val policy: CompatibilityPolicy,
    override val diagnostics: List<RenderDiagnostic>,
    val drawLayers: List<CompiledDrawLayer>,
    val labelLayers: List<CompiledLabelLayer>,
    val terrainSource: CompiledRasterSource?,
    val groundRadiance: GroundRadianceDescriptor?,
    val spriteAtlas: CompiledSpriteAtlas?,
    /**
     * The style's `glyphs` URL template, resolved against the style base URI and with any
     * embedded credential extracted into [secretContext], or null when the style declares no
     * `glyphs` key or the reference could not be resolved. Label-candidate preparation (a
     * separate, opt-in API) turns a null template into an empty batch and a diagnostic instead of
     * failing, so a consumer that never asks for labels is never failed by this field.
     */
    val glyphsTemplate: String?,
    val secretContext: SecretContext,
) : PreparedStyle
