package com.rohittp.rentile

import com.rohittp.rentile.internal.GateLane
import com.rohittp.rentile.internal.createBasemapRasterizer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The priority a caller passes has to reach the render gate, and nothing else can tell whether it
 * did: priority never touches the draw path, so two tiles rendered at different priorities are
 * byte-identical. A call site that dropped the argument, or an overload that forgot to forward it,
 * would leave every other test green and silently give the read-ahead the workers back.
 *
 * So these tests observe the one thing that differs — the lane the gate is asked for — through the
 * gate's test recorder, and cover every render entry point rather than one of them.
 */
class RenderPriorityTest {
    @Test
    fun renderTakesTheCallersPriorityAndDefaultsToReadAhead() = runTest {
        withRecordedRenderLanes { rasterizer, lanes ->
            val style = rasterizer.prepare(StyleInput.InlineJson(BACKGROUND_STYLE))
            val batch = rasterizer.prepareBatch(style, listOf(TILE))
            try {
                rasterizer.render(batch)
                assertEquals(
                    listOf(GateLane.DEFERRED),
                    lanes(),
                    "omitting the priority must render as read-ahead",
                )

                rasterizer.render(batch, priority = RenderPriority.URGENT)
                assertEquals(listOf(GateLane.DEFERRED, GateLane.FIRST), lanes())
            } finally {
                batch.close()
            }
        }
    }

    @Test
    fun renderRawTakesTheCallersPriorityAndDefaultsToReadAhead() = runTest {
        // The path a consumer uploading straight to a texture uses, which is the one that carries
        // the tiles a session is about to present.
        withRecordedRenderLanes { rasterizer, lanes ->
            val style = rasterizer.prepare(StyleInput.InlineJson(BACKGROUND_STYLE))
            val batch = rasterizer.prepareBatch(style, listOf(TILE))
            try {
                rasterizer.renderRaw(batch)
                assertEquals(listOf(GateLane.DEFERRED), lanes())

                rasterizer.renderRaw(batch, priority = RenderPriority.URGENT)
                assertEquals(listOf(GateLane.DEFERRED, GateLane.FIRST), lanes())
            } finally {
                batch.close()
            }
        }
    }

    @Test
    fun theStyleOverloadForwardsThePriorityThroughTheBatchItPrepares() = runTest {
        // This overload prepares a batch and renders it, so it is the one that can lose the
        // priority between two of its own calls.
        withRecordedRenderLanes { rasterizer, lanes ->
            val style = rasterizer.prepare(StyleInput.InlineJson(BACKGROUND_STYLE))

            rasterizer.render(style, listOf(TILE))
            assertEquals(listOf(GateLane.DEFERRED), lanes())

            rasterizer.render(style, listOf(TILE), priority = RenderPriority.URGENT)
            assertEquals(listOf(GateLane.DEFERRED, GateLane.FIRST), lanes())
        }
    }

    @Test
    fun priorityDoesNotEnterOutputIdentity() = runTest {
        // Priority decides when a tile is drawn, never what it is. If it ever reached a content key
        // a consumer's output cache would hold two entries for one tile and serve neither reliably.
        withRecordedRenderLanes { rasterizer, _ ->
            val style = rasterizer.prepare(StyleInput.InlineJson(BACKGROUND_STYLE))
            val batch = rasterizer.prepareBatch(style, listOf(TILE))
            try {
                val readAhead = rasterizer.render(batch).tiles.single()
                val urgent = rasterizer.render(batch, priority = RenderPriority.URGENT).tiles.single()

                assertEquals(readAhead.contentKey, urgent.contentKey)
                assertEquals(readAhead.pngBytes.toList(), urgent.pngBytes.toList())
            } finally {
                batch.close()
            }
        }
    }

    /**
     * One worker, so a lane is the only thing that could decide an order, and a transport that
     * throws, so nothing here depends on the network.
     */
    private suspend fun withRecordedRenderLanes(
        block: suspend (BasemapRasterizer, () -> List<GateLane>) -> Unit,
    ) {
        // Each render below draws exactly one tile and is awaited before the next, so the recorder
        // is never appended to concurrently.
        val recorded = mutableListOf<GateLane>()
        val rasterizer = createBasemapRasterizer(
            RentileConfiguration(
                transport = ResourceTransport { error("this test must not reach the network") },
                rawResourceStore = InMemoryRawResourceStore(),
                executionPolicy = ExecutionPolicy(maxConcurrentMetatileWorkers = 1),
            ),
            renderLaneRecorderForTest = { recorded += it },
        )
        try {
            block(rasterizer) { recorded.toList() }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    private companion object {
        val TILE = TileId(0, 0, 0)

        // Sourceless on purpose: the priority is about scheduling the draw, so the test should not
        // be able to fail for a reason that lives in acquisition.
        const val BACKGROUND_STYLE =
            """{"version":8,"sources":{},"layers":[""" +
                """{"id":"bg","type":"background","paint":{"background-color":"#3366cc"}}]}"""
    }
}
