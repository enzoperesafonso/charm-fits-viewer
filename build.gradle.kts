plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

// The group should match the base of your plugin's ID.
group = "com.github.enzperesafonso"
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

// This task automatically patches the plugin.xml file with the version and change notes.
tasks.patchPluginXml {
    pluginVersion.set(project.version.toString())
    changeNotes.set(
        """
        <b>${project.version}</b>
        <ul>
            <li>Initial release of the IntelliJ FITS Viewer plugin.</li>
            <li>Displays FITS header keywords and values in a table.</li>
            <li>Shows primary image data with a 5th-95th percentile linear stretch.</li>
        </ul>
        """.trimIndent()
    )
}
