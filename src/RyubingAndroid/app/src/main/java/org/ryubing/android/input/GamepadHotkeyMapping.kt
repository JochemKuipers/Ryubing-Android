package org.ryubing.android.input

import android.view.KeyEvent

/**
 * In-game hotkey actions bound to physical gamepad keys (optionally with a modifier).
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

/** Single button or modifier+button chord. [modifierKeyCode] UNKNOWN means no modifier. */
data class HotkeyBinding(
    val keyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
    val modifierKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
) {
    val isBound: Boolean get() = keyCode != KeyEvent.KEYCODE_UNKNOWN

    fun label(): String = when {
        !isBound -> "Unassigned"
        modifierKeyCode == KeyEvent.KEYCODE_UNKNOWN -> AndroidKeyLabels.label(keyCode)
        else -> "${AndroidKeyLabels.label(modifierKeyCode)} + ${AndroidKeyLabels.label(keyCode)}"
    }
}

data class GamepadHotkeyMapping(
    val bindings: Map<HotkeyAction, HotkeyBinding> =
        HotkeyAction.entries.associateWith { HotkeyBinding() },
    val turboModeWhileHeld: Boolean = false,
) {
    fun bindingFor(action: HotkeyAction): HotkeyBinding =
        bindings[action] ?: HotkeyBinding()

    /**
     * Match a key press given keys already held (not including [keyCode] itself).
     * Combo bindings win over single-key bindings for the same action key.
     */
    fun actionForPress(keyCode: Int, heldKeys: Set<Int>): HotkeyAction? {
        val combo = bindings.entries.firstOrNull { (_, b) ->
            b.isBound &&
                b.keyCode == keyCode &&
                b.modifierKeyCode != KeyEvent.KEYCODE_UNKNOWN &&
                b.modifierKeyCode in heldKeys
        }?.key
        if (combo != null) return combo

        return bindings.entries.firstOrNull { (_, b) ->
            b.isBound &&
                b.keyCode == keyCode &&
                b.modifierKeyCode == KeyEvent.KEYCODE_UNKNOWN
        }?.key
    }

    fun withBinding(action: HotkeyAction, binding: HotkeyBinding): GamepadHotkeyMapping {
        val cleaned = bindings.toMutableMap()
        // Drop other actions that use the exact same chord.
        cleaned.entries.removeAll { (a, b) ->
            a != action && b.isBound && b.keyCode == binding.keyCode && b.modifierKeyCode == binding.modifierKeyCode
        }
        cleaned[action] = binding
        HotkeyAction.entries.forEach { if (it !in cleaned) cleaned[it] = HotkeyBinding() }
        return copy(bindings = cleaned)
    }

    fun withoutBinding(action: HotkeyAction): GamepadHotkeyMapping =
        copy(bindings = bindings + (action to HotkeyBinding()))
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
