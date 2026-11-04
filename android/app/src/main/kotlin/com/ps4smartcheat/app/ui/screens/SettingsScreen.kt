package com.ps4smartcheat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.ps4smartcheat.app.BuildConfig
import com.ps4smartcheat.app.data.AppContainer
import com.ps4smartcheat.app.data.AppSettings
import com.ps4smartcheat.app.data.ThemeMode
import com.ps4smartcheat.app.ui.appViewModel
import com.ps4smartcheat.app.ui.components.ConfirmDialog
import com.ps4smartcheat.app.ui.components.LabeledSwitch
import com.ps4smartcheat.app.ui.components.SectionCard
import com.ps4smartcheat.app.ui.components.Selector
import com.ps4smartcheat.core.scan.BackendPreference

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings
    val log = c.log.lines

    fun exportLog(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        val text = c.log.exportJsonLines()
        viewModelScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    resolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                }
                c.log.notify("Diagnostics log exported")
            } catch (e: Exception) {
                c.log.error("Log export failed: ${e.message}")
            }
        }
    }
    fun update(t: (AppSettings) -> AppSettings) = c.settings.update(t)
    fun clearLog() = c.log.clear()
    fun setFreezeInterval(ms: Long) { c.freezer.intervalMs = ms }
    val freezeInterval: Long get() = c.freezer.intervalMs
}

