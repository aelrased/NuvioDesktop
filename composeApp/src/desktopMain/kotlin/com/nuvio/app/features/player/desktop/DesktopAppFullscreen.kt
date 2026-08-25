package com.nuvio.app.features.player.desktop

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Frame
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.nuvio.app.core.ui.DesktopBackHandlers
import com.nuvio.app.features.player.desktop.DesktopHostOs

internal object DesktopAppNavigation

private object DesktopAppFullscreen {
    private var toggleHandler: ((Window?) -> Unit)? = null
    private var fullscreenStateProvider: ((Window?) -> Boolean)? = null
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun setToggleHandler(
        handler: ((Window?) -> Unit)?,
        isFullscreen: (Window?) -> Boolean,
    ): () -> Unit {
        toggleHandler = handler
        fullscreenStateProvider = isFullscreen
        notifyChanged()
        return {
            if (toggleHandler === handler) {
                toggleHandler = null
                fullscreenStateProvider = null
                notifyChanged()
            }
        }
    }

    fun toggle(window: Window? = null) {
        val handler = toggleHandler ?: return
        if (SwingUtilities.isEventDispatchThread()) {
            handler(window)
            notifyChanged()
        } else {
            SwingUtilities.invokeLater {
                handler(window)
                notifyChanged()
            }
        }
    }

    fun isFullscreen(window: Window? = null): Boolean =
        fullscreenStateProvider?.invoke(window) == true

    private fun notifyChanged() {
        _changes.value += 1
    }
}

internal fun registerDesktopAppFullscreenToggle(
    handler: (Window?) -> Unit,
    isFullscreen: (Window?) -> Boolean,
): () -> Unit =
    DesktopAppFullscreen.setToggleHandler(handler, isFullscreen)

internal fun toggleDesktopAppFullscreen(window: Window? = null) {
    DesktopAppFullscreen.toggle(window)
}

internal fun isDesktopAppFullscreen(window: Window? = null): Boolean =
    DesktopAppFullscreen.isFullscreen(window)

internal val desktopFullscreenChanges: StateFlow<Int>
    get() = DesktopAppFullscreen.changes

internal class DesktopAppFullscreenController {
    private var restoreWindowPlacement = WindowPlacement.Floating
    private var windowsRestoreState: WindowsRestoreState? = null

    fun toggle(window: Window, windowState: WindowState) {
        if (DesktopHostOs.current == DesktopHostOs.WINDOWS) {
            toggleWindowsFullscreen(window)
        } else {
            toggleComposeFullscreen(window, windowState)
            if (DesktopHostOs.current == DesktopHostOs.LINUX) {
                enforceLinuxFullscreen(window, windowState)
            }
        }
    }

    /**
     * Compose applies [WindowState.placement] through updaters memoized on the
     * last value it applied, with a write-back listener that re-reads window
     * state on AWT window events. Some window managers (mutter) emit extra
     * state events that convince Compose the window is already windowed while
     * the X11 window still carries _NET_WM_STATE_FULLSCREEN — the exit write is
     * then skipped and the window stays fullscreen. Verify the AWT device state
     * against the intended placement and correct it; a no-op when Compose
     * applied the change itself.
     */
    private fun enforceLinuxFullscreen(window: Window, windowState: WindowState) {
        fun enforce(stage: String) {
            val device = window.graphicsConfiguration?.device ?: return
            val wantFullscreen = windowState.placement == WindowPlacement.Fullscreen
            val awtFullscreen = device.fullScreenWindow === window
            if (wantFullscreen == awtFullscreen) return
            device.fullScreenWindow = if (wantFullscreen) window else null
        }
        SwingUtilities.invokeLater { enforce("immediate") }
        Timer(250) { enforce("delayed") }.apply { isRepeats = false }.start()
    }

    fun dispose(window: Window) {
        exitWindowsFullscreen(window)
    }

    /**
     * Applies a fullscreen state restored from a previous session, before the
     * window has been interacted with. Only acts when [fullscreen] is true;
     * windowed is already the default state for a freshly created window.
     */
    fun applyRestoredFullscreenState(window: Window, windowState: WindowState, fullscreen: Boolean) {
        if (!fullscreen) return
        if (DesktopHostOs.current == DesktopHostOs.WINDOWS) {
            enterWindowsFullscreen(window)
        } else {
            restoreWindowPlacement = windowState.placement
                .takeUnless { it == WindowPlacement.Fullscreen }
                ?: WindowPlacement.Floating
            windowState.placement = WindowPlacement.Fullscreen
        }
    }

    fun isFullscreen(window: Window, windowState: WindowState): Boolean =
        if (DesktopHostOs.current == DesktopHostOs.WINDOWS) {
            windowsRestoreState?.let { true } == true
        } else {
            windowState.placement == WindowPlacement.Fullscreen
        }

    private fun toggleComposeFullscreen(window: Window, windowState: WindowState) {
        if (isFullscreen(window, windowState)) {
            if (DesktopHostOs.current == DesktopHostOs.MACOS) {
                applyMacosComposeFullscreenExit(
                    restorePlacement = restoreWindowPlacement,
                    requestNativeFullscreenExit = { requestNativeComposeFullscreenExit(window) },
                    clearComposeFullscreen = {
                        (window as? ComposeWindow)?.placement = WindowPlacement.Floating
                    },
                    setStatePlacement = { placement ->
                        windowState.placement = placement
                    },
                )
            } else {
                windowState.placement = restoreWindowPlacement
            }
        } else {
            restoreWindowPlacement = windowState.placement
                .takeUnless { it == WindowPlacement.Fullscreen }
                ?: WindowPlacement.Floating
            windowState.placement = WindowPlacement.Fullscreen
        }
    }

