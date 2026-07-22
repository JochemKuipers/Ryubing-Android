package org.ryubing.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import org.ryubing.android.input.SwitchButton

/**
 * On-screen control layout: dual sticks, ABXY, shoulders/triggers, Start/Select.
 * Size, opacity, and stick feel come from settings.
 */
@Composable
fun TouchControls(
    modifier: Modifier = Modifier,
    useSwitchLayout: Boolean = true,
    scale: Float = 1f,
    stickSensitivity: Float = 1f,
    opacity: Float = 0.4f,
    showRightStick: Boolean = true,
    invertStickY: Boolean = false,
    onButton: (SwitchButton, Boolean) -> Unit,
    onStick: (right: Boolean, x: Float, y: Float) -> Unit,
) {
    val s = scale.coerceIn(0.5f, 1.5f)
    val stickSize = (140f * s).dp
    val buttonSize = (56f * s).dp
    val edgePad = (24f * s).dp
    val topPad = (12f * s).dp
    val buttonPad = (4f * s).dp
    val buttonColor = Color.White.copy(alpha = opacity.coerceIn(0.15f, 1f))
    val stickColor = Color.White.copy(alpha = (opacity * 0.5f).coerceIn(0.08f, 0.6f))
    val sens = stickSensitivity.coerceIn(0.25f, 2f)

    fun mapStick(x: Float, y: Float): Pair<Float, Float> {
        val my = if (invertStickY) -y else y
        return clamp(x * sens) to clamp(my * sens)
    }

    Box(modifier) {
        AnalogStick(
            modifier = Modifier.align(Alignment.BottomStart).padding(edgePad).size(stickSize),
            color = stickColor,
            onMove = { x, y ->
                val (mx, my) = mapStick(x, y)
                onStick(false, mx, my)
            },
        )

        if (showRightStick) {
            AnalogStick(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = edgePad + buttonSize * 2.2f, bottom = edgePad)
                    .size(stickSize),
                color = stickColor,
                onMove = { x, y ->
                    val (mx, my) = mapStick(x, y)
                    onStick(true, mx, my)
                },
            )
        }

        val (top, left, right, bottom) = if (useSwitchLayout) {
            listOf(
                "X" to SwitchButton.X,
                "Y" to SwitchButton.Y,
                "A" to SwitchButton.A,
                "B" to SwitchButton.B,
            )
        } else {
            listOf(
                "Y" to SwitchButton.Y,
                "X" to SwitchButton.X,
                "B" to SwitchButton.B,
                "A" to SwitchButton.A,
            )
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(edgePad),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FaceButton(top.first, top.second, buttonSize, buttonPad, buttonColor, onButton)
            Row {
                FaceButton(left.first, left.second, buttonSize, buttonPad, buttonColor, onButton)
                FaceButton(right.first, right.second, buttonSize, buttonPad, buttonColor, onButton)
            }
            FaceButton(bottom.first, bottom.second, buttonSize, buttonPad, buttonColor, onButton)
        }

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = edgePad, vertical = topPad),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row {
                FaceButton("ZL", SwitchButton.LeftTrigger, buttonSize, buttonPad, buttonColor, onButton)
                FaceButton("L", SwitchButton.LeftShoulder, buttonSize, buttonPad, buttonColor, onButton)
            }
            Row {
                FaceButton("−", SwitchButton.Minus, buttonSize, buttonPad, buttonColor, onButton)
                FaceButton("+", SwitchButton.Plus, buttonSize, buttonPad, buttonColor, onButton)
            }
            Row {
                FaceButton("R", SwitchButton.RightShoulder, buttonSize, buttonPad, buttonColor, onButton)
                FaceButton("ZR", SwitchButton.RightTrigger, buttonSize, buttonPad, buttonColor, onButton)
            }
        }
    }
}

@Composable
private fun FaceButton(
    label: String,
    button: SwitchButton,
    size: Dp,
    pad: Dp,
    color: Color,
    onButton: (SwitchButton, Boolean) -> Unit,
) {
    Box(
        Modifier
            .padding(pad)
            .size(size)
            .background(color, CircleShape)
            .pointerInput(button) {
                awaitEachGesture {
                    awaitFirstDown()
                    onButton(button, true)
                    waitForUpOrCancellation()
                    onButton(button, false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label)
    }
}

@Composable
private fun AnalogStick(
    modifier: Modifier = Modifier,
    color: Color,
    onMove: (x: Float, y: Float) -> Unit,
) {
    Box(
        modifier
            .background(color, CircleShape)
            .pointerInput(Unit) {
                val radiusPx = size.width / 2f
                detectDragGestures(
                    onDragEnd = { onMove(0f, 0f) },
                    onDragCancel = { onMove(0f, 0f) },
                ) { change, _ ->
                    val center = Offset(radiusPx, radiusPx)
                    val dx = (change.position.x - center.x) / radiusPx
                    val dy = -(change.position.y - center.y) / radiusPx
                    onMove(clamp(dx), clamp(dy))
                }
            },
    )
}

private fun clamp(v: Float) = max(-1f, min(1f, v))
