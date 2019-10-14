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
        fileList = File("/Users/builder/releases_notarized/10142019-2019.4.0-rc0.txt")
        workingDir = "/Users/builder/releases_notarized"
        appSpecificPassword = "eduk-hlnz-zxyg-qhsy"
        appleId = "tschumacher@tableau.com"
        workspaceRootDir = "/Users/builder/p4"
        certificateId = "Developer ID Application: Tableau Software, Inc. (QJ4XPRK37C)"
    }
