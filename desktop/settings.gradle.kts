pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

plugins {
    // 2026-09 audit follow-up: lets Gradle auto-provision the pinned JDK 17
    // toolchain on machines that only carry another Java version. Harmless in
    // CI (the installed 17 is detected first; nothing downloads).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        // Fallback mirror for Google artifacts if dl.google.com becomes unreachable
        maven("https://maven.aliyun.com/repository/google") {
            content { includeGroupByRegex("androidx\\..*") }
        }
    }
}

rootProject.name = "multivpn"
