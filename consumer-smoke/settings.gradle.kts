pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The Rentile group resolves only from the local test repository, so this harness proves the
        // publication produced by this build rather than an already-released Central artifact that
        // happens to share the version.
        exclusiveContent {
            forRepository {
                maven {
                    name = "LocalTest"
                    url = uri(file("../build/local-maven"))
                }
            }
            filter {
                includeGroup("com.rohittp.rentile")
            }
        }
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev")
            content {
                includeGroup("org.jetbrains.skiko")
            }
        }
    }
}

rootProject.name = "rentile-consumer-smoke"
