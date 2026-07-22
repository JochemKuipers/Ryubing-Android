package org.ryubing.android.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import org.ryubing.android.input.ControllerLayoutPreset
import org.ryubing.android.input.ControllerMapping
import org.ryubing.android.input.ControllerMappingPresets
import org.ryubing.android.input.DpadInputMode
import org.ryubing.android.input.SwitchButton

class ControllerMappingRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ControllerMapping {
        val json = prefs.getString(KEY_MAPPING, null) ?: return ControllerMappingPresets.switchLayout()
        return runCatching { decode(json) }.getOrElse { ControllerMappingPresets.switchLayout() }
    }

    fun save(mapping: ControllerMapping) {
        prefs.edit { putString(KEY_MAPPING, encode(mapping)) }
    }

    fun resetToPreset(preset: ControllerLayoutPreset): ControllerMapping {
        val current = load()
        val mapping = ControllerMappingPresets.forPreset(preset)
            .copy(dpadInputMode = current.dpadInputMode)
        save(mapping)
        return mapping
    }

    private fun encode(mapping: ControllerMapping): String {
        val obj = JSONObject()
        mapping.bindings.forEach { (button, keyCode) ->
            obj.put(button.name, keyCode)
        }
        obj.put("invertLeftStickY", mapping.invertLeftStickY)
        obj.put("invertRightStickY", mapping.invertRightStickY)
        obj.put("dpadInputMode", mapping.dpadInputMode.name)
        return obj.toString()
    }

    private fun decode(json: String): ControllerMapping {
        val obj = JSONObject(json)
        val bindings = mutableMapOf<SwitchButton, Int>()
        for (button in SwitchButton.entries) {
            if (!obj.has(button.name)) continue
            bindings[button] = obj.getInt(button.name)
        }
        val dpadMode = obj.optString("dpadInputMode", DpadInputMode.HatAxes.name)
            .let { runCatching { DpadInputMode.valueOf(it) }.getOrDefault(DpadInputMode.HatAxes) }
        return ControllerMapping(
            bindings = bindings,
            invertLeftStickY = obj.optBoolean("invertLeftStickY", true),
            invertRightStickY = obj.optBoolean("invertRightStickY", true),
            dpadInputMode = dpadMode,
        )
    }

    private companion object {
        const val PREFS_NAME = "ryubing_controller_mapping"
        const val KEY_MAPPING = "mapping_v1"
    }
}
