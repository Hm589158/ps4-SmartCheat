package com.ps4smartcheat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ps4smartcheat.app.data.AppContainer
import com.ps4smartcheat.app.data.SelectedProcess
import com.ps4smartcheat.app.ui.appViewModel
import com.ps4smartcheat.app.ui.components.ErrorText
import com.ps4smartcheat.app.ui.components.KeyValue
import com.ps4smartcheat.app.ui.components.LabeledSwitch
import com.ps4smartcheat.app.ui.components.SectionCard
import com.ps4smartcheat.app.ui.components.WarningBanner
import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.process.GameDetector
import com.ps4smartcheat.core.process.ProcessDetails
import com.ps4smartcheat.core.process.ProcessKind
import com.ps4smartcheat.core.process.listProcessDetails
import com.ps4smartcheat.core.util.Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProcessesViewModel(private val c: AppContainer) : ViewModel() {
    val connection = c.backend.state
    val selected = c.selectedProcess
    private val _processes = MutableStateFlow<List<ProcessDetails>>(emptyList())
    val processes = _processes.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    fun setQuery(q: String) { _query.value = q }

    private val _selecting = MutableStateFlow(false)
    /** A process is being selected (its memory map is read). */
    val selecting = _selecting.asStateFlow()
    private var seenSelection: Pair<Int?, Boolean?>? = null

    fun refresh() {
        if (c.backend.state.value !is ConnectionState.Connected) { _error.value = "Connect to the PS4 first"; return }
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                // Names first, then details (games first); bounded in time, so the list never keeps loading.
                val show: (List<ProcessDetails>) -> Unit = { _processes.value = it }
                // One retry: process enumeration can race with a game starting or exiting.
                val list = try { c.backend.listProcessDetails(onProgress = show) } catch (e: BackendException) { c.backend.listProcessDetails(onProgress = show) }
                _processes.value = list
                val sel = c.selectedProcess.value
                if (sel != null && !sel.exited && list.none { it.pid == sel.pid && it.name == sel.name }) {
                    c.log.warn("Selected process ${sel.name} (pid ${sel.pid}) is no longer running")
                }
            } catch (e: BackendException) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Reloads the list only when the selected game exited or another one was selected elsewhere — not every time the
     * tab is shown (that re-read every process each time and blocked taps meanwhile).
     */
    fun onSelectionChanged(pid: Int?, exited: Boolean?) {
        val key = pid to exited
        val before = seenSelection
        seenSelection = key
        if (before != null && before != key && (exited == true || before.first != pid && _processes.value.none { it.pid == pid })) refresh()
    }

    fun select(details: ProcessDetails) {
        if (_selecting.value) return
        viewModelScope.launch {
            _selecting.value = true
            _error.value = null
            try {
                c.selectDetails(details)
            } catch (e: BackendException) {
                _error.value = "Could not inspect ${details.name}: ${e.message}"
            } finally {
                _selecting.value = false
            }
        }
    }

    fun detectedGame(): ProcessDetails? = GameDetector.pickGame(_processes.value)

    /** Matches name, pid, title ID or path (case-insensitive). */
    fun matches(p: ProcessDetails, q: String): Boolean {
        val t = q.trim()
        if (t.isEmpty()) return true
        return p.name.contains(t, ignoreCase = true) || p.pid.toString() == t ||
            p.titleId?.contains(t, ignoreCase = true) == true || p.path?.contains(t, ignoreCase = true) == true
    }
}

