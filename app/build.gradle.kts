plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Where the app looks for updates. CI sets it from the GitHub repo; locally: -PupdateUrl=... or gradle.properties.
val updateUrl: String = (findProperty("updateUrl") as String?)
    ?: System.getenv("GITHUB_REPOSITORY")?.let { "https://github.com/$it/releases/latest/download/update.json" }
    ?: ""

android {
    namespace = "com.lakshlabs.medreminder"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.lakshlabs.medreminder"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.4.0"
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
    }
    buildFeatures { buildConfig = true }

    signingConfigs {
        // Updates only install over an app signed with the same key. CI passes the key through secrets
        // (see .github/workflows/release.yml); without them the local debug key is used, like debug builds.
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
                storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
// No library dependencies on purpose: framework APIs only, tiny APK.

dependencies {
    // Test-only: runs the app's real code on the JVM (no device needed). Not in the APK.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}

/** Used by the release workflow: prints "versionCode versionName". */
tasks.register("printVersion") {
    val code = android.defaultConfig.versionCode
    val name = android.defaultConfig.versionName
    doLast { println("$code $name") }
}
