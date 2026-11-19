package com.quirogaj.vistio.view.tv

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
internal fun Modifier.tvPointerActivation(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    if (!enabled) return this
    val click by rememberUpdatedState(onClick)
    // TV Material owns D-pad and accessibility activation. Add pointer input for taps and mouse clicks.
    return pointerHoverIcon(PointerIcon.Hand).pointerInput(Unit) {
        detectTapGestures { click() }
    }
}

@Composable
internal fun Modifier.tvFocusOutline(enabled: Boolean): Modifier {
    if (!enabled) return this
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.hasFocus }
        .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
}

@Composable
internal fun CameraAction(
    isTv: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (isTv) {
        androidx.tv.material3.OutlinedButton(
            onClick = onClick,
            modifier = modifier.tvPointerActivation(enabled, onClick),
            enabled = enabled,
        ) {
            androidx.tv.material3.Text(label)
        }
    } else {
        androidx.compose.material3.TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            androidx.compose.material3.Text(label)
        }
    }
}
