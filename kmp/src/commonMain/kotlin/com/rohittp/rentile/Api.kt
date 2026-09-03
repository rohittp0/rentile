package com.rohittp.rentile

import com.rohittp.rentile.internal.createBasemapRasterizer

/** North-up XYZ output-tile identity. */
public data class TileId(
    public val z: Int,
    public val x: Int,
    public val y: Int,
)

/** Input forms accepted by style preparation. */
public sealed interface StyleInput {
    /** Inline style JSON with an optional base URI for relative references. */
    public class InlineJson(
        public val json: String,
        public val baseUri: String? = null,
    ) : StyleInput {
        override fun toString(): String =
            "StyleInput.InlineJson(byteCount=${json.encodeToByteArray().size}, hasBaseUri=${baseUri != null})"
    }

    /** Style document acquired through the configured [ResourceTransport]. */
    public class Remote(
        public val url: String,
    ) : StyleInput {
        override fun toString(): String = "StyleInput.Remote(url=<redacted>)"
    }

    /** Already acquired style bytes with a caller-provided canonical identity. */
    public class Prefetched(
        bytes: ByteArray,
        public val canonicalIdentity: String,
        public val baseUri: String? = null,
    ) : StyleInput {
        internal val bytes: ByteArray = bytes.copyOf()

        override fun toString(): String =
            "StyleInput.Prefetched(canonicalIdentity=<redacted>, byteCount=${bytes.size})"
    }
}

/** Closed set of rendering profiles understood by this library version. */
public class CompatibilityPolicy private constructor(
    public val id: String,
    public val minimumOutputZoom: Int,
    public val maximumOutputZoom: Int,
) {
    public companion object {
        public val RentileV1: CompatibilityPolicy = CompatibilityPolicy(
            id = "rentile-v1",
            minimumOutputZoom = 0,
            maximumOutputZoom = 22,
        )
        public val Default: CompatibilityPolicy = RentileV1
    }

    override fun equals(other: Any?): Boolean = other is CompatibilityPolicy && id == other.id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = id
}

/** Controls how an operation may access the injected raw-resource store and transport. */
public enum class ResourceAccessMode {
    /** Uses fresh cached resources and revalidates stale resources before transport acquisition. */
    NORMAL,

    /** Uses any integrity-valid cached resource without transport access. */
    CACHE_ONLY,

    /** Acquires every resource from transport, replacing cached entries only after validation. */
    RELOAD,

    /**
     * For substitution-enabled output-tile preparation, tries the exact raw cache, then a
     * cache-only child or ancestor substitute, and only then normal exact acquisition.
     *
     * Cached substitutes remain subject to [TileSubstitutionPolicy]. Operations that do not
     * support substitution treat this mode as [NORMAL].
     */
    CACHE_SUBSTITUTE_THEN_NETWORK,
}

/** Content-affecting controls for output-tile creation. */
public data class RenderOptions(
    public val outputSizePx: Int = DEFAULT_OUTPUT_SIZE_PX,
) {
    init {
        require(outputSizePx in SUPPORTED_OUTPUT_SIZES) {
            "outputSizePx must be one of ${SUPPORTED_OUTPUT_SIZES.sorted()}"
        }
    }

    public companion object {
        public const val DEFAULT_OUTPUT_SIZE_PX: Int = 512
        public val SUPPORTED_OUTPUT_SIZES: Set<Int> = setOf(256, 512)
    }
}

/** Host-owned allowance for degraded output tiles during initial preparation. */
public data class TileSubstitutionPolicy(
    public val maximumSubstitutedTiles: Int = 0,
) {
    init {
        require(maximumSubstitutedTiles >= 0)
    }

    public companion object {
        public val Disabled: TileSubstitutionPolicy = TileSubstitutionPolicy()
    }
}

/**
 * What one [BasemapRasterizer.warmRawResources] call did.
 *
 * [fetched] and [alreadyCached] count *source resources*, not output tiles: one output tile usually
 * needs several, and several output tiles often share one.
 */
public data class RawWarmSummary(
    public val fetched: Int,
    public val alreadyCached: Int,
    public val failed: Int,
)

/** How one unavailable source resource was replaced for an output tile. */
public enum class TileSubstitutionStrategy {
    IMMEDIATE_CHILDREN,
    ANCESTOR,
}

/** Credential-free provenance for one substituted source beneath an output tile. */
public data class ResourceSubstitution(
    public val resourceClass: ResourceClass,
    public val sanitizedSourceId: String,
    public val strategy: TileSubstitutionStrategy,
    public val sourceTiles: List<TileId>,
    public val ancestorZoomDistance: Int? = null,
)

/** Successful exact-only recovery performed against an existing prepared batch. */
public data class ExactRecoveryResult(
    public val upgradedTiles: Set<TileId>,
    public val remainingSubstitutedTiles: Set<TileId>,
    public val diagnostics: List<RenderDiagnostic> = emptyList(),
)

/** Sanitized style-layer input for a host-owned geographic-label renderer. */
public data class LabelLayerDescriptor(
    public val id: String,
    public val sourceId: String,
    public val sourceLayer: String,
    public val sourceMinimumZoom: Int,
    public val sourceMaximumZoom: Int,
    public val layerJson: String,
)

