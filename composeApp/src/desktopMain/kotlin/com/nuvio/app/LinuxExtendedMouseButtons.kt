package com.nuvio.app

import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.MouseEvent

// X11 numbers the mouse thumb buttons 8 and 9. Java's XToolkit reports the
// vertical wheel as MouseWheelEvent and keeps the horizontal wheel as buttons 4
// and 5, so the thumb buttons arrive as AWT buttons 6 and 7. Compose derives
// PointerButton.Back from the 4th button, so the back navigation in
// MainAppContent never matched and the button did nothing on Linux.
//
// Re-post the press and release under the button number Compose expects. The
// shared code stays untouched and keeps working unchanged on Windows and macOS,
// where the toolkit already numbers these buttons the way Compose reads them.
//
// Only Back is mapped. Nothing acts on PointerButton.Forward: there is no
// forward navigation anywhere in the app, and the player's own seek arrives
// through the native bridge rather than through AWT, so mapping the second
// thumb button would post events that no handler consumes.
//
// Install this only once the window peer exists. Reaching for the AWT toolkit
// during main() forces it up before GTK has finished initializing, which is the
// ordering main() opens by warning about, and it leaves the window unable to
// close.
private const val X11_THUMB_BACK = 6
private const val COMPOSE_BACK = 4

@Volatile
private var extendedMouseButtonsInstalled = false

fun installLinuxExtendedMouseButtons() {
    if (!System.getProperty("os.name", "").lowercase().contains("linux")) return
    synchronized(LinuxExtendedMouseButtonsLock) {
        if (extendedMouseButtonsInstalled) return
        extendedMouseButtonsInstalled = true
    }
    // Never let an input convenience take the app down with it.
    runCatching {
        val toolkit = Toolkit.getDefaultToolkit()
        toolkit.addAWTEventListener({ event ->
            val source = event as? MouseEvent ?: return@addAWTEventListener
            if (source.id != MouseEvent.MOUSE_PRESSED && source.id != MouseEvent.MOUSE_RELEASED) {
                return@addAWTEventListener
            }
            if (source.button != X11_THUMB_BACK) return@addAWTEventListener
            val component = source.component ?: return@addAWTEventListener
            // The replacement carries the mapped button, so this listener sees
            // it, fails the check above, and returns -- it cannot feed itself.
            runCatching {
                toolkit.systemEventQueue.postEvent(
                    MouseEvent(
                        component,
                        source.id,
                        source.getWhen(),
                        source.modifiersEx,
                        source.x,
                        source.y,
                        source.clickCount,
                        source.isPopupTrigger,
                        COMPOSE_BACK,
                    ),
                )
            }
        }, AWTEvent.MOUSE_EVENT_MASK)
    }
}

private object LinuxExtendedMouseButtonsLock
