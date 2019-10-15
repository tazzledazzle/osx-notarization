package com.tableau.gradle

import com.tableau.gradle.notarization.NotarizationPlugin
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

class NotarizationPluginTests : Spek({
    describe("Notarization Plugin") {
        "security unlock-keychain -p password /Users/builder/Library/Keychains/TableauBuilder.keychain-db ".execute()
        val plugin = NotarizationPlugin()

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
        it("parses notarization response properly") {
            val expectedUrl = "https://osxapps-ssl.itunes.apple.com/itunes-assets/Enigma113/v4/68/0e/47/680e4799-91a2-45af-6c2d-885ee56c92b0/developer_log.json?accessKey=1570672121_2286566782519926408_9NW%2B1lVt6oqCsdIiuTHrR0VdH62WEYX5Xt2W7c8k%2BPDZ%2F1SB%2FxwSpZBT4COGlJAhwJ9ypHtbKLFa1ymJ3eUCjxnKGKdBZx7ncgdn6E2aPBCux4LYAqkjnTm1qmJ2wKx472%2FIN3NWbU0qtaov6UynhtQkZF%2FdwWSPRnNZeVBGFUA%3D"
            val sample = "2019-10-07 12:48:42.024 altool[19403:19482201] No errors getting notarization info.\n" +
                    "\n" +
                    "   RequestUUID: 6c56f7ee-67b3-47b1-9dff-2bdf1987c6e2\n" +
                    "          Date: 2019-10-07 19:11:58 +0000\n" +
                    "        Status: success\n" +
                    "    LogFileURL: $expectedUrl\n" +
                    "   Status Code: 0\n" +
                    "Status Message: Package Approved"
            val actualOutcome = plugin.parseNotarizationInfo(sample)
            assertEquals(true, actualOutcome.statusCode)
            assertEquals(expectedUrl, actualOutcome.logFileUrl)
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