/** Validated encoded MVT bytes acquired through Rentile's transport and raw cache. */
public data class ValidatedMvtTile(
    public val requestedTile: TileId,
    public val sourceTile: TileId,
    public val sourceId: String,
    public val bytes: ByteArray,
    public val contentDigest: String,
)

/**
 * One glyph in the batch atlas: where its cell sits in the texture, and the metrics that position
 * it.
 *
 * Every value is in atlas pixels at the signed-distance-field em of **24 pixels**, which is the
 * size the provider rendered these glyphs at. [LabelGlyphQuad.scale] carries the ratio from that
 * em to the label's own `text-size`, so a consumer multiplies by it rather than assuming any
 * particular text size here.
 *
 * The buffered-versus-unbuffered distinction matters and is the one thing easy to get wrong:
 *
 * - [x], [y], [width] and [height] describe the glyph's **cell** in the texture — the region to
 *   sample. Signed-distance-field glyphs carry a **3-pixel** buffer on all four sides so the
 *   distance field stays continuous past the ink, and that buffer is inside the cell: the cell is
 *   6 pixels wider and 6 taller than the glyph body it contains. [x] and [y] are the cell's
 *   top-left corner, measured from the texture's top-left, x rightwards and y downwards.
 * - [left] and [top] are the provider's bearings for the glyph **body**, not the cell, forwarded
 *   verbatim from the glyph range. [left] is the horizontal distance from the pen position to the
 *   body's left edge, positive rightwards. `-top` is the distance from the **line's ascender** down
 *   to the body's top edge — measured from the top of the line, not from its baseline. [top] is
 *   negative for the overwhelming majority of glyphs, but it is **positive** for any glyph whose ink
 *   rises above the font's own ascender — an accented capital, a tight-ascender CJK face — so a
 *   consumer must not validate on the sign.
 *
 *   A line's baseline sits `-top` below the line top for a glyph whose `height` is zero, which is
 *   how the ascender can be recovered from a range's own space glyph; every other glyph's body
 *   bottom is `-top + height` below the line top, so a cap-height letter lands exactly on the
 *   baseline and a descender passes below it.
 *
 * A consumer drawing this itself therefore places the cell's corner 3 pixels up and left of the
 * bearing, which is what [LabelGlyphQuad.x] and [LabelGlyphQuad.y] already have applied — they
 * are cell corners, so nothing further is needed unless the quads are being recomputed.
 *
 * [advance] is how far the pen moves after this glyph, positive rightwards. [fontStackDigest]
 * identifies the font stack this glyph came from without naming it, and [codepoint] is its
 * Unicode code point.
 */
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
) {
    override fun equals(other: Any?): Boolean =
        other is LabelGlyphAtlas &&
            pngBytes.contentEquals(other.pngBytes) &&
            width == other.width &&
            height == other.height &&
            contentKey == other.contentKey &&
            entries == other.entries

    override fun hashCode(): Int {
        var result = pngBytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + contentKey.hashCode()
        result = 31 * result + entries.hashCode()
        return result
    }

    override fun toString(): String =
        "LabelGlyphAtlas(byteCount=${pngBytes.size}, width=$width, height=$height, " +
            "contentKey=$contentKey, entryCount=${entries.size})"
}

/**
 * One glyph positioned within its label, referencing [LabelGlyphAtlas.entries] by [entryIndex].
 *
 * [x] and [y] are the **top-left corner of the glyph's cell** in label-local coordinates: x
 * rightwards, y downwards, and the origin is the label's own anchor point — the position
 * [LabelCandidate.longitude] and [LabelCandidate.latitude] name — after `text-anchor` and
 * the effective em offset (`text-radial-offset` when positive, otherwise `text-offset`) have been
 * applied. Nothing here is in screen coordinates and nothing is projected.
 *
 * Each line of a multi-line label occupies one `text-line-height` row and is positioned from that
 * row's top edge, which is what [LabelGlyphEntry.top] is measured against; no baseline is involved
 * and none needs to be reconstructed to draw these.
 *
 * The buffer is already compensated for: these are cell corners, not bearings, so the glyph body
 * lands on the provider's bearing without further adjustment.
 *
 * [scale] is `text-size` divided by the 24-pixel signed-distance-field em, and it is the factor
 * relating this label to the atlas. The quad occupies
 * `x .. x + entry.width * scale` by `y .. y + entry.height * scale`, using the cell extent from
 * [LabelGlyphEntry]; [x] and [y] already have it applied, so it is needed only for the extent.
 */
public data class LabelGlyphQuad(
    public val entryIndex: Int,
    public val x: Double, public val y: Double,
    public val scale: Double,
)

