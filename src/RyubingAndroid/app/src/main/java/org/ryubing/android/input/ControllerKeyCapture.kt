package org.ryubing.android.input

import android.view.KeyEvent

/**
 * While a remap/hotkey screen is listening, intercept gamepad key presses in
 * [org.ryubing.android.MainActivity.dispatchKeyEvent].
 *
 * Hotkey capture supports combos: hold a modifier, then press the action button.
 * A single key binds on release if no second key was pressed.
 */
object ControllerKeyCapture {
    var isActive: Boolean = false
        private set

    private var onCaptured: ((HotkeyBinding) -> Unit)? = null
    private var onCancelled: (() -> Unit)? = null
    private var heldDuringCapture = linkedSetOf<Int>()
    private var captureCombos: Boolean = false

    fun start(onCaptured: (Int) -> Unit, onCancelled: (() -> Unit)? = null) {
        startBinding(
            captureCombos = false,
            onCaptured = { onCaptured(it.keyCode) },
            onCancelled = onCancelled,
        )
    }

    fun startBinding(
        captureCombos: Boolean = false,
        onCaptured: (HotkeyBinding) -> Unit,
        onCancelled: (() -> Unit)? = null,
    ) {
        isActive = true
        this.captureCombos = captureCombos
        this.onCaptured = onCaptured
        this.onCancelled = onCancelled
        heldDuringCapture.clear()
    }

    fun cancel() {
        if (!isActive) return
        finish(cancelled = true)
    }

    /** Returns true if the event was consumed for binding. */
    fun tryConsume(event: KeyEvent): Boolean {
        if (!isActive) return false

        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) cancel()
            return false
        }

        if (!isBindableKey(event.keyCode)) return true

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true
                if (!captureCombos) {
                    complete(HotkeyBinding(keyCode = event.keyCode))
                    return true
                }
                // Combo mode: second distinct key while another is held → chord.
                if (heldDuringCapture.isNotEmpty() && event.keyCode !in heldDuringCapture) {
                    val modifier = heldDuringCapture.first()
                    complete(HotkeyBinding(keyCode = event.keyCode, modifierKeyCode = modifier))
                    return true
                }
                heldDuringCapture.add(event.keyCode)
            }
            KeyEvent.ACTION_UP -> {
                if (!captureCombos) return true
                // Released the only held key without a second press → single binding.
                if (heldDuringCapture.size == 1 && heldDuringCapture.contains(event.keyCode)) {
                    complete(HotkeyBinding(keyCode = event.keyCode))
                    return true
                }
                heldDuringCapture.remove(event.keyCode)
            }
        }
        return true
    }

    private fun complete(binding: HotkeyBinding) {
        val cb = onCaptured
        finish(cancelled = false)
        cb?.invoke(binding)
    }

    private fun finish(cancelled: Boolean) {
        val cancelCb = onCancelled
        isActive = false
        onCaptured = null
        onCancelled = null
        heldDuringCapture.clear()
        captureCombos = false
        if (cancelled) cancelCb?.invoke()
    }

    private fun isBindableKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_BUTTON_11,
        KeyEvent.KEYCODE_BUTTON_12,
        -> true
        else -> false
    }
}
