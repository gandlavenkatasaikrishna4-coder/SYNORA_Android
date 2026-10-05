// Module build file for the app. The workflow copies this to android-build/app/build.gradle.kts.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.synora.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.synora.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
}
