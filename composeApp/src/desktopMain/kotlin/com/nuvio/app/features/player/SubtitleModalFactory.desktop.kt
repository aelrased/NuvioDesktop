package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.JavaFXPlayerOverlay
import com.nuvio.app.features.player.desktop.NativePlayerBridge

fun installLinuxSubtitleModalFactory() {
    if (DesktopHostOs.current != DesktopHostOs.LINUX_X11) return
    println("[SubtitleModalFactory] Installing Linux subtitle modal factory (JavaFX WebView)")

    JavaFXPlayerOverlay.init()

    subtitleModalFactory = @Composable { visible, onDismiss, _ ->
        if (visible) {
            println("[SubtitleModalFactory] Showing JavaFX subtitle overlay")

            JavaFXPlayerOverlay.show(
                controlsUrl = NativePlayerBridge.controlsPageUrl,
                onEvent = { type, value ->
                    println("[SubtitleModalFactory] Event from JS: $type $value")
                    if (type == "close") {
                        onDismiss()
                    }
                },
            )
        } else {
            println("[SubtitleModalFactory] Hiding JavaFX subtitle overlay")
            JavaFXPlayerOverlay.hide()
        }
    }
}
