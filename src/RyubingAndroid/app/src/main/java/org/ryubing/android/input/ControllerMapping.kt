package org.ryubing.android.input

import android.view.KeyEvent

/** How physical D-pad input is read from the controller. */
enum class DpadInputMode {
    /** AXIS_HAT_X / AXIS_HAT_Y (default — most Bluetooth pads). */
    HatAxes,

    /** KEYCODE_DPAD_* key events (legacy / some handhelds). */
    LegacyKeys,
}

data class ControllerMapping(
    val bindings: Map<SwitchButton, Int> = emptyMap(),
    val invertLeftStickY: Boolean = true,
    val invertRightStickY: Boolean = true,
    val dpadInputMode: DpadInputMode = DpadInputMode.HatAxes,
) {
    fun keyFor(button: SwitchButton): Int? = bindings[button]?.takeIf { it != KeyEvent.KEYCODE_UNKNOWN }

    fun switchButtonForKey(keyCode: Int): SwitchButton? =
        bindings.entries.firstOrNull { it.value == keyCode }?.key

    fun withBinding(button: SwitchButton, keyCode: Int): ControllerMapping {
        val cleaned = bindings.filterValues { it != keyCode }.filterKeys { it != button }
        return copy(bindings = cleaned + (button to keyCode))
    }

    fun withoutBinding(button: SwitchButton): ControllerMapping =
        copy(bindings = bindings - button)
}

enum class ControllerLayoutPreset {
    Switch,
    Xbox,
}

object ControllerMappingPresets {
    fun forPreset(preset: ControllerLayoutPreset): ControllerMapping = when (preset) {
        ControllerLayoutPreset.Switch -> switchLayout()
        ControllerLayoutPreset.Xbox -> xboxLayout()
    }

    fun switchLayout(): ControllerMapping = ControllerMapping(
        bindings = mapOf(
            SwitchButton.A to KeyEvent.KEYCODE_BUTTON_B,
            SwitchButton.B to KeyEvent.KEYCODE_BUTTON_A,
            SwitchButton.X to KeyEvent.KEYCODE_BUTTON_Y,
            SwitchButton.Y to KeyEvent.KEYCODE_BUTTON_X,
            SwitchButton.LeftShoulder to KeyEvent.KEYCODE_BUTTON_L1,
            SwitchButton.RightShoulder to KeyEvent.KEYCODE_BUTTON_R1,
            SwitchButton.LeftTrigger to KeyEvent.KEYCODE_BUTTON_L2,
            SwitchButton.RightTrigger to KeyEvent.KEYCODE_BUTTON_R2,
            SwitchButton.LeftStick to KeyEvent.KEYCODE_BUTTON_THUMBL,
            SwitchButton.RightStick to KeyEvent.KEYCODE_BUTTON_THUMBR,
            SwitchButton.DpadUp to KeyEvent.KEYCODE_DPAD_UP,
            SwitchButton.DpadDown to KeyEvent.KEYCODE_DPAD_DOWN,
            SwitchButton.DpadLeft to KeyEvent.KEYCODE_DPAD_LEFT,
            SwitchButton.DpadRight to KeyEvent.KEYCODE_DPAD_RIGHT,
            SwitchButton.Plus to KeyEvent.KEYCODE_BUTTON_START,
            SwitchButton.Minus to KeyEvent.KEYCODE_BUTTON_SELECT,
        ),
    )

