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
        google()
        mavenCentral()
        // Zebra LinkOS SDK Repository
        maven { url = uri("https://zebratech.jfrog.io/artifactory/ZSDK-Maven-Releases") }
    }
}

rootProject.name = "ZebraBridge"
include(":app")
