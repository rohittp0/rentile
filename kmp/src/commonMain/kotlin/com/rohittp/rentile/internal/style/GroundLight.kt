package com.rohittp.rentile.internal.style

import com.rohittp.rentile.GroundRadianceDescriptor
import com.rohittp.rentile.StylePreparationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow

private data class LiteralLight(
    val color: DoubleArray,
    val intensity: Double,
    val direction: DoubleArray? = null,
)

internal fun compileGroundRadiance(root: JsonObject): GroundRadianceDescriptor? {
    val element = root["lights"] ?: return null
    if (element is JsonNull) return null
    val lights = element as? JsonArray ?: invalid("style lights must be an array")
    val ambientEntries = lights.entries("ambient")
    val directionalEntries = lights.entries("directional")
    if (ambientEntries.isEmpty() || directionalEntries.isEmpty()) return null
    if (ambientEntries.size != 1) invalid("style must contain exactly one ambient light")
    if (directionalEntries.size != 1) invalid("style must contain exactly one directional light")

    val ambient = literalLight(ambientEntries.single(), "ambient", directional = false)
    val directional = literalLight(directionalEntries.single(), "directional", directional = true)
    val directionZ = cos(checkNotNull(directional.direction)[1] * PI / 180.0)
    val ambientLinear = ambient.color.map { it.pow(LIGHT_GAMMA) * ambient.intensity }
    val directionalLinear = directional.color.map { it.pow(LIGHT_GAMMA) * directional.intensity }
    val luminance = directionalLinear[0] * LUMINANCE_RED +
        directionalLinear[1] * LUMINANCE_GREEN +
        directionalLinear[2] * LUMINANCE_BLUE
    val directionalMinimum = 1.0 - AMBIENT_DIRECTION_REDUCTION * min(luminance, 1.0)
    val directionalMix = min(directionZ + 1.0, 1.0)
    val directionalFactor = directionalMinimum + (1.0 - directionalMinimum) * directionalMix
    val verticalMix = asin(1.0) / PI + 0.5
    val ambientFactor = (VERTICAL_FACTOR_MINIMUM +
        (1.0 - VERTICAL_FACTOR_MINIMUM) * verticalMix) * directionalFactor

    fun component(index: Int): Double = (
        ambientLinear[index] * ambientFactor + directionalLinear[index] * directionZ
        ).pow(1.0 / LIGHT_GAMMA)
    return GroundRadianceDescriptor(component(0), component(1), component(2))
}

private fun JsonArray.entries(type: String): List<JsonObject> = mapNotNull { element ->
    (element as? JsonObject)?.takeIf { it["type"].stringOrNull() == type }
}

private fun literalLight(entry: JsonObject, label: String, directional: Boolean): LiteralLight {
    val properties = when (val element = entry["properties"]) {
        null, JsonNull -> JsonObject(emptyMap())
        is JsonObject -> element
        else -> invalid("$label light properties must be an object")
    }
    val theme = properties["color-use-theme"]
    if (theme != null && theme.stringOrNull() != "none") {
        invalid("$label color theme evaluation is unsupported")
    }
    return LiteralLight(
        color = literalColor(properties["color"], label),
        intensity = literalNumber(properties["intensity"], DEFAULT_INTENSITY, "$label intensity", 0.0, 1.0),
        direction = if (directional) literalDirection(properties["direction"], label) else null,
    )
}

private fun literalColor(value: JsonElement?, label: String): DoubleArray {
    if (value == null || value is JsonNull) return doubleArrayOf(1.0, 1.0, 1.0)
    val primitive = value as? JsonPrimitive
    if (primitive?.isString != true) invalid("$label color must be a literal CSS color")
    val normalized = when (primitive.content.trim().lowercase()) {
        "red" -> "#ff0000"
        "green" -> "#008000"
        "blue" -> "#0000ff"
        else -> primitive.content
    }
    val color = parseCssColor(normalized) ?: invalid("$label color literal is unsupported")
    return doubleArrayOf(color.red / 255.0, color.green / 255.0, color.blue / 255.0)
}

private fun literalNumber(
    value: JsonElement?,
    default: Double,
    label: String,
    minimum: Double,
    maximum: Double,
): Double {
    if (value == null || value is JsonNull) return default
    val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        ?: invalid("$label must be a literal number")
    if (!number.isFinite() || number !in minimum..maximum) invalid("$label is outside its supported range")
    return number
}

private fun literalDirection(value: JsonElement?, label: String): DoubleArray {
    if (value == null || value is JsonNull) return doubleArrayOf(DEFAULT_AZIMUTH, DEFAULT_POLAR)
    val array = value as? JsonArray ?: invalid("$label direction must be a literal two-number array")
    if (array.size != 2 || array.any { it !is JsonPrimitive || it.isString }) {
        invalid("$label direction must be a literal two-number array")
    }
    val azimuth = (array[0] as JsonPrimitive).doubleOrNull ?: invalid("$label azimuth must be numeric")
    val polar = (array[1] as JsonPrimitive).doubleOrNull ?: invalid("$label polar angle must be numeric")
    if (!azimuth.isFinite() || azimuth !in 0.0..360.0) invalid("$label azimuth is outside its supported range")
    if (!polar.isFinite() || polar !in 0.0..90.0) invalid("$label polar angle is outside its supported range")
    return doubleArrayOf(azimuth, polar)
}

private fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun invalid(message: String): Nothing = throw StylePreparationException(message)

private const val LIGHT_GAMMA = 2.2
private const val DEFAULT_INTENSITY = 0.5
private const val DEFAULT_AZIMUTH = 210.0
private const val DEFAULT_POLAR = 30.0
private const val AMBIENT_DIRECTION_REDUCTION = 0.3
private const val VERTICAL_FACTOR_MINIMUM = 0.92
private const val LUMINANCE_RED = 0.2126
private const val LUMINANCE_GREEN = 0.7152
private const val LUMINANCE_BLUE = 0.0722
