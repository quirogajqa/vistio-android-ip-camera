package com.quirogaj.vistio.view.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme as TvMaterialTheme

internal val TvHorizontalMargin = 48.dp
internal val TvVerticalMargin = 27.dp
internal val TvControlGap = 16.dp
internal val TvFocusColor = Color(0xFF7CF4DD)
internal val TvFocusTextColor = Color(0xFF102823)

internal fun Modifier.tvButtonSize(): Modifier = defaultMinSize(minWidth = 88.dp, minHeight = 48.dp)

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
        .then(if (focused) Modifier.border(3.dp, TvFocusColor, RoundedCornerShape(10.dp)) else Modifier)
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
        TvActionButton(label, onClick, modifier, enabled)
    } else {
        androidx.compose.material3.TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            androidx.compose.material3.Text(label)
        }
    }
}

@Composable
internal fun TvActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val colors = TvMaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.tvPointerActivation(enabled, onClick).tvButtonSize()
            .semantics { role = Role.Button },
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Color(0xFF3C2E5E) else Color.Transparent,
            contentColor = if (primary) colors.primary else colors.onSurface,
            focusedContainerColor = TvFocusColor,
            focusedContentColor = TvFocusTextColor,
            disabledContainerColor = Color.Transparent,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (primary) colors.primary.copy(alpha = 0.65f) else colors.border), shape = shape),
            focusedBorder = Border(BorderStroke(3.dp, TvFocusColor), inset = 3.dp, shape = shape),
            disabledBorder = Border(BorderStroke(1.dp, colors.border.copy(alpha = 0.35f)), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.025f),
    ) {
        Text(
            text = label,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 20.dp, vertical = 10.dp),
            textAlign = TextAlign.Center,
            style = TvMaterialTheme.typography.labelLarge.copy(
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
        )
    }
}
