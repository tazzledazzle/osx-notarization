import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.3.41"
    id("com.tableau.NotarizationPlugin")
}

version = "1.0"

dependencies {
    implementation(kotlin("stdlib-jdk8"))
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

notarization {
    // list of paths to binaries
    fileList = File("/Users/builder/releases_notarized/2019-10-16-maestro-2019-4.19.1016.0911.txt")
    // location of bits to be notarized
    workingDir = "/Users/builder/releases_notarized"
    mountLocation = "//builder@devbuilds/maestro"

    // todo: read this sensitive material from properties file
    appSpecificPassword = "eduk-hlnz-zxyg-qhsy"
    appleId = "tschumacher@tableau.com"
    workspaceRootDir = "/Users/builder/p4"
    certificateId = "Developer ID Application: Tableau Software, Inc. (QJ4XPRK37C)"
}
