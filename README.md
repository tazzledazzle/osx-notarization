# 2019 MacOS Notarization/Signing

Showcase of a project I completed while working at Tableau. I've updated 
the `NotarizationPlugin` to work with the latest version of Gradle and added a few new Kotlin features as well

## Overview

This is a Gradle plugin that automates the process of signing and notarizing macOS applications. It is designed to work with the latest version of Gradle and includes several new features and improvements.

## Features
- Automated signing and notarization of macOS applications
- Support for the latest version of Gradle
- Improved error handling and logging


## Usage
Apply the plugin in your `build.gradle(.kts)` file:
#### Groovy DSL
```groovy
plugins {
    id 'com.tableau.notarization.NotarizationPlugin'
}
```
#### Kotlin DSL
```kotlin
plugins {
    id("com.tableau.notarization.NotarizationPlugin")
}
```
Once the plugin as been applied, you can configure the notarization settings in your `build.gradle(.kts)` file. The plugin provides several configuration options to customize the notarization process.

```groovy
notarization {
    binaryListFile = "the-list-of-binaries-to-sign.txt"
    workingDir = "where-your-process-is-running"
    mountLocation = "shared-mount-location-to-retrieve-the-binaries"

    appSpecificPassword = "apple-app-specific-password"
    appleId = "apple-user-id"
    workspaceRootDir = "location-of-your-workspace-root-dir" // this was due to the size of the monolithic repo
    certificateId = "vpn-certificate-id"
}
```

