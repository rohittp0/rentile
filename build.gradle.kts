import org.gradle.api.credentials.AwsCredentials
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.wire) apply false
}

val r2Endpoint = providers.environmentVariable("R2_ENDPOINT")
val r2Bucket = providers.environmentVariable("R2_BUCKET")

// Gradle uses this property for custom S3-compatible endpoints. Keep ordinary builds usable when
// publishing variables are absent; the R2 workflow validates them before invoking a publish task.
r2Endpoint.orNull?.let { System.setProperty("org.gradle.s3.endpoint", it) }

allprojects {
    group = "com.rohittp.rentile"
    version = providers.gradleProperty("VERSION_NAME").get()
}

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "R2"
                    url = uri("s3://${r2Bucket.orNull ?: "r2-publishing-not-configured"}")

                    credentials(AwsCredentials::class) {
                        accessKey = providers.environmentVariable("R2_ACCESS_KEY_ID").orNull
                        secretKey = providers.environmentVariable("R2_SECRET_ACCESS_KEY").orNull
                    }
                }
            }
        }
    }

    tasks.withType(PublishToMavenRepository::class.java).configureEach {
        notCompatibleWithConfigurationCache(
            "Remote Maven publishing is not configuration-cache compatible.",
        )
    }
}
