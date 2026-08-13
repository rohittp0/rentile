import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.wire)
}

abstract class UnpackSkikoAndroidNatives : DefaultTask() {
    @get:InputFiles
    abstract val arm64Jar: ConfigurableFileCollection

    @get:InputFiles
    abstract val x64Jar: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:Inject
    abstract val archiveOperations: ArchiveOperations

    @TaskAction
    fun unpack() {
        fileSystemOperations.sync {
            from(archiveOperations.zipTree(arm64Jar.singleFile)) {
                include("*.so")
                into("arm64-v8a")
            }
            from(archiveOperations.zipTree(x64Jar.singleFile)) {
                include("*.so")
                into("x86_64")
            }
            into(outputDir)
        }
    }
}

val skikoAndroidRuntimeArm64 by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val skikoAndroidRuntimeX64 by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    skikoAndroidRuntimeArm64("org.jetbrains.skiko:skiko-android-runtime-arm64:${libs.versions.skiko.get()}")
    skikoAndroidRuntimeX64("org.jetbrains.skiko:skiko-android-runtime-x64:${libs.versions.skiko.get()}")
}

val unpackSkikoAndroidNatives = tasks.register<UnpackSkikoAndroidNatives>("unpackSkikoAndroidNatives") {
    arm64Jar.from(skikoAndroidRuntimeArm64)
    x64Jar.from(skikoAndroidRuntimeX64)
    outputDir.set(layout.buildDirectory.dir("generated/skikoJniLibs"))
}

val skikoHostOs = System.getProperty("os.name").lowercase().let {
    when {
        it.contains("mac") -> "macos"
        it.contains("win") -> "windows"
        it.contains("nux") || it.contains("nix") -> "linux"
        else -> error("Unsupported host OS for Skiko test runtime: $it")
    }
}

val skikoHostArch = System.getProperty("os.arch").lowercase().let {
    when {
        it.contains("aarch64") || it.contains("arm64") -> "arm64"
        it.contains("x86_64") || it.contains("amd64") -> "x64"
        else -> error("Unsupported host architecture for Skiko test runtime: $it")
    }
}

kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
        klib {
            keepUnsupportedTargets = false
        }
    }

    android {
        namespace = "com.rohittp.rentile"
        compileSdk = 37
        minSdk = 24
        withHostTest {}
        withDeviceTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
            implementation("com.squareup.wire:wire-runtime:${libs.versions.wire.get()}") {
                version { strictly(libs.versions.wire.get()) }
            }
            implementation("org.jetbrains.skiko:skiko") {
                version { strictly(libs.versions.skiko.get()) }
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
        }

        listOf("linuxX64Test", "linuxArm64Test").forEach { sourceSetName ->
            getByName(sourceSetName) {
                kotlin.srcDir("src/nativeCorpusTest/kotlin")
                kotlin.srcDir("src/linuxCorpusTest/kotlin")
                dependencies {
                    implementation(libs.ktor.client.core)
                    implementation(libs.ktor.client.curl)
                }
            }
        }

        listOf("iosArm64Test", "iosSimulatorArm64Test").forEach { sourceSetName ->
            getByName(sourceSetName) {
                kotlin.srcDir("src/nativeCorpusTest/kotlin")
                kotlin.srcDir("src/appleCorpusTest/kotlin")
                dependencies {
                    implementation(libs.ktor.client.core)
                    implementation(libs.ktor.client.darwin)
                }
            }
        }

        getByName("jvmTest").dependencies {
            implementation("org.jetbrains.skiko:skiko-awt-runtime-$skikoHostOs-$skikoHostArch:${libs.versions.skiko.get()}")
        }

        getByName("androidHostTest").dependencies {
            implementation("org.jetbrains.skiko:skiko-awt-runtime-$skikoHostOs-$skikoHostArch:${libs.versions.skiko.get()}")
        }

        getByName("androidDeviceTest").dependencies {
            implementation(libs.junit)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
        }
    }
}

wire {
    sourcePath {
        srcDir("src/commonMain/proto")
    }
    kotlin { }
}

extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(unpackSkikoAndroidNatives) { it.outputDir }
    }
}

