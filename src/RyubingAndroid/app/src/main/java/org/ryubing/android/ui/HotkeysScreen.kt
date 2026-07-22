package org.ryubing.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ryubing.android.data.GamepadHotkeyRepository
import org.ryubing.android.input.ControllerKeyCapture
import org.ryubing.android.input.GamepadHotkeyMapping
import org.ryubing.android.input.HotkeyAction
import org.ryubing.android.input.displayLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotkeysScreen(
    hotkeyRepository: GamepadHotkeyRepository,
    onBack: () -> Unit,
    onHotkeysChanged: () -> Unit,
) {
    var mapping by remember { mutableStateOf(hotkeyRepository.load()) }
    var listeningFor by remember { mutableStateOf<HotkeyAction?>(null) }

    DisposableEffect(Unit) {
        onDispose { ControllerKeyCapture.cancel() }
    }

    fun persist(updated: GamepadHotkeyMapping) {
        mapping = updated
        hotkeyRepository.save(updated)
        onHotkeysChanged()
    }

    fun startListening(action: HotkeyAction) {
        listeningFor = action
        ControllerKeyCapture.startBinding(
            captureCombos = true,
            onCaptured = { binding ->
                persist(mapping.withBinding(action, binding))
                listeningFor = null
            },
            onCancelled = { listeningFor = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hotkeys") },
                navigationIcon = {
                    IconButton(onClick = {
                        ControllerKeyCapture.cancel()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (listeningFor != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Hold a modifier (e.g. Select), then press the action button — " +
                            "or press & release one button for a single binding.\n" +
                            "Listening for “${listeningFor!!.displayLabel()}”…",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                Text(
                    "Tap an action to bind. Combos (Select + A, L3 + X, …) work on pads " +
                        "without extra buttons. Hotkeys are checked before Switch mappings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedButton(
                onClick = {
                    ControllerKeyCapture.cancel()
                    listeningFor = null
                    persist(hotkeyRepository.reset())
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Clear all hotkeys")
            }

            HorizontalDivider()

            HotkeyAction.entries.forEach { action ->
                val binding = mapping.bindingFor(action)
                HotkeyMappingRow(
                    label = action.displayLabel(),
                    boundKeyLabel = binding.label(),
                    isListening = listeningFor == action,
                    onBind = { startListening(action) },
                    onClear = {
                        ControllerKeyCapture.cancel()
                        listeningFor = null
                        persist(mapping.withoutBinding(action))
                    },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Turbo while held",
                    Modifier.weight(1f).padding(end = 12.dp),
                )
                Switch(
                    checked = mapping.turboModeWhileHeld,
                    onCheckedChange = { persist(mapping.copy(turboModeWhileHeld = it)) },
                )
            }
            Text(
                "When enabled, the Turbo Mode binding holds turbo while pressed instead of toggling.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HotkeyMappingRow(
    label: String,
    boundKeyLabel: String,
    isListening: Boolean,
    onBind: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onBind)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
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
        IconButton(onClick = onClear) {
            Icon(Icons.Default.Clear, contentDescription = "Clear binding")
        }
    }
}
