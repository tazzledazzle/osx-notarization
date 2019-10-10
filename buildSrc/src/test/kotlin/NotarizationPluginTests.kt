package com.tableau.gradle

import com.tableau.gradle.notarization.NotarizationPlugin
import com.tableau.gradle.notarization.NotarizationPluginExtension
import org.gradle.internal.impldep.com.google.common.io.Files
import org.gradle.testfixtures.ProjectBuilder
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotarizationPluginTests : Spek({
    describe("Notarization Plugin") {
        "security unlock-keychain -p password /Users/builder/Library/Keychains/TableauBuilder.keychain-db ".execute()
        val plugin = NotarizationPlugin()
        val temporaryDir = Files.createTempDir()
        val extension = NotarizationPluginExtension()
        val project = ProjectBuilder.builder()
            .withProjectDir(temporaryDir)
            .withName("notarization-test-project")
            .build()

        it("parses uuid from output") {
            val expectedUUID = "6e61ea74-7e69-4d47-906a-32dc93968342"
            val sample = "bundle id is 'Tablea.Public'\n" +
                    "signing '/Users/builder/releases_notarized/10072019-fnp_tools/zips/Tablea-Public.zip'\n" +
                    "\"/tableau-cmake/tableau/codesign/Entitlements.plist\": cannot read entitlement data\n" +
                    "setting notarization file to '/Users/builder/releases_notarized/10072019-fnp_tools/zips/Tablea-Public.zip'\n" +
                    "2019-10-07 12:14:03.454 altool[19056:19475295] No errors uploading '/Users/builder/releases_notarized/10072019-fnp_tools/zips/Tablea-Public.zip'.\n" +
                    "RequestUUID = $expectedUUID\n" +
                    "\n"

            val uuid = plugin.parseUUIDFromResultOutput(sample)
            assertEquals(expectedUUID, uuid)
        }

        it("parses a file list") {
            val fakeFileList = File("${System.getProperty("user.dir")}/src/test/resources/fakeFileList.txt")
            val shareLocation = plugin.parseShareLocation(fakeFileList)
            assertEquals("builder@devbuilds/release", shareLocation)
        }
    }
})

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
        e.printStackTrace()
        null
    }
}
