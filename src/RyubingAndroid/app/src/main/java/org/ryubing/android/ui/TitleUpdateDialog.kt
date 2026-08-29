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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ryubing.android.data.ContentMetadataStore
import org.ryubing.android.data.ContentFileStore
import org.ryubing.android.data.TitleUpdateMetadata
import org.ryubing.android.emu.EmulationSession
import java.io.File

@Composable
fun TitleUpdateDialog(
    titleId: String,
    gameTitle: String,
    appDataPath: String,
    session: EmulationSession,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var metadata by remember {
        mutableStateOf(ContentMetadataStore.loadUpdates(appDataPath, titleId))
    }
    var selectedIndex by remember {
        mutableIntStateOf(
            metadata.paths.indexOf(metadata.selected).let { if (it < 0) 0 else it + 1 },
        )
    }

    fun persist(meta: TitleUpdateMetadata) {
        metadata = meta
        ContentMetadataStore.saveUpdates(appDataPath, titleId, meta)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val name = DocumentFile.fromSingleUri(context, uri)?.name ?: "update.nsp"
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in setOf("nsp", "xci")) {
                Toast.makeText(context, "Select an .nsp or .xci update", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val path = withContext(Dispatchers.IO) {
                ContentFileStore.copyUri(session, uri, appDataPath, titleId, "updates", name)
            }
            if (path in metadata.paths) {
                Toast.makeText(context, "Already added", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val updated = metadata.copy(paths = (metadata.paths + path).toMutableList())
            if (updated.selected.isEmpty()) updated.selected = path
            persist(updated)
            selectedIndex = updated.paths.size
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Updates for $gameTitle") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            if (selectedIndex <= 0) return@IconButton
                            val removeAt = selectedIndex - 1
                            val removed = metadata.paths.getOrNull(removeAt) ?: return@IconButton
                            val paths = metadata.paths.toMutableList().also { it.removeAt(removeAt) }
                            val selected = if (metadata.selected == removed) "" else metadata.selected
                            persist(TitleUpdateMetadata(selected = selected, paths = paths))
                            selectedIndex = 0
                        },
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove")
                    }
                    IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add")
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedIndex == 0, onClick = { selectedIndex = 0 })
                        Text("None", Modifier.padding(start = 4.dp))
                    }
                    metadata.paths.forEachIndexed { index, path ->
                        val itemIndex = index + 1
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedIndex == itemIndex,
                                onClick = { selectedIndex = itemIndex },
                            )
                            Text(File(path).name, Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val selected = if (selectedIndex <= 0) {
                        ""
                    } else {
                        metadata.paths.getOrNull(selectedIndex - 1).orEmpty()
                    }
                    persist(metadata.copy(selected = selected))
                    onDismiss()
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