/**
 * Label-local bounds, before any projection, for the consumer's screen-space collision.
 *
 * In the same coordinates and the same units as [LabelGlyphQuad]: the origin is the label's anchor
 * point, x runs rightwards and y downwards, and the values are at the label's own `text-size`
 * rather than at the atlas em. [top] is always the smaller value, since y runs downwards.
 *
 * The signs are not both negative. For a single-line label centred on its anchor, [top] is negative
 * and **[bottom] is positive**: the anchor sits at the centre of the line's row, while the glyph
 * bodies hang from the ascender down through the baseline, so the box extends further below the
 * anchor than above it.
 *
 * The box is the union of every quad's cell extent, expanded on all sides by the layer's
 * `text-padding`. It therefore includes each glyph's 3-pixel signed-distance-field buffer, which
 * makes it very slightly larger than the ink it contains.
 */
public data class LabelBox(
    public val left: Double, public val top: Double,
    public val right: Double, public val bottom: Double,
)

/**
 * Stable layer-and-zoom metadata shared by every candidate that references this entry.
 * One entry per (layer, zoom) pair present in the batch; [LabelCandidate.layerStyleIndex]
 * selects the entry matching that candidate's own `requestedTile.z`.
 *
 * [priority] is the layer's position in the style's own layer list, ascending, so a larger value
 * means the style declared the layer later and it should win a placement conflict against a
 * smaller one. It exists so that two consumers resolve ties the same way; the absolute values are
 * not contiguous and carry no meaning beyond their order.
 *
 * Paint which may depend on a feature belongs to [LabelCandidate]. Keeping this record limited to
 * layer-and-zoom state lets newly admitted road, water, POI, and other label layers use
 * data-driven colors without being evaluated against an empty feature context.
 */
public data class LabelLayerStyle(
    public val layerId: String,
    public val zoom: Int,
    public val priority: Int,
)

/** How a symbol is anchored to its source geometry before viewport collision and drawing. */
public enum class LabelPlacement {
    POINT,
    LINE,
    LINE_CENTER,
}

/** The overlap permission declared by the style, without collapsing `cooperative` to `never`. */
public enum class SymbolOverlap {
    NEVER,
    ALWAYS,
    COOPERATIVE,
}

/** The coordinate frame a symbol property is resolved in by the viewport-owning consumer. */
public enum class SymbolAlignment {
    MAP,
    VIEWPORT,
    AUTO,
}

/** Ordering policy the style asks the viewport-owning symbol renderer to apply. */
public enum class SymbolZOrder {
    AUTO,
    SOURCE,
    VIEWPORT_Y,
}

/** How an icon's box is fitted to the text it is emitted beside. */
public enum class IconTextFit {
    NONE,
    WIDTH,
    HEIGHT,
    BOTH,
}

