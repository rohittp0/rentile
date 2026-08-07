package com.rohittp.rentile.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class SyntheticPngProbeTest {
    @Test
    fun rasterSurfaceEncodesPngWithoutUiState() {
        val png = renderSyntheticPng()

        assertTrue(png.size > PNG_SIGNATURE.size)
        assertContentEquals(PNG_SIGNATURE, png.copyOfRange(0, PNG_SIGNATURE.size))
    }

    private companion object {
        val PNG_SIGNATURE: ByteArray = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
    }
}
