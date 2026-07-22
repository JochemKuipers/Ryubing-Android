package org.ryubing.android.data

import android.content.Context
import android.view.KeyEvent
import androidx.core.content.edit
import org.json.JSONObject
import org.ryubing.android.input.GamepadHotkeyMapping
import org.ryubing.android.input.HotkeyAction
import org.ryubing.android.input.HotkeyBinding

class GamepadHotkeyRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): GamepadHotkeyMapping {
        val json = prefs.getString(KEY_MAPPING, null)
            ?: prefs.getString(KEY_MAPPING_LEGACY, null)
            ?: return GamepadHotkeyMapping()
        val mapping = runCatching { decode(json) }.getOrElse { GamepadHotkeyMapping() }
        // Migrate legacy single-key prefs into v2.
        if (!prefs.contains(KEY_MAPPING) && prefs.contains(KEY_MAPPING_LEGACY)) {
            save(mapping)
        }
        return mapping
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
        mapping.bindings.forEach { (action, binding) ->
            obj.put(
                action.name,
                JSONObject()
                    .put("key", binding.keyCode)
                    .put("mod", binding.modifierKeyCode),
            )
        }
        obj.put("turboModeWhileHeld", mapping.turboModeWhileHeld)
        return obj.toString()
    }

    private fun decode(json: String): GamepadHotkeyMapping {
        val obj = JSONObject(json)
        val bindings = mutableMapOf<HotkeyAction, HotkeyBinding>()
        for (action in HotkeyAction.entries) {
            bindings[action] = when {
                !obj.has(action.name) -> HotkeyBinding()
                obj.opt(action.name) is JSONObject -> {
                    val b = obj.getJSONObject(action.name)
                    HotkeyBinding(
                        keyCode = b.optInt("key", KeyEvent.KEYCODE_UNKNOWN),
                        modifierKeyCode = b.optInt("mod", KeyEvent.KEYCODE_UNKNOWN),
                    )
                }
                // Legacy hotkeys_v1: bare key code int
                else -> HotkeyBinding(keyCode = obj.optInt(action.name, KeyEvent.KEYCODE_UNKNOWN))
            }
        }
        return GamepadHotkeyMapping(
            bindings = bindings,
            turboModeWhileHeld = obj.optBoolean("turboModeWhileHeld", false),
        )
    }

    private companion object {
        const val PREFS_NAME = "ryubing_hotkeys"
        const val KEY_MAPPING = "hotkeys_v2"
        const val KEY_MAPPING_LEGACY = "hotkeys_v1"
    }
}
