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

// TODO: Define your dependencies here
dependencies {

    // Main dependencies for most QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    //shadow(libs.slf4j)
    shadow(libs.snakeyaml)
    shadow(libs.gson)
    implementation("qupath.ext.basicstitching:basic-stitching:0.2.0")
    implementation("io.github.qupath:qupath-extension-bioformats:0.6.0-rc4")
    // If you aren't using Groovy, this can be removed
    //shadow(libs.bundles.groovy)

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)

}
