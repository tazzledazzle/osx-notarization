import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.3.41"
    id("com.tableau.NotarizationPlugin")
}

repositories {
    mavenCentral()
}

version = "1.0"

dependencies {
    implementation(kotlin("stdlib-jdk8"))
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

//project.extensions.get("notarization")  {
    notarization {
        workingDir = "/Users/tschumacher/installers-2019.3"
        appSpecificPassword = "eduk-hlnz-zxyg-qhsy"
        appleId = "tschumacher@tableau.com"
        workspaceRootDir = "~/p4"
        certificateId = "Developer ID Application: Tableau Software, Inc. (QJ4XPRK37C)"
    }
//}
