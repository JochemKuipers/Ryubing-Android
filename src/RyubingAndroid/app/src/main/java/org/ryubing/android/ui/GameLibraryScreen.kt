package org.ryubing.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ryubing.android.R
import org.ryubing.android.data.ContentFileStore
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
    systemDriverCrashed: Boolean,
    onOpenSettings: () -> Unit,
    onOpenDrivers: () -> Unit,
    onPlay: (GameEntry) -> Unit,
) {
    var games by remember { mutableStateOf(repository.scanGames()) }
    var menuFor by remember { mutableStateOf<GameEntry?>(null) }
    var manageUpdatesFor by remember { mutableStateOf<GameEntry?>(null) }
    var manageDlcFor by remember { mutableStateOf<GameEntry?>(null) }
    var manageModsFor by remember { mutableStateOf<GameEntry?>(null) }
    var manageSavesFor by remember { mutableStateOf<GameEntry?>(null) }
    var showDriverCrash by remember { mutableStateOf(systemDriverCrashed) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun withTitleId(game: GameEntry, action: () -> Unit) {
        menuFor = null
        if (game.titleId.isBlank()) {
            Toast.makeText(
                context,
                "Title ID unknown — install keys and refresh",
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            action()
        }
    }

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
                        if (enriched.none { it.titleId.isNotBlank() }) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    "Autoload skipped — no title IDs (install keys and refresh)",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        } else {
                            val (updates, dlc) = ContentAutoloader(context, appDataPath, session)
                                .autoload(settings.updatesFolderUri, enriched)
                            if (updates > 0 || dlc > 0) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        "Autoloaded $updates update(s), $dlc DLC",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    }

                    // Best-effort: move update/DLC content registered on shared (FUSE)
                    // storage into app-private storage so the core reads it without
                    // per-page FUSE overhead during play.
                    enriched.forEach { game ->
                        if (game.titleId.isBlank()) return@forEach
                        runCatching { ContentFileStore.localizeRegisteredContent(appDataPath, game.titleId) }
                            .onFailure {
                                android.util.Log.w("GameLibrary", "Content migration failed for ${game.title}", it)
                            }
                    }

                    // Publish update/DLC counts first (no native probing) so autoloaded
                    // content appears with the toast; then refine with probed versions.
                    val counted = repository.applyContentMetadata(enriched, appDataPath, session = null)
                    withContext(Dispatchers.Main) { games = counted }

                    val detailed = repository.applyContentMetadata(enriched, appDataPath, session)
                    withContext(Dispatchers.Main) { games = detailed }
                } catch (e: Throwable) {
                    android.util.Log.e("GameLibrary", "Library refresh failed", e)
                }
            }
        }
    }

    fun refreshContentDetails() {
        scope.launch {
            val detailed = withContext(Dispatchers.IO) {
                repository.applyContentMetadata(games, appDataPath, session)
            }
            games = detailed
        }
    }

    LaunchedEffect(Unit) {
        refreshLibrary()
    }

    // All Files Access: the emulator core re-opens SAF fds by path (/proc/self/fd/N),
    // which Android 14+'s FUSE daemon only allows for shared-storage files when the app
    // holds All Files Access. Re-check on every resume so granting it from Settings
    // immediately hides the banner and re-scans the library.
    var allFilesAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = Environment.isExternalStorageManager()
                if (granted != allFilesAccess) {
                    allFilesAccess = granted
                    if (granted) refreshLibrary()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!allFilesAccess) {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "All files access required",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "On Android 14+ the emulator can only read games from shared " +
                                "storage (e.g. Download/roms) with All Files Access. " +
                                "Grant it, then return to this screen.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                        Button(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                )
                            },
                        ) {
                            Text("Grant access")
                        }
                    }
                }
            }
            if (games.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_games))
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f).padding(12.dp),
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
                                    if (game.titleId.isNotBlank()) {
                                        append(game.titleId)
                                    }
                                    if (game.version.isNotBlank() && game.version != "0") {
                                        if (isNotEmpty()) append(" · ")
                                        append("v")
                                        append(game.version)
                                        if (game.hasSelectedUpdate) append(" (update)")
                                    }
                                    if (game.updateCount > 0) {
                                        if (isNotEmpty()) append(" · ")
                                        append(game.updateCount)
                                        append(if (game.updateCount == 1) " update" else " updates")
                                    }
                                    if (game.dlcCount > 0) {
                                        if (isNotEmpty()) append(" · ")
                                        append(game.dlcCount)
                                        append(" DLC")
                                    }
                                    if (isEmpty()) {
                                        append("${game.sizeBytes / (1024 * 1024)} MiB")
                                    } else {
                                        append(" · ")
                                        append("${game.sizeBytes / (1024 * 1024)} MiB")
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
                                    onClick = { withTitleId(game) { manageUpdatesFor = game } },
                                )
                                DropdownMenuItem(
                                    text = { Text("Manage DLC") },
                                    onClick = { withTitleId(game) { manageDlcFor = game } },
                                )
                                DropdownMenuItem(
                                    text = { Text("Manage Mods") },
                                    onClick = { withTitleId(game) { manageModsFor = game } },
                                )
                                DropdownMenuItem(
                                    text = { Text("Manage Saves") },
                                    onClick = { withTitleId(game) { manageSavesFor = game } },
                                )
                            }
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
            onDismiss = {
                manageUpdatesFor = null
                refreshContentDetails()
            },
        )
    }

    manageDlcFor?.let { game ->
        DlcDialog(
            titleId = game.titleId,
            gameTitle = game.title,
            appDataPath = appDataPath,
            session = session,
            onDismiss = {
                manageDlcFor = null
                refreshContentDetails()
            },
        )
    }

    manageModsFor?.let { game ->
        ModDialog(
            titleId = game.titleId,
            gameTitle = game.title,
            appDataPath = appDataPath,
            onDismiss = { manageModsFor = null },
        )
    }

    manageSavesFor?.let { game ->
        SaveDialog(
            titleId = game.titleId,
            gameTitle = game.title,
            appDataPath = appDataPath,
            session = session,
            onDismiss = { manageSavesFor = null },
        )
    }

    if (showDriverCrash) {
        AlertDialog(
            onDismissRequest = { showDriverCrash = false },
            title = { Text("System GPU driver crashed") },
            text = {
                Text(
                    "The Qualcomm system driver crashed while compiling a shader. " +
                        "Use a device-specific Turnip driver for better compatibility.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDriverCrash = false
                        onOpenDrivers()
                    },
                ) { Text("Open GPU drivers") }
            },
            dismissButton = { TextButton(onClick = { showDriverCrash = false }) { Text("Dismiss") } },
        )
    }
}
