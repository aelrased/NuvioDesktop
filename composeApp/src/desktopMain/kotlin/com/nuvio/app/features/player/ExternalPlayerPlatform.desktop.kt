package com.nuvio.app.features.player

import java.awt.Desktop
import java.io.File
import java.net.URI

private data class DesktopExternalPlayerIntent(
    val request: ExternalPlayerPlaybackRequest,
    val playerId: String?,
)

internal actual object ExternalPlayerPlatform {
    private const val systemPlayerId = "system"

    private val detectedPlayers: List<DesktopPlayerInstall> by lazy {
        detectDesktopExternalPlayers()
    }

    actual fun defaultPlayerId(): String? = systemPlayerId

    actual fun availablePlayers(): List<ExternalPlayerApp> {
        val detected = detectedPlayers.map { install ->
            ExternalPlayerApp(id = install.definition.id, name = install.definition.name)
        }
        return listOf(ExternalPlayerApp(systemPlayerId, "System default")) + detected
    }

    actual fun open(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerOpenResult {
        val player = playerId?.takeIf { it != systemPlayerId }
        if (player != null) {
            val install = detectedPlayers.firstOrNull { it.definition.id == player }
            if (install != null) {
                val result = buildDesktopPlayerCommand(install, request)
                val command = result.command
                if (command != null) {
                    return try {
                        ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .start()
                        ExternalPlayerOpenResult.Opened
                    } catch (e: Exception) {
                        ExternalPlayerOpenResult.Failed
                    }
                }
                return ExternalPlayerOpenResult.Failed
            }
        }
        return if (openUri(request.sourceUrl)) {
            ExternalPlayerOpenResult.Opened
        } else {
            ExternalPlayerOpenResult.Failed
        }
    }

    actual fun buildIntent(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerIntentResult =
        ExternalPlayerIntentResult.Success(DesktopExternalPlayerIntent(request, playerId))

    internal fun launch(intent: Any): Boolean {
        val desktopIntent = intent as? DesktopExternalPlayerIntent ?: return false
        return open(desktopIntent.request, desktopIntent.playerId) == ExternalPlayerOpenResult.Opened
    }

    private fun openUri(rawUri: String): Boolean {
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return false
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull()

        if (desktop != null && Desktop.isDesktopSupported()) {
            val opened = runCatching {
                if (uri.scheme.equals("file", ignoreCase = true)) {
                    desktop.open(File(uri))
                } else {
                    desktop.browse(uri)
                }
            }.isSuccess
            if (opened) return true
        }

        return openWithPlatformCommand(rawUri)
    }

    private fun openWithPlatformCommand(rawUri: String): Boolean {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            osName.contains("mac") -> listOf("open", rawUri)
            osName.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", rawUri)
            else -> listOf("xdg-open", rawUri)
        }
        return runCatching { ProcessBuilder(command).start() }.isSuccess
    }

    private fun detectDesktopExternalPlayers(): List<DesktopPlayerInstall> {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            osName.contains("win") -> detectWindowsExternalPlayers().map { it.toDesktopPlayerInstall() }
            osName.contains("mac") -> detectMacosExternalPlayers().map { it.toDesktopPlayerInstall() }
            else -> detectLinuxExternalPlayers().map { it.toDesktopPlayerInstall() }
        }
    }
}