    fun xboxLayout(): ControllerMapping = ControllerMapping(
        bindings = mapOf(
            SwitchButton.A to KeyEvent.KEYCODE_BUTTON_A,
            SwitchButton.B to KeyEvent.KEYCODE_BUTTON_B,
            SwitchButton.X to KeyEvent.KEYCODE_BUTTON_X,
            SwitchButton.Y to KeyEvent.KEYCODE_BUTTON_Y,
            SwitchButton.LeftShoulder to KeyEvent.KEYCODE_BUTTON_L1,
            SwitchButton.RightShoulder to KeyEvent.KEYCODE_BUTTON_R1,
            SwitchButton.LeftTrigger to KeyEvent.KEYCODE_BUTTON_L2,
            SwitchButton.RightTrigger to KeyEvent.KEYCODE_BUTTON_R2,
            SwitchButton.LeftStick to KeyEvent.KEYCODE_BUTTON_THUMBL,
            SwitchButton.RightStick to KeyEvent.KEYCODE_BUTTON_THUMBR,
            SwitchButton.DpadUp to KeyEvent.KEYCODE_DPAD_UP,
            SwitchButton.DpadDown to KeyEvent.KEYCODE_DPAD_DOWN,
            SwitchButton.DpadLeft to KeyEvent.KEYCODE_DPAD_LEFT,
            SwitchButton.DpadRight to KeyEvent.KEYCODE_DPAD_RIGHT,
            SwitchButton.Plus to KeyEvent.KEYCODE_BUTTON_START,
            SwitchButton.Minus to KeyEvent.KEYCODE_BUTTON_SELECT,
        ),
    )
}

object AndroidKeyLabels {
    fun label(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_UNKNOWN -> "Unassigned"
        KeyEvent.KEYCODE_BUTTON_A -> "A (gamepad)"
        KeyEvent.KEYCODE_BUTTON_B -> "B (gamepad)"
        KeyEvent.KEYCODE_BUTTON_X -> "X (gamepad)"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y (gamepad)"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L1 / LB"
        KeyEvent.KEYCODE_BUTTON_R1 -> "R1 / RB"
        KeyEvent.KEYCODE_BUTTON_L2 -> "L2 / LT"
        KeyEvent.KEYCODE_BUTTON_R2 -> "R2 / RT"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "Left stick click"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "Right stick click"
        KeyEvent.KEYCODE_BUTTON_START -> "Start / +"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "Select / −"
        KeyEvent.KEYCODE_DPAD_UP -> "D-pad Up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "D-pad Down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "D-pad Left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "D-pad Right"
        KeyEvent.KEYCODE_BUTTON_11 -> "Button 11"
        KeyEvent.KEYCODE_BUTTON_12 -> "Button 12"
        else -> KeyEvent.keyCodeToString(keyCode)
            .removePrefix("KEYCODE_")
            .replace('_', ' ')
            .lowercase()
            .replaceFirstChar { it.uppercase() }
    }
}

/** Switch actions shown in the remap UI, in a sensible order. */
val REMAPPABLE_SWITCH_BUTTONS: List<SwitchButton> = listOf(
    SwitchButton.A,
    SwitchButton.B,
    SwitchButton.X,
    SwitchButton.Y,
    SwitchButton.LeftShoulder,
    SwitchButton.RightShoulder,
    SwitchButton.LeftTrigger,
    SwitchButton.RightTrigger,
    SwitchButton.LeftStick,
    SwitchButton.RightStick,
    SwitchButton.DpadUp,
    SwitchButton.DpadDown,
    SwitchButton.DpadLeft,
    SwitchButton.DpadRight,
    SwitchButton.Minus,
    SwitchButton.Plus,
)

fun SwitchButton.displayLabel(): String = when (this) {
    SwitchButton.A -> "A"
    SwitchButton.B -> "B"
    SwitchButton.X -> "X"
    SwitchButton.Y -> "Y"
    SwitchButton.LeftShoulder -> "L"
    SwitchButton.RightShoulder -> "R"
    SwitchButton.LeftTrigger -> "ZL"
    SwitchButton.RightTrigger -> "ZR"
    SwitchButton.LeftStick -> "Left stick (click)"
    SwitchButton.RightStick -> "Right stick (click)"
    SwitchButton.DpadUp -> "D-pad Up"
    SwitchButton.DpadDown -> "D-pad Down"
    SwitchButton.DpadLeft -> "D-pad Left"
    SwitchButton.DpadRight -> "D-pad Right"
    SwitchButton.Minus -> "−"
    SwitchButton.Plus -> "+"
}
