package com.tableau.gradle.notarization

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime

class NotarizationPlugin : Plugin<Project> {
    private val CODE_NOT_SIGNED_TEXT: String = "code object is not signed at all"
    lateinit var project: Project
    lateinit var workingDir: File
    lateinit var workspaceRootDir: File
    private val bundleUUIDList = ArrayList<Pair<String, String>>()

    override fun apply(target: Project) {
        project = target
        workingDir = project.projectDir
        val notarizationExtension = project.extensions.create("notarization", NotarizationPluginExtension::class.java)
        createNotarizationMainTasks(notarizationExtension)
        createDownloadBinariesTasks(notarizationExtension)
        createStapleAndPublishTasks(notarizationExtension)
    }

    private fun createDownloadBinariesTasks(notarizationExtension: NotarizationPluginExtension) {
        val fileShareLocation = parseShareLocation(notarizationExtension.fileList)
//        addBinariesToBinariesList(notarizationExtension)

        val localReleaseDir = "${LocalDateTime.now()}-notarized-batch"
        val devbuildsReleaseDir = File("${System.getProperty("user.home")}/devbuilds_release/")

        project.tasks.register("mountSmbfs") {task ->
            task.group = "notarization"

            task.doLast {
                // mount dir
                project.exec { execSpec ->
                    execSpec.workingDir = File("${System.getProperty("user.home")}/")
                    execSpec.executable = "mount"
                    execSpec.args(arrayOf("-t", "smbfs", "//$fileShareLocation", devbuildsReleaseDir))
                }
            }
        }

        project.tasks.register("createLocalReleaseDirectory"){ task ->
            task.group = "notarization"
            task.doLast {
                // create release local dir
                project.exec { execSpec ->
                    execSpec.workingDir = File("${System.getProperty("user.home")}/releases_notarized")
                    execSpec.executable = "mkdir"
                    //todo: parse the notary group's name?
                    execSpec.args(arrayOf("-p", localReleaseDir))
                }
            }
        }
        // copy to release local dir
        project.tasks.register("copyBinariesFromShare", Copy::class.java) { copyTask: Copy ->
            copyTask.group = "notarization"
            copyTask.dependsOn(project.tasks.named("createLocalReleaseDirectory"), project.tasks.named("mountSmbfs"))

            // todo: need to replace the original path and replace the slashes
            copyTask.from(notarizationExtension.binariesList) // todo: may fail
            copyTask.into(localReleaseDir)
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
        // only binaries allowed [.pkg,.zip,.dmg]
        project.tasks.register("zipApps"){task ->
            task.group = "notarization"
            task.dependsOn(project.tasks.named("copyBinariesFromShare"))
            task.doLast {
                workingDir.listFiles().filter { it.name.endsWith("app") }.forEach { file ->
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
            task.dependsOn(project.tasks.named("zipApps"))
            task.doLast {
                notarizationExtension
                    .binariesList
                    .forEach { file ->
                        println("Checking signing status of $file")
                        val signedOutput = ByteArrayOutputStream()
                        project.exec { execSpec ->
                            execSpec.executable = "codesign"
                            execSpec.standardOutput = signedOutput
                            execSpec.args(arrayOf("-dvv", file.absolutePath))
                        }

                        val signed = checkSignedOutput(signedOutput.toString())
                        if (!signed) {
                            println("signing '$file'")
                            // sign
                            project.exec { execSpec ->
                                execSpec.executable = "codesign"
                                execSpec.args(
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
                                    "${notarizationExtension.certificateId}",
                                    "$file"
                                )
                            }
                        }
                        println("bundle Id is ${file.name.toBundleId()}")
                    }
            }
        }
        // todo: ensure signing control flow
        project.tasks.register("postToNoarizationService") {task ->
            task.group = "notarization"
            task.dependsOn(project.tasks.named("checkAndSign"))

            task.doLast {
                notarizationExtension
                    .binariesList
                    .forEach { file ->
                        // notarize
                        val baos = ByteArrayOutputStream()
                        project.exec { execSpec ->
                            execSpec.executable = "xcrun"
                            execSpec.standardOutput = baos
                            execSpec.args(
                                "altool", "--notarize-app", "--primary-bundle-id", file.name.toBundleId(), "-u",
                                notarizationExtension.appleId, "-p", notarizationExtension.appSpecificPassword, "--file",
                                file.absolutePath
                            )
                        }
                        println(baos.toString())


                        val notarizationResult: String = baos.toString()
                        // todo: parse output for RequestUUID and save it
                        val requestUUID: String = parseUUIDFromResultOutput(notarizationResult)
                        bundleUUIDList.add(Pair(file.name.toBundleId(), requestUUID))
                    }
            }
        }
    }

    private fun createStapleAndPublishTasks(notarizationExtension: NotarizationPluginExtension) {
        project.tasks.register("pollAndWriteJsonTicket") {task ->
            task.group = "notarization"
            task.dependsOn(project.tasks.named("postToNoarizationService"))
            task.doLast {
                val bundleResponseUrlList = ArrayList<Pair<String, String>>()
                // todo: coroutine this so we don't block

                // polling
                bundleUUIDList.forEach { pair ->
                    val bundleId = pair.first
                    val uuid = pair.second
                    println("Bundle Id: '$bundleId', UUID: '$uuid'")

                    val notarizationStdOut = executeQueryNotarizationService(uuid, notarizationExtension)
                    var notarizationStatus = parseNotarizationInfo(notarizationStdOut.toString())
                    // check the pair until we receive true for first
                    while (!notarizationStatus.first) {
                        Thread.sleep(1800000L) // 30 min
                        notarizationStatus =
                            parseNotarizationInfo(executeQueryNotarizationService(uuid, notarizationExtension).toString())
                        //todo: write tests
                    }

                    // query and save the results of the notarization
                    val baos = ByteArrayOutputStream()
                    project.exec { execSpec ->
                        execSpec.standardOutput = baos
                        execSpec.executable = "curl"
                        execSpec.args(arrayOf(notarizationStatus.second))
                    }
                    println(baos.toString())
                    bundleResponseUrlList.add(Pair(bundleId, baos.toString()))
                }

                // writes contents out to file
                bundleResponseUrlList.forEach {pair ->
                    val ticketFile = File(workingDir, "${pair.first}.notarization.json")
                    ticketFile.apply {
                        writeText(pair.second)
                    }
                    // todo: write test
                }
            }
        }

        project.tasks.register("stapleRecursivelyAndValidate") {task ->
            task.group = "notarization"
            task.dependsOn(project.tasks.named("pollAndWriteJsonTicket"))

            task.doLast {
                workingDir.walkTopDown().forEach { file ->
                    project.exec { execSpec ->
                        execSpec.executable = "stapler"
                        execSpec.args(arrayOf("staple", file))
                    }
                    project.exec { execSpec ->
                        execSpec.executable = "stapler"
                        execSpec.args(arrayOf("validate", file))
                    }
                }
            }
        }

        // lifecycle task
        project.tasks.register("notarize") {task ->
            task.group = "notarization"
            task.dependsOn(project.tasks.named("stapleRecursivelyAndValidate"))
        }
    }

    // private methods
    fun parseShareLocation(fileList: File?): String {
        // will be in the format \\devbuilds\$serverLocation\$path or \\$mainServer\$serverLocation\$path

        val strBuffer = StringBuilder()
        // todo: make this real, for now default to devbuilds/release
        val locationSet = HashSet<String>()

        fileList?.readLines()?.forEach { line ->
            println("found: $line")
            val parts = line.split("\\")
            val mountLocation = "//builder@${parts[2]}/${parts[3]}"
            locationSet.add(mountLocation)
            val mountFolder = "${System.getProperty("user.home")}/devbuilds_release"
            strBuffer.append(line.replace("\\", "/")
                .replace("//devbuilds/release", mountFolder)
                .replace("//devbuilds/maestro", mountFolder) + "\n"
            )
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
            throw Exception("No Request UUID found in output")
        }

        return uuid!!
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

    private fun executeQueryNotarizationService(uuid: String, notarizationExtension: NotarizationPluginExtension): ByteArrayOutputStream {
        val baos = ByteArrayOutputStream()
        project.exec { execSpec ->
            execSpec.standardOutput = baos
            execSpec.executable = "xcrun"
            execSpec.args(
                arrayListOf(
                    "altool", "--notarization-info", uuid, "-u", notarizationExtension.appleId,
                    "-p", notarizationExtension.appSpecificPassword
                )
            )
        }
        return baos
    }

    private fun parseNotarizationInfo(notarizationInfo: String): Pair<Boolean, String?> {
        val sample = "2019-10-07 12:48:42.024 altool[19403:19482201] No errors getting notarization info.\n" +
                "\n" +
                "   RequestUUID: 6c56f7ee-67b3-47b1-9dff-2bdf1987c6e2\n" +
                "          Date: 2019-10-07 19:11:58 +0000\n" +
                "        Status: success\n" +
                "    LogFileURL: https://osxapps-ssl.itunes.apple.com/itunes-assets/Enigma113/v4/68/0e/47/680e4799-91a2-45af-6c2d-885ee56c92b0/developer_log.json?accessKey=1570672121_2286566782519926408_9NW%2B1lVt6oqCsdIiuTHrR0VdH62WEYX5Xt2W7c8k%2BPDZ%2F1SB%2FxwSpZBT4COGlJAhwJ9ypHtbKLFa1ymJ3eUCjxnKGKdBZx7ncgdn6E2aPBCux4LYAqkjnTm1qmJ2wKx472%2FIN3NWbU0qtaov6UynhtQkZF%2FdwWSPRnNZeVBGFUA%3D\n" +
                "   Status Code: 0\n" +
                "Status Message: Package Approved"
        var status: Boolean = false
        var logFileUrl: String? = null
        notarizationInfo.split("\n").forEach { line ->
            if (line.contains("Status")) {
                val parts = line.split(": ")
                status = parts[1].trim().contains("success")
                //todo: write test
            }

            if (line.contains("LogFileURL"))  {
                val parts = line.split(": ")
                logFileUrl = parts[1].trim()
            }
        }
        return Pair(status, logFileUrl)
    }

    private fun checkSignedOutput(toString: String): Boolean {
        return (toString.contains(CODE_NOT_SIGNED_TEXT))
    }

    private fun addBinariesToBinariesList(notarizationExtension: NotarizationPluginExtension) {
        if (notarizationExtension.workingDir == null) {
//            throw GradleException("No working dir to pull binaries from!")
        }

        notarizationExtension.binariesList.addAll(
            parseBinariesFromFileList(notarizationExtension.fileList)
        )
    }

    fun parseBinariesFromFileList(listFile: File?): ArrayList<File> {
        // todo: at this point the file should be cleansed and redirected to the mounted folder
        val fileStringList = listFile?.readLines()
        println(fileStringList)
        return fileStringList?.groupBy { File(it) }?.keys!! as ArrayList<File>
    }
}

fun String.toBundleId(): String = this.replace("-", ".")

open class NotarizationPluginExtension {

    var fileList: File? = null
    var binariesList: ArrayList<File> = ArrayList()
    var workingDir: String? = null
    var appSpecificPassword: String? = null
    var appleId: String? = null
    var workspaceRootDir: String? = null
    var certificateId: String? = null
}