    private fun requestNativeComposeFullscreenExit(window: Window): Boolean {
        if (DesktopHostOs.current != DesktopHostOs.MACOS) return false
        return runCatching {
            NativePlayerBridge.setMacosWindowFullscreen(
                windowViewPtr = AwtNativeViewResolver.resolveNativeViewPointer(window),
                fullscreen = false,
            )
        }.isSuccess
    }

    private fun toggleWindowsFullscreen(window: Window) {
        if (activeFullscreenDevice(window) != null) {
            exitWindowsFullscreen(window)
        } else {
            enterWindowsFullscreen(window)
        }
    }

    private fun enterWindowsFullscreen(window: Window) {
        val device = window.graphicsConfiguration?.device
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        windowsRestoreState = WindowsRestoreState(
            device = device,
            bounds = Rectangle(window.bounds),
            frameState = (window as? Frame)?.extendedState,
        )
        device.fullScreenWindow = window
        window.toFront()
        window.requestFocus()
    }

    private fun exitWindowsFullscreen(window: Window) {
        val restoreState = windowsRestoreState
        val device = activeFullscreenDevice(window) ?: restoreState?.device ?: return
        if (device.fullScreenWindow === window) {
            device.fullScreenWindow = null
        }
        val frame = window as? Frame
        if (frame != null && restoreState?.frameState != null) {
            frame.extendedState = restoreState.frameState
        }
        if (restoreState != null && frame?.extendedState != Frame.MAXIMIZED_BOTH) {
            window.bounds = restoreState.bounds
        }
        if (windowsRestoreState?.device === restoreState?.device) {
            windowsRestoreState = null
        }
    }

    private fun activeFullscreenDevice(window: Window): GraphicsDevice? {
        windowsRestoreState?.device
            ?.takeIf { it.fullScreenWindow === window }
            ?.let { return it }
        return GraphicsEnvironment.getLocalGraphicsEnvironment()
            .screenDevices
            .firstOrNull { it.fullScreenWindow === window }
    }

    private data class WindowsRestoreState(
        val device: GraphicsDevice,
        val bounds: Rectangle,
        val frameState: Int?,
    )
}

/**
 * ComposeWindow does not clear its fullscreen flag when placement is changed directly from
 * Fullscreen to Maximized on macOS. Let AppKit complete its asynchronous fullscreen exit and let
 * Compose's native window listener restore WindowState; writing Maximized during that transition
 * can alter the frame AppKit is restoring. The Compose fallback is only used if the native macOS
 * request cannot be made.
 */
internal fun applyMacosComposeFullscreenExit(
    restorePlacement: WindowPlacement,
    requestNativeFullscreenExit: () -> Boolean,
    clearComposeFullscreen: () -> Unit,
    setStatePlacement: (WindowPlacement) -> Unit,
) {
    if (requestNativeFullscreenExit()) return

    val targetPlacement = restorePlacement
        .takeUnless { it == WindowPlacement.Fullscreen }
        ?: WindowPlacement.Floating
    clearComposeFullscreen()
    setStatePlacement(targetPlacement)
}

internal fun installDesktopAppFullscreenShortcuts(window: Window): () -> Unit {
    val dispatcher = KeyEventDispatcher { event ->
        if (event.id == KeyEvent.KEY_PRESSED && event.keyCode == KeyEvent.VK_ESCAPE) {
            if (isDesktopAppFullscreen(window)) {
                toggleDesktopAppFullscreen(window)
            } else {
                DesktopBackHandlers.handleBack()
            }
            return@KeyEventDispatcher true
        }
        if (!event.isDesktopAppFullscreenShortcut()) return@KeyEventDispatcher false
        toggleDesktopAppFullscreen(window)
        true
    }
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
    return {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
    }
}

private fun KeyEvent.isDesktopAppFullscreenShortcut(): Boolean {
    if (id != KeyEvent.KEY_PRESSED) return false
    if (keyCode == KeyEvent.VK_F11) return true
    if (keyCode != KeyEvent.VK_F) return false
    val modifiers = modifiersEx
    val hasMacFullscreenModifiers =
        modifiers and KeyEvent.META_DOWN_MASK != 0 &&
            modifiers and KeyEvent.CTRL_DOWN_MASK != 0 &&
            modifiers and KeyEvent.ALT_DOWN_MASK == 0
    return hasMacFullscreenModifiers
}

internal fun installDesktopMouseButtonShortcuts(window: Window): () -> Unit {
    val listener = AWTEventListener { awtEvent ->
        if (awtEvent.id != MouseEvent.MOUSE_PRESSED) return@AWTEventListener
        val mouseEvent = awtEvent as? MouseEvent ?: return@AWTEventListener
        val isSideButton = if (DesktopHostOs.current == DesktopHostOs.LINUX) {
            mouseEvent.button in 6..9
        } else {
            mouseEvent.button in 4..5
        }
        if (!isSideButton) return@AWTEventListener
        if (!window.isFocused) return@AWTEventListener
        DesktopBackHandlers.handleBack()
    }
    Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
    return {
        Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
    }
}
