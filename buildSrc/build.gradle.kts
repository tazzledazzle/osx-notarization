import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.3.41"
    id("java-gradle-plugin")
}

val kotlin_version = "1.3.41"
val spek_version = "2.0.8"

version = "1.0"

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(gradleApi())
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.2")

    testImplementation("org.spekframework.spek2:spek-dsl-jvm:$spek_version")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.spekframework.spek2:spek-runner-junit5:$spek_version")

    // spek requires kotlin-reflect, can be omitted if already in the classpath
    testRuntimeOnly("org.jetbrains.kotlin:kotlin-reflect:$kotlin_version")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.withType<Test> {
    useJUnitPlatform {
        includeEngines("spek2")
    }

    testLogging {
        events("failed")
        events("skipped")
        events("standard_out")
        events("standard_error")

        showExceptions = true
        showStackTraces = true
        showCauses = true
        exceptionFormat = TestExceptionFormat.FULL
    }
}

gradlePlugin {
    plugins {
        create("simplePlugin") {
            id = "com.tableau.NotarizationPlugin"
            implementationClass = "com.tableau.gradle.notarization.NotarizationPlugin"
        }
    }
}