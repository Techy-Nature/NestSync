pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NestSync"

// :core    the git engine, shared by both apps (plain Kotlin, no Android code)
// :app     the Android app (APK)
// :desktop the Linux and macOS app (AppImage, DMG)
include(":core")
include(":app")
include(":desktop")
