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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ryubing.android.R

/**
 * Custom Vulkan (Turnip) driver management. Imported .zip drivers are staged for the JNI
 * shim's adrenotools loader. The list always includes the built-in system driver.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverManagerScreen(onBack: () -> Unit) {
    val systemDriver = stringResource(R.string.system_driver)
    val drivers = remember { mutableStateListOf(systemDriver) }
    var selected by remember { mutableStateOf(systemDriver) }

    val driverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // TODO: unpack the driver zip into app storage and hand its path to the JNI shim
        //       via adrenotools when USE_ADRENOTOOLS is enabled. Tracked in docs.
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported driver"
            if (name !in drivers) drivers += name
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
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(drivers) { driver ->
                Card(Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.layout.Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = driver == selected, onClick = { selected = driver })
                            .padding(16.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = driver == selected, onClick = { selected = driver })
                        Text(driver, Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
}
