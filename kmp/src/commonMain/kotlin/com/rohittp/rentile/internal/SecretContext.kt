package com.rohittp.rentile.internal

import com.rohittp.rentile.RasterizerClosedException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal data class ProtectedResourceUrl(
    val canonicalUrl: String,
    private val secretContext: SecretContext,
    private val index: Int,
) {
    fun resolve(): String = secretContext.resolve(index)

    override fun toString(): String = "ProtectedResourceUrl(canonicalUrl=<redacted>)"
}

@OptIn(ExperimentalAtomicApi::class)
internal class SecretContext {
    private val cleared = AtomicBoolean(false)
    private val values = AtomicReference<List<String>>(emptyList())

    fun protectUrl(url: String): ProtectedResourceUrl {
        check(!cleared.load()) { "Secret context is cleared" }
        while (true) {
            val current = values.load()
            val index = current.size
            check(!cleared.load()) { "Secret context is cleared" }
            if (values.compareAndSet(current, current + url)) {
                if (cleared.load()) {
                    values.store(emptyList())
                    throw RasterizerClosedException()
                }
                return ProtectedResourceUrl(url.withRedactedAuthenticationQuery(), this, index)
            }
        }
    }

    fun clear() {
        if (cleared.compareAndSet(expectedValue = false, newValue = true)) {
            values.store(emptyList())
        }
    }

    fun resolve(index: Int): String {
        if (cleared.load()) throw RasterizerClosedException()
        return values.load().getOrNull(index) ?: throw RasterizerClosedException()
    }
}
