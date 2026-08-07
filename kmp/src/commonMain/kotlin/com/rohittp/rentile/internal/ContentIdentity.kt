package com.rohittp.rentile.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.toByteString

internal fun ByteArray.sha256Hex(): String = toByteString().sha256().hex()

internal fun String.sha256Hex(): String = encodeToByteArray().sha256Hex()

internal fun JsonElement.canonicalJson(): String = when (this) {
    is JsonObject -> entries
        .sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
            JsonPrimitive(key).toString() + ":" + value.canonicalJson()
        }
    is JsonArray -> joinToString(prefix = "[", postfix = "]") { it.canonicalJson() }
    is JsonPrimitive -> toString()
    JsonNull -> "null"
}

private val authenticationQueryNames: Set<String> = setOf(
    "access_token",
    "apikey",
    "api_key",
    "key",
    "mtsid",
    "session",
    "session_id",
    "token",
)

/** Replaces authentication query values while preserving content-affecting query values. */
internal fun String.withRedactedAuthenticationQuery(): String {
    val queryStart = indexOf('?')
    if (queryStart < 0) return this
    val fragmentStart = indexOf('#', startIndex = queryStart + 1).let { if (it < 0) length else it }
    val prefix = substring(0, queryStart + 1)
    val query = substring(queryStart + 1, fragmentStart)
    val suffix = substring(fragmentStart)
    val redacted = query.split('&').joinToString("&") { parameter ->
        val separator = parameter.indexOf('=')
        val name = if (separator < 0) parameter else parameter.substring(0, separator)
        if (name.lowercase() in authenticationQueryNames) "$name=<redacted>" else parameter
    }
    return prefix + redacted + suffix
}

internal fun JsonElement.redactedForIdentity(): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { (_, value) -> value.redactedForIdentity() })
    is JsonArray -> JsonArray(map { it.redactedForIdentity() })
    is JsonPrimitive -> if (isString) JsonPrimitive(content.withRedactedAuthenticationQuery()) else this
    JsonNull -> JsonNull
}
