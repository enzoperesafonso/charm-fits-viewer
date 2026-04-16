plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "org.example"
version = project.findProperty("pluginVersion") ?: "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure the IntelliJ Platform plugin
intellijPlatform {
    pluginConfiguration {
        xml.set(project.layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml"))
        icon.set(project.layout.projectDirectory.file("src/main/resources/META-INF/pluginIcon.svg"))
        ideaVersion {
            sinceBuild.set("232")
            untilBuild.set("999.*")
        }
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2023.2.5")
        bundledPlugin("com.intellij.java")
        instrumentationTools()
    }
    implementation("gov.nasa.gsfc.heasarc:nom-tam-fits:1.22.0")
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Configure the patchPluginXml task to inject version and change notes
tasks.patchPluginXml {
    pluginVersion.set(project.version.toString())
    changeNotes.set(
        """
        <b>${project.version}</b>
        <ul>
            <li><b>Pro Interface:</b> New persistent sidebar for HDU selection and image settings.</li>
            <li><b>Advanced Stretching:</b> Added <b>Asinh</b> (Inverse Hyperbolic Sine) stretch mode for high-dynamic-range data.</li>
            <li><b>Celestial Coordinates:</b> Professional <b>HMS/DMS</b> formatting for RA/Dec in the status bar.</li>
            <li><b>Expanded Support:</b> Native support for <b>.fits.fz</b> (Rice-compressed) FITS files.</li>
            <li><b>Multi-HDU Navigation:</b> Easily switch between images and metadata tables in a single file.</li>
            <li><b>Interactive Tools:</b> Real-time zoom, fit-to-screen, and improved histogram controls.</li>
        </ul>
        """.trimIndent()
    )
}

// Configure the signing task for publishing to JetBrains Marketplace
tasks.signPlugin {
    // This is a placeholder. You'll need to configure your signing key.
    // See https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
    // certificateChainFile.set(file("path/to/your/certificate.pem"))
    // privateKeyFile.set(file("path/to/your/private.pem"))
    // privateKeyPassword.set(System.getenv("PRIVATE_KEY_PASSWORD"))
}

// Configure the publishing task for JetBrains Marketplace
tasks.publishPlugin {
    // This is a placeholder. You'll need to configure your Marketplace token.
    // See https://plugins.jetbrains.com/docs/intellij/publishing.html
    // token.set(System.getenv("PUBLISH_TOKEN"))
    // pluginId.set("org.example.charm-fits-viewer") // Should match <id> in plugin.xml
    // channels.set(listOf("default")) // Or "eap", "beta", etc.
}
