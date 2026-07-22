package org.ryubing.android.input

import android.view.KeyEvent

/**
 * In-game hotkey actions bound to physical gamepad keys.
 * [TurboMode] toggles on press unless [GamepadHotkeyMapping.turboModeWhileHeld] is set.
 */
enum class HotkeyAction {
    ToggleVSync,
    Screenshot,
    ShowUi,
    Pause,
    ToggleMute,
    ResScaleUp,
    ResScaleDown,
    VolumeUp,
    VolumeDown,
    CustomVSyncInc,
    CustomVSyncDec,
    TurboMode,
}

data class GamepadHotkeyMapping(
    val bindings: Map<HotkeyAction, Int> = HotkeyAction.entries.associateWith { KeyEvent.KEYCODE_UNKNOWN },
    val turboModeWhileHeld: Boolean = false,
) {
    fun keyFor(action: HotkeyAction): Int =
        bindings[action] ?: KeyEvent.KEYCODE_UNKNOWN

    fun actionForKey(keyCode: Int): HotkeyAction? =
        bindings.entries.firstOrNull { it.value == keyCode && it.value != KeyEvent.KEYCODE_UNKNOWN }?.key

    fun withBinding(action: HotkeyAction, keyCode: Int): GamepadHotkeyMapping {
        val cleaned = bindings.filterValues { it != keyCode }.toMutableMap()
        cleaned[action] = keyCode
        // Ensure every action remains present.
        HotkeyAction.entries.forEach { if (it !in cleaned) cleaned[it] = KeyEvent.KEYCODE_UNKNOWN }
        return copy(bindings = cleaned)
    }

    fun withoutBinding(action: HotkeyAction): GamepadHotkeyMapping =
        copy(bindings = bindings + (action to KeyEvent.KEYCODE_UNKNOWN))
}

fun HotkeyAction.displayLabel(): String = when (this) {
    HotkeyAction.ToggleVSync -> "Toggle VSync"
    HotkeyAction.Screenshot -> "Screenshot"
    HotkeyAction.ShowUi -> "Show / hide UI"
    HotkeyAction.Pause -> "Pause"
    HotkeyAction.ToggleMute -> "Toggle mute"
    HotkeyAction.ResScaleUp -> "Resolution scale +"
    HotkeyAction.ResScaleDown -> "Resolution scale −"
    HotkeyAction.VolumeUp -> "Volume +"
    HotkeyAction.VolumeDown -> "Volume −"
    HotkeyAction.CustomVSyncInc -> "Custom VSync +"
    HotkeyAction.CustomVSyncDec -> "Custom VSync −"
    HotkeyAction.TurboMode -> "Turbo mode"
}
