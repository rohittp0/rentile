package com.rohittp.rentile

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlin.test.Test

class NativeMapCatalogCorpusSmokeTest {
    @Test
    fun rendersCompletePublicCatalogOnNativeTarget(): Unit =
        NativeMapCatalogCorpusSmokeRunner(
            HttpClient(Darwin) {
                followRedirects = false
                expectSuccess = false
            },
        ).run()
}