/** Which point of an icon's final, possibly text-fitted box is attached to the symbol anchor. */
public enum class LabelIconAnchor {
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

/** One geographic point from a line geometry used by a line-placed label. */
public data class LabelLinePoint(
    public val longitude: Double,
    public val latitude: Double,
)

/**
 * The sprite the style pairs with this label. [imageName] is an opaque lookup key into sprite
 * resources owned and resolved by the consumer; Rentile does not expose a public sprite atlas.
 *
 * First apply [textFit] and [textFitPadding] to derive the icon's final drawn dimensions from the
 * label bounds. Derive the displacement from [anchor] against that fitted box, add
 * [offsetX]/[offsetY], and rotate that icon-local displacement by the icon's final rotation. Add
 * [translateX]/[translateY] afterwards in the frame selected by [translateAlignment]. Carrying the
 * anchor instead of a shift computed from the unfitted sprite is essential: `left`, `right`, and
 * corner anchors move when text fitting changes the icon's dimensions.
 *
 * [offsetX] and [offsetY] carry `icon-offset` scaled by `icon-size`; [translateX] and [translateY]
 * carry `icon-translate`, which the style specification does not scale or rotate with the icon.
 * [padding] expands the icon's oriented collision box. [haloWidth] and [haloBlur] affect paint
 * only and must not enlarge that collision geometry.
 */
public data class LabelIconRef(
    public val imageName: String,
    public val width: Double, public val height: Double,
    public val anchor: LabelIconAnchor,
    public val offsetX: Double, public val offsetY: Double,
    public val translateX: Double, public val translateY: Double,
    public val translateAlignment: SymbolAlignment,
    public val color: Int,
    public val opacity: Double,
    public val haloColor: Int,
    public val haloWidth: Double,
    public val haloBlur: Double,
    public val rotationDegrees: Double,
    public val padding: Double,
    public val optional: Boolean,
    public val overlap: SymbolOverlap,
    public val ignorePlacement: Boolean,
    public val rotationAlignment: SymbolAlignment,
    public val pitchAlignment: SymbolAlignment,
    public val keepUpright: Boolean,
    public val avoidEdges: Boolean,
    public val textFit: IconTextFit,
    /** `icon-text-fit-padding` in top, right, bottom, left order. */
    public val textFitPadding: List<Double>,
)

/**
 * One Label decoded, evaluated and laid out, but not positioned on screen and not
 * resolved against any other Label.
 *
 * No screen *position* appears here: the only position is geographic ([longitude], [latitude]),
 * and every geometry is label-local, relative to that anchor. Several style-declared scalars are
 * nevertheless in pixels — [padding], [haloWidth], [haloBlur] and [translateX] — because that is
 * the unit the style specification gives them. They are inputs to the consumer's screen-space
 * placement, not results of it.
 */
public data class LabelCandidate(
    public val layerStyleIndex: Int,
    public val requestedTile: TileId,
    public val sourceTile: TileId,
    public val longitude: Double,
    public val latitude: Double,
    public val placement: LabelPlacement,
    /** Source line in geographic coordinates; empty for point placement. */
    public val line: List<LabelLinePoint>,
    /** Tangent direction at the selected line anchor, clockwise from screen-space east. */
    public val rotationDegrees: Double,
    /** Requested repeat distance for line placement, in pixels. */
    public val symbolSpacing: Double,
    public val keepUpright: Boolean,
    public val avoidEdges: Boolean,
    public val zOrder: SymbolZOrder,
    /** Authored `text-rotate`, separate from the source-line tangent in [rotationDegrees]. */
    public val textRotationDegrees: Double,
    /** Maximum permitted change in direction across a line label. */
    public val maxAngleDegrees: Double,
    public val rotationAlignment: SymbolAlignment,
    public val pitchAlignment: SymbolAlignment,
    /** Whether the paired icon may be placed when this text cannot be placed. */
    public val textOptional: Boolean,
    public val glyphs: List<LabelGlyphQuad>,
    public val boundingBox: LabelBox,
    public val icon: LabelIconRef?,
    /**
     * Whether the style grants this label permission to overlap others, from `text-overlap` or the
     * legacy `text-allow-overlap`.
     *
     * `cooperative` remains distinct so the viewport-owning consumer can negotiate the collision
     * with the other symbol instead of Rentile prematurely collapsing it to `never`.
     */
    public val overlap: SymbolOverlap,
    public val ignorePlacement: Boolean,
    public val padding: Double,
    public val sortKey: Double,
    /** Resolved `text-color`, packed as `0xAARRGGBB`. */
    public val color: Int,
    /** Resolved `text-halo-color`, packed as `0xAARRGGBB`. */
    public val haloColor: Int,
    public val opacity: Double,
    public val haloWidth: Double,
    public val haloBlur: Double,
    /**
     * `text-translate`, in pixels, to be applied to the label's projected anchor position.
     *
     * Unlike the effective em offset (`text-radial-offset` when positive, otherwise
     * `text-offset`) — which is already folded into every [LabelGlyphQuad] — this is a pixel
     * displacement of the anchor itself and is **not** scaled by `text-size`, exactly as
     * [LabelIconRef.translateX] is not scaled by `icon-size`. It is carried rather than applied
     * because Rentile cannot apply it: the anchor it moves is a geographic position here, and a
     * pixel offset only becomes meaningful once the consumer has projected it.
     *
     * Ignoring it silently misplaces the label, which is why it is here even though only two layers
     * in the rolling corpus use it. Add it to the projected anchor before laying the quads out
     * around that point.
     *
     * [translateAlignment] identifies whether this displacement rotates with the map or remains
     * fixed to the viewport; Rentile carries the authoring choice because the consumer owns the
     * camera needed to apply it.
     */
    public val translateX: Double,
    /** See [translateX]. */
    public val translateY: Double,
    public val translateAlignment: SymbolAlignment,
)

/** The immutable result of one Label acquisition. Not a Prepared Batch; see CONTEXT.md. */
public data class LabelCandidateBatch(
    public val candidates: List<LabelCandidate>,
    public val layerStyles: List<LabelLayerStyle>,
    public val atlas: LabelGlyphAtlas,
    public val contentKey: String,
    public val diagnostics: List<RenderDiagnostic>,
)

/**
 * One Glyph Range a [LabelCandidatePlan] will acquire. Identity only: no URL, no credential.
 *
 * The raw font stack is deliberately absent. `text-font` may be a data-driven expression, so a
 * resolved stack can carry bytes from a decoded feature property; [fontStackDigest] identifies it
 * without republishing it, and matches [LabelGlyphEntry.fontStackDigest] so a closure entry
 * correlates with the atlas entries it eventually produces.
 */
public data class GlyphRangeRef(
    public val fontStackDigest: String,
    /** The first codepoint of the 256-wide block, so `0`, `256`, `512` and so on. */
    public val rangeStart: Int,
)

/**
 * A frozen Glyph Closure for one tile set, held between Label Tile acquisition and Glyph Range
 * acquisition. Not a Prepared Batch; see CONTEXT.md.
 *
 * A caller that must know a glyph URL before it is fetched reads [glyphUrls] and then passes this
 * same plan to [BasemapRasterizer.acquireLabelCandidates]. Both read one frozen list, so the
 * closure cannot under-approximate the acquisition that follows it - which a second, independent
 * query could, because tile bytes can legitimately change between two acquisitions.
 *
 * Reusable: acquiring from one plan repeatedly yields equal batches.
 *
 * [close] frees this plan's internal acquisition state eagerly rather than waiting for the plan to
 * become unreachable. [tiles], [glyphClosure] and [diagnostics] are computed at construction and
 * remain readable afterwards, but [glyphUrls] is not: it throws [LabelCandidatePlanClosedException]
 * once this plan is closed. That asymmetry is deliberate - the three properties need neither that
 * state nor the style once computed, while composing a URL is cheap enough that there is no reason
 * to let a caller do it against a plan whose acquisition can no longer happen.
 */
public interface LabelCandidatePlan : AutoCloseable {
    /** The de-duplicated tile set this plan was computed over, in (z, x, y) order. */
    public val tiles: List<TileId>

