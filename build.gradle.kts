plugins {
    // Support writing the extension in Groovy (remove this if you don't want to)
    groovy
    // To optionally create a shadow/fat jar that bundle up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
}

// Configure your extension here
qupathExtension {
    name = "qupath-extension-qpsc"
    group = "io.github.michaelsnelson"
    version = "0.2.1-SNAPSHOT"
    description = "A QuPath extension to allow interaction with a microscope through PycroManager and MicroManager."
    automaticModule = "io.github.michaelsnelson.extension.qpsc"
}
allprojects {
    repositories {
        mavenLocal() // Checks your local Maven repository first.
        mavenCentral()
        // Optionally include other repositories if needed.
        maven {
            name = "OME"
            url = uri("https://repo.openmicroscopy.org/artifactory/ome-releases")
        }

    }
}
val javafxVersion = "17.0.2"
// TODO: Define your dependencies here
dependencies {

    // Main dependencies for most QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    //shadow(libs.slf4j)
    shadow(libs.snakeyaml)
    shadow(libs.gson)

    implementation("io.github.michaelsnelson:qupath-extension-tiles-to-pyramid:0.1.0")
    implementation("io.github.qupath:qupath-extension-bioformats:0.6.0-rc4")
    // If you aren't using Groovy, this can be removed
    shadow(libs.bundles.groovy)

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation("io.github.qupath:qupath-app:0.6.0-rc4")
    //testImplementation(libs.junit)
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.1")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation(libs.bundles.logging)
    testImplementation(libs.qupath.fxtras)
    testImplementation("org.openjfx:javafx-base:$javafxVersion")
    testImplementation("org.openjfx:javafx-graphics:$javafxVersion")
    testImplementation("org.openjfx:javafx-controls:$javafxVersion")
    testImplementation("org.mockito:mockito-core:5.2.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.2.0")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs = listOf(
        "--add-modules", "javafx.base,javafx.graphics,javafx.controls",
        "--add-opens", "javafx.graphics/javafx.stage=ALL-UNNAMED"
    )
}