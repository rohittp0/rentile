plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.wire) apply false
}

allprojects {
    group = "com.rohittp.rentile"
    version = providers.gradleProperty("VERSION_NAME").get()
}
