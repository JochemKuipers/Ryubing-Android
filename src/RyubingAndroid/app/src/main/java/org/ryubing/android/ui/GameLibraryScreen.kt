package org.ryubing.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ryubing.android.R
import org.ryubing.android.data.GameEntry
import org.ryubing.android.data.GameRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameLibraryScreen(
    repository: GameRepository,
    onOpenSettings: () -> Unit,
    onOpenDrivers: () -> Unit,
    onPlay: (GameEntry) -> Unit,
) {
    var games by remember { mutableStateOf(repository.scanGames()) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            repository.addFolder(uri)
            games = repository.scanGames()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onClick = onOpenDrivers) {
                        Icon(Icons.Filled.Memory, contentDescription = stringResource(R.string.drivers_title))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { folderPicker.launch(null) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_games_folder))
            }
        },
    ) { padding ->
        if (games.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_games))
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(games) { game ->
                    Card(Modifier.fillMaxSize().clickable { onPlay(game) }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(game.title)
                            Text("${game.sizeBytes / (1024 * 1024)} MiB")
                        }
                    }
                }
            }
        }
    }
}