@Composable
private fun NumberField(label: String, value: Int, range: IntRange, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s.filter { it.isDigit() }.take(9)
            text.toIntOrNull()?.takeIf { it in range }?.let(onValue)
        },
        label = { Text("$label (${range.first}–${range.last})") },
        isError = text.toIntOrNull()?.let { it !in range } ?: true,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SettingsScreen() {
    val vm = appViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    val logLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/x-ndjson"),
    ) { uri -> if (uri != null) vm.exportLog(resolver, uri) }
    var confirmEditing by remember { mutableStateOf(false) }
    var confirmNonWritable by remember { mutableStateOf(false) }
    var confirmSystem by remember { mutableStateOf(false) }
    var confirmNonLocal by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Memory editing") {
            LabeledSwitch(
                "Enable memory editing", s.editingEnabled,
                { on -> if (on) confirmEditing = true else vm.update { it.copy(editingEnabled = false, allowSystemProcesses = false) } },
                "Off = read-only. Resets to off every time the app starts.",
                tag = "editingSwitch",
            )
            LabeledSwitch(
                "Allow writes to non-writable regions", s.allowNonWritableRegions,
                { on -> if (on) confirmNonWritable = true else vm.update { it.copy(allowNonWritableRegions = false) } },
                "Code/read-only patches. Each write still needs confirmation.", enabled = s.editingEnabled,
            )
            LabeledSwitch(
                "Allow writes to system processes", s.allowSystemProcesses,
                { on -> if (on) confirmSystem = true else vm.update { it.copy(allowSystemProcesses = false) } },
                "Dangerous: can crash the console. Resets on restart.", enabled = s.editingEnabled,
            )
            var freeze by remember { mutableStateOf(vm.freezeInterval.toInt()) }
            NumberField("Freeze interval ms", freeze, 50..10_000) { freeze = it; vm.setFreezeInterval(it.toLong()) }
        }
        SectionCard("Scan backend") {
            Selector("Backend", BackendPreference.entries, s.scanBackend, { it.label }, { b -> vm.update { it.copy(scanBackend = b) } })
            Text(
                "Automatic tries Turbo Scan (ps4debug-NG ≥ 1.3) → server-side scan (ps4debug-NG) → Android chunked scan, " +
                    "falling back when a backend is missing or cannot run a request. Float and unaligned scans always run on Android.",
                style = MaterialTheme.typography.bodySmall,
            )
            LabeledSwitch(
                "ps4debug-NG detection and auto-authentication", s.probeServerScan, { v -> vm.update { it.copy(probeServerScan = v) } },
                "On connect: CMD_BRANDING, then the CMD_PROC_AUTH challenge–response (re-done after reconnects) only if the payload identifies as ps4debug-NG.",
            )
            LabeledSwitch(
                "Turbo page-table aliasing reads", s.turboAliasing, { v -> vm.update { it.copy(turboAliasing = v) } },
                "ps4debug-NG option that maps target pages into the payload for faster reads. Off by default (more invasive); not verified on hardware.",
            )
        }
        SectionCard("Scanning") {
            LabeledSwitch("Include read-only regions", s.includeReadOnlyRegions, { v -> vm.update { it.copy(includeReadOnlyRegions = v) } })
            LabeledSwitch("Include executable regions", s.includeExecutableRegions, { v -> vm.update { it.copy(includeExecutableRegions = v) } })
            LabeledSwitch("Unaligned scan (slower)", s.unalignedScan, { v -> vm.update { it.copy(unalignedScan = v) } }, "Check every byte offset instead of natural alignment.")
            NumberField("Max results", s.maxResults, 1_000..5_000_000) { v -> vm.update { it.copy(maxResults = v) } }
            NumberField("Read chunk KiB", s.chunkKb, 4..4096) { v -> vm.update { it.copy(chunkKb = v) } }
            NumberField("Results per page", s.pageSize, 10..200) { v -> vm.update { it.copy(pageSize = v) } }
            NumberField("Skip regions larger than MiB (0 = no limit)", s.maxRegionMiB, 0..65_536) { v -> vm.update { it.copy(maxRegionMiB = v) } }
            NumberField("Live refresh interval ms", s.monitorIntervalMs, 250..10_000) { v -> vm.update { it.copy(monitorIntervalMs = v) } }
        }
        SectionCard("Game detection") {
            LabeledSwitch(
                "Auto-select the running game", s.autoSelectGame, { v -> vm.update { it.copy(autoSelectGame = v) } },
                "After connecting, and when the same game restarts, select it automatically (by title ID and path, not only the process name). Read-only.",
            )
        }
        SectionCard("Connection") {
            NumberField("Connect timeout ms", s.connectTimeoutMs, 500..60_000) { v -> vm.update { it.copy(connectTimeoutMs = v) } }
            NumberField("Read timeout ms", s.readTimeoutMs, 1_000..120_000) { v -> vm.update { it.copy(readTimeoutMs = v) } }
            LabeledSwitch("Query firmware version", s.queryFirmware, { v -> vm.update { it.copy(queryFirmware = v) } }, "Uses the CMD_FW_VERSION extension when the payload supports it.")
            LabeledSwitch(
                "Allow non-local addresses", s.allowNonLocalHosts,
                { on -> if (on) confirmNonLocal = true else vm.update { it.copy(allowNonLocalHosts = false) } },
                "ps4debug is unauthenticated. Keep this off.",
            )
        }
        SectionCard("Appearance") {
            Selector("Theme", ThemeMode.entries, s.theme, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, { t -> vm.update { it.copy(theme = t) } })
        }
        SectionCard("Diagnostics log") {
            Text("Structured events (connection, protocol, process, scan…). Kept in memory only; never uploaded. Export to share when reporting a problem.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::clearLog) { Text("Clear") }
                OutlinedButton(onClick = { logLauncher.launch("smartcheat-log.jsonl") }) { Text("Export (JSON Lines)") }
            }
            if (log.isEmpty()) Text("Empty")
            log.takeLast(100).reversed().forEach { Text(it.format(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
        SectionCard("About") {
            Text("PS4 SmartCheat ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
            Text(
                "Primary backend: ps4debug-NG (port 744) with automatic CMD_PROC_AUTH for server-side scanning; classic ps4debug is " +
                    "supported in compatibility mode. Tested against an automated mock server only; " +
                    "real PS4 11.00 + GoldHEN behaviour is pending hardware verification. For offline games and homebrew you control.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirmEditing) ConfirmDialog(
        "Enable memory editing?",
        "Writes change memory of the selected process on your console. Wrong writes can crash the game or corrupt progress. " +
            "Every write is confirmed and verified; originals are kept for Restore while the app runs.",
        "Enable", { vm.update { it.copy(editingEnabled = true) }; confirmEditing = false }, { confirmEditing = false },
    )
    if (confirmNonWritable) ConfirmDialog(
        "Allow non-writable regions?", "Patching code or read-only data can crash the game.", "Allow",
        { vm.update { it.copy(allowNonWritableRegions = true) }; confirmNonWritable = false }, { confirmNonWritable = false },
    )
    if (confirmSystem) ConfirmDialog(
        "Allow system process writes?", "Writing to system processes can crash or hang the console and is never needed for games.", "Allow",
        { vm.update { it.copy(allowSystemProcesses = true) }; confirmSystem = false }, { confirmSystem = false },
    )
    if (confirmNonLocal) ConfirmDialog(
        "Allow non-local addresses?", "Only use this for unusual local setups (e.g. VPN to your own network). Never expose ps4debug to the internet.", "Allow",
        { vm.update { it.copy(allowNonLocalHosts = true) }; confirmNonLocal = false }, { confirmNonLocal = false },
    )
}