    /**
     * Exactly the Glyph Ranges [BasemapRasterizer.acquireLabelCandidates] will request from this
     * plan - not a superset and not an estimate. Sorted by resolved font stack then
     * [GlyphRangeRef.rangeStart], de-duplicated across every layer and tile in the batch, and
     * stable across runs. The sort key is not exposed, so the order is stable but not
     * re-derivable; [glyphUrls] returns its list in this same order.
     */
    public val glyphClosure: List<GlyphRangeRef>

    /**
     * The URL of every entry in [glyphClosure], in the same order, composed by Rentile's own
     * substitution so a caller never re-derives it.
     *
     * [template] is the caller's copy of the style's `glyphs` value, resolved against the style's
     * base URI. Rentile holds its own copy but will not emit it, because that copy can carry the
     * provider credential. Instead it checks the two agree and throws
     * [GlyphTemplateMismatchException] when they do not, so a relative reference passed
     * unresolved, a stale template, or another style's template fails here rather than as labels
     * that silently stop drawing.
     *
     * That agreement check compares only the redacted form of both templates, so it cannot catch
     * every mistake: the authentication value substituted into the URLs returned here is the
     * caller's own [template], not Rentile's, and it is never verified against what acquisition
     * will actually use. A caller that passes a stale or already-redacted credential gets back a
     * plausible, non-empty list whose every URL is wrong for that reason alone.
     *
     * Returns an empty list, without checking [template], when the style resolves no `glyphs`
     * template: such a plan will fetch nothing.
     *
     * Throws [LabelCandidatePlanClosedException] once this plan has been [close]d - unlike
     * [tiles], [glyphClosure] and [diagnostics], which remain readable after close.
     */
    public fun glyphUrls(template: String): List<String>

    public val diagnostics: List<RenderDiagnostic>

    /** Idempotent, non-blocking, and non-throwing. */
    override fun close()
}

public enum class TerrainDemEncoding {
    MAPBOX,
    TERRARIUM,
}

/** Sanitized planning metadata for the style-selected terrain source. */
public data class TerrainSourceDescriptor(
    public val sourceId: String,
    public val encoding: TerrainDemEncoding,
    public val minimumZoom: Int,
    public val maximumZoom: Int,
    public val tileSizePx: Int,
)

/** Evaluated literal Mapbox ambient-plus-directional ground-light radiance. */
public data class GroundRadianceDescriptor(
    public val red: Double,
    public val green: Double,
    public val blue: Double,
)

/**
 * The decoded pixels of one elevation Source Tile, carrying the exact channel values its
 * [TerrainDemEncoding] packed rather than elevations. A consumer still applies that encoding's own
 * formula to [rgba] to obtain metres; Rentile decodes the image container and nothing beyond it.
 *
 * **Layout.** [rgba] holds `width * height * 4` bytes: one texel per four bytes in red, green,
 * blue, alpha order, rows tightly packed at `width * 4` bytes with no padding, and rows ordered
 * **top-down**, so index `(y * width + x) * 4` is the texel at column `x` of row `y` counting `y`
 * from the tile's top edge, which is its north edge under XYZ. A consumer that walks these rows
 * bottom-up gets a mirrored planet rather than a failure.
 *
 * **Alpha.** [rgba] is **never premultiplied**. A DEM packs elevation across red, green and blue,
 * so scaling those channels by a non-opaque alpha would corrupt the elevation silently instead of
 * merely darkening a picture. Rentile reads these pixels as `ColorAlphaType.UNPREMUL` and
 * preserves whatever alpha the encoded image carried rather than assuming it is opaque, and it
 * requests no colour-space conversion, so the channel values are the ones the image encoded even
 * when it declares a colour profile.
 */
public data class DemTexels(
    public val width: Int,
    public val height: Int,
    public val rgba: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "DEM texel dimensions must be positive" }
        require(rgba.size.toLong() == width.toLong() * height.toLong() * 4L) {
            "DEM texels must hold exactly one tightly packed RGBA8 texel per pixel"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is DemTexels &&
            width == other.width &&
            height == other.height &&
            rgba.contentEquals(other.rgba)

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + rgba.contentHashCode()
        return result
    }

    override fun toString(): String = "DemTexels(width=$width, height=$height, byteCount=${rgba.size})"
}

/**
 * Validated encoded DEM image bytes acquired through Rentile's transport and raw cache, together
 * with the [texels] that the decode which validated them already produced.
 *
 * [bytes] stays the exact encoded resource the provider served - a WebP for most Terrain RGB
 * sources, a PNG for some - and remains the right value to hash for cache identity. [texels] is
 * that same image decoded once, so a consumer needs no decoder of its own for whichever container
 * the provider chose.
 */
