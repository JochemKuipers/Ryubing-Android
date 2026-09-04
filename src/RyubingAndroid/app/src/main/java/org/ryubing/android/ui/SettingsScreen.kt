package org.ryubing.android.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ryubing.android.R
import org.ryubing.android.ProcessRestarter
import org.ryubing.android.data.ControllerMappingRepository
import org.ryubing.android.data.DataFolderResolver
import org.ryubing.android.data.DriverRepository
import org.ryubing.android.data.EmulatorConfig
import org.ryubing.android.data.GamepadHotkeyRepository
import org.ryubing.android.data.SafPathResolver
import org.ryubing.android.data.SettingsRepository
import org.ryubing.android.emu.EmulationSession
import java.io.File
import kotlin.math.roundToInt

private enum class SettingsCategory(val title: String) {
    System("System"),
    Graphics("Graphics"),
    Audio("Audio"),
    Input("Input"),
    Hotkeys("Hotkeys"),
    Drivers("GPU Drivers"),
    Content("Content"),
    Keys("Keys"),
    Data("Data location"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    mappingRepository: ControllerMappingRepository,
    hotkeyRepository: GamepadHotkeyRepository,
    driverRepository: DriverRepository,
    session: EmulationSession,
    initialDrivers: Boolean = false,
    onMappingChanged: () -> Unit,
    onHotkeysChanged: () -> Unit,
    onBack: () -> Unit,
) {
    var config by remember { mutableStateOf(repository.load()) }
    var category by remember {
        mutableStateOf(if (initialDrivers) SettingsCategory.Drivers else null)
    }
    var showDriverFetcher by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun update(newConfig: EmulatorConfig) {
        config = newConfig
        repository.save(newConfig)
    }

    LaunchedEffect(category) {
        if (category != SettingsCategory.Drivers) showDriverFetcher = false
    }

    val keysPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { session.importProdKeys(uri) }
            Toast.makeText(
                context,
                if (ok) "prod.keys installed" else "Failed to install prod.keys",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val firmwarePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            Toast.makeText(context, "Installing firmware…", Toast.LENGTH_SHORT).show()
            val name = DocumentFile.fromSingleUri(context, uri)?.name ?: "firmware.zip"
            val ok = withContext(Dispatchers.IO) { session.installFirmware(uri, name) }
            Toast.makeText(
                context,
                if (ok) "Firmware installed" else "Failed to install firmware",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val updatesFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
        update(config.copy(updatesFolderUri = uri.toString()))
    }

    BackHandler(enabled = category != null) {
        if (category == SettingsCategory.Drivers && showDriverFetcher) {
            showDriverFetcher = false
        } else {
            category = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            category == SettingsCategory.Drivers && showDriverFetcher ->
                                "Download drivers"
                            else -> category?.title ?: stringResource(R.string.settings_title)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when {
                                category == SettingsCategory.Drivers && showDriverFetcher ->
                                    showDriverFetcher = false
                                category != null -> category = null
                                else -> onBack()
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            when (category) {
                null -> {
                    SettingsCategory.entries.forEachIndexed { index, cat ->
                        if (index > 0) HorizontalDivider()
                        CategoryRow(title = cat.title) { category = cat }
                    }
                }
                SettingsCategory.System -> SystemSettingsPage(config, ::update)
                SettingsCategory.Graphics -> GraphicsSettingsPage(config, ::update)
                SettingsCategory.Audio -> AudioSettingsPage(config, ::update)
                SettingsCategory.Input -> InputSettingsPage(
                    config = config,
                    update = ::update,
                    mappingRepository = mappingRepository,
                    onMappingChanged = onMappingChanged,
                )
                SettingsCategory.Hotkeys -> HotkeysPanel(
                    hotkeyRepository = hotkeyRepository,
                    onHotkeysChanged = onHotkeysChanged,
                )
                SettingsCategory.Drivers -> {
                    if (showDriverFetcher) {
                        DriverFetcherPanel(
                            repository = driverRepository,
                            onInstalled = { showDriverFetcher = false },
                        )
                    } else {
                        DriversPanel(
                            repository = driverRepository,
                            onOpenFetcher = { showDriverFetcher = true },
                        )
                    }
                }
                SettingsCategory.Content -> ContentSettingsPage(
                    config = config,
                    onUpdate = ::update,
                    onPickFolder = { updatesFolderPicker.launch(null) },
                )
                SettingsCategory.Keys -> KeysSettingsPage(
                    onInstallKeys = { keysPicker.launch(arrayOf("*/*")) },
                    onInstallFirmware = { firmwarePicker.launch(arrayOf("*/*")) },
                )
                SettingsCategory.Data -> DataLocationPage(config, ::update)
            }
        }
    }
}

@Composable
private fun CategoryRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SystemSettingsPage(config: EmulatorConfig, update: (EmulatorConfig) -> Unit) {
    DropdownRow(
        label = "Language",
        valueLabel = languageLabel(config.systemLanguage),
        options = LANGUAGE_OPTIONS,
        selected = config.systemLanguage,
        onSelect = { update(config.copy(systemLanguage = it)) },
    )
    DropdownRow(
        label = "Region",
        valueLabel = regionLabel(config.systemRegion),
        options = REGION_OPTIONS,
        selected = config.systemRegion,
        onSelect = { update(config.copy(systemRegion = it)) },
    )
    DropdownRow(
        label = "DRAM",
        valueLabel = dramLabel(config.memoryConfiguration),
        options = DRAM_OPTIONS,
        selected = config.memoryConfiguration,
        onSelect = { update(config.copy(memoryConfiguration = it)) },
    )
    DropdownRow(
        label = "Memory manager",
        valueLabel = memoryModeLabel(config.memoryManagerMode),
        options = MEMORY_MODE_OPTIONS,
        selected = config.memoryManagerMode,
        onSelect = { update(config.copy(memoryManagerMode = it)) },
    )
    SwitchRow(
        "Native Code Execution (NCE)",
        config.useNce,
    ) { update(config.copy(useNce = it)) }
    DropdownRow(
        label = "NCE debug log",
        valueLabel = nceDebugLabel(config.nceDebugLevel),
        options = NCE_DEBUG_OPTIONS,
        selected = config.nceDebugLevel,
        onSelect = { update(config.copy(nceDebugLevel = it)) },
    )
    SwitchRow("Docked mode", config.dockedMode) { update(config.copy(dockedMode = it)) }
    SwitchRow("Enable PPTC", config.enablePptc) { update(config.copy(enablePptc = it)) }
    SwitchRow("Low-power PPTC", config.enableLowPowerPtc) {
        update(config.copy(enableLowPowerPtc = it))
    }
    SwitchRow("FS integrity checks", config.enableFsIntegrity) {
        update(config.copy(enableFsIntegrity = it))
    }
    SwitchRow("Internet access", config.enableInternet) {
        update(config.copy(enableInternet = it))
    }
    SwitchRow("Ignore missing services", config.ignoreMissingServices) {
        update(config.copy(ignoreMissingServices = it))
    }
    SwitchRow("Match system time", config.matchSystemTime) {
        update(config.copy(matchSystemTime = it))
    }
    if (!config.matchSystemTime) {
        IntStepperRow(
            label = "Time offset (hours)",
            value = (config.systemTimeOffset / 3600L).toInt().coerceIn(-24, 24),
            range = -24..24,
            step = 1,
            onChange = { update(config.copy(systemTimeOffset = it.toLong() * 3600L)) },
        )
    }
    DropdownRow(
        label = "Time zone",
        valueLabel = config.timeZone,
        options = TIMEZONE_OPTIONS,
        selected = TIMEZONE_OPTIONS.indexOfFirst { it.second == config.timeZone }.coerceAtLeast(0),
        onSelect = { update(config.copy(timeZone = TIMEZONE_OPTIONS[it].second)) },
    )
    IntStepperRow(
        label = "Tick scalar (%)",
        value = config.tickScalar.toInt().coerceIn(50, 400),
        range = 50..400,
        step = 10,
        onChange = { update(config.copy(tickScalar = it.toLong())) },
    )
    SwitchRow("File logging", config.enableFileLog) {
        update(config.copy(enableFileLog = it))
    }
}

@Composable
private fun GraphicsSettingsPage(config: EmulatorConfig, update: (EmulatorConfig) -> Unit) {
    Text("In-game overlays", style = MaterialTheme.typography.titleSmall)
    SwitchRow("Performance HUD", config.showPerformanceHud) {
        update(config.copy(showPerformanceHud = it))
    }
    if (config.showPerformanceHud) {
        SwitchRow("FPS", config.hudShowFps) {
            update(config.copy(hudShowFps = it))
        }
        SwitchRow("Frame time", config.hudShowFrameTime) {
            update(config.copy(hudShowFrameTime = it))
        }
        SwitchRow("FIFO %", config.hudShowFifo) {
            update(config.copy(hudShowFifo = it))
        }
        SwitchRow("CPU backend (NCE/JIT)", config.hudShowCpuBackend) {
            update(config.copy(hudShowCpuBackend = it))
        }
        SwitchRow("Process memory", config.hudShowMemory) {
            update(config.copy(hudShowMemory = it))
        }
        SwitchRow("GPU backend", config.hudShowGpu) {
            update(config.copy(hudShowGpu = it))
        }
        SwitchRow("Presented frames", config.hudShowPresentedFrames) {
            update(config.copy(hudShowPresentedFrames = it))
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    DropdownRow(
        label = "Resolution scale",
        valueLabel = "${config.resScale}x",
        options = RES_SCALE_OPTIONS,
        selected = resScaleToIndex(config.resScale),
        onSelect = { update(config.copy(resScale = RES_SCALE_VALUES[it])) },
    )
    DropdownRow(
        label = "VSync",
        valueLabel = vsyncLabel(config.vsyncMode),
        options = VSYNC_OPTIONS,
        selected = config.vsyncMode,
        onSelect = { update(config.copy(vsyncMode = it)) },
    )
    SwitchRow("Custom VSync interval", config.enableCustomVSync) {
        update(config.copy(enableCustomVSync = it))
    }
    IntStepperRow(
        label = "Custom VSync Hz",
        value = config.customVSyncInterval,
        range = 30..240,
        step = 5,
        onChange = { update(config.copy(customVSyncInterval = it)) },
    )
    DropdownRow(
        label = "Aspect ratio",
        valueLabel = aspectLabel(config.aspectRatio),
        options = ASPECT_OPTIONS,
        selected = config.aspectRatio,
        onSelect = { update(config.copy(aspectRatio = it)) },
    )
    DropdownRow(
        label = "Anti-aliasing",
        valueLabel = aaLabel(config.antiAliasing),
        options = AA_OPTIONS,
        selected = config.antiAliasing,
        onSelect = { update(config.copy(antiAliasing = it)) },
    )
    DropdownRow(
        label = "Scaling filter",
        valueLabel = scalingFilterLabel(config.scalingFilter),
        options = SCALING_FILTER_OPTIONS,
        selected = config.scalingFilter,
        onSelect = { update(config.copy(scalingFilter = it)) },
    )
    SliderRow(
        label = "Scaling filter level: ${config.scalingFilterLevel}",
        value = config.scalingFilterLevel.toFloat(),
        range = 0f..100f,
        onChange = { update(config.copy(scalingFilterLevel = it.roundToInt())) },
    )
    DropdownRow(
        label = "Max anisotropy",
        valueLabel = anisotropyLabel(config.maxAnisotropy),
        options = ANISOTROPY_OPTIONS,
        selected = anisotropyToIndex(config.maxAnisotropy),
        onSelect = { update(config.copy(maxAnisotropy = ANISOTROPY_VALUES[it])) },
    )
    DropdownRow(
        label = "Backend threading",
        valueLabel = backendThreadingLabel(config.backendThreading),
        options = BACKEND_THREADING_OPTIONS,
        selected = config.backendThreading,
        onSelect = { update(config.copy(backendThreading = it)) },
    )
    SwitchRow("Shader cache", config.enableShaderCache) {
        update(config.copy(enableShaderCache = it))
    }
    SwitchRow("SPIR-V shader generation", config.enableSpirvCompilationOnVulkan) {
        update(config.copy(enableSpirvCompilationOnVulkan = it))
    }
    SwitchRow("Texture recompression", config.enableTextureRecompression) {
        update(config.copy(enableTextureRecompression = it))
    }
    SwitchRow("Macro HLE", config.enableMacroHle) {
        update(config.copy(enableMacroHle = it))
    }
    SwitchRow("Color space passthrough", config.enableColorSpacePassthrough) {
        update(config.copy(enableColorSpacePassthrough = it))
    }
}

@Composable
private fun AudioSettingsPage(config: EmulatorConfig, update: (EmulatorConfig) -> Unit) {
    SwitchRow("Mute", config.audioMuted) { update(config.copy(audioMuted = it)) }
    SliderRow(
        label = "Volume: ${(config.audioVolume * 100).roundToInt()}%",
        value = config.audioVolume,
        range = 0f..1f,
        onChange = { update(config.copy(audioVolume = it, audioMuted = false)) },
    )
}

@Composable
private fun InputSettingsPage(
    config: EmulatorConfig,
    update: (EmulatorConfig) -> Unit,
    mappingRepository: ControllerMappingRepository,
    onMappingChanged: () -> Unit,
) {
    ControllerRemapPanel(
        mappingRepository = mappingRepository,
        onMappingChanged = onMappingChanged,
    )
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text("Touch controls", style = MaterialTheme.typography.titleSmall)
    SwitchRow("On-screen touch controls", config.showTouchControls) {
        update(config.copy(showTouchControls = it))
    }
    if (config.showTouchControls) {
        SliderRow(
            label = "Touch overlay scale: ${(config.touchControlsScale * 100).roundToInt()}%",
            value = config.touchControlsScale,
            range = 0.5f..1.5f,
            onChange = { update(config.copy(touchControlsScale = it)) },
        )
        SliderRow(
            label = "Touch stick sensitivity: ${"%.2f".format(config.touchStickSensitivity)}",
            value = config.touchStickSensitivity,
            range = 0.25f..2f,
            onChange = { update(config.copy(touchStickSensitivity = it)) },
        )
        SliderRow(
            label = "Touch overlay opacity: ${(config.touchControlsOpacity * 100).roundToInt()}%",
            value = config.touchControlsOpacity,
            range = 0.15f..1f,
            onChange = { update(config.copy(touchControlsOpacity = it)) },
        )
        SwitchRow("Show right stick", config.showTouchRightStick) {
            update(config.copy(showTouchRightStick = it))
        }
        SwitchRow("Invert touch stick Y", config.touchInvertStickY) {
            update(config.copy(touchInvertStickY = it))
        }
        SwitchRow("Switch controller layout", config.useSwitchLayout) {
            update(config.copy(useSwitchLayout = it))
        }
    }
    SwitchRow("Motion sensor", config.enableMotion) {
        update(config.copy(enableMotion = it))
    }
    if (config.enableMotion) {
        SliderRow(
            label = "Motion sensitivity: ${"%.2f".format(config.motionSensitivity)}",
            value = config.motionSensitivity,
            range = 0.25f..2f,
            onChange = { update(config.copy(motionSensitivity = it)) },
        )
    }
}

@Composable
private fun ContentSettingsPage(
    config: EmulatorConfig,
    onUpdate: (EmulatorConfig) -> Unit,
    onPickFolder: () -> Unit,
) {
    Text(
        "Updates / DLC folder used for automatic discovery after library scans.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        if (config.updatesFolderUri.isBlank()) "No folder selected"
        else config.updatesFolderUri,
        Modifier.padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
    )
    Button(
        onClick = onPickFolder,
        Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Text("Pick Updates/DLC folder")
    }
    if (config.updatesFolderUri.isNotBlank()) {
        OutlinedButton(
            onClick = { onUpdate(config.copy(updatesFolderUri = "")) },
            Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Clear folder")
        }
    }
}

@Composable
private fun KeysSettingsPage(
    onInstallKeys: () -> Unit,
    onInstallFirmware: () -> Unit,
) {
    Text(
        "Import decryption keys and a firmware package. Keys are required to load games.",
        Modifier.padding(top = 4.dp, bottom = 8.dp),
        style = MaterialTheme.typography.bodySmall,
    )
    Button(
        onClick = onInstallKeys,
        Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Text("Install prod.keys")
    }
    Button(
        onClick = onInstallFirmware,
        Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Text("Install firmware (.zip / .xci)")
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun IntStepperRow(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("$label: $value", Modifier.weight(1f))
        OutlinedButton(onClick = { onChange((value - step).coerceIn(range)) }) { Text("−") }
        OutlinedButton(onClick = { onChange((value + step).coerceIn(range)) }) { Text("+") }
    }
}

@Composable
private fun DropdownRow(
    label: String,
    valueLabel: String,
    options: List<Pair<Int, String>>,
    @Suppress("UNUSED_PARAMETER") selected: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(valueLabel)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

private val LANGUAGE_OPTIONS = listOf(
    0 to "Japanese",
    1 to "American English",
    2 to "French",
    3 to "German",
    4 to "Italian",
    5 to "Spanish",
    6 to "Chinese",
    7 to "Korean",
    8 to "Dutch",
    9 to "Portuguese",
    10 to "Russian",
    11 to "Taiwanese",
    12 to "British English",
    13 to "Canadian French",
    14 to "Latin American Spanish",
    15 to "Simplified Chinese",
    16 to "Traditional Chinese",
    17 to "Brazilian Portuguese",
)

private val REGION_OPTIONS = listOf(
    0 to "Japan",
    1 to "USA",
    2 to "Europe",
    3 to "Australia",
    4 to "China",
    5 to "Korea",
    6 to "Taiwan",
)

private val DRAM_OPTIONS = listOf(
    0 to "4 GiB",
    1 to "6 GiB",
    2 to "8 GiB",
    3 to "12 GiB",
)

private val MEMORY_MODE_OPTIONS = listOf(
    0 to "Software page table",
    1 to "Host mapped",
    2 to "Host mapped (unsafe)",
)

private val NCE_DEBUG_OPTIONS = listOf(
    0 to "Off",
    1 to "Errors",
    2 to "Standard",
    3 to "Verbose",
)

private val TIMEZONE_OPTIONS = listOf(
    0 to "UTC",
    1 to "America/New_York",
    2 to "America/Los_Angeles",
    3 to "Europe/London",
    4 to "Europe/Paris",
    5 to "Europe/Berlin",
    6 to "Asia/Tokyo",
    7 to "Asia/Shanghai",
    8 to "Australia/Sydney",
)

private val RES_SCALE_VALUES = listOf(0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)
private val RES_SCALE_OPTIONS = RES_SCALE_VALUES.mapIndexed { i, v -> i to "${v}x" }

private val VSYNC_OPTIONS = listOf(
    0 to "Switch",
    1 to "Unbounded",
    2 to "Custom",
)

private val ASPECT_OPTIONS = listOf(
    0 to "Fixed 4:3",
    1 to "Fixed 16:9",
    2 to "Fixed 16:10",
    3 to "Fixed 21:9",
    4 to "Fixed 32:9",
    5 to "Stretch to window",
)

private val AA_OPTIONS = listOf(
    0 to "None",
    1 to "FXAA",
    2 to "SMAA Low",
    3 to "SMAA Medium",
    4 to "SMAA High",
    5 to "SMAA Ultra",
)

private val SCALING_FILTER_OPTIONS = listOf(
    0 to "Bilinear",
    1 to "Nearest",
    2 to "FSR",
)

private val ANISOTROPY_VALUES = listOf(-1f, 2f, 4f, 8f, 16f)
private val ANISOTROPY_OPTIONS = listOf(
    0 to "Auto",
    1 to "2x",
    2 to "4x",
    3 to "8x",
    4 to "16x",
)

private val BACKEND_THREADING_OPTIONS = listOf(
    0 to "Auto",
    1 to "Off",
    2 to "On",
)

private fun languageLabel(v: Int) = LANGUAGE_OPTIONS.firstOrNull { it.first == v }?.second ?: v.toString()
private fun regionLabel(v: Int) = REGION_OPTIONS.firstOrNull { it.first == v }?.second ?: v.toString()
private fun dramLabel(v: Int) = DRAM_OPTIONS.firstOrNull { it.first == v }?.second ?: v.toString()
private fun memoryModeLabel(v: Int) = MEMORY_MODE_OPTIONS.firstOrNull { it.first == v }?.second ?: v.toString()
private fun nceDebugLabel(v: Int) = NCE_DEBUG_OPTIONS.firstOrNull { it.first == v }?.second ?: v.toString()
private fun vsyncLabel(v: Int) = VSYNC_OPTIONS.firstOrNull { it.first == v }?.second ?: v.toString()
private fun aspectLabel(v: Int) = ASPECT_OPTIONS.firstOrNull { it.first == v }?.second ?: v.toString()
private fun aaLabel(v: Int) = AA_OPTIONS.firstOrNull { it.first == v }?.second ?: v.toString()
private fun scalingFilterLabel(v: Int) = SCALING_FILTER_OPTIONS.firstOrNull { it.first == v }?.second ?: v.toString()
private fun anisotropyLabel(v: Float) =
    ANISOTROPY_OPTIONS.getOrNull(anisotropyToIndex(v))?.second ?: v.toString()
private fun backendThreadingLabel(v: Int) =
    BACKEND_THREADING_OPTIONS.firstOrNull { it.first == v }?.second ?: v.toString()

private fun resScaleToIndex(scale: Float): Int {
    val idx = RES_SCALE_VALUES.indexOfFirst { it == scale }
    return if (idx >= 0) idx else RES_SCALE_VALUES.indexOf(1f).coerceAtLeast(0)
}

private fun anisotropyToIndex(value: Float): Int {
    val idx = ANISOTROPY_VALUES.indexOfFirst { it == value }
    return if (idx >= 0) idx else 0
}

// --- Data location ---

/**
 * Emulator data folder selection: internal filesDir (default), the Android/data external
 * files dir (a fixed location the system SAF picker cannot reach — Android 11+ hides
 * Android/data and refuses persistable grants there, hence the dedicated option), or any
 * custom folder chosen with the system folder picker (resolved to a real filesystem path
 * via SafPathResolver, since the core only reads raw paths). Changing the folder migrates
 * data (optional) and restarts the app so the core re-initializes against the new path.
 */
@Composable
private fun DataLocationPage(
    config: EmulatorConfig,
    update: (EmulatorConfig) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingMode by remember { mutableStateOf<Int?>(null) }
    var pendingCustomPath by remember { mutableStateOf("") }
    var migrating by remember { mutableStateOf(false) }

    val currentPath = DataFolderResolver.resolve(context, config)

    Text("Emulator data folder", style = MaterialTheme.typography.titleMedium)
    Text(
        "Keys, save data, mods, profiles and caches are stored here.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 8.dp),
    )
    Text(
        "Current: ${currentPath.absolutePath}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(bottom = 12.dp),
    )

    fun requestChange(mode: Int, customPath: String) {
        pendingMode = mode
        pendingCustomPath = customPath
    }

    val customFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            // Resolve the SAF tree URI to a real filesystem path; the emulator core only
            // reads raw paths. SAF-only locations (e.g. cloud roots) don't resolve — reject.
            val path = withContext(Dispatchers.IO) {
                SafPathResolver.resolve(context, uri)
            }
            if (path == null) {
                Toast.makeText(
                    context,
                    "That location can't be used — it has no filesystem path",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                requestChange(DataFolderResolver.MODE_CUSTOM, path)
            }
        }
    }

    RadioRow(
        label = "Internal storage (private)",
        description = context.filesDir.absolutePath,
        selected = config.dataFolderMode == DataFolderResolver.MODE_INTERNAL,
        onClick = { requestChange(DataFolderResolver.MODE_INTERNAL, "") },
    )
    RadioRow(
        label = "Android/data (browsable)",
        description = context.getExternalFilesDir(null)?.absolutePath ?: "Unavailable",
        selected = config.dataFolderMode == DataFolderResolver.MODE_ANDROID_DATA,
        onClick = { requestChange(DataFolderResolver.MODE_ANDROID_DATA, "") },
    )
    RadioRow(
        label = "Custom folder",
        description = if (
            config.dataFolderMode == DataFolderResolver.MODE_CUSTOM &&
            config.dataFolderCustomPath.isNotBlank()
        ) {
            config.dataFolderCustomPath
        } else {
            "Choose any folder on the device"
        },
        selected = config.dataFolderMode == DataFolderResolver.MODE_CUSTOM,
        onClick = { customFolderPicker.launch(null) },
    )

    val mode = pendingMode
    if (mode != null) {
        val newConfig = config.copy(dataFolderMode = mode, dataFolderCustomPath = pendingCustomPath)
        val targetPath = DataFolderResolver.resolve(context, newConfig)
        val canCopy = targetPath.absolutePath != currentPath.absolutePath &&
            !targetPath.absolutePath.startsWith(currentPath.absolutePath + File.separator)

        AlertDialog(
            onDismissRequest = { pendingMode = null },
            title = { Text("Change data folder?") },
            text = {
                Text(
                    "New location:\n${targetPath.absolutePath}\n\n" +
                        "The app will restart to apply it. Data in the old location is kept.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (canCopy) {
                            migrating = true
                            pendingMode = null
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { DataFolderResolver.migrateData(currentPath, targetPath) }
                                        .onFailure { android.util.Log.e("DataLocation", "Migration failed", it) }
                                    update(newConfig)
                                }
                                ProcessRestarter.restart(context)
                            }
                        } else {
                            pendingMode = null
                            update(newConfig)
                            ProcessRestarter.restart(context)
                        }
                    },
                ) { Text(if (canCopy) "Copy data & restart" else "Apply & restart") }
            },
            dismissButton = {
                if (canCopy) {
                    TextButton(
                        onClick = {
                            pendingMode = null
                            update(newConfig)
                            ProcessRestarter.restart(context)
                        },
                    ) { Text("Apply without copying") }
                } else {
                    TextButton(onClick = { pendingMode = null }) { Text("Cancel") }
                }
            },
        )
    }

    if (migrating) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Copying data…") },
            text = {
                Text("Copying keys, saves, mods and caches. Large shader caches can take several minutes.")
            },
        )
    }
}

@Composable
private fun RadioRow(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label)
            if (description.isNotBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
