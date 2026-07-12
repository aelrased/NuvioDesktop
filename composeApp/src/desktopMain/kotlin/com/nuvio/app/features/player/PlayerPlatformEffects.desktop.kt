package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.core.ui.DesktopBackHandlers
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerBridge

@Composable
actual fun LockPlayerToLandscape() = Unit

@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) {
    val keepAwakeController = remember { DesktopKeepAwakeController() }

    SideEffect {
        keepAwakeController.setEnabled(keepScreenAwake)
    }

    DisposableEffect(keepAwakeController) {
        onDispose {
            keepAwakeController.close()
        }
    }
}

@Composable
actual fun ManagePlayerPictureInPicture(
    isPlaying: Boolean,
    playerSize: IntSize,
) = Unit

@Composable
actual fun rememberPlayerGestureController(): PlayerGestureController? = null

private var lastDesktopBackHandler: (() -> Unit)? = null

actual fun setDesktopBackHandler(handler: (() -> Unit)?) {
    if (lastDesktopBackHandler != null) {
        DesktopBackHandlers.removeBack(lastDesktopBackHandler!!)
    }
    if (handler != null) {
        DesktopBackHandlers.pushBack(handler)
    }
    lastDesktopBackHandler = handler
}

private class DesktopKeepAwakeController : AutoCloseable {
    private var inhibitProcess: Process? = null
    private var windowsDisplaySleepInhibited = false

    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            startInhibit()
        } else {
            stopInhibit()
        }
    }

    private fun startInhibit() {
        if (inhibitProcess?.isAlive == true) return
        if (windowsDisplaySleepInhibited) return

        inhibitProcess = when (DesktopHostOs.current) {
            DesktopHostOs.MACOS -> startMacOsInhibit()
            DesktopHostOs.LINUX -> startLinuxInhibit()
            DesktopHostOs.WINDOWS -> {
                setWindowsDisplaySleepInhibited(true)
                null
            }
            else -> null
        }
    }

    private fun startMacOsInhibit(): Process? {
        val currentPid = ProcessHandle.current().pid().toString()
        return runCatching {
            ProcessBuilder(
                "/usr/bin/caffeinate",
                "-d",
                "-i",
                "-w",
                currentPid,
            ).start()
        }.getOrNull()
    }

    private fun startLinuxInhibit(): Process? {
        // Tier 1: systemd-inhibit (systemd / elogind / Devuan)
        // The "sleep infinity" process blocks, keeping the inhibitor alive.
        runCatching {
            val process = ProcessBuilder(
                "systemd-inhibit",
                "--what=handle-lid-switch:sleep:idle",
                "--who=Nuvio",
                "--why=Playing video",
                "sleep",
                "infinity",
            ).start()
            if (process.isAlive) return process
        }

        // Tier 2: busctl call to logind (newer systemd without systemd-inhibit)
        runCatching {
            val process = ProcessBuilder(
                "busctl",
                "call",
                "--user",
                "org.freedesktop.login1",
                "/org/freedesktop/login1",
                "org.freedesktop.login1.Manager",
                "Inhibit",
                "ssss",
                "sleep",
                "Nuvio",
                "Playing video",
                "delay",
            ).start()
            process.waitFor()
            if (process.exitValue() == 0) {
                // busctl exits immediately; re-inhibit periodically via a wrapper
                val wrapper = ProcessBuilder(
                    "bash", "-c",
                    "while true; do busctl call --user org.freedesktop.login1 /org/freedesktop/login1 org.freedesktop.login1.Manager Inhibit ssss sleep Nuvio \"Playing video\" delay >/dev/null 2>&1; sleep 60; done",
                ).start()
                if (wrapper.isAlive) return wrapper
            }
        }

        // Tier 3: xdg-screensaver suspend (X11 only, needs window ID)
        // On Wayland this is a no-op; systemd-inhibit above should have worked.
        runCatching {
            val display = System.getenv("DISPLAY")
            if (!display.isNullOrBlank()) {
                // Try to find the active window ID via xdotool
                val xdotool = ProcessBuilder("xdotool", "getactivewindow").start()
                val windowId = xdotool.inputStream.bufferedReader().readLine()?.trim()
                xdotool.waitFor()
                if (windowId != null && windowId.matches(Regex("\\d+"))) {
                    val process = ProcessBuilder(
                        "xdg-screensaver",
                        "suspend",
                        windowId,
                    ).start()
                    if (process.isAlive) return process
                }
            }
        }

        return null
    }

    private fun stopInhibit() {
        inhibitProcess
            ?.takeIf(Process::isAlive)
            ?.destroy()
        inhibitProcess = null
        if (windowsDisplaySleepInhibited) {
            setWindowsDisplaySleepInhibited(false)
        }
    }

    private fun setWindowsDisplaySleepInhibited(inhibited: Boolean) {
        if (windowsDisplaySleepInhibited == inhibited) return

        val applied = runCatching {
            NativePlayerBridge.setWindowsDisplaySleepInhibited(inhibited)
        }.getOrDefault(false)
        if (applied) {
            windowsDisplaySleepInhibited = inhibited
        }
    }

    override fun close() {
        stopInhibit()
    }
}
