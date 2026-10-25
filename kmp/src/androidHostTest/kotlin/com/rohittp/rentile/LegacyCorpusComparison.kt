package com.rohittp.rentile

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Opt-in old/new differential corpus support. Raw snapshots are PRIVATE, not report artifacts. */
internal class LegacyCorpusComparison(
    private val mode: String,
    private val snapshot: Path,
    private val report: Path,
    captureClock: RentileClock = RentileClock.System,
) {
    val clockEpochMillis: Long = frozenEpoch(mode, snapshot, captureClock)
    val clock: RentileClock = RentileClock { clockEpochMillis }
    private val mutex = Mutex()
    private val requests = mutableListOf<String>()

    fun wrap(delegate: ResourceTransport): ResourceTransport = ResourceTransport { request ->
        mutex.withLock {
            val identity = listOf(
                request.resourceClass.name, request.url, request.metadata.accept,
                request.metadata.ifNoneMatch, request.metadata.ifModifiedSince,
            ).joinToString("\u0000").encodeToByteArray().digest()
            val metadataPath = snapshot.resolve("$identity.json")
            val bodyPath = snapshot.resolve("$identity.body")
            val response = if (Files.exists(metadataPath)) {
                val saved = Json.decodeFromString<Snapshot>(Files.readString(metadataPath))
                val body = Files.readAllBytes(bodyPath)
                check(body.digest() == saved.bodySha256) { "Frozen corpus response integrity failure" }
                saved.response(body)
            } else {
                check(mode == "CAPTURE") { "Frozen corpus resource missing: $identity" }
                delegate.execute(request).also { result ->
                    val saved = Snapshot.from(result)
                    Files.write(bodyPath, result.body, StandardOpenOption.CREATE_NEW)
                    Files.writeString(metadataPath, Json.encodeToString(saved), StandardOpenOption.CREATE_NEW)
                }
            }
            check(response.body.size.toLong() <= request.maxResponseBytes) { "Frozen response exceeds request limit" }
            requests += listOf(request.resourceClass.name, identity, request.maxResponseBytes,
                response.statusCode, response.body.size, response.body.digest()).joinToString("\t")
            response
        }
    }

    fun finish() {
        Files.write(report.resolve("acquisition.tsv"), requests.sorted())
        val outputs = Files.walk(report).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".png") &&
                (report.relativize(it).startsWith("tiles") || report.relativize(it).startsWith("legacy-samples")) }
                .map { path ->
                    "${report.relativize(path)}\t${Files.size(path)}\t${Files.readAllBytes(path).digest()}"
                }.sorted().toList()
        }
        Files.write(report.resolve("output-bytes.tsv"), outputs)
    }

    fun complete(profileStyles: Int) {
        Files.writeString(report.resolve("comparison-complete.json"),
            """{"schema":1,"mode":"$mode","acquisition":"${acquisition()}","profileStyles":$profileStyles,"clockEpochMillis":$clockEpochMillis}""",
            StandardOpenOption.CREATE_NEW)
    }

    @Serializable
    private data class Snapshot(
        val status: Int,
        val bodySha256: String,
        val contentType: String?,
        val etag: String?,
        val lastModified: String?,
        val cacheControl: String?,
        val expiresAtEpochMillis: Long?,
        val vary: List<String>,
        val retryAfterMillis: Long?,
        val redirectLocation: String?,
        val wireByteCount: Long?,
    ) {
        fun response(body: ByteArray) = TransportResponse(status, body, TransportResponseMetadata(
            contentType, etag, lastModified, cacheControl, expiresAtEpochMillis, vary,
            retryAfterMillis, redirectLocation, wireByteCount,
        ))

        companion object {
            fun from(response: TransportResponse): Snapshot = response.metadata.let { metadata ->
                Snapshot(response.statusCode, response.body.digest(), metadata.contentType, metadata.etag,
                    metadata.lastModified, metadata.cacheControl, metadata.expiresAtEpochMillis,
                    metadata.vary, metadata.retryAfterMillis, metadata.redirectLocation, metadata.wireByteCount)
            }
        }
    }

    companion object {
        private fun frozenEpoch(mode: String, snapshot: Path, captureClock: RentileClock): Long {
            require(mode in setOf("CAPTURE", "REPLAY")) { "Invalid corpus snapshot mode" }
            val path = snapshot.resolve("clock-epoch-millis.txt")
            if (mode == "CAPTURE") {
                require(Files.list(snapshot).use { it.findAny().isEmpty }) { "Capture snapshot must be empty" }
                val epoch = captureClock.nowEpochMillis()
                require(epoch > 0) { "Invalid capture clock epoch" }
                Files.writeString(path, "$epoch\n", StandardOpenOption.CREATE_NEW)
            }
            require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) { "Frozen clock file missing or invalid" }
            val encoded = Files.readString(path).trim()
            require(encoded.matches(Regex("[1-9][0-9]{0,18}"))) { "Invalid frozen clock epoch" }
            return requireNotNull(encoded.toLongOrNull()) { "Invalid frozen clock epoch" }
        }

        fun enabled(): Boolean = !System.getenv("RENTILE_CORPUS_SNAPSHOT_MODE").isNullOrBlank()

        private fun acquisition(): String = System.getenv("RENTILE_CORPUS_ACQUISITION") ?: "NATIVE"

        /** Reflection deliberately keeps this exact harness compilable with the 0.15.1 API. */
        fun options(size: Int, includeHillshade: Boolean, acquisition: String = acquisition()): RenderOptions {
            val options = RenderOptions(size, includeHillshade)
            return when (acquisition) {
                "BASELINE" -> {
                    check(RenderOptions::class.java.methods.none { it.name == "getRasterAcquisitionDensity" }) {
                        "BASELINE corpus requires the old RenderOptions API"
                    }
                    options
                }
                "NATIVE" -> options
                "OUTPUT_ZOOM" -> {
                    val getter = RenderOptions::class.java.getMethod("getRasterAcquisitionDensity")
                    val setting = getter.returnType.enumConstants.single { (it as Enum<*>).name == "OUTPUT_ZOOM" }
                    val copy = RenderOptions::class.java.methods.single { it.name == "copy" }
                    val values = (1..copy.parameterCount).map { index ->
                        RenderOptions::class.java.getMethod("component$index").invoke(options)
                    }.toMutableList()
                    // Later options append components after the density setting. Locate its type
                    // rather than assuming it is still the last field; all other defaults survive.
                    val densityIndex = copy.parameterTypes.indices.single {
                        copy.parameterTypes[it] == getter.returnType
                    }
                    values[densityIndex] = setting
                    copy.invoke(options, *values.toTypedArray()) as RenderOptions
                }
                else -> error("Invalid corpus acquisition mode")
            }
        }

        fun fromEnvironment(report: Path): LegacyCorpusComparison? {
            val mode = System.getenv("RENTILE_CORPUS_SNAPSHOT_MODE")?.takeIf(String::isNotBlank) ?: return null
            require(mode in setOf("CAPTURE", "REPLAY")) { "Invalid corpus snapshot mode" }
            require(acquisition() in setOf("BASELINE", "OUTPUT_ZOOM")) { "Comparison requires an explicit old/new acquisition mode" }
            val snapshot = Path.of(requireNotNull(System.getenv("RENTILE_CORPUS_PRIVATE_SNAPSHOT_DIR")))
                .toAbsolutePath().normalize()
            require(!snapshot.startsWith(report.toAbsolutePath().normalize())) { "Private snapshot must be outside corpus report" }
            require(!Files.exists(report.resolve("comparison-complete.json"))) { "Comparison report already complete" }
            if (mode == "CAPTURE") Files.createDirectories(snapshot) else require(Files.isDirectory(snapshot))
            return LegacyCorpusComparison(mode, snapshot, report)
        }

        private fun ByteArray.digest(): String = MessageDigest.getInstance("SHA-256").digest(this)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
