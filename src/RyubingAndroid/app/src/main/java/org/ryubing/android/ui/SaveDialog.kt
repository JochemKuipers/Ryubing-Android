package org.ryubing.android.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ryubing.android.data.SaveInfo
import org.ryubing.android.data.SaveStore
import org.ryubing.android.emu.EmulationSession

@Composable
fun SaveDialog(
    titleId: String,
    gameTitle: String,
    appDataPath: String,
    session: EmulationSession,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(appDataPath, titleId) { SaveStore(appDataPath, titleId) }
    var saveId by remember { mutableStateOf<String?>(null) }
    var save by remember { mutableStateOf<SaveInfo?>(null) }
    var busy by remember { mutableStateOf(true) }
    var confirmClear by remember { mutableStateOf(false) }

    fun fail(error: Throwable) {
        busy = false
        Toast.makeText(context, error.message ?: "Save operation failed", Toast.LENGTH_LONG).show()
    }

    LaunchedEffect(titleId) {
        runCatching {
            saveId = withContext(Dispatchers.IO) { session.findUserSaveId(titleId) }
            save = withContext(Dispatchers.IO) { store.find(saveId) }
        }.onFailure(::fail)
        busy = false
    }

    val backupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val current = save
        if (uri == null || current == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: error("Cannot create backup")
                    output.use { store.backup(current, it) }
                }
            }.onSuccess {
                busy = false
                Toast.makeText(context, "Save backed up", Toast.LENGTH_SHORT).show()
            }.onFailure(::fail)
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open backup")
                    input.use { store.restore(it, saveId) }
                }
            }.onSuccess { restored ->
                saveId = restored.directory.name
                save = restored
                busy = false
                Toast.makeText(context, "Save restored", Toast.LENGTH_SHORT).show()
            }.onFailure(::fail)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Saves for $gameTitle") },
        text = {
            Column {
                when {
                    busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp))
                        Text("Working…")
                    }
                    save == null -> Text("No saves for this game yet. Play once to create one before restoring a backup.")
                    else -> {
                        Text("Account save", style = MaterialTheme.typography.titleSmall)
                        Text(
                            save!!.directory.absolutePath,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(formatBytes(save!!.sizeBytes), Modifier.padding(top = 4.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Button(
                        enabled = !busy && save != null,
                        onClick = { backupPicker.launch("${safeFileName(gameTitle)} - save.zip") },
                        modifier = Modifier.weight(1f),
                    ) { Text("Backup") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { restorePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    ) { Text("Restore") }
                }
                if (save != null) {
                    TextButton(enabled = !busy, onClick = { confirmClear = true }) { Text("Clear save") }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Done") } },
    )

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear save data?") },
            text = { Text("This permanently deletes this game's account save. Back it up first if you may need it later.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val current = save ?: return@TextButton
                        confirmClear = false
                        busy = true
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { store.clear(current) } }
                                .onSuccess {
                                    save = store.find(saveId)
                                    busy = false
                                    Toast.makeText(context, "Save cleared", Toast.LENGTH_SHORT).show()
                                }
                                .onFailure(::fail)
                        }
                    },
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GiB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun safeFileName(value: String): String =
    value.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "Ryubing" }