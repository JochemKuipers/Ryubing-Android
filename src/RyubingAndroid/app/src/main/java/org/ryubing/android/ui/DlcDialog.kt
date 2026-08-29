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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import org.json.JSONArray
import org.ryubing.android.data.ContentMetadataStore
import org.ryubing.android.data.ContentFileStore
import org.ryubing.android.data.DlcContainer
import org.ryubing.android.data.DlcNcaEntry
import org.ryubing.android.emu.EmulationSession
import java.io.File

private data class DlcRow(
    val containerPath: String,
    val ncaPath: String,
    val label: String,
    var enabled: Boolean,
)

@Composable
fun DlcDialog(
    titleId: String,
    gameTitle: String,
    appDataPath: String,
    session: EmulationSession,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var containers by remember {
        mutableStateOf(ContentMetadataStore.loadDlc(appDataPath, titleId))
    }
    val rows = remember {
        mutableStateListOf<DlcRow>().also { list ->
            list.addAll(flattenRows(containers))
        }
    }

    fun refreshRows(updated: MutableList<DlcContainer>) {
        containers = updated
        rows.clear()
        rows.addAll(flattenRows(updated))
    }

    fun persist(updated: MutableList<DlcContainer>) {
        // Sync enabled flags from rows back into containers.
        for (row in rows) {
            for (c in updated) {
                if (c.path != row.containerPath) continue
                for (n in c.dlcNcaList) {
                    if (n.path == row.ncaPath) {
                        n.isEnabled = row.enabled
                    }
                }
            }
        }
        ContentMetadataStore.saveDlc(appDataPath, titleId, updated)
        refreshRows(updated)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val name = DocumentFile.fromSingleUri(context, uri)?.name ?: "dlc.nsp"
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in setOf("nsp", "xci")) {
                Toast.makeText(context, "Select an .nsp or .xci DLC package", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val path = withContext(Dispatchers.IO) {
                ContentFileStore.copyUri(session, uri, appDataPath, titleId, "dlc", name)
            }
            if (containers.any { it.path == path }) {
                Toast.makeText(context, "Already added", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val json = withContext(Dispatchers.IO) {
                session.initialize()
                session.getDlcContentListJson(path, name, titleId)
            }
            if (json.isNullOrBlank()) {
                Toast.makeText(context, "No DLC content found for this title", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val probed = ContentMetadataStore.parseDlcArray(JSONArray(json))
            val updated = containers.toMutableList().also { it.addAll(probed) }
            ContentMetadataStore.saveDlc(appDataPath, titleId, updated)
            refreshRows(updated)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DLC for $gameTitle") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    if (rows.isEmpty()) {
                        Text("No DLC installed.", Modifier.padding(8.dp))
                    }
                    rows.forEachIndexed { index, row ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = row.enabled,
                                onCheckedChange = { checked ->
                                    rows[index] = row.copy(enabled = checked)
                                },
                            )
                            Text(row.label, Modifier.weight(1f).padding(start = 4.dp))
                            IconButton(
                                onClick = {
                                    val updated = containers
                                        .map { c ->
                                            if (c.path != row.containerPath) {
                                                c
                                            } else {
                                                c.copy(
                                                    dlcNcaList = c.dlcNcaList
                                                        .filterNot { it.path == row.ncaPath }
                                                        .toMutableList(),
                                                )
                                            }
                                        }
                                        .filter { it.dlcNcaList.isNotEmpty() }
                                        .toMutableList()
                                    ContentMetadataStore.saveDlc(appDataPath, titleId, updated)
                                    refreshRows(updated)
                                },
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    persist(containers.toMutableList())
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

private fun flattenRows(containers: List<DlcContainer>): List<DlcRow> {
    val result = mutableListOf<DlcRow>()
    for (c in containers) {
        val containerName = File(c.path).name
        for (n in c.dlcNcaList) {
            result += DlcRow(
                containerPath = c.path,
                ncaPath = n.path,
                label = containerName,
                enabled = n.isEnabled,
            )
        }
    }
    return result
}

private fun DlcContainer.copy(
    path: String = this.path,
    dlcNcaList: MutableList<DlcNcaEntry> = this.dlcNcaList,
) = DlcContainer(path = path, dlcNcaList = dlcNcaList)
