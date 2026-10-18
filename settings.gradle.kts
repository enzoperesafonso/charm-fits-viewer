plugins {
    // Lets Gradle download the JDK requested by `jvmToolchain` if it isn't installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "charm-fits-viewer"
