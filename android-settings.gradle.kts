// Settings for the Android app build. The workflow copies this to android-build/settings.gradle.kts.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "synora-android"
include(":app")
