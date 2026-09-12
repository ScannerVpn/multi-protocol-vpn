import java.security.MessageDigest
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.multivpn.android"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.multivpn.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        // THE single source of truth for the Android version. The desktop app
        // keeps its own appVersion in desktop/build.gradle.kts — the two
        // release on different cadences.
        // 0.3.0 = feature parity pass: real per-config ping (urlTest), live
        // traffic counters, no-reconnect config switching, WireGuard/AmneziaWG
        // from .conf, per-app split tunneling, encrypted backup/restore.
        versionName = "0.3.1"
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
    // core-hashes.json and enforced by verifyCore before every build.
    implementation(files("libs/hiddify-core-4.1.0.aar"))

    testImplementation(libs.junit)
}

val verifyCore by tasks.registering {
    group = "verification"
    description = "Verify the in-process VPN core against the committed SHA256 pin"
    val core = layout.projectDirectory.file("libs/hiddify-core-4.1.0.aar")
    val pins = rootProject.layout.projectDirectory.file("core-hashes.json")
    inputs.file(core)
    inputs.file(pins)
    doLast {
        check(core.asFile.isFile) { "Missing VPN core. Run python3 fetch-core.py in android/." }
        val expected = (JsonSlurper().parse(pins.asFile) as Map<*, *>)[core.asFile.name] as String
        val digest = MessageDigest.getInstance("SHA-256")
        core.asFile.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) { "VPN core SHA256 mismatch; refusing to build. Run python3 fetch-core.py." }
    }
}
tasks.named("preBuild") { dependsOn(verifyCore) }
