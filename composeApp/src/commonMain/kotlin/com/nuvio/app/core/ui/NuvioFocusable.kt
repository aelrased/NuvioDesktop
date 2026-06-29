package com.nuvio.app.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

fun Modifier.nuvioFocusBorder(
    shape: Shape,
    focusColor: Color = Color.Unspecified,
): Modifier = composed {
    val color = if (focusColor == Color.Unspecified) {
        MaterialTheme.nuvio.colors.focusRing
    } else {
        focusColor
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    this
        .focusable(interactionSource = interactionSource)
        .then(
            if (isFocused) {
                Modifier.border(BorderStroke(3.dp, color), shape)
            } else {
                Modifier
            }
        )
}
