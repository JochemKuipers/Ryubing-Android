package org.ryubing.android.ui

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * In-app folder browser used for selecting the emulator data folder.
 *
 * Deliberately does NOT use the system SAF picker: since Android 11 that picker hides
 * Android/data and Android/obb and refuses persistable grants inside them (the "use this
 * folder" popup never appears). This app holds All Files Access, so plain java.io.File
 * browsing works everywhere — including inside Android/data.
 */
@Composable
fun FolderBrowserDialog(
    initialDir: File,
    onDismiss: () -> Unit,
    onSelect: (File) -> Unit,
) {
    var current by remember {
        mutableStateOf(initialDir.takeIf { it.isDirectory } ?: Environment.getExternalStorageDirectory())
    }
    var error by remember { mutableStateOf<String?>(null) }

    val entries = remember(current) {
        current.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList<File>()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose folder", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    current.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (current.parentFile != null) {
                    TextButton(
                        onClick = {
                            error = null
                            current = current.parentFile ?: current
                        },
                        modifier = Modifier.padding(0.dp),
                    ) { Text("↑ Up one level") }
                }
                if (error != null) {
                    Text(
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    items(entries, key = { it.absolutePath }) { dir ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (dir.listFiles() == null) {
                                        error = "Cannot read '${dir.name}'"
                                    } else {
                                        error = null
                                        current = dir
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(dir.name, Modifier.weight(1f))
                        }
                    }
                }
                if (entries.isEmpty() && error == null) {
                    Text("No subfolders.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(current) }) { Text("Select this folder") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}