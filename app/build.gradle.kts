plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lakshlabs.medreminder"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.lakshlabs.medreminder"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "1.3.2"
    }
    buildTypes {
        release { isMinifyEnabled = true; isShrinkResources = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
// No library dependencies on purpose: framework APIs only, tiny APK.

android {
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    // Test-only: runs the app's real code on the JVM (no device needed). Not in the APK.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
