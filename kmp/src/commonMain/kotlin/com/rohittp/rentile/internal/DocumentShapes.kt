package com.rohittp.rentile.internal

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Cheap "is this the kind of document it claims to be?" checks for the style closure.
 *
 * They exist for one shape of failure: a network that answers 200 with something that is not the
 * document at all — a captive portal's sign-in page is the usual one, and it arrives with no
 * validators, so under stale-while-revalidate it would be stored, served on every later
 * preparation, fail in whatever parses it every time, and be the newest file in an age-trimmed
 * cache. These are deliberately shallow: they answer whether the bytes can possibly be the
 * document, not whether it is a good one. The compiler and the decoders still own that.
 */
private val shapeJson = Json { isLenient = false }

/** A JSON object, whatever is in it. */
internal fun ByteArray.isJsonObjectDocument(): Boolean = jsonObjectOrNull() != null

/** A JSON object carrying at least one field a style document must have. */
internal fun ByteArray.isStyleDocument(): Boolean {
    val root = jsonObjectOrNull() ?: return false
    return "version" in root || "layers" in root
}

/** The eight-byte PNG signature; sprite images are PNG on every path that reads them. */
internal fun ByteArray.hasPngSignature(): Boolean {
    if (size < PNG_SIGNATURE.size) return false
    return PNG_SIGNATURE.indices.all { this[it] == PNG_SIGNATURE[it] }
}

private fun ByteArray.jsonObjectOrNull(): JsonObject? = try {
    shapeJson.parseToJsonElement(decodeToString()) as? JsonObject
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
)
