package com.tableau.gradle.notarization

import kotlinx.coroutines.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.process.ExecSpec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException

class NotarizationPlugin : Plugin<Project> {
    private val CODE_NOT_SIGNED_TEXT: String = "code object is not signed at all"
    private val ARTIFACT_ALREADY_UPLOADED: String = "The software asset has already been uploaded. The upload ID is"
    lateinit var project: Project
    private val bundleUUIDList = ArrayList<Pair<String, String>>()
    private val bundleUUIDListFile = File("out/bundleUUIDList.txt")
    val shareMount = File("/Users/builder/devbuilds_release")
    val mountDir = shareMount
    val releasesNotarizedDir = File("${System.getProperty("user.home")}/releases_notarized")
    lateinit var localReleaseDir: File
    var outDir: File = File("out")

    override fun apply(target: Project) {
        // configure plugin
        project = target
        outDir.mkdirs()
        val notarizationExtension = project.getExtensions().create(
            "notarization",
            NotarizationPluginExtension::class.java
        )
        notarizationExtension.binaryListFile = File("${System.getProperty("user.home")}/devbuilds_release/binaryList.txt")
        project.afterEvaluate {
            createTasks(notarizationExtension, project)
            createStapleAndPublishTasks(notarizationExtension)
        }
    }