public data class ValidatedDemTile(
    public val requestedTile: TileId,
    public val sourceTile: TileId,
    public val sourceId: String,
    public val encoding: TerrainDemEncoding,
    public val bytes: ByteArray,
    public val contentDigest: String,
    /** Appended, not inserted, for the reason recorded on [ResourceLimits.maxGlyphRangeBytes]. */
    public val texels: DemTexels,
) {
    override fun equals(other: Any?): Boolean =
        other is ValidatedDemTile &&
            requestedTile == other.requestedTile &&
            sourceTile == other.sourceTile &&
            sourceId == other.sourceId &&
            encoding == other.encoding &&
            bytes.contentEquals(other.bytes) &&
            contentDigest == other.contentDigest &&
            texels == other.texels

    override fun hashCode(): Int {
        var result = requestedTile.hashCode()
        result = 31 * result + sourceTile.hashCode()
        result = 31 * result + sourceId.hashCode()
        result = 31 * result + encoding.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + contentDigest.hashCode()
        result = 31 * result + texels.hashCode()
        return result
    }

    override fun toString(): String =
        "ValidatedDemTile(requestedTile=$requestedTile, sourceTile=$sourceTile, sourceId=$sourceId, " +
            "encoding=$encoding, byteCount=${bytes.size}, contentDigest=$contentDigest, texels=$texels)"
}

/** Operational limits owned by one long-lived [BasemapRasterizer]. */
public data class ExecutionPolicy(
    public val maxConcurrentExchanges: Int = 8,
    public val maxConcurrentExchangesPerOrigin: Int = 6,
    public val maxConcurrentDecodes: Int = 2,
    public val maxResidentDecodedBytes: Long = 128L * 1024L * 1024L,
    public val maxConcurrentMetatileWorkers: Int = 1,
) {
    init {
        require(maxConcurrentExchanges > 0)
        require(maxConcurrentExchangesPerOrigin in 1..maxConcurrentExchanges)
        require(maxConcurrentDecodes > 0)
        require(maxResidentDecodedBytes > 0)
        require(maxConcurrentMetatileWorkers > 0)
    }
}

/** Hard safety ceilings for untrusted encoded and decoded resources. */
public data class ResourceLimits(
    public val maxStyleBytes: Long = 8L * 1024L * 1024L,
    public val maxMetadataBytes: Long = 4L * 1024L * 1024L,
    public val maxTileBytes: Long = 32L * 1024L * 1024L,
    public val maxSpriteImageBytes: Long = 32L * 1024L * 1024L,
    public val maxGeoJsonBytes: Long = 64L * 1024L * 1024L,
    public val maxRasterDimensionPx: Int = 8192,
    public val maxDecodedRasterBytes: Long = 256L * 1024L * 1024L,
    public val maxMvtLayers: Int = 512,
    public val maxMvtFeatures: Int = 500_000,
    public val maxMvtTags: Int = 4_000_000,
    public val maxMvtCommands: Int = 8_000_000,
    public val maxMvtCoordinates: Int = 8_000_000,
    public val maxMvtExtent: Int = 65_536,
    public val maxRedirects: Int = 5,
    /**
     * Appended, not inserted, and every field added here in future must be too. These two arrived
     * with label candidates, and while they sat between `maxGeoJsonBytes` and
     * `maxRasterDimensionPx` a caller written against the previous version silently changed
     * meaning instead of failing to compile: six positional arguments ending in a `4096` intended
     * for `maxRasterDimensionPx` type-check as a `Long` and set `maxGlyphRangeBytes` to 4 KiB, so
     * every glyph range over 4 KiB raised [SafetyLimitException]. A source-compatible signature
     * that quietly reassigns a caller's arguments is worse than an incompatible one.
     */
    public val maxGlyphRangeBytes: Long = 1L * 1024L * 1024L,
    /**
     * The expanded rolling corpus required 159 ranges for Outdoor at Tokyo z14 on 2026-08-23;
     * the measurement and resulting headroom are documented in `compatibility/README.md`.
     * That count is per tile, while this ceiling is per batch: a real
     * consumer acquires ten to thirty tiles in one [acquireLabelCandidates] call. Ranges dedupe
     * across the whole batch, so a large viewport over one dense area does not multiply it by
     * the tile count — but a style with several font stacks does multiply it by the stack count.
     * The failure mode is a hard [SafetyLimitException] that fails the entire acquisition, so
     * headroom is worth more than tightness here. 256 is the next power of two above that observed
     * closure and still a hard per-batch bound; 512 was rejected because a dense atlas at that
     * range count cannot fit the independent [maxRasterDimensionPx] and
     * [maxDecodedRasterBytes] defaults. Tightening this needs a real multi-tile viewport
     * measurement, not a re-derivation from one corpus tile.
     */
    public val maxGlyphRangesPerBatch: Int = 256,
) {
    init {
        require(maxStyleBytes > 0)
        require(maxMetadataBytes > 0)
        require(maxTileBytes > 0)
        require(maxSpriteImageBytes > 0)
        require(maxGeoJsonBytes > 0)
        require(maxGlyphRangeBytes > 0)
        require(maxGlyphRangesPerBatch > 0)
        require(maxRasterDimensionPx > 0)
        require(maxDecodedRasterBytes > 0)
        require(maxMvtLayers > 0)
        require(maxMvtFeatures > 0)
        require(maxMvtTags > 0)
        require(maxMvtCommands > 0)
        require(maxMvtCoordinates > 0)
        require(maxMvtExtent > 0)
        require(maxRedirects >= 0)
    }
}

