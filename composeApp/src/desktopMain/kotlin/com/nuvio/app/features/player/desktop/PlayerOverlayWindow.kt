package com.nuvio.app.features.player.desktop

import java.awt.*
import java.awt.event.*
import java.awt.geom.*
import javax.swing.*
import kotlin.math.max
import kotlin.math.min

internal class PlayerOverlayWindow(
    private val eventSink: NativePlayerEventSink,
) {
    private var window: JWindow? = null
    private var panel: OverlayPanel? = null
    private var host: NativePlayerHost? = null
    private var positionTimer: Timer? = null
    private var autoHideTimer: Timer? = null
    private var disposed = false

    fun attachTo(host: NativePlayerHost) {
        this.host = host
        if (disposed) return

        val w = JWindow().apply {
            isAlwaysOnTop = true
            isFocusable = false
            background = Color(0, 0, 0, 0)
        }

        forceTransparent(w)

        val p = OverlayPanel(eventSink, this)
        p.background = Color(0, 0, 0, 0)
        p.isOpaque = false
        w.contentPane = p
        w.setSize(host.width, host.height)

        val loc = host.locationOnScreen
        w.setLocation(loc.x, loc.y)
        w.isVisible = true

        window = w
        panel = p

        startPositionTracking()
        showControls(3000)
    }

    private fun forceTransparent(w: JWindow) {
        runCatching {
            val clazz = Class.forName("com.sun.awt.AWTUtilities")
            val method = clazz.getMethod(
                "setWindowOpaque",
                java.awt.Window::class.java,
                java.lang.Boolean::class.javaPrimitiveType,
            )
            method.invoke(null, w, false)
        }.onFailure {
            runCatching {
                w.opacity = 0.99f
            }
        }
    }

    fun updateState(
        positionMs: Long,
        durationMs: Long,
        paused: Boolean,
        isLoading: Boolean,
        title: String,
    ) {
        panel?.apply {
            this.positionMs = positionMs
            this.durationMs = durationMs
            this.isPaused = paused
            this.isLoading = isLoading
            this.title = title
            repaint()
        }
    }

    fun showControls(durationMs: Int = 3000) {
        panel?.controlsVisible = true
        panel?.repaint()
        autoHideTimer?.stop()
        autoHideTimer = Timer(durationMs) {
            panel?.controlsVisible = false
            panel?.repaint()
        }.apply {
            isRepeats = false
            start()
        }
    }

    fun detach() {
        host = null
        positionTimer?.stop()
        positionTimer = null
        autoHideTimer?.stop()
        autoHideTimer = null
        window?.isVisible = false
        window?.dispose()
        window = null
        panel = null
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        detach()
    }

    private fun startPositionTracking() {
        positionTimer?.stop()
        positionTimer = Timer(50) {
            val h = host ?: return@Timer
            val w = window ?: return@Timer
            if (!h.isDisplayable || !h.isShowing) return@Timer
            try {
                val loc = h.locationOnScreen
                if (w.location.x != loc.x || w.location.y != loc.y) {
                    w.setLocation(loc.x, loc.y)
                }
                if (w.width != h.width || w.height != h.height) {
                    w.setSize(h.width, h.height)
                    panel?.repaint()
                }
            } catch (_: Exception) {}
        }.apply {
            isRepeats = true
            start()
        }
    }

    internal class OverlayPanel(
        private val eventSink: NativePlayerEventSink,
        private val overlay: PlayerOverlayWindow,
    ) : JPanel() {
        var positionMs: Long = 0L
        var durationMs: Long = 0L
        var isPaused: Boolean = true
        var isLoading: Boolean = false
        var title: String = ""
        var controlsVisible: Boolean = true

        private var isDraggingSeekbar = false

        init {
            isOpaque = false
            preferredSize = Dimension(0, 0)
            layout = null

            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    overlay.showControls(3000)
                    val h = height
                    val barY = h - 80
                    if (e.y in (barY - 20)..(barY + 30) && durationMs > 0) {
                        isDraggingSeekbar = true
                        scrubTo(e.x)
                    } else {
                        eventSink.onPlayerEvent("togglePlayback", 0.0)
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (isDraggingSeekbar) {
                        isDraggingSeekbar = false
                        scrubTo(e.x)
                        eventSink.onPlayerEvent("scrubFinish", 0.0)
                    }
                }

                override fun mouseEntered(e: MouseEvent) {
                    overlay.showControls(3000)
                }

                override fun mouseExited(e: MouseEvent) {
                    overlay.showControls(1000)
                }
            })

            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    overlay.showControls(3000)
                }

                override fun mouseDragged(e: MouseEvent) {
                    if (isDraggingSeekbar) {
                        scrubTo(e.x)
                    }
                }
            })
        }

        private fun scrubTo(mouseX: Int) {
            if (durationMs <= 0) return
            val W = width
            val barMargin = 20
            val barWidth = W - 2 * barMargin
            if (barWidth <= 0) return
            val fraction = max(0.0, min(1.0, (mouseX - barMargin).toDouble() / barWidth))
            val seekMs = (fraction * durationMs).toLong()
            eventSink.onPlayerEvent("scrubChange", seekMs.toDouble())
        }

        override fun paintComponent(g: Graphics) {
            if (!controlsVisible) return
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val W = width
            val H = height
            if (W <= 0 || H <= 0) return

            drawScrim(g2, W, H)
            drawTitle(g2, W)
            drawSeekbar(g2, W, H)
            drawTimeLabels(g2, W, H)
            drawPlayPause(g2, W, H)
            if (isLoading) drawSpinner(g2, W, H)
        }

        private fun drawScrim(g2: Graphics2D, w: Int, h: Int) {
            val topGradient = GradientPaint(
                0f, 0f, Color(0, 0, 0, 200),
                0f, 150f, Color(0, 0, 0, 0),
            )
            g2.paint = topGradient
            g2.fillRect(0, 0, w, 150)

            val bottomGradient = GradientPaint(
                0f, (h - 250).toFloat(), Color(0, 0, 0, 0),
                0f, h.toFloat(), Color(0, 0, 0, 220),
            )
            g2.paint = bottomGradient
            g2.fillRect(0, h - 250, w, 250)
        }

        private fun drawTitle(g2: Graphics2D, w: Int) {
            if (title.isEmpty()) return
            g2.font = Font("SansSerif", Font.BOLD, 18)
            g2.color = Color(221, 255, 255, 255)
            val fm = g2.fontMetrics
            val maxW = w - 40
            val text = if (fm.stringWidth(title) > maxW) {
                val chars = title.length * maxW / fm.stringWidth(title)
                title.substring(0, chars.coerceAtMost(title.length)) + "..."
            } else title
            g2.drawString(text, 20, 45)
        }

        private fun drawSeekbar(g2: Graphics2D, w: Int, h: Int) {
            val barMargin = 20
            val barWidth = w - 2 * barMargin
            val barY = h - 60
            val barH = 6
            if (barWidth <= 0 || durationMs <= 0) return

            g2.color = Color(255, 255, 255, 70)
            g2.fill(RoundRectangle2D.Float(barMargin.toFloat(), barY.toFloat(), barWidth.toFloat(), barH.toFloat(), barH.toFloat(), barH.toFloat()))

            val progress = max(0.0, min(1.0, positionMs.toDouble() / durationMs))
            val progW = (barWidth * progress).toInt()
            if (progW > 0) {
                g2.color = Color.WHITE
                g2.fill(RoundRectangle2D.Float(barMargin.toFloat(), barY.toFloat(), progW.toFloat(), barH.toFloat(), barH.toFloat(), barH.toFloat()))
            }

            val dotR = 10
            g2.color = Color.WHITE
            g2.fillOval(barMargin + progW - dotR, barY - dotR + barH / 2, dotR * 2, dotR * 2)
        }

        private fun drawTimeLabels(g2: Graphics2D, w: Int, h: Int) {
            val barMargin = 20
            val barY = h - 60
            val barH = 6
            val posSec = (positionMs / 1000).toInt()
            val durSec = (durationMs / 1000).toInt()
            val timeText = String.format("%d:%02d / %d:%02d", posSec / 60, posSec % 60, durSec / 60, durSec % 60)
            g2.font = Font("SansSerif", Font.PLAIN, 14)
            g2.color = Color(204, 255, 255, 220)
            g2.drawString(timeText, barMargin, barY + barH + 24)
        }

        private fun drawPlayPause(g2: Graphics2D, w: Int, h: Int) {
            val cx = w / 2
            val cy = h / 2 - 20
            val size = 40
            g2.color = Color(221, 255, 255, 200)
            if (isPaused) {
                val triangle = intArrayOf(
                    cx - size / 3, cy - size / 2,
                    cx + size / 2, cy,
                    cx - size / 3, cy + size / 2,
                )
                g2.fillPolygon(triangle, intArrayOf(triangle[0], triangle[2], triangle[4]), 3)
            } else {
                val barW = size / 5
                val gap = size / 4
                g2.fillRect(cx - gap - barW, cy - size / 2, barW, size)
                g2.fillRect(cx + gap, cy - size / 2, barW, size)
            }
        }

        private fun drawSpinner(g2: Graphics2D, w: Int, h: Int) {
            val cx = w / 2
            val cy = h / 2 + 40
            val r = 20
            g2.color = Color(170, 255, 255, 180)
            g2.stroke = BasicStroke(3f)
            val angle = ((System.currentTimeMillis() / 10) % 360).toInt()
            g2.drawArc(cx - r, cy - r, r * 2, r * 2, angle, 120)
        }
    }
}
