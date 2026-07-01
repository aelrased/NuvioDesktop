package com.nuvio.app.features.player.desktop

import java.awt.Component
import java.lang.reflect.Field
import java.lang.reflect.Method

internal object AwtNativeViewResolver {
    fun resolveNativeViewPointer(component: Component): Long =
        when (DesktopHostOs.current) {
            DesktopHostOs.MACOS -> MacosAwtViewResolver.resolveNativeViewPointer(component)
            DesktopHostOs.WINDOWS -> WindowsAwtViewResolver.resolveNativeViewPointer(component)
            DesktopHostOs.LINUX_X11 -> LinuxX11AwtViewResolver.resolveNativeViewPointer(component)
            DesktopHostOs.LINUX_WAYLAND -> error("Native desktop playback via AWT view is not supported on pure Wayland. Use LinuxPlayerHost offscreen path.")
            else -> error("Native desktop playback is not implemented for ${DesktopHostOs.current}.")
        }
}

private object MacosAwtViewResolver {
    private val componentPeerField: Field by lazy {
        Component::class.java.getDeclaredField("peer").apply { isAccessible = true }
    }

    fun resolveNativeViewPointer(component: Component): Long {
        val peer = componentPeerField.get(component)
            ?: error("AWT component peer is not ready for native playback.")

        val platformWindow = invokeObject(peer, "getPlatformWindow")
        val contentView = invokeObject(platformWindow, "getContentView")
        val pointer = invokeLong(contentView, "getAWTView")
        if (pointer == 0L) {
            error("macOS AWT view pointer was zero.")
        }
        return pointer
    }

    private fun findMethod(type: Class<*>, name: String): Method {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredMethod(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        error("Method $name was not found on ${type.name}.")
    }

    private fun invokeObject(target: Any, methodName: String): Any =
        findMethod(target.javaClass, methodName).invoke(target)
            ?: error("$methodName returned null.")

    private fun invokeLong(target: Any, methodName: String): Long =
        (findMethod(target.javaClass, methodName).invoke(target) as Number).toLong()
}

private object WindowsAwtViewResolver {
    private val componentPeerField: Field by lazy {
        Component::class.java.getDeclaredField("peer").apply { isAccessible = true }
    }

    fun resolveNativeViewPointer(component: Component): Long {
        val peer = componentPeerField.get(component)
            ?: error("AWT component peer is not ready for native playback.")

        val pointer = invokeLong(peer, "getHWnd")
        if (pointer == 0L) {
            error("Windows AWT HWND pointer was zero.")
        }
        return pointer
    }

    private fun findMethod(type: Class<*>, name: String): Method {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredMethod(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        error("Method $name was not found on ${type.name}.")
    }

    private fun invokeLong(target: Any, methodName: String): Long =
        (findMethod(target.javaClass, methodName).invoke(target) as Number).toLong()
}

private object LinuxX11AwtViewResolver {
    private val componentPeerField: Field by lazy {
        Component::class.java.getDeclaredField("peer").apply { isAccessible = true }
    }

    fun resolveNativeViewPointer(component: Component): Long {
        val peer = componentPeerField.get(component)
            ?: error("AWT component peer is not ready for native playback.")

        // Method 1: getPlatformWindow().getWindowHandle() (pure X11)
        val platformWindow = invokeObjectOrNull(peer, "getPlatformWindow")
        if (platformWindow != null) {
            val handle = invokeLongOrNull(platformWindow, "getWindowHandle")
            if (handle != null && handle > 0) return handle
            val surfaceData = invokeObjectOrNull(platformWindow, "getSurfaceData")
            if (surfaceData != null) {
                val h2 = invokeLongOrNull(surfaceData, "getNativeWindow")
                if (h2 != null && h2 > 0) return h2
            }
        }

        // Method 2: getWindow().getHandle()
        val window = invokeObjectOrNull(peer, "getWindow")
        if (window != null) {
            val handle = invokeLongOrNull(window, "getHandle")
            if (handle != null && handle > 0) return handle
        }

        // Method 3: XBaseWindow.window field (long) is the X11 window handle
        var cls: Class<*>? = peer.javaClass
        while (cls != null && cls != Any::class.java) {
            runCatching {
                val windowField = cls.getDeclaredField("window")
                windowField.isAccessible = true
                if (windowField.type == Long::class.javaPrimitiveType) {
                    val handle = windowField.getLong(peer)
                    if (handle > 0) return handle
                } else {
                    val x11Window = windowField.get(peer)
                    if (x11Window != null) {
                        val handleField = x11Window.javaClass.getDeclaredField("handle")
                        handleField.isAccessible = true
                        val handle = handleField.getLong(x11Window)
                        if (handle > 0) return handle
                        val h2 = invokeLongOrNull(x11Window, "getHandle")
                        if (h2 != null && h2 > 0) return h2
                    }
                }
            }
            cls = cls.superclass
        }

        // Method 4: xdotool — find windows for this PID and match component bounds
        val xdotoolHandle = resolveViaXdotool(component)
        if (xdotoolHandle != null) return xdotoolHandle

        error("Linux X11 native window handle could not be resolved. Ensure X11/XWayland is running.")
    }

    private fun resolveViaXdotool(component: Component): Long? {
        return runCatching {
            val pid = ProcessHandle.current().pid()
            val proc = ProcessBuilder("xdotool", "search", "--pid", pid.toString())
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (output.isEmpty()) {
                System.err.println("[X11Resolver] xdotool: no windows found for pid=$pid")
                return@runCatching null
            }
            val windowIds = output.lines().mapNotNull { it.trim().toLongOrNull() }
            System.err.println("[X11Resolver] xdotool found ${windowIds.size} windows: $windowIds")

            for (wid in windowIds) {
                val geoProc = ProcessBuilder("xdotool", "getwindowgeometry", "--shell", wid.toString())
                    .redirectErrorStream(true)
                    .start()
                val geoOutput = geoProc.inputStream.bufferedReader().readText().trim()
                geoProc.waitFor()
                System.err.println("[X11Resolver] window $wid geometry: $geoOutput")
            }

            if (windowIds.size == 1) {
                System.err.println("[X11Resolver] single window, using ${windowIds[0]}")
                return@runCatching windowIds[0]
            }

            null
        }.onFailure { System.err.println("[X11Resolver] xdotool failed: ${it.message}") }.getOrNull()
    }

    private fun findMethod(type: Class<*>, name: String): Method? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredMethod(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }

    private fun invokeObjectOrNull(target: Any, methodName: String): Any? =
        findMethod(target.javaClass, methodName)?.invoke(target)

    private fun invokeLongOrNull(target: Any, methodName: String): Long? =
        try {
            val method = findMethod(target.javaClass, methodName) ?: return null
            (method.invoke(target) as? Number)?.toLong()
        } catch (_: Exception) {
            null
        }
}
