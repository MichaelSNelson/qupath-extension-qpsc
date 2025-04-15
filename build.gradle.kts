plugins {
    // Support writing the extension in Groovy (remove this if you don't want to)
    groovy
    // To optionally create a shadow/fat jar that bundle up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
}

// TODO: Configure your extension here (please change the defaults!)
qupathExtension {
    name = "qupath-extension-qpsc"
    group = "io.github.michaelsnelson"
    version = "0.2.1-SNAPSHOT"
    description = "A QuPath extension to allow interaction with a microscope through PycroManager and MicroManager."
    automaticModule = "io.github.michalsnelson.extension.qpsc"
}

// TODO: Define your dependencies here
dependencies {

    // Main dependencies for most QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    shadow(libs.slf4j)
    implementation(libs.snakeyaml)
    implementation(libs.gson)
    //implementation("qupath.ext.basicstitching:basic-stitching:0.2.0")
    //implementation("io.github.qupath:qupath-extension-bioformats:0.6.0-rc3")
    // If you aren't using Groovy, this can be removed
    shadow(libs.bundles.groovy)

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)

}
