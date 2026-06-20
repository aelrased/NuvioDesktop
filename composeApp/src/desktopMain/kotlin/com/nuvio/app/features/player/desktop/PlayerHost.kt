package com.nuvio.app.features.player.desktop

internal interface PlayerHost {
    var nativeHandle: Long

    var onMouseClick: (() -> Unit)?

    fun setControlsVisible(visible: Boolean)
    fun noteCursorActivity()
    fun resetCursorVisibility()
}
