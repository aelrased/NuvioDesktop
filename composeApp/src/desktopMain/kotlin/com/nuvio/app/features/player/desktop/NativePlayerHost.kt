package com.nuvio.app.features.player.desktop

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class NativePlayerHost : PlayerHost {
    @Volatile
    override var nativeHandle: Long = 0L

    override var onMouseClick: (() -> Unit)? = null

    private var pixelBuffer: IntArray? = null
    private var pixelBytes: ByteArray? = null

    var latestImage: Image? = null
        private set

    fun renderFrame(width: Int, height: Int): Boolean {
        val handle = nativeHandle
        if (handle == 0L || width <= 0 || height <= 0) return false

        val count = width * height
        val pix = pixelBuffer?.takeIf { it.size >= count }
            ?: IntArray(count).also { pixelBuffer = it }

        if (!NativePlayerBridge.renderFrame(handle, pix, width, height)) return false

        val byteCount = count * 4
        val bytes = pixelBytes?.takeIf { it.size >= byteCount }
            ?: ByteArray(byteCount).also { pixelBytes = it }

        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pix, 0, count)

        val imageInfo = ImageInfo.makeS32(width, height, ColorAlphaType.UNPREMUL)
        latestImage = Image.makeRaster(imageInfo, bytes, width * 4)
        return true
    }

    override fun setControlsVisible(visible: Boolean) {}
    override fun noteCursorActivity() {}
    override fun resetCursorVisibility() {}
}
