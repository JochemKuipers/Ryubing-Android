package org.ryubing.android.input

/**
 * Bit indices matching Ryujinx.Input.GamepadButtonInputId (byte enum). The managed
 * AndroidGamepad tests `(mask & (1 shl index)) != 0`, so the bit for each button is
 * `1 shl ordinal`. Keep in sync with GamepadButtonInputId.cs.
 */
enum class SwitchButton(val bit: Int) {
    A(1 shl 1),
    B(1 shl 2),
    X(1 shl 3),
    Y(1 shl 4),
    LeftStick(1 shl 5),
    RightStick(1 shl 6),
    LeftShoulder(1 shl 7),
    RightShoulder(1 shl 8),
    LeftTrigger(1 shl 9),
    RightTrigger(1 shl 10),
    DpadUp(1 shl 11),
    DpadDown(1 shl 12),
    DpadLeft(1 shl 13),
    DpadRight(1 shl 14),
    Minus(1 shl 15),
    Plus(1 shl 16),
}
