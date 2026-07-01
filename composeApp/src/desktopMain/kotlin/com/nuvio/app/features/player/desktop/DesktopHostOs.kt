package com.nuvio.app.features.player.desktop

import java.util.Locale

internal enum class DesktopHostOs {
    MACOS,
    WINDOWS,
    LINUX_X11,
    LINUX_WAYLAND,
    UNKNOWN;

    val isLinuxX11: Boolean get() = this == LINUX_X11
    val isLinuxWayland: Boolean get() = this == LINUX_WAYLAND
    val isLinux: Boolean get() = this == LINUX_X11 || this == LINUX_WAYLAND

    companion object {
        val current: DesktopHostOs by lazy {
            val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            when {
                osName.contains("mac") -> MACOS
                osName.contains("win") -> WINDOWS
                osName.contains("linux") -> detectLinuxDisplay()
                else -> UNKNOWN
            }
        }

        private fun detectLinuxDisplay(): DesktopHostOs {
            val waylandDisplay = System.getenv("WAYLAND_DISPLAY")
            val x11Display = System.getenv("DISPLAY")
            val sessionType = System.getenv("XDG_SESSION_TYPE").orEmpty().lowercase(Locale.ROOT)

            return when {
                x11Display != null && waylandDisplay != null -> {
                    // XWayland: both DISPLAY and WAYLAND_DISPLAY are set.
                    // AWT/Compose runs under XWayland, so we can use X11 direct rendering.
                    LINUX_X11
                }
                x11Display != null -> {
                    // Pure X11
                    LINUX_X11
                }
                waylandDisplay != null -> {
                    // Pure Wayland (no X11)
                    LINUX_WAYLAND
                }
                sessionType.contains("wayland") -> {
                    LINUX_WAYLAND
                }
                else -> {
                    // Default to X11 for compatibility
                    LINUX_X11
                }
            }
        }
    }
}
