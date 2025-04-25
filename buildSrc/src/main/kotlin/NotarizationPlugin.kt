package com.tableau.gradle.notarization

import kotlinx.coroutines.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.lang.Exception
import kotlin.system.exitProcess

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

        val notarizationExtension = extensions.create<NotarizationPluginExtension>("notarization")
        project.afterEvaluate {
            createTasks(notarizationExtension)
        }
    }

    private fun Project.createTasks(notarizationExtension: NotarizationPluginExtension) {

        if (!notarizationExtension.binaryListFile.exists()) {
            throw FileNotFoundException("You must specify a `binaryListFile` in the notarization extension!")
        }

        val localDirName = notarizationExtension.binaryListFile.nameWithoutExtension
        val localReleaseDir = layout.buildDirectory.dir("releases_notarized/$ocalDirName").get().asFile

        tasks.register("mountSmbfs") {
            group = "notarization"
            description = "Mounts the SMB share for notarization"
            doLast {
                exec {
                    executable("mount")
                    args("-t", "smbfs", notarizationExtension.mountLocation, notarizationExtension.shareMount)
                }
            }
        }
        tasks.register("createLocalReleaseDirectory") {
            group = "notarization"
            description = "Creates the local release directory
            doLast {
                localReleaseDir.mkdirs()
            }
        }
        tasks.register("copyBinariesFromShare") {
            group = "notarization"
            description = "Copies binaries from the share to the local release directory"
            dependsOn("mountSmbfs", "createLocalReleaseDirectory")
            doLast {
                val binaries = notarizationExtension.binaryListFile.readLines()
                    .map { File(notarizationExtension.shareMount, it) }

                copy {
                    from(binaries)
                    into(localReleaseDir)
                }
            }
        }
        tasks.register("zipApps") {
            group = "notarization"
            description = "Zips the binaries for notarization"
            dependsOn("copyBinariesFromShare")
            doLast {
                localReleaseDir.listFiles { file -> file.extension == "app" }?.forEach { appFile ->
                    exec {
                        executable("/usr/bin/ditto")
                        args("-c", "-k", "--keepParent", appFile.absolutePath, "${appFile.nameWithoutExtension}.zip")
                    }
                }
            }
        }
    }
    tasks.register("checkAndSign")
    {
        group = "notarization"
        dependsOn("zipApps")
        doLast {
            localReleaseDir.listFiles()?.forEach { file ->
                val result = exec {
                    commandLint("codesign", "-dvv", file.absolutePath)
                    isIgnoreExitValue = true
                }
                if (result.exitValue != 0) {
                    exec {
                        commandLine(
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

        tasks.register("postToNotarizationService") {
            group = "notarization"
            dependsOn("checkAndSign")
            doLast {
                localReleaseDir.listFiles()?.forEach { file ->
                    exec {
                        commandLine(
                            "xcrun", "altool", "--notarize-app",
                            "--primary-bundle-id", file.name.toBundleId(),
                            "-u", notarizationExtension.appleId,
                            "-p", notarizationExtension.appSpecificPassword,
                            "--file", file.absolutePath
                        )
                    }
                }
            }
        }
        tasks.register("pollAndWriteJsonTicket") {
            group = "notarization"
            dependsOn("postToNotarizationService")
            doFirst {
                if (!notarizationExtension.binaryListFile.exists()) {
                    throw FileNotFoundException("You must specify a `binaryListFile` in the notarization extension!")
                }
                localReleaseDir = layout.buildDirectory
                    .dir("releases_notarized/${notarizationExtension.binaryListFile.nameWithoutExtension}").get().asFile
            }
            doLast {
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
                    delay(1000L)
                    runBlocking {
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
                    val ticketFile = File(
                        localReleaseDir, "${bundleId}.notarization.json"
                                println("Writing ticket file: ${ticketFile.absolutePath}")
                                ticketFile.writeText(jsonContent)
                    )
                }
            }
        }
        tasks.register("stapleRecursivelyAndValidate") {
            group = "notarization"
            dependsOn("pollAndWriteJsonTicket")
            doLast {
                localReleaseDir.walkTopDown().filter { it.extension == "dmg" }.forEach { file ->
                    exec {
                        commandLine("stapler", "staple", file.absolutePath)
                    }
                    exec {
                        commandLine("stapler", "validate", file.absolutePath)
                    }
                }
            }
        }
        tasks.register("writeBundleListToFile") { task ->
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
        val lines = bundleUUIDListFile.readLines()
        val pairsList = ArrayList<Pair<String, String>>()
        for (i in 0 until lines.size step 2) {
            pairsList.add(Pair(lines[i], lines[i + 1]))
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
            notarizationExtension.binaryListFile.readLines().groupBy { File(it) }.keys.toList()
    }
}

fun String.toBundleId(): String = this.replace("_", "-").split("-").take(5).joinToString(".")

open class NotarizationPluginExtension {
    lateinit var binaryListFile: File
    var binariesList: List<File> = ArrayList()
    var workingDir: String? = null
    var appSpecificPassword: String? = null
    var appleId: String? = null
    var workspaceRootDir: String? = null
    var certificateId: String? = null
    var mountLocation: String = "//builder@devbuilds/release"
}

data class NotarizationInfo(
    val bundleId: String? = "",
    val requestUUID: String? = "",
    val statusMsg: String? = "",
    val status: String? = "",
    val logFileUrl: String? = "",
    val statusCode: Boolean? = false,
    val notarizationExt: NotarizationPluginExtension? = NotarizationPluginExtension()
)
