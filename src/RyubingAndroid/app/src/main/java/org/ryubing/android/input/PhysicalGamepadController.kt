package org.ryubing.android.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.ryubing.android.emu.EmulationSession
import kotlin.math.abs

/**
 * Maps Android gamepad key/motion events (built-in handheld controls, Bluetooth pads)
 * into [EmulationSession] input for the virtual Switch controller.
 */
class PhysicalGamepadController(
    private val session: EmulationSession,
    private val useSwitchLayout: Boolean,
) {
    private var leftTriggerPressed = false
    private var rightTriggerPressed = false

    fun onKeyEvent(event: KeyEvent): Boolean {
        val button = mapKeyCode(event.keyCode) ?: return false
        if ((event.flags and KeyEvent.FLAG_FALLBACK) != 0) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> session.setButton(button, true)
            KeyEvent.ACTION_UP -> session.setButton(button, false)
            else -> return false
        }
        return true
    }

    fun onMotionEvent(event: MotionEvent) {
        if (event.action != MotionEvent.ACTION_MOVE &&
            event.action != MotionEvent.ACTION_HOVER_MOVE
        ) {
            return
        }

        val device = event.device
        val source = InputDevice.SOURCE_JOYSTICK

        fun hasAxis(axis: Int): Boolean = device?.getMotionRange(axis, source) != null
        fun axisValue(axis: Int): Float = event.getAxisValue(axis)

        val rightXAxis = if (hasAxis(MotionEvent.AXIS_RX)) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z
        val rightYAxis = if (hasAxis(MotionEvent.AXIS_RY)) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ

        val leftX = if (hasAxis(MotionEvent.AXIS_X)) axisValue(MotionEvent.AXIS_X) else 0f
        val leftY = if (hasAxis(MotionEvent.AXIS_Y)) axisValue(MotionEvent.AXIS_Y) else 0f
        val rightX = if (hasAxis(rightXAxis)) axisValue(rightXAxis) else 0f
        val rightY = if (hasAxis(rightYAxis)) axisValue(rightYAxis) else 0f

        session.setStick(false, leftX, -leftY)
        session.setStick(true, rightX, -rightY)

        val rightStickUsesZ = rightXAxis == MotionEvent.AXIS_Z
        val rightStickUsesRz = rightYAxis == MotionEvent.AXIS_RZ

        val rawLt = when {
            hasAxis(MotionEvent.AXIS_LTRIGGER) -> axisValue(MotionEvent.AXIS_LTRIGGER)
            hasAxis(MotionEvent.AXIS_BRAKE) -> axisValue(MotionEvent.AXIS_BRAKE)
            !rightStickUsesZ && hasAxis(MotionEvent.AXIS_Z) -> axisValue(MotionEvent.AXIS_Z)
            else -> 0f
        }
        val rawRt = when {
            hasAxis(MotionEvent.AXIS_RTRIGGER) -> axisValue(MotionEvent.AXIS_RTRIGGER)
            hasAxis(MotionEvent.AXIS_GAS) -> axisValue(MotionEvent.AXIS_GAS)
            !rightStickUsesRz && hasAxis(MotionEvent.AXIS_RZ) -> axisValue(MotionEvent.AXIS_RZ)
            else -> 0f
        }

        val lt = if (abs(rawLt) < 0.02f) 0f else rawLt.coerceIn(0f, 1f)
        val rt = if (abs(rawRt) < 0.02f) 0f else rawRt.coerceIn(0f, 1f)

        updateTrigger(lt, true)
        updateTrigger(rt, false)

        device?.let { updateDpadHat(event, it) }
    }

    private fun updateTrigger(value: Float, left: Boolean) {
        val button = if (left) SwitchButton.LeftTrigger else SwitchButton.RightTrigger
        val pressed = if (left) leftTriggerPressed else rightTriggerPressed
        when {
            !pressed && value >= PRESS_THRESHOLD -> {
                if (left) leftTriggerPressed = true else rightTriggerPressed = true
                session.setButton(button, true)
            }
            pressed && value <= RELEASE_THRESHOLD -> {
                if (left) leftTriggerPressed = false else rightTriggerPressed = false
                session.setButton(button, false)
            }
        }
    }

    private fun updateDpadHat(event: MotionEvent, device: InputDevice) {
        if (device.sources and InputDevice.SOURCE_DPAD != 0) return

        val hor = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val vert = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        session.setButton(SwitchButton.DpadUp, vert < 0f)
        session.setButton(SwitchButton.DpadDown, vert > 0f)
        session.setButton(SwitchButton.DpadLeft, hor < 0f)
        session.setButton(SwitchButton.DpadRight, hor > 0f)
    }

    private fun mapKeyCode(keyCode: Int): SwitchButton? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A ->
            if (useSwitchLayout) SwitchButton.B else SwitchButton.A
        KeyEvent.KEYCODE_BUTTON_B ->
            if (useSwitchLayout) SwitchButton.A else SwitchButton.B
        KeyEvent.KEYCODE_BUTTON_X ->
            if (useSwitchLayout) SwitchButton.Y else SwitchButton.X
        KeyEvent.KEYCODE_BUTTON_Y ->
            if (useSwitchLayout) SwitchButton.X else SwitchButton.Y
        KeyEvent.KEYCODE_BUTTON_L1 -> SwitchButton.LeftShoulder
        KeyEvent.KEYCODE_BUTTON_L2 -> SwitchButton.LeftTrigger
        KeyEvent.KEYCODE_BUTTON_R1 -> SwitchButton.RightShoulder
        KeyEvent.KEYCODE_BUTTON_R2 -> SwitchButton.RightTrigger
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_11 -> SwitchButton.LeftStick
        KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_12 -> SwitchButton.RightStick
        KeyEvent.KEYCODE_DPAD_UP -> SwitchButton.DpadUp
        KeyEvent.KEYCODE_DPAD_DOWN -> SwitchButton.DpadDown
        KeyEvent.KEYCODE_DPAD_LEFT -> SwitchButton.DpadLeft
        KeyEvent.KEYCODE_DPAD_RIGHT -> SwitchButton.DpadRight
        KeyEvent.KEYCODE_BUTTON_START -> SwitchButton.Plus
        KeyEvent.KEYCODE_BUTTON_SELECT -> SwitchButton.Minus
        else -> null
    }

    private companion object {
        const val PRESS_THRESHOLD = 0.65f
        const val RELEASE_THRESHOLD = 0.45f
    }
}
