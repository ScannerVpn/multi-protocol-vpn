plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.multivpn.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.multivpn.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        // THE single source of truth for the Android version. The desktop app
        // keeps its own appVersion in desktop/build.gradle.kts — the two
        // release on different cadences.
        // 0.3.0 = feature parity pass: real per-config ping (urlTest), live
        // traffic counters, no-reconnect config switching, WireGuard/AmneziaWG
        // from .conf, per-app split tunneling, encrypted backup/restore.
        versionName = "0.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // The tunnel core — hiddify-core v4.1.0 AAR, the SAME core the Windows
    // app bundles (desktop core-hashes pins it). Downloaded once by
    // fetch-core.ps1 into app/libs/ (not in git, ~107 MB); SHA256 in
    // core-hashes.json and enforced by the fileExists guard below.
    implementation(files("libs/hiddify-core-4.1.0.aar"))

    testImplementation(libs.junit)
}
