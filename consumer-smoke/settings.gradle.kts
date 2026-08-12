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
        val rentileRepositoryUrl = providers.gradleProperty("rentileRepositoryUrl")
            .orElse(uri(file("../build/local-maven")).toString())

        // The Rentile group resolves only from the repository under test. Release workflows first
        // use the isolated local repository, then repeat with the public R2 URL and a fresh Gradle
        // user home so neither Central nor a previous dependency cache can mask a broken release.
        exclusiveContent {
            forRepository {
                maven {
                    name = "RentileUnderTest"
                    url = uri(rentileRepositoryUrl.get())
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
