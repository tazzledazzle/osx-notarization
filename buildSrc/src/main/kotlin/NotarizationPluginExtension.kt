package com.tableau.gradle.notarization

import java.io.File

open class NotarizationPluginExtension {
    var binaryListFile: File = File("src/test/resources/binaryList.txt")
    var binariesList: List<File> = ArrayList()
    var workingDir: String? = null
    var appSpecificPassword: String? = null
    var appleId: String? = null
    var workspaceRootDir: String? = null
    var certificateId: String? = null
    var mountLocation: String = "//builder@devbuilds/release"
}