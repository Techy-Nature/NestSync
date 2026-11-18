import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// The Linux and macOS app. Same look and the same git engine (:core) as the Android app.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// GitHub Actions sets GITHUB_RUN_NUMBER, so desktop builds share the APK's version number.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val appVersion = "1.0.$buildNumber"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    // Same JSON API Android has built in, for the saved repo list and settings.
    implementation("org.json:json:20240303")
    implementation("org.slf4j:slf4j-nop:1.7.36")
}

compose.desktop {
    application {
        mainClass = "io.github.techynature.nestsync.MainKt"
        jvmArgs += listOf("-Dnestsync.version=$appVersion")

        nativeDistributions {
            // Mac: a .dmg. Linux: the plain app folder, which the workflow wraps into an AppImage.
            targetFormats(TargetFormat.Dmg)
            packageName = "NestSync"
            packageVersion = appVersion
            description = "Sync your git repos with one click"
            vendor = "Techy-Nature"
            copyright = "MIT License"

            // Only the parts of Java the app needs, which keeps the download small.
            // jdk.crypto.ec is needed for HTTPS to most git servers.
            modules(
                "java.desktop",
                "java.instrument",
                "java.management",
                "java.naming",
                "java.security.jgss",
                "jdk.crypto.ec",
                "jdk.unsupported",
            )

            linux {
                iconFile.set(project.file("icons/NestSync.png"))
            }
            macOS {
                bundleID = "io.github.techynature.nestsync"
                iconFile.set(project.file("icons/NestSync.icns"))
                appCategory = "public.app-category.developer-tools"
                // Apple wants a version that starts above 0; 1.0.N already does.
                dmgPackageVersion = appVersion
            }
        }
    }
}
