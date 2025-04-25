package com.tableau.gradle.notarization

data class NotarizationInfo(
    val bundleId: String? = "",
    val requestUUID: String? = "",
    val statusMsg: String? = "",
    val status: String? = "",
    val logFileUrl: String? = "",
    val statusCode: Boolean? = false,
    val notarizationExt: NotarizationPluginExtension? = NotarizationPluginExtension()
)