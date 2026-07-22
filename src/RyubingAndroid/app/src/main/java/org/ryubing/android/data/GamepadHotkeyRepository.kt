package org.ryubing.android.data

import android.content.Context
import android.view.KeyEvent
import androidx.core.content.edit
import org.json.JSONObject
import org.ryubing.android.input.GamepadHotkeyMapping
import org.ryubing.android.input.HotkeyAction

class GamepadHotkeyRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): GamepadHotkeyMapping {
        val json = prefs.getString(KEY_MAPPING, null) ?: return GamepadHotkeyMapping()
        return runCatching { decode(json) }.getOrElse { GamepadHotkeyMapping() }
    }

    fun save(mapping: GamepadHotkeyMapping) {
        prefs.edit { putString(KEY_MAPPING, encode(mapping)) }
    }

    fun reset(): GamepadHotkeyMapping {
        val mapping = GamepadHotkeyMapping()
        save(mapping)
        return mapping
    }

    private fun encode(mapping: GamepadHotkeyMapping): String {
        val obj = JSONObject()
        mapping.bindings.forEach { (action, keyCode) ->
            obj.put(action.name, keyCode)
        }
        obj.put("turboModeWhileHeld", mapping.turboModeWhileHeld)
        return obj.toString()
    }

    private fun decode(json: String): GamepadHotkeyMapping {
        val obj = JSONObject(json)
        val bindings = mutableMapOf<HotkeyAction, Int>()
        for (action in HotkeyAction.entries) {
            bindings[action] = if (obj.has(action.name)) {
                obj.getInt(action.name)
            } else {
                KeyEvent.KEYCODE_UNKNOWN
            }
        }
        return GamepadHotkeyMapping(
            bindings = bindings,
            turboModeWhileHeld = obj.optBoolean("turboModeWhileHeld", false),
        )
    }

    private companion object {
        const val PREFS_NAME = "ryubing_hotkeys"
        const val KEY_MAPPING = "hotkeys_v1"
    }
}
