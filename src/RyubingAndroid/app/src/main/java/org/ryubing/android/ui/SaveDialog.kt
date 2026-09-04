package org.ryubing.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ryubing.android.data.ExtractedSaveArchive
import org.ryubing.android.data.SaveArchiveKind
import org.ryubing.android.data.SaveInfo
import org.ryubing.android.data.SaveStore
import org.ryubing.android.emu.EmulationSession

@Composable
fun SaveDialog(
    titleId: String,
    gameTitle: String,
    appDataPath: String,
    session: EmulationSession,
    titleNamesById: Map<String, String> = emptyMap(),
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(appDataPath, titleId) { SaveStore(appDataPath, titleId) }
    var saveId by remember { mutableStateOf<String?>(null) }
    var save by remember { mutableStateOf<SaveInfo?>(null) }
    var busy by remember { mutableStateOf(true) }
    var confirmClear by remember { mutableStateOf(false) }
    var pendingEden by remember { mutableStateOf<ExtractedSaveArchive?>(null) }
    var selectedEdenTitleId by remember { mutableStateOf<String?>(null) }

    fun fail(error: Throwable) {
        busy = false
        Toast.makeText(context, error.message ?: "Save operation failed", Toast.LENGTH_LONG).show()
    }

    fun refreshSave(id: String?) {
        saveId = id
        save = store.find(id)
    }

    LaunchedEffect(titleId) {
        runCatching {
            val id = withContext(Dispatchers.IO) { session.ensureUserSaveId(titleId) }
            withContext(Dispatchers.IO) { refreshSave(id) }
        }.onFailure(::fail)
        busy = false
    }

    fun finishRestore(extracted: ExtractedSaveArchive, edenTitleId: String?) {
        busy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val id = session.ensureUserSaveId(titleId)
                        ?: error("Could not create a save slot for this game")
                    store.restoreExtracted(extracted, id, edenTitleId)
                }
            }.onSuccess { restored ->
                refreshSave(restored.directory.name)
                busy = false
                Toast.makeText(context, "Save restored", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                store.discard(extracted)
                fail(error)
            }
        }
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
                    input.use { store.extractAndInspect(it) }
                }
            }.onSuccess { extracted ->
                when (val kind = extracted.kind) {
                    is SaveArchiveKind.Ryubing -> finishRestore(extracted, null)
                    is SaveArchiveKind.Eden -> {
                        val match = kind.titleIds.firstOrNull { it.equals(titleId, ignoreCase = true) }
                        when {
                            kind.titleIds.size == 1 -> finishRestore(extracted, kind.titleIds.single())
                            match != null && kind.titleIds.size > 1 -> {
                                selectedEdenTitleId = match
                                pendingEden = extracted
                                busy = false
                            }
                            kind.titleIds.size > 1 -> {
                                selectedEdenTitleId = kind.titleIds.first()
                                pendingEden = extracted
                                busy = false
                            }
                            else -> {
                                store.discard(extracted)
                                fail(IllegalStateException("This backup belongs to a different game"))
                            }
                        }
                    }
                    SaveArchiveKind.Unknown -> {
                        store.discard(extracted)
                        fail(IllegalStateException("Unrecognized save archive"))
                    }
                }
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
                    save == null -> Text("Could not create a save slot for this game yet.")
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
                    TextButton(
                        enabled = !busy,
                        onClick = { openSaveFolder(context, save!!.directory) },
                    ) { Text("Open folder") }
                    TextButton(enabled = !busy, onClick = { confirmClear = true }) { Text("Clear save") }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Done") } },
    )

    pendingEden?.let { extracted ->
        val kind = extracted.kind as? SaveArchiveKind.Eden ?: return@let
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    store.discard(extracted)
                    pendingEden = null
                }
            },
            title = { Text("Select game save") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "This Eden export contains multiple games. Choose which save to restore into $gameTitle.",
                        Modifier.padding(bottom = 8.dp),
                    )
                    kind.titleIds.forEach { id ->
                        val label = titleNamesById.entries
                            .firstOrNull { it.key.equals(id, ignoreCase = true) }
                            ?.value
                            ?.let { "$it ($id)" }
                            ?: id
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedEdenTitleId = id }
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(
                                selected = selectedEdenTitleId.equals(id, ignoreCase = true),
                                onClick = { selectedEdenTitleId = id },
                            )
                            Text(label, Modifier.padding(start = 4.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && selectedEdenTitleId != null,
                    onClick = {
                        val chosen = selectedEdenTitleId ?: return@TextButton
                        pendingEden = null
                        finishRestore(extracted, chosen)
                    },
                ) { Text("Restore") }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        store.discard(extracted)
                        pendingEden = null
                    },
                ) { Text("Cancel") }
            },
        )
    }

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
                                    refreshSave(saveId)
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

/** Opens the committed save folder (same target as desktop Open User Save Directory). */
private fun openSaveFolder(context: Context, saveRoot: File) {
    val target = resolveOpenTarget(saveRoot)
    if (!target.exists() && !target.mkdirs()) {
        Toast.makeText(context, "Could not create save folder", Toast.LENGTH_LONG).show()
        return
    }

    val intents = buildList {
        documentsUriFor(target)?.let { uri ->
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
        runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", target)
        }.getOrNull()?.let { uri ->
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    for (intent in intents) {
        try {
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(Intent.createChooser(intent, null))
                return
            }
        } catch (_: Exception) {
            // try the next candidate
        }
    }

    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("Save folder", target.absolutePath))
    Toast.makeText(
        context,
        "Path copied to clipboard:\n${target.absolutePath}",
        Toast.LENGTH_LONG,
    ).show()
}

private fun resolveOpenTarget(saveRoot: File): File {
    val committed = File(saveRoot, "0")
    val working = File(saveRoot, "1")
    return when {
        committed.isDirectory -> committed
        working.isDirectory -> working
        else -> committed
    }
}

private fun documentsUriFor(file: File): Uri? {
    val path = file.absolutePath
    val prefixes = listOf(
        "/storage/emulated/0/" to "primary",
        "/sdcard/" to "primary",
    )
    for ((prefix, volume) in prefixes) {
        if (!path.startsWith(prefix)) continue
        val rel = path.removePrefix(prefix)
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "$volume:$rel",
        )
    }
    return null
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GiB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun safeFileName(value: String): String =
    value.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "Ryubing" }
