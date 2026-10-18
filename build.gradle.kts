import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // The IDE provides the Kotlin stdlib at runtime; don't use APIs newer than the oldest supported IDE ships.
        apiVersion = KotlinVersion.KOTLIN_2_0
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("gov.nasa.gsfc.heasarc:nom-tam-fits:1.23.0") {
        // Compile-time annotations only; not needed at runtime.
        exclude(group = "com.github.spotbugs")
        exclude(group = "jakarta.annotation")
    }

    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        create(IntelliJPlatformType.PyCharmCommunity, providers.gradleProperty("platformVersion")) {
            // Resolve from the IntelliJ Maven repository rather than the installer feed
            useInstaller = false
        }
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        changeNotes = """
            <b>1.1.0</b>
            <ul>
              <li>Much lower memory use: only the selected image plane is read, large images are downsampled for display, and nothing is kept after the tab is closed.</li>
              <li>All file reading happens in the background; the IDE no longer freezes when switching HDUs.</li>
              <li>Correct BSCALE/BZERO/BLANK handling, unsigned 8-bit data, and FITS orientation (row 1 at the bottom).</li>
              <li>Data cubes: choose which plane to show.</li>
              <li>Zoom around the cursor with Ctrl/⌘ + scroll, drag to pan, and keyboard shortcuts for zoom, fit and panning.</li>
              <li>Accurate RA/Dec readout for TAN, SIN, ARC, ZEA and STG projections; on-sky measurement uses true angular separation.</li>
              <li>Reloads automatically when the file changes on disk.</li>
            </ul>
        """.trimIndent()
    }

    pluginVerification {
        ides {
            recommended()
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}
