package com.rohittp.rentile

import com.rohittp.rentile.internal.mvt.DecodedVectorGeometry
import com.rohittp.rentile.internal.mvt.MvtDecoder
import com.rohittp.rentile.internal.sha256Hex
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Opt-in local corpus probe. Reports contain basenames and digests, never provider URLs/keys. */
class HostExtrusionCorpusSmokeTest {
    @Test fun comparesCompactSourcePolygonsAndMeasuresBoundedAcquisition(): Unit = runBlocking {
        val directory = System.getenv("RENTILE_EXTRUSION_CORPUS_DIR")?.let(Path::of) ?: return@runBlocking
        val report = System.getenv("RENTILE_EXTRUSION_CORPUS_REPORT")?.let(Path::of)
        val files = Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().matches(Regex("(paris|manhattan|tokyo)_15_\\d+_\\d+\\.pbf")) }.sorted().toList()
        }
        assertTrue(files.isNotEmpty(), "No z15 city MVT files in corpus")
        val rows = mutableListOf<String>()
        for (file in files) {
            val bytes = Files.readAllBytes(file)
            val parts = file.fileName.toString().removeSuffix(".pbf").split('_')
            val tile = TileId(15, parts[2].toInt(), parts[3].toInt())
            val r = Rentile.create(RentileConfiguration(ResourceTransport { TransportResponse(200, bytes) }, object : RawResourceStore {
                override suspend fun read(key: RawResourceKey): StoredRawResource? = null
                override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {}
                override suspend fun remove(key: RawResourceKey) {}
            }))
            try {
                val s = r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(ExtrusionFixtures.layer())), CompatibilityPolicy.RentileV1HostExtrusions)
                // Warm the code, not the disk cache. The default raw store is NoOp.
                repeat(3) { r.acquireExtrusionCandidates(s, listOf(tile)) }
                val times = ArrayList<Long>()
                var b = r.acquireExtrusionCandidates(s, listOf(tile))
                repeat(7) {
                    val start = System.nanoTime()
                    b = r.acquireExtrusionCandidates(s, listOf(tile))
                    times += System.nanoTime() - start
                }
                val original = MvtDecoder(ResourceLimits()).decode(bytes).layers.single { it.name == "building" }
                val polygons = original.features.withIndex().filter { it.value.geometry is DecodedVectorGeometry.Polygons }
                assertEquals(polygons.size, b.candidates.size)
                for ((position, indexed) in polygons.withIndex()) {
                    val candidate = b.candidates[position]
                    assertEquals(indexed.index, candidate.featureIndex)
                    val rings = (indexed.value.geometry as DecodedVectorGeometry.Polygons).rings
                    assertEquals(rings.size, candidate.geometry.ringCount)
                    for ((ringIndex, ring) in rings.withIndex()) {
                        val start = candidate.geometry.ringStart(ringIndex)
                        assertEquals(ring.points.size, candidate.geometry.ringEnd(ringIndex) - start)
                        for ((i, point) in ring.points.withIndex()) {
                            assertEquals(point.x, candidate.geometry.x(start + i))
                            assertEquals(point.y, candidate.geometry.y(start + i))
                        }
                    }
                }
                rows += """{"file":"${file.fileName}","digest":"${bytes.sha256Hex()}","encodedBytes":${bytes.size},"candidates":${b.candidates.size},"polygons":${b.candidates.sumOf { it.geometry.polygonCount }},"rings":${b.candidates.sumOf { it.geometry.ringCount }},"vertices":${b.candidates.sumOf { it.geometry.vertexCount }},"primitiveBytes":${b.candidates.sumOf { it.geometry.primitiveByteCount }},"estimatedRetainedBytes":${b.estimatedRetainedBytes},"medianMillis":${times.sorted()[3] / 1_000_000.0}}"""
            } finally { r.close(); r.awaitClosed() }
        }
        val json = "{\"platform\":\"JVM macOS arm64\",\"transport\":\"local in-memory fixtures\",\"tiles\":[${rows.joinToString(",")}]}"
        report?.let { Files.writeString(it, json) }
        println(json)
    }
}