/** Dependencies and non-content policy owned by one rasterizer instance. */
public data class RentileConfiguration(
    public val transport: ResourceTransport,
    public val rawResourceStore: RawResourceStore,
    public val sessionProvider: MapSessionProvider = MapSessionProvider.None,
    public val credentialProvider: CredentialProvider = CredentialProvider.None,
    public val clock: RentileClock = RentileClock.System,
    public val metricsSink: MetricsSink = MetricsSink.None,
    public val diagnosticSink: DiagnosticSink = DiagnosticSink.None,
    public val executionPolicy: ExecutionPolicy = ExecutionPolicy(),
    public val resourceLimits: ResourceLimits = ResourceLimits(),
)

/** Immutable style program owned by the rasterizer instance that prepared it. */
public interface PreparedStyle {
    public val digest: String
    public val policy: CompatibilityPolicy
    public val diagnostics: List<RenderDiagnostic>
}

/** Network-free rendering input with content keys available before drawing. */
public interface PreparedBatch : AutoCloseable {
    public val tiles: List<TileId>
    public val contentKeys: Map<TileId, String>
    public val diagnostics: List<RenderDiagnostic>
    public val substitutions: Map<TileId, List<ResourceSubstitution>>

    /** Idempotent, non-blocking, and non-throwing. */
    override fun close()
}

/** A successful, caller-owned encoded output tile. */
public data class RenderedTile(
    public val id: TileId,
    public val pngBytes: ByteArray,
    public val contentKey: String,
    public val diagnostics: List<RenderDiagnostic> = emptyList(),
)

/**
 * One output tile as premultiplied N32 pixels, skipping PNG entirely.
 *
 * A host that uploads tiles straight to a GL texture reads [rgbaBytes] and is done. The PNG path
 * makes it encode and then immediately decode the same image -- measured at ~65 ms of encode per
 * 256 px tile on a mid-range phone, plus the decode, purely to feed a disk cache the live path
 * never reads back within a session.
 *
 * [contentKey] is identical to the PNG path's for the same tile: the pixel format is a transport
 * choice, not part of content identity, so a tile rendered either way shares one cache entry.
 */
public data class RawRenderedTile(
    public val id: TileId,
    public val rgbaBytes: ByteArray,
    public val widthPx: Int,
    public val heightPx: Int,
    public val contentKey: String,
    public val diagnostics: List<RenderDiagnostic> = emptyList(),
)

/** All-or-error result for a raw-pixel render operation. */
public data class RawRenderBatch(
    public val tiles: List<RawRenderedTile>,
    public val diagnostics: List<RenderDiagnostic> = emptyList(),
)

/** All-or-error result for a caller-defined render operation. */
public data class RenderBatch(
    public val tiles: List<RenderedTile>,
    public val diagnostics: List<RenderDiagnostic> = emptyList(),
)

/** Public renderer boundary. Implementations are process-local resource owners. */
public interface BasemapRasterizer : AutoCloseable {
    public suspend fun prepare(
        style: StyleInput,
        policy: CompatibilityPolicy = CompatibilityPolicy.Default,
    ): PreparedStyle

    /**
     * Stable identity for a caller-owned rendered-output cache lookup.
     *
     * Unlike [PreparedBatch.contentKeys], this key is available before raw tile acquisition. It
     * includes every output-affecting request input known at this boundary and deliberately omits
     * credentials, sessions, validators, and acquired-resource digests. A caller must store the
     * eventual content key and substitution provenance beside the rendered bytes it indexes with
     * this value.
     */
    public fun outputRequestKey(
        style: PreparedStyle,
        tile: TileId,
        options: RenderOptions = RenderOptions(),
    ): String

    public suspend fun prepareBatch(
        style: PreparedStyle,
        tiles: List<TileId>,
        options: RenderOptions = RenderOptions(),
        resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
        substitutionPolicy: TileSubstitutionPolicy = TileSubstitutionPolicy.Disabled,
    ): PreparedBatch

    /**
     * Retries exact acquisition only for resources currently represented by substitutes.
     * Acquisition failures retain the existing substitutes and do not fail the recovery cycle.
     */
    public suspend fun retryExact(batch: PreparedBatch): ExactRecoveryResult

    /** Resolved visible text-bearing vector symbol layers in style order. URL templates remain private. */
    public fun labelLayerDescriptors(style: PreparedStyle): List<LabelLayerDescriptor>

