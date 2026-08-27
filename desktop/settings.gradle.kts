pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
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
