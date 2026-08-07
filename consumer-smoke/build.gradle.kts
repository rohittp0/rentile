plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.3.21"
    id("com.android.kotlin.multiplatform.library") version "9.3.1"
}

val rentileVersion = providers.gradleProperty("rentileVersion").orElse("0.1.0-SNAPSHOT")

kotlin {
    android {
        namespace = "com.rohittp.rentile.smoke"
        compileSdk = 37
        minSdk = 24
    }
    iosArm64()
    iosSimulatorArm64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("com.rohittp.rentile:kmp:${rentileVersion.get()}")
        }
    }
}
