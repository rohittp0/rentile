package com.rohittp.rentile

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import kotlin.test.Test

class NativeMapCatalogCorpusSmokeTest {
    @Test
    fun rendersCompletePublicCatalogOnNativeTarget(): Unit =
        NativeMapCatalogCorpusSmokeRunner(
            HttpClient(Curl) {
                followRedirects = false
                expectSuccess = false
            },
        ).run()
}
