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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import org.ryubing.android.input.SwitchButton

/**
 * A minimal on-screen control layout: a left analog stick, a right ABXY cluster, and
 * shoulder + Start/Select buttons. Presses are forwarded as button/stick updates. This is
 * intentionally simple; a fully skinnable/remappable overlay is a follow-up.
 */
@Composable
fun TouchControls(
    modifier: Modifier = Modifier,
    onButton: (SwitchButton, Boolean) -> Unit,
    onStick: (right: Boolean, x: Float, y: Float) -> Unit,
) {
    Box(modifier) {
        // Left analog stick.
        AnalogStick(
            modifier = Modifier.align(Alignment.BottomStart).padding(32.dp).size(140.dp),
            onMove = { x, y -> onStick(false, x, y) },
        )

        // Face buttons (ABXY).
        Column(
            Modifier.align(Alignment.BottomEnd).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FaceButton("X", SwitchButton.X, onButton)
            Row {
                FaceButton("Y", SwitchButton.Y, onButton)
                FaceButton("A", SwitchButton.A, onButton)
            }
            FaceButton("B", SwitchButton.B, onButton)
        }

        // Shoulder + system buttons.
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FaceButton("L", SwitchButton.LeftShoulder, onButton)
            Row {
                FaceButton("-", SwitchButton.Minus, onButton)
                FaceButton("+", SwitchButton.Plus, onButton)
            }
            FaceButton("R", SwitchButton.RightShoulder, onButton)
        }
    }
}

@Composable
private fun FaceButton(label: String, button: SwitchButton, onButton: (SwitchButton, Boolean) -> Unit) {
    Box(
        Modifier
            .padding(6.dp)
            .size(56.dp)
            .background(Color(0x66FFFFFF), CircleShape)
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
private fun AnalogStick(modifier: Modifier = Modifier, onMove: (x: Float, y: Float) -> Unit) {
    Box(
        modifier
            .background(Color(0x33FFFFFF), CircleShape)
            .pointerInput(Unit) {
                val radiusPx = size.width / 2f
                detectDragGestures(
                    onDragEnd = { onMove(0f, 0f) },
                    onDragCancel = { onMove(0f, 0f) },
                ) { change, _ ->
                    val center = Offset(radiusPx, radiusPx)
                    val dx = (change.position.x - center.x) / radiusPx
                    // Y is inverted: screen-down is negative on the stick axis.
                    val dy = -(change.position.y - center.y) / radiusPx
                    onMove(clamp(dx), clamp(dy))
                }
            },
    )
}

private fun clamp(v: Float) = max(-1f, min(1f, v))
