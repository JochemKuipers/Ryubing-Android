package org.ryubing.android.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ryubing.android.data.ModEntry
import org.ryubing.android.data.ModsStore

@Composable
fun ModDialog(
    titleId: String,
    gameTitle: String,
    appDataPath: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(appDataPath, titleId) { ModsStore(context, appDataPath, titleId) }
    var mods by remember { mutableStateOf(store.scan()) }
    var busy by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ModEntry?>(null) }

    fun runOperation(operation: () -> String) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(operation) }
            busy = false
            result.onSuccess { message ->
                mods = withContext(Dispatchers.IO) { store.scan() }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(context, error.message ?: "Mod operation failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: "Imported mod.zip"
        runOperation {
            val count = store.importZip(uri, name)
            "Imported $count mod${if (count == 1) "" else "s"}"
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runOperation {
            val count = store.importFolder(uri)
            "Imported $count mod${if (count == 1) "" else "s"}"
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Mods for $gameTitle") },
        text = {
            Column {
                Text(
                    "Mods apply the next time the game starts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Row(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { zipPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Import .zip") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    ) { Text("Import folder") }
                }
                if (busy) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp))
                        Text("Importing…")
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (mods.isEmpty() && !busy) {
                        Text("No mods yet. Import a .zip or folder to get started.", Modifier.padding(vertical = 20.dp))
                    }
                    mods.forEach { mod ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = mod.enabled,
                                enabled = !busy,
                                onCheckedChange = { enabled ->
                                    runOperation {
                                        store.setEnabled(mod, enabled)
                                        if (enabled) "${mod.name} enabled" else "${mod.name} disabled"
                                    }
                                },
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                                Text(mod.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    mod.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(enabled = !busy, onClick = { pendingDelete = mod }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete ${mod.name}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Done") } },
    )

    pendingDelete?.let { mod ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${mod.name}?") },
            text = { Text("This permanently removes the imported mod files.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        runOperation { store.remove(mod); "${mod.name} deleted" }
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}