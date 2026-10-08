package com.rohittp.rentile.internal.sprite

import com.rohittp.rentile.internal.ProtectedResourceUrl
import com.rohittp.rentile.internal.SecretContext
import com.rohittp.rentile.internal.metadata.resolveHttpReference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a style's root `sprite` key resolved to, kept on the prepared style for
 * `acquireSpriteAtlas` (ADR 0036).
 *
 * Preparation itself resolves a sprite only when a layer draws from one, so the prepared atlas
 * alone cannot answer "does this style have a sprite?": a style whose sprite no layer uses
 * prepared without fetching it. This keeps the answer, with the URL protected exactly as every
 * other credential-bearing URL in a compiled style is.
 */
internal sealed interface SpriteReference {
    /** The style declares no `sprite`: there is no atlas, and that is not an error. */
    data object Absent : SpriteReference

    /**
     * The style declares a sprite this profile cannot acquire - the multi-sprite array form, a
     * non-string value, or a relative reference with no base URI. [reason] is a static sentence
     * and must never carry a URL.
     */
    class Unacquirable(val reason: String) : SpriteReference

    /** The sprite's base URL, absolute, before any `.json`, `.png` or `@2x` suffix. */
    class Resolved(val baseUrl: ProtectedResourceUrl) : SpriteReference
}

internal fun spriteReferenceOf(root: JsonObject, baseUri: String?, secretContext: SecretContext): SpriteReference {
    val value = root["sprite"] ?: return SpriteReference.Absent
    val reference = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: return SpriteReference.Unacquirable("The style's sprite is not a single sprite URL")
    val resolved = resolveAbsoluteSpriteUrl(reference, baseUri)
        ?: return SpriteReference.Unacquirable("The sprite URL cannot be resolved against the style base URI")
    return SpriteReference.Resolved(secretContext.protectUrl(resolved))
}

/** An absolute `http(s)` sprite reference as-is, anything else resolved against [baseUri]. */
internal fun resolveAbsoluteSpriteUrl(spriteReference: String, baseUri: String?): String? = when {
    spriteReference.startsWith("https://") || spriteReference.startsWith("http://") -> spriteReference
    baseUri != null -> resolveHttpReference(baseUri, spriteReference)
    else -> null
}
