package com.rohittp.rentile.internal.style

import com.rohittp.rentile.CompatibilityPolicy
import com.rohittp.rentile.PreparedStyle
import com.rohittp.rentile.RenderDiagnostic
import com.rohittp.rentile.internal.ProtectedResourceUrl
import com.rohittp.rentile.internal.SecretContext

internal data class CompiledColor(
    val red: Int,
    val green: Int,
    val blue: Int,
    val alpha: Int,
)

internal data class CompiledBackgroundLayer(
    val color: CompiledColor,
    val opacity: Float,
)

internal enum class RasterScheme {
    XYZ,
    TMS,
}

internal enum class RasterResampling {
    LINEAR,
    NEAREST,
}

internal data class CompiledRasterSource(
    val idDigest: String,
    val tileTemplates: List<ProtectedResourceUrl>,
    val tileSize: Int,
    val scheme: RasterScheme,
    val minZoom: Int,
    val maxZoom: Int,
)

internal sealed interface CompiledDrawLayer

internal data class BackgroundDrawLayer(
    val background: CompiledBackgroundLayer,
) : CompiledDrawLayer

internal data class RasterDrawLayer(
    val source: CompiledRasterSource,
    val opacity: Float,
    val resampling: RasterResampling,
    val minZoom: Double,
    val maxZoom: Double,
) : CompiledDrawLayer {
    fun isActiveAt(zoom: Int): Boolean = zoom >= minZoom && zoom < maxZoom && zoom >= source.minZoom
}

internal class CompiledPreparedStyle(
    val owner: Any,
    override val digest: String,
    override val policy: CompatibilityPolicy,
    override val diagnostics: List<RenderDiagnostic>,
    val drawLayers: List<CompiledDrawLayer>,
    val secretContext: SecretContext,
) : PreparedStyle
