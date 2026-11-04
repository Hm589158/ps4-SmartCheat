package com.ps4smartcheat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.edit.ProcessSafety
import com.ps4smartcheat.core.model.ProcessEntry
import com.ps4smartcheat.core.util.Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProcessesViewModel(private val c: AppContainer) : ViewModel() {
    val connection = c.backend.state
    val selected = c.selectedProcess
    private val _processes = MutableStateFlow<List<ProcessEntry>>(emptyList())
    val processes = _processes.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun refresh() {
        if (c.backend.state.value !is ConnectionState.Connected) { _error.value = "Connect to the PS4 first"; return }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _processes.value = c.backend.getProcesses().sortedBy { it.pid }
                val sel = c.selectedProcess.value
                if (sel != null && _processes.value.none { it.pid == sel.pid && it.name == sel.name }) {
                    c.log.warn("Selected process ${sel.name} (pid ${sel.pid}) is no longer running")
                    c.select(null)
                }
            } catch (e: BackendException) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    fun select(entry: ProcessEntry) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val info = runCatching { c.backend.getProcessInfo(entry.pid) }.getOrNull()
                val regions = c.backend.getMemoryRegions(entry.pid)
                c.select(SelectedProcess(entry, info, regions))
                c.log.info("Selected ${entry.name} (pid ${entry.pid}, ${regions.size} regions)")
            } catch (e: BackendException) {
                _error.value = "Could not inspect ${entry.name}: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }
}

@Composable
fun ProcessesScreen(onOpenScanner: () -> Unit) {
    val vm = appViewModel { ProcessesViewModel(it) }
    val connection by vm.connection.collectAsStateWithLifecycle()
    val processes by vm.processes.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var showSystem by remember { mutableStateOf(false) }
    var showRegions by remember { mutableStateOf(false) }

    LaunchedEffect(connection is ConnectionState.Connected) {
        if (connection is ConnectionState.Connected && processes.isEmpty()) vm.refresh()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::refresh, enabled = !loading && connection is ConnectionState.Connected) { Text("Refresh") }
                if (selected != null) OutlinedButton(onClick = onOpenScanner) { Text("Scan ${selected!!.name}") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            ErrorText(error)
            if (connection !is ConnectionState.Connected) Text("Not connected.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        selected?.let { sel ->
            item { SelectedProcessCard(sel, showRegions) { showRegions = !showRegions } }
        }
        item { LabeledSwitch("Show system processes", showSystem, { showSystem = it }, "Sce* processes are hidden; writes to them are blocked by default.") }
        val visible = processes.filter { showSystem || !ProcessSafety.isSystemProcess(it.name) }
        items(visible, key = { it.pid }) { p ->
            val isSel = selected?.pid == p.pid
            ListItem(
                headlineContent = { Text(p.name) },
                supportingContent = { Text("pid ${p.pid}", fontFamily = FontFamily.Monospace) },
                trailingContent = {
                    when {
                        isSel -> AssistChip(onClick = {}, label = { Text("Selected") })
                        ProcessSafety.isSystemProcess(p.name) -> Text("system", color = MaterialTheme.colorScheme.tertiary)
                        else -> {}
                    }
                },
                modifier = Modifier.clickable(enabled = !loading) { vm.select(p) },
            )
            HorizontalDivider()
        }
        if (connection is ConnectionState.Connected && processes.isNotEmpty() && visible.isEmpty()) {
            item { Text("Only system processes are running. Start a game, then refresh.") }
        }
    }
}

@Composable
private fun SelectedProcessCard(sel: SelectedProcess, showRegions: Boolean, toggle: () -> Unit) {
    SectionCard("Selected: ${sel.name}") {
        KeyValue("PID", sel.pid.toString(), mono = true)
        KeyValue("Title ID", sel.titleId ?: "unknown")
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
