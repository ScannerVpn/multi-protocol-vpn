import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "com.multivpn"
version = "3.6.3"

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.hierynomus:sshj:0.38.0")
    implementation("org.slf4j:slf4j-simple:2.0.16")
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

compose.desktop {
    application {
        mainClass = "vpn.MainKt"
        nativeDistributions {
            // Windows app: MSI for installs, EXE for the portable build.
            // (AppImage is the Linux format and produced a useless artifact here.)
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "MultiVPN"
            packageVersion = "3.6.3"
            description = "MultiVPN - multi-protocol VPN client"
            vendor = "MultiVPN"
            // java.net.http: MSI downloads; jdk.crypto.ec: SSH host keys (sshj)
            modules("java.net.http", "jdk.crypto.ec")
            windows {
                upgradeUuid = "8e2f7a41-6c3b-4d9e-9f5a-2b7c8d1e4a6f"
                console = false
            }
        }
    }
}
