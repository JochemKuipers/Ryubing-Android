package org.ryubing.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ryubing.android.R
import org.ryubing.android.data.DriverRelease
import org.ryubing.android.data.DriverReleaseAsset
import org.ryubing.android.data.DriverRepoClient
import org.ryubing.android.data.DriverRepoGroup
import org.ryubing.android.data.DriverRepository
import org.ryubing.android.data.GpuDriver
import java.io.File

/** Installed drivers list + local zip import (embedded in Settings). */
@Composable
fun DriversPanel(
    repository: DriverRepository,
    onOpenFetcher: () -> Unit,
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
                val wiped = repository.saveSelectedId(imported.id)
                statusMessage = if (wiped) {
                    "Imported ${imported.displayName}. Shader caches cleared."
                } else {
                    "Imported ${imported.displayName}"
                }
            } catch (e: Exception) {
                statusMessage = "Import failed: ${e.message ?: "unknown error"}"
            }
        }
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Select the Vulkan driver used at game launch. Custom Turnip packages " +
                "are for Adreno GPUs; other GPUs should stay on System. " +
                "Changing driver clears all game shader caches.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = onOpenFetcher,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Download from repository")
        }
        OutlinedButton(
            onClick = { driverPicker.launch(arrayOf("application/zip")) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.import_driver))
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        drivers.forEach { driver ->
            DriverSelectRow(
                driver = driver,
                selected = driver.id == selectedId,
                onSelect = {
                    if (driver.id != selectedId) {
                        selectedId = driver.id
                        repository.saveSelectedId(driver.id)
                        statusMessage = "Selected ${driver.displayName}. Shader caches cleared."
                    }
                },
                onDelete = if (driver.isSystem) {
                    null
                } else {
                    {
                        val wasSelected = selectedId == driver.id
                        repository.deleteDriver(driver.id)
                        drivers = repository.loadDrivers(systemDriverLabel)
                        selectedId = repository.loadSelectedId()
                        statusMessage = if (wasSelected) {
                            "Deleted ${driver.displayName}. Shader caches cleared."
                        } else {
                            "Deleted ${driver.displayName}"
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun DriverSelectRow(
    driver: GpuDriver,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(driver.displayName, Modifier.weight(1f).padding(start = 8.dp))
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete driver")
            }
        }
    }
}

/** GitHub release browser for common Turnip/Adreno driver repos. */
@Composable
fun DriverFetcherPanel(
    repository: DriverRepository,
    onInstalled: () -> Unit,
) {
    val context = LocalContext.current
    val systemDriverLabel = stringResource(R.string.system_driver)
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<DriverRepoGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var downloadingKey by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var expandedRepos by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedReleases by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun refresh() {
        scope.launch {
            loading = true
            statusMessage = null
            val fetched = withContext(Dispatchers.IO) {
                DriverRepoClient.DEFAULT_REPOS.map { repo ->
                    async { DriverRepoClient.fetchGroup(repo) }
                }.awaitAll()
            }
            groups = fetched
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun download(asset: DriverReleaseAsset) {
        val key = asset.downloadUrl
        scope.launch {
            downloadingKey = key
            statusMessage = "Downloading ${asset.name}…"
            try {
                val dest = File(context.cacheDir, "driver_dl_${System.currentTimeMillis()}.zip")
                val imported = withContext(Dispatchers.IO) {
                    DriverRepoClient.downloadAsset(asset.downloadUrl, dest)
                    try {
                        repository.importDriver(dest)
                    } finally {
                        dest.delete()
                    }
                }
                val wiped = repository.saveSelectedId(imported.id)
                statusMessage = if (wiped) {
                    "Installed ${imported.displayName}. Shader caches cleared."
                } else {
                    "Installed ${imported.displayName}"
                }
                onInstalled()
                repository.loadDrivers(systemDriverLabel)
            } catch (e: Exception) {
                statusMessage = "Download failed: ${e.message ?: "unknown error"}"
            } finally {
                downloadingKey = null
            }
        }
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Browse public GitHub releases for Turnip / AdrenoTools driver packages. " +
                "Downloaded zips are installed and selected automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(
            onClick = { refresh() },
            enabled = !loading && downloadingKey == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Refresh")
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        if (loading) {
            Row(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            groups.forEach { group ->
                RepoGroupBlock(
                    group = group,
                    expanded = group.repo.path in expandedRepos,
                    expandedReleases = expandedReleases,
                    downloadingKey = downloadingKey,
                    onToggleRepo = {
                        expandedRepos = if (group.repo.path in expandedRepos) {
                            expandedRepos - group.repo.path
                        } else {
                            expandedRepos + group.repo.path
                        }
                    },
                    onToggleRelease = { releaseKey ->
                        expandedReleases = if (releaseKey in expandedReleases) {
                            expandedReleases - releaseKey
                        } else {
                            expandedReleases + releaseKey
                        }
                    },
                    onDownload = ::download,
                )
            }
        }
    }
}

@Composable
private fun RepoGroupBlock(
    group: DriverRepoGroup,
    expanded: Boolean,
    expandedReleases: Set<String>,
    downloadingKey: String?,
    onToggleRepo: () -> Unit,
    onToggleRelease: (String) -> Unit,
    onDownload: (DriverReleaseAsset) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleRepo)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(group.repo.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    group.repo.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when {
                    group.error != null -> Text(
                        "Failed: ${group.error}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    group.releases.isEmpty() -> Text(
                        "No zip releases found",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> group.releases.forEach { release ->
                        ReleaseBlock(
                            release = release,
                            expanded = "${group.repo.path}/${release.tagName}" in expandedReleases,
                            downloadingKey = downloadingKey,
                            onToggle = {
                                onToggleRelease("${group.repo.path}/${release.tagName}")
                            },
                            onDownload = onDownload,
                        )
                    }
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ReleaseBlock(
    release: DriverRelease,
    expanded: Boolean,
    downloadingKey: String?,
    onToggle: () -> Unit,
    onDownload: (DriverReleaseAsset) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(release.title, style = MaterialTheme.typography.bodyLarge)
                val badges = buildList {
                    if (release.latest) add("latest")
                    if (release.prerelease) add("pre-release")
                }.joinToString(" · ")
                if (badges.isNotEmpty()) {
                    Text(
                        badges,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                release.assets.forEach { asset ->
                    val busy = downloadingKey == asset.downloadUrl
                    OutlinedButton(
                        onClick = { onDownload(asset) },
                        enabled = downloadingKey == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(asset.name)
                    }
                }
            }
        }
    }
}
