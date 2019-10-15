package com.tableau.gradle.notarization

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

class NotarizationPlugin : Plugin<Project> {
    private val CODE_NOT_SIGNED_TEXT: String = "code object is not signed at all"
    private val ARTIFACT_ALREADY_UPLOADED: String = "The software asset has already been uploaded. The upload ID is"
    lateinit var project: Project
    lateinit var workingDir: File
    lateinit var workspaceRootDir: File
    private val bundleUUIDList = ArrayList<Pair<String, String>>()
    private val bundleUUIDListFile = File("out/bundleUUIDList.txt")

    override fun apply(target: Project) {

        project = target
        val notarizationExtension = project.extensions.create("notarization", NotarizationPluginExtension::class.java)
        createNotarizationMainTasks(notarizationExtension)
        createDownloadBinariesTasks(notarizationExtension)
        createStapleAndPublishTasks(notarizationExtension)
        val notarizationExt = project.extensions.getByType(NotarizationPluginExtension::class.java)
        workingDir = File("/Users/builder/releases_notarized/2019-10-14-notarized-batch")
        // workingDir = File(notarizationExt.workingDir!!)
        // workspaceRootDir = File(notarizationExt.workspaceRootDir!!)
        // if (workingDir == null) {
        //     println("Working dir not set!")
        //     System.exit(1)
        // }

    }

