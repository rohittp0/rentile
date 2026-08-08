package com.rohittp.rentile

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RentileInitializationDeviceTest {

    @Test
    fun createDoesNotFailOnAndroidRegexInitialization() {
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { error("Transport must not run during creation") },
                rawResourceStore = object : RawResourceStore {
                    override suspend fun read(key: RawResourceKey): StoredRawResource? = null

                    override suspend fun write(
                        key: RawResourceKey,
                        resource: StoredRawResource,
                    ) = Unit

                    override suspend fun remove(key: RawResourceKey) = Unit
                },
            ),
        )

        rasterizer.close()
    }
}
