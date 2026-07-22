package org.ryubing.android.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ryubing.android.R
import org.ryubing.android.data.ContentAutoloader
import org.ryubing.android.data.GameEntry
import org.ryubing.android.data.GameRepository
import org.ryubing.android.data.SettingsRepository
import org.ryubing.android.emu.EmulationSession

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GameLibraryScreen(
    repository: GameRepository,
    settingsRepository: SettingsRepository,
    session: EmulationSession,
    appDataPath: String,
    onOpenSettings: () -> Unit,
    onOpenDrivers: () -> Unit,
    onPlay: (GameEntry) -> Unit,
) {
    var games by remember { mutableStateOf(repository.scanGames()) }
    var menuFor by remember { mutableStateOf<GameEntry?>(null) }
    var manageUpdatesFor by remember { mutableStateOf<GameEntry?>(null) }
    var manageDlcFor by remember { mutableStateOf<GameEntry?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun refreshLibrary() {
        scope.launch {
            val scanned = withContext(Dispatchers.IO) { repository.scanGames() }
            games = scanned
            val settings = settingsRepository.load()
            withContext(Dispatchers.IO) {
                try {
                    session.initialize()
                    val enriched = repository.enrichGames(scanned, session)
                    withContext(Dispatchers.Main) { games = enriched }
                    if (settings.updatesFolderUri.isNotBlank()) {
                        ContentAutoloader(context, appDataPath, session)
                            .autoload(settings.updatesFolderUri, enriched)
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshLibrary()
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            repository.addFolder(uri)
            refreshLibrary()
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
                items(games, key = { it.uri.toString() }) { game ->
                    Box {
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onPlay(game) },
                                    onLongClick = { menuFor = game },
                                ),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(game.title)
                                val subtitle = buildString {
                                    append("${game.sizeBytes / (1024 * 1024)} MiB")
                                    if (game.titleId.isNotBlank()) {
                                        append(" · ")
                                        append(game.titleId)
                                    }
                                    if (game.version.isNotBlank() && game.version != "0") {
                                        append(" · v")
                                        append(game.version)
                                    }
                                }
                                Text(subtitle)
                            }
                        }
                        Box(Modifier.align(Alignment.TopEnd)) {
                            IconButton(onClick = { menuFor = game }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Game options")
                            }
                            DropdownMenu(
                                expanded = menuFor?.uri == game.uri,
                                onDismissRequest = { menuFor = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Manage Updates") },
                                    onClick = {
                                        menuFor = null
                                        if (game.titleId.isBlank()) {
                                            Toast.makeText(
                                                context,
                                                "Title ID unknown — install keys and refresh",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        } else {
                                            manageUpdatesFor = game
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Manage DLC") },
                                    onClick = {
                                        menuFor = null
                                        if (game.titleId.isBlank()) {
                                            Toast.makeText(
                                                context,
                                                "Title ID unknown — install keys and refresh",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        } else {
                                            manageDlcFor = game
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    manageUpdatesFor?.let { game ->
        TitleUpdateDialog(
            titleId = game.titleId,
            gameTitle = game.title,
            appDataPath = appDataPath,
            session = session,
            onDismiss = { manageUpdatesFor = null },
        )
    }

    manageDlcFor?.let { game ->
        DlcDialog(
            titleId = game.titleId,
            gameTitle = game.title,
            appDataPath = appDataPath,
            session = session,
            onDismiss = { manageDlcFor = null },
        )
    }
}