    fun createTasks(notarizationExtension: NotarizationPluginExtension, project: Project = this.project) {

        if (!notarizationExtension.binaryListFile?.exists()!!) {
            throw FileNotFoundException("You must specify a `binaryListFile` in the notarization extension!")
        }

        val localDirName = notarizationExtension.binaryListFile?.nameWithoutExtension
        val localReleaseDir = project.layout.buildDirectory.dir("releases_notarized/$localDirName").get().asFile

        project.tasks.register<Exec>("mountSmbfs", Exec::class.java) { task: Exec ->
            task.group = "notarization"
            task.description = "Mounts the SMB share for notarization"
            task.executable("mount")
            task.args("-t", "smbfs", notarizationExtension.mountLocation, notarizationExtension.mountLocation)
        }

        project.tasks.register("createLocalReleaseDirectory") {
            it.group = "notarization"
            it.description = "Creates the local release directory"
            it.doLast {
                localReleaseDir.mkdirs()
            }
        }
        project.tasks.register("copyBinariesFromShare") {
            it.group = "notarization"
            it.description = "Copies binaries from the share to the local release directory"
            it.dependsOn("mountSmbfs", "createLocalReleaseDirectory")
            it.doLast {
                val binaries = notarizationExtension.binaryListFile?.readLines()
                    ?.map { file -> File(notarizationExtension.mountLocation, file) }

                project.copy {
                    it.from(binaries)
                    it.into(localReleaseDir)
                }
            }
        }
        project.tasks.register("zipApps", Exec::class.java) { task: Exec ->
            task.group = "notarization"
            task.description = "Zips the binaries for notarization"
            task.dependsOn("copyBinariesFromShare")
            task.doLast {
                localReleaseDir.listFiles { file -> file.extension == "app" }?.forEach { appFile ->
                    task.executable("/usr/bin/ditto")
                    task.args("-c", "-k", "--keepParent", appFile.absolutePath, "${appFile.nameWithoutExtension}.zip")
                }
            }
        }

        project.tasks.register<Exec>("checkAndSign", Exec::class.java) { task: Exec ->
            task.group = "notarization"
            task.dependsOn("zipApps")
            task.doLast {
                localReleaseDir.listFiles()?.forEach { file ->
                    val result = project.exec { task: ExecSpec ->
                        task.commandLine("codesign", "-dvv", file.absolutePath)
                        task.isIgnoreExitValue = true
                    }
                    if (result.exitValue != 0) {
                        task.commandLine(
                            "codesign",
                            "--deep",
                            "--force",
                            "--options",
                            "runtime",
                            "--entitlements",
                            "${notarizationExtension.workspaceRootDir}/tableau-cmake/tableau/codesign/Entitlements.plist",
                            "--strict",
                            "--timestamp",
                            "--verbose",
                            "--sign",
                            notarizationExtension.certificateId,
                            file.absolutePath
                        )
                    }
                }
            }
        }

        project.tasks.register<Exec>("postToNotarizationService", Exec::class.java) { task: Exec ->
            task.group = "notarization"
            task.dependsOn("checkAndSign")

            localReleaseDir.listFiles()?.forEach { file ->
                task.commandLine(
                    "xcrun", "altool", "--notarize-app",
                    "--primary-bundle-id", file.name.toBundleId(),
                    "-u", notarizationExtension.appleId,
                    "-p", notarizationExtension.appSpecificPassword,
                    "--file", file.absolutePath
                )
            }
        }

        project.tasks.register<Exec>("pollAndWriteJsonTicket", Exec::class.java) { task: Exec ->
            task.group = "notarization"
            task.dependsOn("postToNotarizationService")
            task.doLast {
                val baos = ByteArrayOutputStream()
                val stderr = ByteArrayOutputStream()
                task.standardOutput = baos
                task.errorOutput = stderr
                task.executable = "xcrun"
                task.isIgnoreExitValue = true
                task.args(
                    "altool", "--notarization-info", notarizationExtension.appleId,
                    "-p", notarizationExtension.appSpecificPassword
                )
                val notarizationStdOut = baos.toString() + "\n" + stderr.toString()
                val notarizationInfo = parseNotarizationInfo(
                    notarizationStdOut,
                    notarizationExtension
                )
                println("Notarization Info: $notarizationInfo")
                bundleUUIDList.add(Pair(notarizationInfo.bundleId!!, notarizationInfo.requestUUID!!))
            }
        }
        project.tasks.register("pollAndWriteJsonTicket") {
            it.group = "notarization"
            it.dependsOn("postToNotarizationService")
            it.doFirst {
                if (!notarizationExtension.binaryListFile?.exists()!!) {
                    throw FileNotFoundException("You must specify a `binaryListFile` in the notarization extension!")
                }
                this.localReleaseDir = project.layout.buildDirectory
                    .dir("releases_notarized/${notarizationExtension.binaryListFile?.nameWithoutExtension}").get().asFile
            }
            it.doLast {
                val bundleUUIDList = populateListFromFile(bundleUUIDListFile).toMutableList()
                val bundleResposeUrlList = mutableListOf<Pair<String, String>>()
                val failedNotarizationList = mutableListOf<NotarizationInfo>()

                runBlocking {
                    bundleUUIDList.forEach { pair ->
                        launch {
                            delay(100L)
                            val (bundleId, uuid) = pair
                            println("Querying RequestUUID: '$uuid' BundleId: '$bundleId'")
                            val notarizationStdOut = executeQueryNotarizationService(uuid, notarizationExtension)
                            val notarizationInfo = parseNotarizationInfo(
                                notarizationStdOut,
                                notarizationExtension,
                                bundleId
                            )
                            println("Notarization Info: $notarizationInfo")

                            if (!notarizationInfo.statusCode!! && notarizationInfo.status !in listOf(
                                    "success",
                                    "invalid"
                                )
                            ) {
                                failedNotarizationList.add(notarizationInfo)
                            } else {
                                addResponseToUrlList(
                                    Pair(notarizationInfo.statusCode, notarizationInfo.logFileUrl!!),
                                    bundleResposeUrlList, bundleId
                                )
                            }
                        }

                    }
                }
                while (failedNotarizationList.isNotEmpty()) {
                    println("Waiting for notarization to complete...")
                    runBlocking {
                        delay(1000L)
                        failedNotarizationList.toList().forEach { notarizationInfo ->
                            launch {
                                delay(100L)
                                val notarizationStdOut = executeQueryNotarizationService(
                                    notarizationInfo.requestUUID,
                                    notarizationExtension
                                )
                                val newNotarizationInfo = parseNotarizationInfo(
                                    notarizationStdOut,
                                    notarizationExtension,
                                    notarizationInfo.bundleId
                                )
                                if (newNotarizationInfo.statusCode!! || newNotarizationInfo.status == "success") {
                                    failedNotarizationList.remove(notarizationInfo)
                                    addResponseToUrlList(
                                        Pair(newNotarizationInfo.statusCode, newNotarizationInfo.logFileUrl!!),
                                        bundleResposeUrlList, notarizationInfo.bundleId
                                    )
                                    failedNotarizationList.remove(notarizationInfo)
                                }
                            }
                        }
                    }
                }
                bundleUUIDList.forEach { (bundleId, jsonContent) ->
                    val ticketFile = File(localReleaseDir, "${bundleId}.notarization.json")
                    println("Writing ticket file: ${ticketFile.absolutePath}")
                    ticketFile.writeText(jsonContent)
                }
            }
        }
        project.tasks.register<Exec>("stapleRecursivelyAndValidate", Exec::class.java) { task: Exec ->

            task.group = "notarization"
            task.dependsOn("pollAndWriteJsonTicket")

            localReleaseDir.walkTopDown().filter { it.extension == "dmg" }.forEach { file ->
                task.commandLine("stapler", "staple", file.absolutePath)
                task.commandLine("stapler", "validate", file.absolutePath)
            }
        }

        project.tasks.register("writeBundleListToFile"){ task ->
        task.onlyIf { (bundleUUIDList.size != 0) && (!bundleUUIDList.isEmpty()) }

        task.doLast {
            val bundleUUIDFile = bundleUUIDListFile
            if (!bundleUUIDFile.parentFile.exists()) {
                bundleUUIDFile.parentFile.mkdirs()
            }

            if (bundleUUIDFile.exists()) {
                bundleUUIDFile.apply {
                    writeText("")
                }
            }
            bundleUUIDList.forEach { pair ->
                bundleUUIDFile.apply {
                    appendText("${pair.first}\n")
                    appendText("${pair.second}\n")
                }
            }
        }
    }
}


private fun createStapleAndPublishTasks(notarizationExtension: NotarizationPluginExtension) {
    val taskNames = listOf(
        "mountAndCreateLocalDir",
        "zipApps",
        "checkAndSign",
        "postToNotarizationService",
        "pollAndWriteJsonTicket",
        "stapleRecursivelyAndValidate"
    )


    project.tasks.register("stapleRecursivelyAndValidate") { task ->
        task.group = "notarization"
        task.doLast {
            localReleaseDir.walkTopDown().forEach { file ->
                //todo: add other cases that need notarization
                println("$file")

                if (file.endsWith(".dmg")) {
                    project.exec { execSpec ->
                        execSpec.executable = "stapler"
                        execSpec.args("staple", file)
                        execSpec.isIgnoreExitValue = true
                    }
                    project.exec { execSpec ->
                        execSpec.executable = "stapler"
                        execSpec.args("validate", file)
                        execSpec.isIgnoreExitValue = true
                    }
                }
            }
        }
    }

    // lifecycle task
    project.tasks.register("notarize") { task ->
        task.group = "notarization"
        task.dependsOn(taskNames)
    }

}

private fun addResponseToUrlList(
    notarizationStatus: Pair<Boolean?, String?>,
    bundleResponseUrlList: List<Pair<String, String>>,
    bundleId: String?
) {
    val baos = ByteArrayOutputStream()
    project.exec { execSpec ->
        execSpec.standardOutput = baos
        execSpec.isIgnoreExitValue = true
        execSpec.commandLine("curl", "${notarizationStatus.second}")
    }

    val jsonResponse = baos.toString()
    (bundleResponseUrlList as ArrayList<Pair<String, String>>).add(Pair(bundleId!!, jsonResponse))
}

private fun populateListFromFile(bundleUUIDListFile: File): List<Pair<String, String>> {
    val pairsList = ArrayList<Pair<String, String>>()
    val lines = bundleUUIDListFile.readLines().chunked(2) { lines ->
        if (lines.size == 2) pairsList.add(Pair(lines[0], lines[1]))
        else pairsList.add(Pair(lines[0], ""))
    }

    return pairsList
}

// private methods
fun parseShareLocation(fileList: File?): String {
    // will be in the format \\devbuilds\$serverLocation\$path or \\$mainServer\$serverLocation\$path

    val strBuffer = StringBuilder()
    // todo: make this real, for now default to devbuilds/release
    val locationSet = HashSet<String>()

    fileList?.readLines()?.forEach { line ->
        // println("found: $line")
        val mountFolder = "\\\\devbuilds\\release"
        val parts = line.split("\\")
        val mountLocation = "//builder@${parts[2]}/${parts[3]}"
        locationSet.add(mountLocation)

        strBuffer.append(
            line.replace("\\", "/")
                .replace("//devbuilds/release", mountFolder)
                .replace("//devbuilds/maestro", mountFolder) + "\n"
        )
    }
    outDir.mkdirs()
    File(outDir, "tempFileList.txt").apply {
        writeText(strBuffer.toString())
    }
    return "builder@devbuilds/release"
}

fun parseUUIDFromResultOutput(notarizationResult: String): String {
    var uuid: String? = null

    notarizationResult.split("\n").forEach { line ->
        if (line.contains("RequestUUID")) {
            val pairs = line.split(" = ")
            uuid = pairs[1]
            return@forEach
        }
    }

    if (uuid is Nothing || uuid == null) {
        println("No Request UUID found in standard output. Checking standard error")
        notarizationResult.split("\n").forEach errorOutCheck@{ line ->
            if (line.contains(ARTIFACT_ALREADY_UPLOADED)) {
                val re = Regex("The upload ID is (\\d*\\w*-\\d*\\w*-\\d*\\w*-\\d*\\w*-\\d*\\w*)")
                val results = re.find(line)
                uuid = results?.groups?.get(1)?.value!!
                println("Found UUID '$uuid'")
                return uuid!!
            }
        }
    }

    return uuid ?: ""
}

private fun executeQueryNotarizationService(
    uuid: String?,
    notarizationExtension: NotarizationPluginExtension?
): String {
    println("Querying RequestUUID: '$uuid' NotarizationExtension: '$notarizationExtension'")
    val baos = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    if (uuid != null && notarizationExtension != null) {
        project.exec { execSpec ->
            execSpec.standardOutput = baos
            execSpec.errorOutput = stderr
            execSpec.executable = "xcrun"
            execSpec.isIgnoreExitValue = true

            execSpec.args(
                "altool", "--notarization-info", uuid, "-u", notarizationExtension.appleId,
                "-p", notarizationExtension.appSpecificPassword
            )
        }
        return baos.toString() + "\n" + stderr.toString()
    }
    return "RequestUUID: '$uuid' NotarizationExtension: '$notarizationExtension'"
}

fun parseNotarizationInfo(
    notarizationInfo: String,
    notarizationExtension: NotarizationPluginExtension = NotarizationPluginExtension(),
    bundleId: String? = ""
): NotarizationInfo {
    var status: String? = null
    var requestUUID: String? = null
    var logFileUrl: String? = null
    var statusCode = false
    var statusMsg: String? = null
    notarizationInfo.split("\n").forEach { line ->
        when {
            (line.contains("Status:")) -> {
                val parts = line.split(": ")
                status = parts[1].trim()
                println("Status: ${parts[1]}")
            }

            (line.contains("Status Code:")) -> {
                val parts = line.split(": ")
                statusCode = (parts[1].trim().toInt() == 0)
            }

            (line.contains("RequestUUID:")) -> {
                val parts = line.split(": ")
                requestUUID = parts[1].trim()
            }

            (line.contains("LogFileURL:")) -> {
                val parts = line.split(": ")
                logFileUrl = parts[1].trim()
            }

            (line.contains("Status Message:")) -> {
                val parts = line.split(": ")
                statusMsg = parts[1].trim()
            }

            else -> {
                // send extra logs to debug for now
//                    project.logger.debug(line)
            }
        }


    }
    return NotarizationInfo(
        bundleId = bundleId,
        statusCode = statusCode,
        status = status,
        requestUUID = requestUUID,
        logFileUrl = logFileUrl,
        statusMsg = statusMsg,
        notarizationExt = notarizationExtension
    )
}

private fun checkSignedOutput(signedOutput: String): Boolean {
    return (!signedOutput.contains(CODE_NOT_SIGNED_TEXT))
}

// todo: write tests
private fun addBinariesToBinariesList(notarizationExtension: NotarizationPluginExtension) {
    "${System.getProperty("user.home")}/devbuilds_release"
    println("Files List: ${notarizationExtension.binaryListFile}")
    // only binaries allowed [.pkg,.zip,.dmg]
    //todo: need to ensure that no backslashes '\' are present
    notarizationExtension.binariesList =
        notarizationExtension.binaryListFile?.readLines()?.groupBy { File(it) }?.keys?.toList()!!
}
}

fun String.toBundleId(): String = this.replace("_", "-").split("-").take(5).joinToString(".")

