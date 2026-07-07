package org.ryubing.android.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ryubing.android.R
import org.ryubing.android.data.SettingsRepository
import org.ryubing.android.emu.EmulationSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repository: SettingsRepository, session: EmulationSession, onBack: () -> Unit) {
    var config by remember { mutableStateOf(repository.load()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun update(newConfig: org.ryubing.android.data.EmulatorConfig) {
        config = newConfig
        repository.save(newConfig)
    }

    val keysPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { session.importProdKeys(uri) }
            Toast.makeText(
                context,
                if (ok) "prod.keys installed" else "Failed to install prod.keys",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val firmwarePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            Toast.makeText(context, "Installing firmware…", Toast.LENGTH_SHORT).show()
            val name = DocumentFile.fromSingleUri(context, uri)?.name ?: "firmware.zip"
            val ok = withContext(Dispatchers.IO) { session.installFirmware(uri, name) }
            Toast.makeText(
                context,
                if (ok) "Firmware installed" else "Failed to install firmware",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            SwitchRow("Docked mode", config.dockedMode) { update(config.copy(dockedMode = it)) }
            SwitchRow("Enable PPTC (experimental on mobile)", config.enablePptc) { update(config.copy(enablePptc = it)) }
            SwitchRow("Shader cache", config.enableShaderCache) { update(config.copy(enableShaderCache = it)) }

            Text(
                "Memory manager: ${memoryModeLabel(config.memoryManagerMode)}",
                Modifier.padding(vertical = 12.dp),
            )
            Text(
                "Resolution scale: ${config.resScale}x",
                Modifier.padding(bottom = 12.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("System", style = MaterialTheme.typography.titleMedium)
            Text(
                "Import decryption keys and a firmware package. Keys are required to load games.",
                Modifier.padding(top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { keysPicker.launch(arrayOf("*/*")) },
                Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Text("Install prod.keys")
            }
            Button(
                onClick = { firmwarePicker.launch(arrayOf("*/*")) },
                Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Install firmware (.zip / .xci)")
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun memoryModeLabel(mode: Int): String = when (mode) {
    0 -> "Software page table"
    1 -> "Host mapped"
    else -> "Host mapped (unsafe)"
}
