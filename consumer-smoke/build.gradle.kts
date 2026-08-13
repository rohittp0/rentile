plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.3.21"
    id("com.android.kotlin.multiplatform.library") version "9.3.1"
}

// This harness is a standalone build, so it does not read the root `gradle.properties` that holds
// the library's sole version source. Derive the coordinate from `VERSION_NAME` there instead of
// pinning a literal, so the smoke consumer always resolves the version currently being built.
// Publication workflows may still override it with `-PrentileVersion` to verify a stated coordinate.
val declaredLibraryVersion = providers
    .fileContents(layout.projectDirectory.file("../gradle.properties"))
    .asText
    .map { text ->
        text.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("VERSION_NAME=") }
            ?.substringAfter('=')
            ?.trim()
            .orEmpty()
    }

val rentileVersion: String = providers.gradleProperty("rentileVersion")
    .orElse(declaredLibraryVersion)
    .orNull
    ?.takeIf { it.isNotEmpty() }
    ?: error(
        "Cannot determine the Rentile version to consume: pass -PrentileVersion=<version> or " +
            "declare VERSION_NAME in the root gradle.properties.",
    )

kotlin {
    android {
        namespace = "com.rohittp.rentile.smoke"
        compileSdk = 37
        minSdk = 24
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("com.rohittp.rentile:kmp:$rentileVersion")
        }
    }
}
