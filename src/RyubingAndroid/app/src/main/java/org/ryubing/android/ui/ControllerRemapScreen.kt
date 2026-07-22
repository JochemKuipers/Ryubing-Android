package org.ryubing.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ryubing.android.data.ControllerMappingRepository
import org.ryubing.android.input.AndroidKeyLabels
import org.ryubing.android.input.ControllerKeyCapture
import org.ryubing.android.input.ControllerLayoutPreset
import org.ryubing.android.input.ControllerMapping
import org.ryubing.android.input.DpadInputMode
import org.ryubing.android.input.REMAPPABLE_SWITCH_BUTTONS
import org.ryubing.android.input.SwitchButton
import org.ryubing.android.input.displayLabel

/** Controller remap UI for embedding in settings (no Scaffold). */
@Composable
fun ControllerRemapPanel(
    mappingRepository: ControllerMappingRepository,
    onMappingChanged: () -> Unit,
) {
    var mapping by remember { mutableStateOf(mappingRepository.load()) }
    var listeningFor by remember { mutableStateOf<SwitchButton?>(null) }

    DisposableEffect(Unit) {
        onDispose { ControllerKeyCapture.cancel() }
    }

    fun persist(updated: ControllerMapping) {
        mapping = updated
        mappingRepository.save(updated)
        onMappingChanged()
    }

    fun startListening(button: SwitchButton) {
        listeningFor = button
        ControllerKeyCapture.start(
            onCaptured = { keyCode ->
                persist(mapping.withBinding(button, keyCode))
                listeningFor = null
            },
            onCancelled = { listeningFor = null },
        )
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (listeningFor != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Press a controller button for “${listeningFor!!.displayLabel()}”…",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            Text(
                "Tap an action, then press the physical button you want to use. " +
                    "Bindings apply to built-in controls and Bluetooth gamepads.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    ControllerKeyCapture.cancel()
                    listeningFor = null
                    persist(mappingRepository.resetToPreset(ControllerLayoutPreset.Switch))
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Switch preset")
            }
            OutlinedButton(
                onClick = {
                    ControllerKeyCapture.cancel()
                    listeningFor = null
                    persist(mappingRepository.resetToPreset(ControllerLayoutPreset.Xbox))
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Xbox preset")
            }
        }

        HorizontalDivider()
        Text("D-pad", style = MaterialTheme.typography.titleSmall)
        RemapSwitchRow(
            label = "Use hat axes (AXIS_HAT)",
            checked = mapping.dpadInputMode == DpadInputMode.HatAxes,
            onChange = { useHat ->
                persist(
                    mapping.copy(
                        dpadInputMode = if (useHat) DpadInputMode.HatAxes else DpadInputMode.LegacyKeys,
                    ),
                )
            },
        )
        Text(
            if (mapping.dpadInputMode == DpadInputMode.HatAxes) {
                "D-pad reads AXIS_HAT_X / AXIS_HAT_Y directly (recommended for most controllers)."
            } else {
                "Legacy mode: bind KEYCODE_DPAD_* below. Needed for pads that only send D-pad keys."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val faceButtons = REMAPPABLE_SWITCH_BUTTONS.filter { it !in DPAD_SWITCH_BUTTONS }
        val dpadButtons = REMAPPABLE_SWITCH_BUTTONS.filter { it in DPAD_SWITCH_BUTTONS }

        HorizontalDivider()
        Text("Face & shoulder buttons", style = MaterialTheme.typography.titleSmall)

        faceButtons.forEach { button ->
            MappingRow(
                switchLabel = button.displayLabel(),
                boundKeyLabel = mapping.keyFor(button)?.let(AndroidKeyLabels::label) ?: "Unassigned",
                isListening = listeningFor == button,
                onBind = { startListening(button) },
                onClear = {
                    ControllerKeyCapture.cancel()
                    listeningFor = null
                    persist(mapping.withoutBinding(button))
                },
            )
        }

        if (mapping.dpadInputMode == DpadInputMode.LegacyKeys) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("D-pad buttons (legacy)", style = MaterialTheme.typography.titleSmall)
            dpadButtons.forEach { button ->
                MappingRow(
                    switchLabel = button.displayLabel(),
                    boundKeyLabel = mapping.keyFor(button)?.let(AndroidKeyLabels::label) ?: "Unassigned",
                    isListening = listeningFor == button,
                    onBind = { startListening(button) },
                    onClear = {
                        ControllerKeyCapture.cancel()
                        listeningFor = null
                        persist(mapping.withoutBinding(button))
                    },
                )
            }
        } else {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("D-pad (hat axes)", style = MaterialTheme.typography.titleSmall)
            dpadButtons.forEach { button ->
                MappingRow(
                    switchLabel = button.displayLabel(),
                    boundKeyLabel = "Hat axes",
                    isListening = false,
                    bindEnabled = false,
                    onBind = {},
                    onClear = {},
                    clearEnabled = false,
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Analog sticks", style = MaterialTheme.typography.titleSmall)
        RemapSwitchRow(
            label = "Invert left stick Y",
            checked = mapping.invertLeftStickY,
            onChange = { persist(mapping.copy(invertLeftStickY = it)) },
        )
        RemapSwitchRow(
            label = "Invert right stick Y",
            checked = mapping.invertRightStickY,
            onChange = { persist(mapping.copy(invertRightStickY = it)) },
        )
    }
}

private val DPAD_SWITCH_BUTTONS = setOf(
    SwitchButton.DpadUp,
    SwitchButton.DpadDown,
    SwitchButton.DpadLeft,
    SwitchButton.DpadRight,
)

@Composable
private fun MappingRow(
    switchLabel: String,
    boundKeyLabel: String,
    isListening: Boolean,
    onBind: () -> Unit,
    onClear: () -> Unit,
    bindEnabled: Boolean = true,
    clearEnabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (bindEnabled) Modifier.clickable(onClick = onBind) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(switchLabel, style = MaterialTheme.typography.bodyLarge)
            Text(
                boundKeyLabel,
                style = MaterialTheme.typography.bodySmall,
                color = if (isListening) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (clearEnabled) {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Clear, contentDescription = "Clear binding")
            }
        }
    }
}

@Composable
private fun RemapSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