    private fun createDownloadBinariesTasks(notarizationExtension: NotarizationPluginExtension) {
        val fileShareLocation = parseShareLocation(notarizationExtension.fileList)
        val releasesNotarizedDir = File("${System.getProperty("user.home")}/releases_notarized")
        //todo: make this configurable
        // val localReleaseDir = File(releasesNotarizedDir, "${LocalDate.now()}-notarized-batch")
        val localReleaseDir = File(releasesNotarizedDir, "2019-10-14-jdk-notarization")


        project.tasks.register("mountSmbfs") {task ->
            task.group = "notarization"

            task.doLast {
              val devbuildsReleaseDir = File("/Users/builder/devbuilds_release")
                if (!devbuildsReleaseDir.exists()) {
                    devbuildsReleaseDir.mkdirs()
                }
                if (devbuildsReleaseDir.listFiles().size == 0){
                    // mount dir
                    project.exec { execSpec ->
                        execSpec.workingDir = File("${System.getProperty("user.home")}/")
                        execSpec.executable = "mount"
                        execSpec.args("-t", "smbfs", "//$fileShareLocation", devbuildsReleaseDir)
                    }
                }
            }
        }

        project.tasks.register("createLocalReleaseDirectory"){ task ->
            task.group = "notarization"
            task.doLast {
                // create release local dir
                project.exec { execSpec ->
                    execSpec.workingDir = releasesNotarizedDir
                    execSpec.executable = "mkdir"
                    //todo: parse the notary group's name?
                    execSpec.args("-p", localReleaseDir)
                }
            }
        }
        // copy to release local dir
        project.tasks.register("copyBinariesFromShare") { copyTask ->
            copyTask.group = "notarization"
            copyTask.onlyIf { localReleaseDir.listFiles().size == 0 }
            copyTask.doFirst {
                if (notarizationExtension.binariesList.size == 0) {
                    println("binaries List is empty")
                    addBinariesToBinariesList(notarizationExtension)
                }
            }
            // copyTask.dependsOn(project.tasks.named("createLocalReleaseDirectory"), project.tasks.named("mountSmbfs"))
            copyTask.doLast {
                println("copying ${notarizationExtension.binariesList} into '$localReleaseDir'")
                notarizationExtension.workingDir = localReleaseDir.absolutePath
                project.copy {
                    it.from(notarizationExtension.binariesList)
                    it.into(localReleaseDir.absolutePath)
                }
                notarizationExtension.binariesList = notarizationExtension.binariesList
                    .groupBy { File("${localReleaseDir.absolutePath}/${it.name}")}.keys.toList() as List<File>
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

        // only binaries allowed [.pkg,.zip,.dmg]
        project.tasks.register("zipApps"){ task ->
            task.group = "notarization"
            // task.dependsOn(project.tasks.named("copyBinariesFromShare"))

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
                        try {
                            println("Checking signing status of $file")
                            var signedOutput = ByteArrayOutputStream()
                            val stderr = ByteArrayOutputStream()
                            project.exec { execSpec ->
                                execSpec.executable = "codesign"
                                execSpec.standardOutput = signedOutput
                                execSpec.errorOutput = stderr
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
        // todo: ensure signing control flow
        project.tasks.register("postToNoarizationService") { task ->
            task.group = "notarization"

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
                            execSpec.setIgnoreExitValue(true)
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
         val taskNames = listOf("mountAndCreateLocalDir",
                            "writeBundleListToFile",
                            "zipApps",
                            "checkAndSign",
                            "postToNoarizationService",
                            "pollAndWriteJsonTicket",
                            "stapleRecursivelyAndValidate")

        project.tasks.register("pollAndWriteJsonTicket") {task ->
            task.group = "notarization"
            task.doLast {
                val bundleResponseUrlList = ArrayList<Pair<String, String>>()
                // todo: coroutine this so we don't block
                if (bundleUUIDList.size == 0 || bundleUUIDList.isEmpty()) {
                    bundleUUIDList.addAll(populateListFromFile(bundleUUIDListFile))
                }

                val jobList = ArrayList<Job>()
                val failedNotarizationList = ArrayList<FailedNotarizationQuery>()
                // polling
                bundleUUIDList.forEach { pair ->
                    val job = GlobalScope.launch {
                        val bundleId = pair.first
                        val uuid = pair.second
                        delay(100L)
                        println("Bundle Id: '$bundleId', UUID: '$uuid'")

                        val notarizationStdOut = executeQueryNotarizationService(uuid, notarizationExtension)
                        var notarizationStatus = parseNotarizationInfo(notarizationStdOut)
                        println(notarizationStatus)
                        //   check the pair until we receive true for first
                        if (!notarizationStatus.first) {
                            failedNotarizationList.add(FailedNotarizationQuery(
                                bundleId = bundleId,
                                uuid = uuid,
                                status = notarizationStatus.first,
                                notarizationExtension = notarizationExtension))
                        }
                        else {
                        // query and save the results of the notarization
                            addResponseToUrlList(notarizationStatus, bundleResponseUrlList, bundleId)
                        }
                    }
                    jobList.add(job)
                }

                for (job in jobList) {
                    runBlocking { job.join() }
                }
                jobList.clear()

                while(failedNotarizationList.size > 0) {
                    GlobalScope.launch {
                        delay(100L) // wait to start the process again
                        val remainderList = failedNotarizationList
                        remainderList.forEach { query ->
                            val notarizationStdOut = executeQueryNotarizationService(query.uuid, query.notarizationExtension).toString()
                            val notarizationStatus = parseNotarizationInfo(notarizationStdOut)

                            //   check the pair until we receive true for first
                            if (notarizationStatus.first) {
                                // query and save the results of the notarization
                                addResponseToUrlList(notarizationStatus, bundleResponseUrlList, query.bundleId)
                                failedNotarizationList.remove(query)
                            }
                        }
                    }
                }
                // writes contents out to file
                bundleResponseUrlList.forEach { pair ->
                    val jsonFileName = "${pair.first}.notarization.json"
                    val ticketFile = File(workingDir, jsonFileName)
                   ticketFile.apply {
                       writeText(pair.second)
                   }
                    println("Writing '$ticketFile'...")
                }
            }
        }

        project.tasks.register("stapleRecursivelyAndValidate") {task ->
            task.group = "notarization"
            task.doLast {
                workingDir.walkTopDown().forEach { file ->
                    //todo: add other cases that need notarization
                    println("$file")

                    if (file.endsWith(".dmg")) {
                        project.exec { execSpec ->
                            execSpec.executable = "stapler"
                            execSpec.args("staple", file)
                            execSpec.setIgnoreExitValue(true)
                        }
                        project.exec { execSpec ->
                            execSpec.executable = "stapler"
                            execSpec.args("validate", file)
                            execSpec.setIgnoreExitValue(true)
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

            // for (i in 1 until taskNames.size) {
            //     project.tasks.named(taskNames[i]) {
            //         it.mustRunAfter(project.tasks.named(taskNames[i - 1]))
            //     }
            // }

    }

    private fun addResponseToUrlList(
        notarizationStatus: Pair<Boolean, String?>,
        bundleResponseUrlList: List<Pair<String, String>>,
        bundleId: String) {
        val baos = ByteArrayOutputStream()
        project.exec { execSpec ->
            execSpec.standardOutput = baos
            execSpec.commandLine("curl", "${notarizationStatus.second}")
        }

        val jsonResponse = baos.toString()
        (bundleResponseUrlList as ArrayList<Pair<String, String>>).add(Pair(bundleId, jsonResponse))
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
            val parts = line.split("\\")
            val mountLocation = "//builder@${parts[2]}/${parts[3]}"
            locationSet.add(mountLocation)

          //  strBuffer.append(line.replace("\\", "/")
          //      .replace("//devbuilds/release", mountFolder)
          //      .replace("//devbuilds/maestro", mountFolder) + "\n"
          //  )
        }

//        fileList?.apply {
//            writeText(strBuffer.toString())
//        }
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
                    uuid = results?.groups?.get(1)?.value!! ?: ""
                    println("Found UUID '$uuid'")
                    return uuid!!
                }
            }
        }

        return uuid ?: ""
    }

    fun executeBinarySigning(notarizationExtension: NotarizationPluginExtension, file: File?): String? {
        var command: String? = null
        project.exec { execSpec ->
            execSpec.executable = "codesign"
            execSpec.args(
                "--deep", "--force", "--options", "runtime", "--entitlements",
                "${notarizationExtension.workspaceRootDir}/tableau-cmake/tableau/codesign/Entitlements.plist",
                "--strict", "--timestamp", "--verbose", "--sign", "${notarizationExtension.certificateId}",
                "$file"
            )
            command = execSpec.commandLine.joinToString(" ")
        }
        return command
    }

    private fun executeQueryNotarizationService(uuid: String, notarizationExtension: NotarizationPluginExtension): String {
        val baos = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        project.exec { execSpec ->
            execSpec.standardOutput = baos
            execSpec.errorOutput = stderr
            execSpec.executable = "xcrun"
            execSpec.args(
                    "altool", "--notarization-info", uuid, "-u", notarizationExtension.appleId,
                    "-p", notarizationExtension.appSpecificPassword
            )
        }
        return baos.toString() + "\n" + stderr.toString()
    }

    fun parseNotarizationInfo(notarizationInfo: String): Pair<Boolean, String?> {
        var status: Boolean = false
        var logFileUrl: String? = null
        notarizationInfo.split("\n").forEach { line ->
            if (line.contains("Status:")) {
                val parts = line.split(": ")
                status = parts[1].trim().contains("success")
                println("Status: ${parts[1]}")
            }

            if (line.contains("LogFileURL:"))  {
                val parts = line.split(": ")
                logFileUrl = parts[1].trim()
            }
        }
        return Pair(status, logFileUrl)
    }

    private fun checkSignedOutput(signedOutput: String): Boolean {
        return (!signedOutput.contains(CODE_NOT_SIGNED_TEXT))
    }

    private fun addBinariesToBinariesList(notarizationExtension: NotarizationPluginExtension) {

        val mountFolder = "${System.getProperty("user.home")}/devbuilds_release"
        println( "Files List: ${notarizationExtension.fileList}")
        val filenames = parseBinariesFromFileList(notarizationExtension.fileList!!.readLines())
        println(filenames)
        if (filenames.size > 1) {
            notarizationExtension.binariesList.addAll(filenames)
        }
        else if (filenames.size == 1) {
            notarizationExtension.binariesList.add(filenames)
        }
        else {
            throw Exception("No files in ${notarizationExtension.fileList}")
        }
    }

fun parseBinariesFromFileList(pathStringList: List<String>): List<File> {
        return pathStringList.groupBy { File(it) }.keys.toList()
    }
}

fun String.toBundleId(): String = this.replace("_", "-").split("-").take(5).joinToString(".")

open class NotarizationPluginExtension {

    var fileList: File? = null
    var binariesList: List<File> = ArrayList()
    var workingDir: String? = null
    var appSpecificPassword: String? = null
    var appleId: String? = null
    var workspaceRootDir: String? = null
    var certificateId: String? = null
}

//todo: broken here
private suspend fun coexecute(str: String):  String? = runBlocking {
    val parts = str.split("\\s".toRegex())
    val proc = ProcessBuilder(*parts.toTypedArray())
        .directory(File(System.getProperty("user.dir")))
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()

    proc.waitFor()
    proc.inputStream.bufferedReader().readText()
}

private fun String.execute(): String? {
    return try {
        val parts = this.split("\\s".toRegex())
        val proc = ProcessBuilder(*parts.toTypedArray())
            .directory(File(System.getProperty("user.dir")))
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        proc.waitFor(60, TimeUnit.MINUTES)
        proc.inputStream.bufferedReader().readText()
    } catch(e: IOException) {
        println(e.printStackTrace())
        null
    }
}

data class FailedNotarizationQuery(val bundleId: String, val uuid: String, val status: Boolean, val notarizationExtension: NotarizationPluginExtension)
