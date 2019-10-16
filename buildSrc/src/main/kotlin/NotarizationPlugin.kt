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
    val mountDir = File("/Users/builder/devbuilds_release")
    val releasesNotarizedDir = File("${System.getProperty("user.home")}/releases_notarized")
    lateinit var localReleaseDir: File
    var outDir: File = File( "out")

    override fun apply(target: Project) {
        // configure plugin
        project = target
        outDir.mkdirs()
        project.extensions.create("notarization", NotarizationPluginExtension::class.java)
        val notarizationExtension = project.extensions.getByName("notarization") as NotarizationPluginExtension
        project.afterEvaluate {
            // add tasks
            createNotarizationMainTasks(notarizationExtension)
            createDownloadBinariesTasks(notarizationExtension)
            createStapleAndPublishTasks(notarizationExtension)
        }
    }

    private fun createDownloadBinariesTasks(notarizationExtension: NotarizationPluginExtension) {
        if (notarizationExtension.binaryListFile is Nothing || !notarizationExtension.binaryListFile.exists()) {
            throw FileNotFoundException("You must specify a `binaryListFile` in the notarization extension!")
        }
        val fileShareLocation = notarizationExtension.mountLocation
        val localDirName = notarizationExtension.binaryListFile.name.replace(".txt", "")

        project.tasks.register("mountSmbfs") {task ->
            task.group = "notarization"

            task.doLast {
                if (!mountDir.exists()) {
                    mountDir.mkdirs()
                }
                if (mountDir.listFiles()?.size == 0){
                    // mount dir
                    project.exec { execSpec ->
                        execSpec.workingDir = File("${System.getProperty("user.home")}/")
                        execSpec.executable = "mount"
                        execSpec.args("-t", "smbfs", fileShareLocation, mountDir)
                    }
                }
            }
        }

        project.tasks.register("createLocalReleaseDirectory"){ task ->
            task.group = "notarization"
            task.description = "create release local dir if doesn't exist"

            task.doFirst {
                if (notarizationExtension.binaryListFile == null) {
                    println("You must specify a 'fileList' value in the notarization exension!")
                    exitProcess(1)
                }

                localReleaseDir = File(releasesNotarizedDir, localDirName)
            }

            task.doLast {
                project.exec { execSpec ->
                    execSpec.workingDir = releasesNotarizedDir
                    execSpec.executable = "mkdir"
                    //todo: parse the notary group's name?
                    execSpec.args("-p", localReleaseDir)
                }
            }
        }

        project.tasks.register("copyBinariesFromShare") { copyTask ->
            // validate extension
            if (notarizationExtension.binaryListFile == null) {
                project.logger.error("You must specify a 'fileList' value in the notarization exension!")
                exitProcess(1)
            }
            localReleaseDir = File(releasesNotarizedDir, localDirName)

            copyTask.group = "notarization"
            copyTask.onlyIf { localReleaseDir.listFiles()!!.isEmpty() }
            copyTask.mustRunAfter(project.tasks.named("createLocalReleaseDirectory"))

            copyTask.doFirst {
                if (notarizationExtension.binariesList.size == 0) {
                    println("binaries List is empty")
                    addBinariesToBinariesList(notarizationExtension)
                }
            }

            copyTask.dependsOn(
                project.tasks.named("createLocalReleaseDirectory"),
                project.tasks.named("mountSmbfs")
            )

            copyTask.doLast {
                project.logger.info("copying ${notarizationExtension.binariesList} into '$localReleaseDir'")
                notarizationExtension.workingDir = localReleaseDir.absolutePath
                project.copy {
                    it.from(notarizationExtension.binariesList)
                    it.into(localReleaseDir.absolutePath)
                }
            }
        }

        project.tasks.register("mountAndCreateLocalDir") {task ->
            task.group = "notarization"
            task.dependsOn(project.tasks.named("mountSmbfs"),
                project.tasks.named("createLocalReleaseDirectory"),
                project.tasks.named("copyBinariesFromShare")
            )
        }
    }

    private fun createNotarizationMainTasks(notarizationExtension: NotarizationPluginExtension) {

        project.tasks.register("zipApps"){ task ->
            task.group = "notarization"
            task.mustRunAfter(project.tasks.named("copyBinariesFromShare"))

            task.doFirst {
                if (notarizationExtension.binariesList.size == 0) {
                    println("binaries List is empty")
                    addBinariesToBinariesList(notarizationExtension)
                }
            }

            task.doLast {
                notarizationExtension.binariesList.filter { it.name.endsWith("app") }.forEach { file ->
                    // zip
                    println("zipping '$file' into '${file.name.toBundleId()}.zip'")
                    project.exec { execSpec ->
                        execSpec.executable = "/usr/bin/ditto"
                        execSpec.args("-c", "-k", "--keepParent", file.absolutePath, "${file.name.toBundleId()}.zip")
                    }
                }
            }
        }

        project.tasks.register("checkAndSign") { task ->
            task.group = "notarization"
            task.mustRunAfter(project.tasks.named("zipApps"))

            task.doFirst {
                if (notarizationExtension.binariesList.size == 0) {
                    println("binaries List is empty")
                    addBinariesToBinariesList(notarizationExtension)
                }
            }

            task.doLast {
                notarizationExtension.binariesList.forEach { file ->
                        try {
                            println("Checking signing status of $file")
                            val signedOutput = ByteArrayOutputStream()
                            val stderr = ByteArrayOutputStream()
                            project.exec { execSpec ->
                                execSpec.executable = "codesign"
                                execSpec.standardOutput = signedOutput
                                execSpec.errorOutput = stderr
                                execSpec.isIgnoreExitValue = true

                                execSpec.args("-dvv", file.absolutePath)
                            }
                            //todo: append stderr to the output and check it
                            val signed = checkSignedOutput(signedOutput.toString())
                            println("Is file '$file' signed? $signed")
                        // val signedOutput = "codesign -dvv ${file.absolutePath}".execute()!!
                        }
                        // println()
                        catch (e: Exception) {
                            println("signing '$file'")
                            // sign
                            project.exec { execSpec ->
                                execSpec.executable = "codesign"
                                execSpec.isIgnoreExitValue = true

                                execSpec.args("--deep", "--force", "--options", "runtime", "--entitlements",
                                    "${notarizationExtension.workspaceRootDir}/tableau-cmake/tableau/codesign/Entitlements.plist",
                                    "--strict", "--timestamp", "--verbose", "--sign", "${notarizationExtension.certificateId}",
                                    "$file"
                                )
                            }
                        }
                        println("bundle Id is ${file.name.toBundleId()}")
                    }
            }
        }

        project.tasks.register("writeBundleListToFile") { task ->
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
                        appendText("${pair.first}\n" )
                        appendText("${pair.second}\n")
                    }
                }
            }
        }

        project.tasks.register("postToNotarizationService") { task ->
            task.group = "notarization"
            task.mustRunAfter(project.tasks.named("checkAndSign"))

            task.doFirst {
                if (notarizationExtension.binariesList.size == 0) {
                    println("binaries List is empty")
                    addBinariesToBinariesList(notarizationExtension)
                }
            }

            task.doLast {
                notarizationExtension
                    .binariesList
                    .forEach { file ->
                        // notarize
                        val baos = ByteArrayOutputStream()
                        val stderr = ByteArrayOutputStream()
                        project.exec { execSpec ->
                            execSpec.executable = "xcrun"
                            execSpec.standardOutput = baos
                            execSpec.errorOutput = stderr
                            execSpec.args("altool", "--notarize-app", "--primary-bundle-id", file.name.toBundleId(), "-u",
                                notarizationExtension.appleId, "-p", notarizationExtension.appSpecificPassword, "--file",
                                file.absolutePath
                            )
                            execSpec.isIgnoreExitValue = true
                        }

                        val notarizationResult: String = baos.toString() + "\n" + stderr.toString()
                        // todo: parse output for RequestUUID and save it
                        val requestUUID: String = parseUUIDFromResultOutput(notarizationResult)
                        bundleUUIDList.add(Pair(file.name.toBundleId(), requestUUID))
                    }
            }
            task.finalizedBy(project.tasks.named("writeBundleListToFile"))
        }
    }

    private fun createStapleAndPublishTasks(notarizationExtension: NotarizationPluginExtension) {
        val taskNames = listOf(
//            "mountSmbfs",
//            "createLocalReleaseDirectory",
//            "copyBinariesFromShare",
            "mountAndCreateLocalDir",
            "zipApps",
            "checkAndSign",
            "postToNotarizationService",
            "pollAndWriteJsonTicket",
            "stapleRecursivelyAndValidate")

        project.tasks.register("pollAndWriteJsonTicket") { task ->
            task.group = "notarization"
            task.mustRunAfter(project.tasks.named("postToNotarizationService"))

            task.doFirst {
                // validate extension
                if (notarizationExtension.binaryListFile == null) {
                    println("You must specify a 'fileList' value in the notarization exension!")
                    exitProcess(1)
                }

                localReleaseDir = File(releasesNotarizedDir, notarizationExtension.binaryListFile.name.replace(".txt", ""))
            }

            task.doLast {
                val bundleResponseUrlList = ArrayList<Pair<String, String>>()
                // todo: coroutine this so we don't block
                if (bundleUUIDList.size == 0 || bundleUUIDList.isEmpty()) {
                    bundleUUIDList.addAll(populateListFromFile(bundleUUIDListFile))
                }

                val jobList = ArrayList<Job>()
                val failedNotarizationList = ArrayList<NotarizationInfo>()
                // polling
                bundleUUIDList.forEach { pair ->
                    val job = GlobalScope.launch {
                        delay(100L)
                        val bundleId = pair.first
                        val uuid = pair.second
                        println("Bundle Id: '$bundleId', UUID: '$uuid'")

                        val notarizationStdOut = executeQueryNotarizationService(uuid, notarizationExtension)
                        val notarizationInfo = parseNotarizationInfo(notarizationStdOut, notarizationExtension, bundleId)
                        println(notarizationInfo)

                        // todo: ensure that statusCode is the correct condition here
                        //   check the pair until we receive true for first
                        if (!notarizationInfo.statusCode!! && notarizationInfo.status != "success" && notarizationInfo.status != "invalid") {
                            failedNotarizationList.add(notarizationInfo)
                        }
                        else {
                        // query and save the results of the notarization
                            addResponseToUrlList(Pair(notarizationInfo.statusCode, notarizationInfo.logFileUrl),
                                bundleResponseUrlList, bundleId)
                        }
                    }
                    jobList.add(job)
                }

                for (job in jobList) {
                    runBlocking { job.join() }
                }
                jobList.clear()

                while(failedNotarizationList.size > 0) {
                    val remainderList = failedNotarizationList
                    remainderList.forEach { remainder ->
                            val job = GlobalScope.launch {
                                delay(6000L) // wait to start the process again
                                val notarizationStdOut =
                                    executeQueryNotarizationService(remainder.requestUUID, remainder.notarizationExt)
                                val notarizationInfo = parseNotarizationInfo(
                                    notarizationStdOut,
                                    notarizationExtension,
                                    remainder.bundleId
                                )

                                //   check the pair until we receive true for first
                                if (notarizationInfo.statusCode!! && notarizationInfo.status == "success") {
                                    // query and save the results of the notarization
                                    addResponseToUrlList(
                                        Pair(notarizationInfo.statusCode, notarizationInfo.logFileUrl),
                                        bundleResponseUrlList, notarizationInfo.bundleId
                                    )
                                    failedNotarizationList.remove(remainder)
                                }
                            }
                            jobList.add(job)
                        }
                    for (job in jobList) {
                        runBlocking { job.join() }
                    }
                }
                // writes contents out to file
                bundleResponseUrlList.forEach { pair ->
                    val jsonFileName = "${pair.first}.notarization.json"
                    val ticketFile = File(localReleaseDir, jsonFileName)
                    println("Writing '$ticketFile'...")
                    ticketFile.apply {
                       writeText(pair.second)
                   }
                }
            }
        }

        project.tasks.register("stapleRecursivelyAndValidate") {task ->
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
        project.tasks.register("notarize") {task ->
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
        for(i in 0 until lines.size step 2) {
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

            strBuffer.append(line.replace("\\", "/")
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

        if (uuid is Nothing || uuid == null){
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

    private fun executeQueryNotarizationService(uuid: String?, notarizationExtension: NotarizationPluginExtension?): String {
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
        return NotarizationInfo(bundleId = bundleId,
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
        println( "Files List: ${notarizationExtension.binaryListFile}")
        // only binaries allowed [.pkg,.zip,.dmg]
        //todo: need to ensure that no backslashes '\' are present
        notarizationExtension.binariesList = notarizationExtension.binaryListFile.readLines().groupBy { File(it) }.keys.toList()
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

data class NotarizationInfo (
    val bundleId: String? = "",
    val requestUUID: String? = "",
    val statusMsg: String? = "",
    val status: String? = "",
    val logFileUrl: String? = "",
    val statusCode: Boolean? = false,
    val notarizationExt: NotarizationPluginExtension? = NotarizationPluginExtension()
)
