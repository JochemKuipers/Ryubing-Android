package org.ryubing.android.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.ryubing.android.emu.EmulationSession
import kotlin.math.abs

/**
 * Maps Android gamepad key/motion events (built-in handheld controls, Bluetooth pads)
 * into [EmulationSession] input using a user-defined [ControllerMapping], and dispatches
 * [GamepadHotkeyMapping] actions before Switch button mappings.
 */
class PhysicalGamepadController(
    private val session: EmulationSession,
    mapping: ControllerMapping,
    hotkeys: GamepadHotkeyMapping = GamepadHotkeyMapping(),
) {
    private var leftTriggerPressed = false
    private var rightTriggerPressed = false
    private var mappingHolder: ControllerMapping = mapping
    private var hotkeysHolder: GamepadHotkeyMapping = hotkeys
    private var turboHeldActive = false
    private val heldKeys = mutableSetOf<Int>()

    fun updateMapping(mapping: ControllerMapping) {
        leftTriggerPressed = false
        rightTriggerPressed = false
        mappingHolder = mapping
    }

    fun updateHotkeys(hotkeys: GamepadHotkeyMapping) {
        if (turboHeldActive) {
            session.setTurboHeld(false)
            turboHeldActive = false
        }
        hotkeysHolder = hotkeys
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (ControllerKeyCapture.isActive) return false

        val isDpadKey = event.keyCode in DPAD_KEYS
        val m = mappingHolder

        // Hat mode: D-pad comes from AXIS_HAT_* only — ignore DPAD keycodes (incl. FALLBACK).
        if (m.dpadInputMode == DpadInputMode.HatAxes && isDpadKey) return false

        // Legacy mode: accept FALLBACK DPAD keys synthesized from hat axes.
        // Other FALLBACK keys are still ignored to avoid double-fires.
        val isFallback = (event.flags and KeyEvent.FLAG_FALLBACK) != 0
        if (isFallback && !(m.dpadInputMode == DpadInputMode.LegacyKeys && isDpadKey)) {
            return false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) {
                    heldKeys.add(event.keyCode)
                    return mappingHolder.switchButtonForKey(event.keyCode) != null
                }
                val hotkey = hotkeysHolder.actionForPress(event.keyCode, heldKeys)
                heldKeys.add(event.keyCode)
                if (hotkey != null) {
                    if (hotkey == HotkeyAction.TurboMode && hotkeysHolder.turboModeWhileHeld) {
                        turboHeldActive = true
                        session.setTurboHeld(true)
                    } else {
                        session.performHotkey(hotkey)
                    }
                    return true
                }
                val button = mappingHolder.switchButtonForKey(event.keyCode) ?: return false
                session.setButton(button, true)
                return true
            }
            KeyEvent.ACTION_UP -> {
                heldKeys.remove(event.keyCode)
                if (turboHeldActive) {
                    val turbo = hotkeysHolder.bindingFor(HotkeyAction.TurboMode)
                    if (event.keyCode == turbo.keyCode ||
                        (turbo.modifierKeyCode != KeyEvent.KEYCODE_UNKNOWN &&
                            event.keyCode == turbo.modifierKeyCode)
                    ) {
                        turboHeldActive = false
                        session.setTurboHeld(false)
                    }
                }
                val button = mappingHolder.switchButtonForKey(event.keyCode) ?: return false
                session.setButton(button, false)
                return true
            }
            else -> return false
        }
    }

    fun onMotionEvent(event: MotionEvent) {
        if (ControllerKeyCapture.isActive) return
        if (event.action != MotionEvent.ACTION_MOVE &&
            event.action != MotionEvent.ACTION_HOVER_MOVE
        ) {
            return
        }

        val m = mappingHolder
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

        val leftStickY = if (m.invertLeftStickY) -leftY else leftY
        val rightStickY = if (m.invertRightStickY) -rightY else rightY

        session.setStick(false, leftX, leftStickY)
        session.setStick(true, rightX, rightStickY)

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

        if (m.dpadInputMode == DpadInputMode.HatAxes) {
            device?.let { updateDpadHat(event, it) }
        }
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
        val hasHat = device.getMotionRange(MotionEvent.AXIS_HAT_X, InputDevice.SOURCE_JOYSTICK) != null ||
            device.getMotionRange(MotionEvent.AXIS_HAT_Y, InputDevice.SOURCE_JOYSTICK) != null ||
            device.getMotionRange(MotionEvent.AXIS_HAT_X) != null ||
            device.getMotionRange(MotionEvent.AXIS_HAT_Y) != null
        if (!hasHat) return

        val hor = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val vert = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        session.setButton(SwitchButton.DpadUp, vert < -0.5f)
        session.setButton(SwitchButton.DpadDown, vert > 0.5f)
        session.setButton(SwitchButton.DpadLeft, hor < -0.5f)
        session.setButton(SwitchButton.DpadRight, hor > 0.5f)
    }

    private companion object {
        const val PRESS_THRESHOLD = 0.65f
        const val RELEASE_THRESHOLD = 0.45f
        val DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}
