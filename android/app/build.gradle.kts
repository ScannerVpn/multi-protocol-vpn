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
        versionCode = 5
        // THE single source of truth for the Android version. The desktop app
        // keeps its own appVersion in desktop/build.gradle.kts — the two
        // release on different cadences.
        // 0.4.0 = desktop feature completion: the سرورها tab (SSH provisioning
        // with the same bundled scripts), OpenVPN via its own native core,
        // and the disconnect state-machine fix — built on top of 0.3.1's
        // verified-connect lifecycle fixes.

        // Only ship the ABIs the core .so files actually carry. Splitting per
        // ABI keeps each APK at roughly a QUARTER of the universal one — the
        // single biggest size lever this app has, since the two native cores
        // (libbox and libovpn3) dominate the payload.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    buildTypes {
        release {
            // Shrinking is not optional here: two native cores carry large
            // Java layers (hiddify-core, openvpn lib, appcompat) and R8 strips
            // several MB of unused classes from them.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // Keep debug builds fast; shrinking only the release artifact.
            isMinifyEnabled = false
        }
    }

    // Per-ABI APKs for release (a universal APK still builds for testing).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md",
        )
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

    // The provisioning scripts live ONCE, at the repo root in server/, and are
    // copied into the APK at build time. Duplicating them into the Android
    // source tree is what let the desktop's own copies drift before, and a
    // script that differs between clients means the same button provisions two
    // different servers.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/serverScripts"))
}

val copyServerScripts by tasks.registering(Copy::class) {
    from(rootProject.file("../server")) { include("*.sh") }
    into(layout.buildDirectory.dir("generated/serverScripts/scripts"))
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(copyServerScripts) }

// LintVital (release lint) scans the sources before the assets merge and
// fails the build with "directory does not exist" if it runs first — both its
// analyze and report tasks need the generated scripts.
tasks.matching { it.name.contains("LintVital", ignoreCase = true) }
    .configureEach { dependsOn(copyServerScripts) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Material Symbols (shield_lock, speed, alt_route, bolt …) — the icon set
    // the user's mockups are drawn with. R8 strips the unused ones from the
    // release APK; only debug builds carry the whole set.
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // The tunnel core — hiddify-core v4.1.0 AAR, the SAME core the Windows
    // app bundles (desktop core-hashes pins it). Downloaded once by
    // fetch-core.ps1 into app/libs/ (not in git, ~107 MB); SHA256 in
    // core-hashes.json and enforced by verifyCore before every build.
    implementation(files("libs/hiddify-core-4.1.0.aar"))

    // The سرورها tab: SSH provisioning of the user's own VPS with the SAME
    // scripts the desktop runs (server/*.sh, bundled as assets).
    implementation(libs.jsch)

    // OpenVPN. libbox cannot speak the protocol at all — the shipped .so
    // registers no openvpn outbound (verified with strings/nm) — so .ovpn
    // configs run on their own native core in a separate VpnService.
    implementation(libs.openvpn)

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