@Composable
fun ProcessesScreen(onOpenScanner: () -> Unit) {
    val vm = appViewModel { ProcessesViewModel(it) }
    val connection by vm.connection.collectAsStateWithLifecycle()
    val processes by vm.processes.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val selecting by vm.selecting.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var showRegions by rememberSaveable { mutableStateOf(false) }
    val connected = connection is ConnectionState.Connected

    LaunchedEffect(connected) {
        if (connected && processes.isEmpty()) vm.refresh()
    }
    // Refresh when the selected process exits or is replaced, so the list reflects the console.
    LaunchedEffect(selected?.pid, selected?.exited) { if (connected) vm.onSelectionChanged(selected?.pid, selected?.exited) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::refresh, enabled = !loading && connected) { Text("Refresh") }
                val sel = selected
                if (sel != null && !sel.exited) OutlinedButton(onClick = onOpenScanner) { Text("Scan ${sel.name}") }
            }
            if (loading || selecting) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("processesLoading"))
            ErrorText(error)
            if (!connected) Text("Not connected.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        selected?.takeIf { it.exited }?.let { sel ->
            item { WarningBanner("${sel.exitedReason}. Its scan session and stored originals were discarded. Start the game again — it is picked up automatically.") }
        }
        val game = vm.detectedGame()
        if (game != null && (selected == null || selected?.exited == true || selected?.pid != game.pid)) {
            item {
                SectionCard("Detected game") {
                    Text("${game.titleId ?: "no title ID"} — ${game.name} (pid ${game.pid})", modifier = Modifier.testTag("detectedGame"))
                    Text(game.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { vm.select(game) }, enabled = !selecting) { Text("Select game") }
                }
            }
        }
        selected?.takeIf { !it.exited }?.let { sel ->
            item { SelectedProcessCard(sel, showRegions) { showRegions = !showRegions } }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = vm::setQuery, singleLine = true,
                label = { Text("Search name, pid, title ID or path") },
                modifier = Modifier.fillMaxWidth().testTag("processSearch"),
            )
            LabeledSwitch("Show system processes", showSystem, { showSystem = it }, "System processes (Sce*, NPXS apps) are hidden; writes to them are blocked by default.")
        }
        val visible = processes.filter { (showSystem || it.kind != ProcessKind.SYSTEM) && vm.matches(it, query) }
        items(visible, key = { it.pid }) { p ->
            val isSel = selected?.pid == p.pid && selected?.exited != true
            ListItem(
                headlineContent = { Text(p.name) },
                supportingContent = {
                    Text(
                        listOfNotNull("pid ${p.pid}", p.titleId, p.kind.label).joinToString(" · "),
                        fontFamily = FontFamily.Monospace,
                    )
                },
                trailingContent = {
                    when {
                        isSel -> AssistChip(onClick = {}, label = { Text("Selected") })
                        p.kind == ProcessKind.GAME -> Text("game", color = MaterialTheme.colorScheme.secondary)
                        p.kind == ProcessKind.SYSTEM -> Text("system", color = MaterialTheme.colorScheme.tertiary)
                        else -> {}
                    }
                },
                modifier = Modifier.testTag("process-${p.name}").clickable(enabled = !selecting) { vm.select(p) },
            )
            HorizontalDivider()
        }
        if (connected && processes.isNotEmpty() && visible.isEmpty()) {
            item {
                Text(if (query.isNotBlank()) "No process matches \"$query\"." else "Only system processes are running. Start a game, then refresh.")
            }
        }
    }
}

@Composable
private fun SelectedProcessCard(sel: SelectedProcess, showRegions: Boolean, toggle: () -> Unit) {
    SectionCard("Selected: ${sel.name}") {
        KeyValue("PID", sel.pid.toString(), mono = true)
        KeyValue("Title ID", sel.titleId ?: "unknown")
        KeyValue("Kind", sel.kind.label)
        sel.info?.path?.takeIf { it.isNotBlank() }?.let { KeyValue("Path", it) }
        val readable = sel.regions.filter { it.isReadable }
        val writable = sel.regions.filter { it.isReadable && it.isWritable }
        KeyValue("Regions", "${sel.regions.size} total, ${readable.size} readable, ${writable.size} writable")
        KeyValue("Writable size", "${writable.sumOf { it.size } / (1024 * 1024)} MiB")
        OutlinedButton(onClick = toggle) { Text(if (showRegions) "Hide memory map" else "Show memory map") }
        if (showRegions) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (sel.regions.isEmpty()) Text("Memory map is empty.")
                sel.regions.take(500).forEach { r ->
                    Text(
                        "${Address.format(r.start)}-${Address.format(r.end)} ${r.protString()} ${r.name.ifBlank { "-" }}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (sel.regions.size > 500) Text("… ${sel.regions.size - 500} more", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
