import java.io.FileInputStream
import java.util.Properties

plugins {
    kotlin("jvm") version "2.1.10"
    id("com.tableau.NotarizationPlugin")
}

version = "2.0"
repositories {
    mavenCentral()
    google()
}
dependencies {
    implementation(kotlin("stdlib-jdk8"))
}
kotlin {
    jvmToolchain(21)
}
tasks.withType<Test> {
    useJUnitPlatform()
}
val properties = Properties()
properties.load(FileInputStream("buildSrc/src/main/resources/notarization.properties"))
notarization {
    // list of paths to binaries
    binaryListFile = File(properties.getProperty("binaryListFile"))
    // location of bits to be notarized
    workingDir = properties.getProperty("workingDir")
    mountLocation = properties.getProperty("mountLocation")

    appSpecificPassword = properties.getProperty("appSpecificPassword")
    appleId = properties.getProperty("appleId")
    workspaceRootDir = properties.getProperty("workspaceRootDir")
    certificateId = properties.getProperty("certificateId")
}
