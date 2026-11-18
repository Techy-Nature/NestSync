plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// GitHub Actions sets GITHUB_RUN_NUMBER, so every build gets a higher version
// and Android accepts it as an update.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "io.github.techynature.nestsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.techynature.nestsync"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("release") {
            // A private key can be supplied through repository secrets (see README).
            // Without one, the bundled key is used so every build can update the last.
            val customStore = System.getenv("NESTSYNC_KEYSTORE_FILE")
            if (!customStore.isNullOrBlank()) {
                storeFile = file(customStore)
                storePassword = System.getenv("NESTSYNC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NESTSYNC_KEY_ALIAS")
                keyPassword = System.getenv("NESTSYNC_KEY_PASSWORD")
            } else {
                storeFile = file("nestsync-public.keystore")
                storePassword = "nestsync"
                keyAlias = "nestsync"
                keyPassword = "nestsync"
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
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.SF",
                "META-INF/*.RSA",
                "META-INF/*.DSA",
                "META-INF/ECLIPSE_*",
                "plugin.properties",
                "about.html",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The git engine (and JGit) lives in :core, shared with the desktop app.
    implementation(project(":core"))
    implementation("org.slf4j:slf4j-nop:1.7.36")
}
