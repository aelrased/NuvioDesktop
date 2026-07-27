package com.nuvio.app.features.player.desktop

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.Cursor
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.swing.Timer

internal class WaylandPlayerHost : PlayerHost {
    @Volatile
    override var nativeHandle: Long = 0L

    override var onMouseClick: (() -> Unit)? = null
    override var onDoubleClick: (() -> Unit)? = null
    override var onCursorActivity: (() -> Unit)? = null

    private var lastWidth = 0
    private var lastHeight = 0

    /* Double-buffer: DirectByteBuffer for zero-copy JNI */
    private var bufA: ByteBuffer? = null
    private var bufB: ByteBuffer? = null
    private var useBufA = true

    var latestImage: Image? = null
        private set

    private var latestImageData: Data? = null
    private var reusedBytes: ByteArray? = null

    private var controlsVisible = true
    private var cursorVisible = true
    private var cursorHideTimer: Timer? = null

    private fun ensureDirectBuffer(existing: ByteBuffer?, capacity: Int): ByteBuffer {
        if (existing != null && existing.capacity() >= capacity) return existing
        return ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())
    }

    fun renderFrame(width: Int, height: Int): Boolean {
        val handle = nativeHandle
        if (handle == 0L || width <= 0 || height <= 0) return false

        /* DirectBuffer SW path */
        val byteCountLong = width.toLong() * height.toLong() * BytesPerPixel
        if (byteCountLong <= 0L || byteCountLong > Int.MAX_VALUE) return false
        val byteCount = byteCountLong.toInt()
        val buf: ByteBuffer
        if (useBufA) {
            bufA = ensureDirectBuffer(bufA, byteCount)
            buf = bufA!!
        } else {
            bufB = ensureDirectBuffer(bufB, byteCount)
            buf = bufB!!
        }
        useBufA = !useBufA

        if (!NativePlayerBridge.renderFrameDirect(handle, buf, width, height)) return false
        if (nativeHandle == 0L) return false

        /* Create Skia Image from DirectByteBuffer (reuse byte array to avoid alloc) */
        val imageInfo = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
        val prevImage = latestImage
        val prevData = latestImageData
        if (reusedBytes == null || reusedBytes!!.size < byteCount) reusedBytes = ByteArray(byteCount)
        buf.position(0)
        buf.get(reusedBytes!!)
        buf.position(0)
        val data = Data.makeFromBytes(reusedBytes!!)
        val image = Image.makeRaster(imageInfo, data, width * 4)
        if (image.isClosed) {
            image.close()
            data.close()
            latestImage = prevImage
            latestImageData = prevData
            return false
        }

        latestImage = image
        latestImageData = data
        prevImage?.close()
        prevData?.close()
        lastWidth = width
        lastHeight = height
        return true
    }

    override fun setControlsVisible(visible: Boolean) {
        if (controlsVisible == visible) return
        controlsVisible = visible
        cancelCursorHideTimer()
        setCursorVisible(visible)
    }

    override fun noteCursorActivity() {
        onCursorActivity?.invoke()
        if (controlsVisible) {
            cancelCursorHideTimer()
            setCursorVisible(true)
            return
        }
        setCursorVisible(true)
        restartCursorHideTimer()
    }

    override fun resetCursorVisibility() {
        controlsVisible = true
        cancelCursorHideTimer()
        setCursorVisible(true)
    }

    override fun dispose() {
        resetCursorVisibility()
        latestImage?.close()
        latestImage = null
        latestImageData?.close()
        latestImageData = null
    }

    private fun setCursorVisible(visible: Boolean) {
        if (cursorVisible == visible) return
        cursorVisible = visible
        val window = activeWindow ?: return
        window.cursor = if (visible) Cursor.getDefaultCursor() else hiddenCursor
    }

    private fun restartCursorHideTimer() {
        cancelCursorHideTimer()
        cursorHideTimer = Timer(CursorIdleHideDelayMs) {
            if (!controlsVisible) {
                setCursorVisible(false)
            }
            cancelCursorHideTimer()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun cancelCursorHideTimer() {
        cursorHideTimer?.stop()
        cursorHideTimer = null
    }

    private val activeWindow: Window?
        get() = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            ?: Window.getWindows().firstOrNull { it.isVisible && it.isActive }

    private companion object {
        const val BytesPerPixel = 4L
        const val CursorIdleHideDelayMs = 3_000
        val hiddenCursor: Cursor by lazy {
            val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "nuvio-hidden-cursor")
        }
    }
}
