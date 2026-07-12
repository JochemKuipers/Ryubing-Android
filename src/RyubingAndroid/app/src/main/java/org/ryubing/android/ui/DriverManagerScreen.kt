package org.ryubing.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ryubing.android.R
import org.ryubing.android.data.DriverRepository

/**
 * Custom Vulkan (Turnip) driver management. Imported .zip drivers are copied into app
 * storage and the active selection is persisted across restarts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverManagerScreen(
    repository: DriverRepository,
    onBack: () -> Unit,
) {
    val systemDriverLabel = stringResource(R.string.system_driver)
    var drivers by remember(repository, systemDriverLabel) {
        mutableStateOf(repository.loadDrivers(systemDriverLabel))
    }
    var selectedId by remember(repository) {
        mutableStateOf(repository.loadSelectedId())
    }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val driverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            statusMessage = null
            try {
                val imported = withContext(Dispatchers.IO) {
                    repository.importDriver(uri)
                }
                drivers = repository.loadDrivers(systemDriverLabel)
                selectedId = imported.id
                repository.saveSelectedId(imported.id)
                statusMessage = "Imported ${imported.displayName}"
            } catch (e: Exception) {
                statusMessage = "Import failed: ${e.message ?: "unknown error"}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drivers_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { driverPicker.launch(arrayOf("application/zip")) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.import_driver))
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            statusMessage?.let { Text(it) }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(drivers, key = { it.id }) { driver ->
                    Card(Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.layout.Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = driver.id == selectedId,
                                    onClick = {
                                        selectedId = driver.id
                                        repository.saveSelectedId(driver.id)
                                    },
                                )
                                .padding(16.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = driver.id == selectedId,
                                onClick = {
                                    selectedId = driver.id
                                    repository.saveSelectedId(driver.id)
                                },
                            )
                            Text(driver.displayName, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        }
    }
}
