plugins {
    id("com.android.application")        // makes this an Android app (creates assembleDebug)
    id("org.jetbrains.kotlin.android")   // only needed if your code is Kotlin
}

android {
    namespace = "com.example.capsulelite"   // must match the package in your code
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.capsulelite"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
