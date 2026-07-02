package com.nuvio.app.features.player.desktop

import javafx.application.Platform
import javafx.event.EventHandler
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.web.WebView
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.stage.WindowEvent
import netscape.javascript.JSObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object JavaFXPlayerOverlay {
    private var stage: Stage? = null
    private var webView: WebView? = null
    private var initialized = false
    private var onEventCallback: ((String, Double) -> Unit)? = null
    private var modalOpen = false
    private var pageLoaded = false

    fun init() {
        if (initialized) return
        initialized = true
        println("[JavaFXOverlay] Initializing JavaFX Platform")
        CompletableFuture.runAsync {
            Platform.startup { }
        }.get(10, TimeUnit.SECONDS)
        println("[JavaFXOverlay] JavaFX Platform initialized")
    }

    fun show(
        controlsUrl: String,
        onEvent: (String, Double) -> Unit,
        onReady: (() -> Unit)? = null,
    ) {
        onEventCallback = onEvent

        Platform.runLater {
            val stg = stage
            val wv = webView
            if (stg != null && wv != null && stg.isShowing) {
                stg.toFront()
                if (!modalOpen && pageLoaded) {
                    openModalAndHideChrome(wv)
                    modalOpen = true
                }
                onReady?.invoke()
                return@runLater
            }

            try {
                val web = WebView()
                web.engine.setJavaScriptEnabled(true)
                web.setPrefSize(480.0, 580.0)

                val bridge = JFXBridge { type, value ->
                    println("[JavaFXOverlay] Event from JS: $type $value")
                    NativePlayerController.eventForwarder?.invoke(type, value)
                    onEventCallback?.invoke(type, value)
                }

                web.engine.loadWorker.stateProperty().addListener { _ ->
                    if (web.engine.loadWorker.state == javafx.concurrent.Worker.State.SUCCEEDED) {
                        println("[JavaFXOverlay] Page loaded, setting JS bridge")
                        try {
                            val window = web.engine.executeScript("window") as JSObject
                            window.setMember("javaFxBridge", bridge)
                            pageLoaded = true
                            openModalAndHideChrome(web)
                            modalOpen = true
                            onReady?.invoke()
                        } catch (e: Exception) {
                            println("[JavaFXOverlay] Bridge error: ${e.message}")
                        }
                    }
                }

                web.engine.load(controlsUrl)

                val st = Stage()
                st.initStyle(StageStyle.TRANSPARENT)
                st.isAlwaysOnTop = true
                val scene = Scene(web)
                scene.fill = Color.TRANSPARENT
                st.scene = scene
                st.setOnCloseRequest(EventHandler { _: WindowEvent ->
                    modalOpen = false
                    pageLoaded = false
                    stage = null
                    webView = null
                    NativePlayerController.eventForwarder?.invoke("close", 0.0)
                    onEventCallback?.invoke("close", 0.0)
                })
                st.setOnHiding(EventHandler { _: WindowEvent ->
                    modalOpen = false
                })

                webView = web
                stage = st
                st.show()
                st.toFront()
                println("[JavaFXOverlay] Stage created and shown")
            } catch (e: Exception) {
                println("[JavaFXOverlay] Error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun openModalAndHideChrome(wv: WebView) {
        try {
            wv.engine.executeScript(
                "openPlayerModal('subtitles'); " +
                "var c=document.getElementById('chrome');if(c)c.style.display='none';" +
                "var s=document.getElementById('seekBarContainer');if(s)s.style.display='none';" +
                "var b=document.getElementById('controlsBottom');if(b)b.style.display='none';"
            )
        } catch (e: Exception) {
            println("[JavaFXOverlay] openModal error: ${e.message}")
        }
    }

    fun executeScript(script: String) {
        Platform.runLater {
            try {
                webView?.engine?.executeScript(script)
            } catch (_: Exception) { }
        }
    }

    fun updateControls(json: String) {
        Platform.runLater {
            try {
                webView?.engine?.executeScript("window.playerControls($json)")
            } catch (_: Exception) { }
        }
    }

    fun hide() {
        Platform.runLater {
            stage?.hide()
            modalOpen = false
        }
    }

    fun dispose() {
        Platform.runLater {
            stage?.close()
            stage = null
            webView = null
            modalOpen = false
            pageLoaded = false
        }
    }
}

class JFXBridge(private val onEvent: (String, Double) -> Unit) {
    fun onPlayerEvent(type: String, value: Double) {
        println("[JFXBridge] onPlayerEvent: $type $value")
        onEvent(type, value)
    }

    fun controlsReady() {
        println("[JFXBridge] controlsReady")
    }
}
