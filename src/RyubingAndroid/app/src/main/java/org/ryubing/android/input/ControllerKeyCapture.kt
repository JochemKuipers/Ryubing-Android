package org.ryubing.android.input

import android.view.KeyEvent

/**
 * While the remap screen is listening, intercept the next gamepad key press in
 * [org.ryubing.android.MainActivity.dispatchKeyEvent].
 */
object ControllerKeyCapture {
    var isActive: Boolean = false
        private set

    private var onCaptured: ((Int) -> Unit)? = null
    private var onCancelled: (() -> Unit)? = null

    fun start(onCaptured: (Int) -> Unit, onCancelled: (() -> Unit)? = null) {
        isActive = true
        this.onCaptured = onCaptured
        this.onCancelled = onCancelled
    }

    fun cancel() {
        if (!isActive) return
        isActive = false
        onCancelled?.invoke()
        onCaptured = null
        onCancelled = null
    }

    /** Returns true if the event was consumed for binding. */
    fun tryConsume(event: KeyEvent): Boolean {
        if (!isActive) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true

        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            cancel()
            return false
        }

        if (!isBindableKey(event.keyCode)) return true

        onCaptured?.invoke(event.keyCode)
        isActive = false
        onCaptured = null
        onCancelled = null
        return true
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