mavenPublishing {
    if (System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null) {
        signAllPublications()
    }

    pom {
        name.set("Rentile KMP")
        description.set("Headless Kotlin Multiplatform basemap tile rasterizer with encoded PNG output.")
        inceptionYear.set("2026")
        url.set("https://rohittp.com/rentile/")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("rohittp0")
                name.set("Rohit T P")
                email.set("tprohit9@gmail.com")
                organization.set("rohittp.com")
                organizationUrl.set("https://rohittp.com")
                url.set("https://rohittp.com")
            }
        }
        scm {
            url.set("https://github.com/rohittp0/rentile")
            connection.set("scm:git:git://github.com/rohittp0/rentile.git")
            developerConnection.set("scm:git:ssh://git@github.com/rohittp0/rentile.git")
        }
    }
}

publishing {
    repositories {
        maven {
            name = "LocalTest"
            url = uri(rootProject.layout.buildDirectory.dir("local-maven"))
        }
    }
}

val corpusManifest = providers.environmentVariable("RENTILE_COVERAGE_MANIFEST")
val corpusReportDirectory = providers.environmentVariable("RENTILE_CORPUS_REPORT_DIR")
val corpusStyleId = providers.environmentVariable("RENTILE_CORPUS_STYLE_ID")

tasks.matching { it.name == "testAndroidHostTest" }.configureEach {
    inputs.property("rentileCorpusManifest", corpusManifest.orElse(""))
    inputs.property("rentileCorpusReportDirectory", corpusReportDirectory.orElse(""))
    inputs.property("rentileCorpusStyleId", corpusStyleId.orElse(""))
    outputs.upToDateWhen { System.getenv("RENTILE_COVERAGE_MANIFEST").isNullOrBlank() }
}

val nativeCorpusEnabled = providers.environmentVariable("RENTILE_NATIVE_CORPUS")
val nativeHttpsBridgeOrigin = providers.environmentVariable("RENTILE_NATIVE_HTTPS_BRIDGE_ORIGIN")
val nativeCorpusZ0Only = providers.environmentVariable("RENTILE_NATIVE_CORPUS_Z0_ONLY")
val nativeCorpusOutputDirectory = providers.environmentVariable("RENTILE_NATIVE_CORPUS_OUTPUT_DIR")
tasks.withType<KotlinNativeTest>().configureEach {
    inputs.property("rentileNativeCorpus", nativeCorpusEnabled.orElse(""))
    inputs.property("rentileNativeHttpsBridgeOrigin", nativeHttpsBridgeOrigin.orElse(""))
    inputs.property("rentileNativeCorpusZ0Only", nativeCorpusZ0Only.orElse(""))
    inputs.property("rentileNativeCorpusOutputDirectory", nativeCorpusOutputDirectory.orElse(""))
    nativeCorpusEnabled.orNull?.let { environment("RENTILE_NATIVE_CORPUS", it) }
    nativeHttpsBridgeOrigin.orNull?.let { environment("RENTILE_NATIVE_HTTPS_BRIDGE_ORIGIN", it) }
    nativeCorpusZ0Only.orNull?.let { environment("RENTILE_NATIVE_CORPUS_Z0_ONLY", it) }
    nativeCorpusOutputDirectory.orNull?.let { environment("RENTILE_NATIVE_CORPUS_OUTPUT_DIR", it) }
}

val iosSimulatorId = providers.environmentVariable("RENTILE_IOS_SIMULATOR_ID")
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    iosSimulatorId.orNull?.let { device.set(it) }
    nativeCorpusEnabled.orNull?.let { environment("SIMCTL_CHILD_RENTILE_NATIVE_CORPUS", it) }
    nativeHttpsBridgeOrigin.orNull?.let {
        environment("SIMCTL_CHILD_RENTILE_NATIVE_HTTPS_BRIDGE_ORIGIN", it)
    }
    nativeCorpusZ0Only.orNull?.let {
        environment("SIMCTL_CHILD_RENTILE_NATIVE_CORPUS_Z0_ONLY", it)
    }
    nativeCorpusOutputDirectory.orNull?.let {
        environment("SIMCTL_CHILD_RENTILE_NATIVE_CORPUS_OUTPUT_DIR", it)
    }
}
