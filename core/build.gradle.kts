// The git engine on its own: plain Kotlin + JGit, used by the Android app and the desktop app.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

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
    // Pure-Java git. 5.13 is the newest line that runs on Android without extra Java 11 APIs.
    api("org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r")

    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
}

// Run the tests as if on a fresh computer, so a developer's own ~/.gitconfig
// (for example commit signing) can't change the results.
tasks.test {
    val home = layout.buildDirectory.dir("test-home").get().asFile
    doFirst { home.mkdirs() }
    systemProperty("user.home", home.path)
    environment("HOME", home.path)
    environment("XDG_CONFIG_HOME", File(home, ".config").path)
    environment("GIT_CONFIG_NOSYSTEM", "1")
}