    /** All-or-error validated MVT acquisition. Tile substitution is deliberately not applied. */
    /**
     * Fetches every raw source resource [tiles] will need into the raw cache, decoding and
     * rasterizing nothing.
     *
     * This exists because acquisition and rasterization otherwise run in lockstep and leave each
     * other idle. Measured on a cold 1817-frame export: raising raster workers from 2 to 4 cut
     * rasterization wall time by 57 s and grew acquisition by 61 s, for no net change — the signature
     * of a serial pipeline. Warming the cache ahead of the rasterization cursor is how that idle
     * network time gets used.
     *
     * Two properties make this cheap where a `prepareBatch`-based read-ahead was not:
     *
     * - **It does not decode.** A prefetch that decoded would take the cores the rasterizer needs.
     *   An earlier whole-set read-ahead built on `prepareBatch` did exactly that and measured a 5-7x
     *   regression.
     * - **It costs no heap.** Bytes land in the caller's [RawResourceStore], which is disk-backed in
     *   every production host, not in a decoded in-memory form.
     *
     * Tile substitution is deliberately not applied: substituting is a *rendering* decision about a
     * resource that turned out to be unavailable, and warming makes no rendering decisions. A tile
     * this cannot fetch is simply not warmed, and the later `prepareBatch` substitutes as it always
     * would.
     *
     * **Per-resource failures are absorbed, not thrown.** A prefetch must never fail the work it is
     * meant to help; [RawWarmSummary.failed] reports how many were lost. It still throws for callers'
     * programming errors — an unowned style, a tile outside the compatibility profile — and for
     * cancellation.
     *
     * Ordering is the caller's responsibility, and it matters: issue tiles in the order they will be
     * rendered. A read-ahead that fetched a whole session in plan order while the cursor sat near the
     * start spent the contended connection budget on tiles it would not reach for minutes.
     */
    public suspend fun warmRawResources(
        style: PreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): RawWarmSummary

    public suspend fun acquireLabelTiles(
        style: PreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): List<ValidatedMvtTile>

    /**
     * Stable identity for a caller-owned label-candidate cache lookup, available before any
     * network.
     *
     * Covers style identity, tile identities and a label-semantics version. It deliberately omits
     * credentials, sessions, validators and the glyph closure: which Glyph Ranges a tile set needs
     * is not knowable until its features are decoded, so this key cannot depend on them. A caller
     * must store [LabelCandidateBatch.contentKey] beside whatever it indexes with this value.
     */
    public fun labelCandidateRequestKey(style: PreparedStyle, tiles: List<TileId>): String

    /**
     * Acquires this tile set's Label Tiles, decodes and evaluates them, and freezes the Glyph
     * Ranges the batch will need - without acquiring any of them.
     *
     * Tile substitution is deliberately not applied, exactly as in [acquireLabelCandidates].
     */
    public suspend fun planLabelCandidates(
        style: PreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): LabelCandidatePlan

    /**
     * Acquires [LabelCandidatePlan.glyphClosure] and assembles the batch, reusing the access mode
     * the plan was made with so one batch cannot disagree with itself about what caching meant.
     */
    public suspend fun acquireLabelCandidates(plan: LabelCandidatePlan): LabelCandidateBatch

    /**
     * All-or-error validated Label acquisition. Tile substitution is deliberately not applied.
     *
     * A style declaring no `glyphs` template yields an empty batch carrying
     * [DiagnosticCode.GLYPH_RANGE_UNAVAILABLE] rather than failing: label preparation is opt-in,
     * and a style without glyphs is legitimate.
     */
    public suspend fun acquireLabelCandidates(
        style: PreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): LabelCandidateBatch

    /** Returns null when the prepared style does not select a raster-dem terrain source. */
    public fun terrainSourceDescriptor(style: PreparedStyle): TerrainSourceDescriptor?

    /** Returns null when the prepared style has no complete supported ground-light pair. */
    public fun groundRadianceDescriptor(style: PreparedStyle): GroundRadianceDescriptor?

    /** All-or-error validated DEM acquisition. Tile substitution is deliberately not applied. */
    public suspend fun acquireTerrainTiles(
        style: PreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): List<ValidatedDemTile>

    public suspend fun render(
        batch: PreparedBatch,
        tiles: List<TileId> = batch.tiles,
    ): RenderBatch

    /**
     * Renders to premultiplied N32 pixels instead of PNG. Same drawing, same content identity;
     * only the encode is skipped. See [RawRenderedTile].
     */
    public suspend fun renderRaw(
        batch: PreparedBatch,
        tiles: List<TileId> = batch.tiles,
    ): RawRenderBatch

    public suspend fun render(
        style: PreparedStyle,
        tiles: List<TileId>,
        options: RenderOptions = RenderOptions(),
        resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
        substitutionPolicy: TileSubstitutionPolicy = TileSubstitutionPolicy.Disabled,
    ): RenderBatch

    /** Idempotent, non-blocking, and non-throwing. */
    override fun close()

    /** Suspends until workers, leases, native objects, and secret state are released. */
    public suspend fun awaitClosed()
}

/** Factory for the process-local deep rendering module. */
public object Rentile {
    public fun create(configuration: RentileConfiguration): BasemapRasterizer =
        createBasemapRasterizer(configuration)
}
