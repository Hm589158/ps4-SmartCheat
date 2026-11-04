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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.ps4smartcheat.app.ui.appViewModel
import com.ps4smartcheat.app.ui.components.ErrorText
import com.ps4smartcheat.app.ui.components.LabeledSwitch
import com.ps4smartcheat.app.ui.components.SectionCard
import com.ps4smartcheat.app.ui.components.Selector
import com.ps4smartcheat.app.ui.components.WarningBanner
import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.edit.EditTarget
import com.ps4smartcheat.core.edit.WriteOutcome
import com.ps4smartcheat.core.scan.ValueCodec
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.core.scan.describe
import com.ps4smartcheat.core.util.Address
import com.ps4smartcheat.core.watch.ResolvedWatch
import com.ps4smartcheat.core.watch.WatchKeys
import com.ps4smartcheat.core.watch.WatchReader
import com.ps4smartcheat.core.watch.WatchResolver
import com.ps4smartcheat.core.watch.WatchStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One row of the address list as shown: resolved location plus the last value read. */
data class WatchRow(val resolved: ResolvedWatch, val value: ByteArray?, val frozen: Boolean) {
    val id: String get() = resolved.entry.id
}

class AddressesViewModel(private val c: AppContainer) : ViewModel() {
    val connection = c.backend.state
    val selected = c.selectedProcess
    val games = c.addresses.games
    val settings = c.settings.settings
    val frozen = c.freezer.frozen
    private val reader = WatchReader()

    private val _values = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val values = _values.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _live = MutableStateFlow(true)
    val live = _live.asStateFlow()
    private var reading = false

    val intervalMs: Long get() = c.settings.value.monitorIntervalMs.coerceIn(250, 10_000).toLong()

    fun setLive(v: Boolean) { _live.value = v }

    fun gameKey(): String? = c.selectedProcess.value?.let { WatchKeys.gameKey(it.titleId, it.name) }

    /** Resolves the selected game's entries against its current memory map (no console access). */
    fun resolved(): List<ResolvedWatch> {
        val sel = c.selectedProcess.value ?: return emptyList()
        val key = gameKey() ?: return emptyList()
        return WatchResolver.resolveAll(c.addresses.entries(key), sel.pid, sel.regions)
    }

    /** Reads every mapped entry; nearby entries share one request. Skipped while a read is in flight. */
    fun refresh() {
        val sel = c.selectedProcess.value ?: return
        if (sel.exited || reading || c.backend.state.value !is ConnectionState.Connected) return
        val items = resolved()
        if (items.isEmpty()) { _values.value = emptyMap(); return }
        reading = true
        viewModelScope.launch {
            try {
                _values.value = reader.read(c.backend, sel.pid, items)
                _error.value = null
            } catch (e: BackendException) {
                _error.value = "Could not read values: ${e.message}"
            } finally {
                reading = false
            }
        }
    }

    fun addManual(addressText: String, type: ValueType, label: String) {
        val addr = Address.parse(addressText) ?: run { _error.value = "Enter a hexadecimal address"; return }
        viewModelScope.launch {
            _message.value = c.addToAddressList(addr, type, label)
            _error.value = null
            refresh()
        }
    }

    fun rename(row: WatchRow, label: String) {
        val key = gameKey() ?: return
        if (label.isBlank()) { _error.value = "Enter a name"; return }
        viewModelScope.launch { c.addresses.update(key, row.resolved.entry.copy(label = label)) }
    }

    fun remove(row: WatchRow) {
        val key = gameKey() ?: return
        val sel = c.selectedProcess.value
        if (sel != null) c.freezer.unfreeze(sel.pid, row.resolved.address)
        viewModelScope.launch {
            c.addresses.remove(key, row.id)
            _message.value = "Removed \"${row.resolved.entry.label}\""
        }
    }

    /**
     * Freezes the value currently in memory (writes the same bytes repeatedly while connected). Goes through the
     * editor's validation and verified write; refused for absolute addresses from an earlier session.
     */
    fun toggleFreeze(row: WatchRow) {
        val sel = c.selectedProcess.value ?: return
        val r = row.resolved
        if (c.freezer.isFrozen(sel.pid, r.address)) {
            c.freezer.unfreeze(sel.pid, r.address)
            return
        }
        if (r.status == WatchStatus.ABSOLUTE_UNVERIFIED) {
            _error.value = "\"${r.entry.label}\" is an absolute address from an earlier session. Check it in the editor before freezing."
            return
        }
        val bytes = row.value ?: run { _error.value = "Value not read yet"; return }
        viewModelScope.launch {
            try {
                val plan = c.editor.plan(EditTarget(sel.pid, sel.name, r.address, r.entry.type), ValueCodec.format(r.entry.type, bytes))
                if (plan.dangerous) { _error.value = "Freeze is not allowed here: ${plan.warnings.joinToString()}"; return@launch }
                val out = c.editor.execute(plan)
                if (out is WriteOutcome.Verified) {
                    c.freezer.freeze(plan, r.entry.label)
                    _message.value = "Freezing \"${r.entry.label}\" at ${plan.replacementText}"
                } else _error.value = out.message
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun describe(address: Long): String = c.selectedProcess.value?.regions?.describe(address) ?: ""
}

@Composable
fun AddressesScreen(onEdit: (Long, ValueType) -> Unit, onViewMemory: (Long) -> Unit) {
    val vm = appViewModel { AddressesViewModel(it) }
    val connection by vm.connection.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val games by vm.games.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val frozen by vm.frozen.collectAsStateWithLifecycle()
    val values by vm.values.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val connected = connection is ConnectionState.Connected
    val sel = selected
    val alive = sel != null && !sel.exited

    // games is read so the rows recompute when the list changes.
    val rows = remember(games, sel, values, frozen) {
        vm.resolved().map { r -> WatchRow(r, values[r.entry.id], frozen.any { it.pid == sel?.pid && it.address == r.address }) }
    }

    LaunchedEffect(sel?.pid, sel?.regions, games, connected) { vm.refresh() }
    LaunchedEffect(live, connected, alive) {
        while (live && connected && alive) {
            kotlinx.coroutines.delay(vm.intervalMs)
            vm.refresh()
        }
    }

    var expanded by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().testTag("addressList"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            when {
                sel == null -> WarningBanner("No process selected. Pick the game on the Processes screen.")
                sel.exited -> WarningBanner("${sel.exitedReason}. Values are shown again when the game runs.")
                else -> Text("${sel.titleId ?: "no title ID"} — ${sel.name} (pid ${sel.pid})", style = MaterialTheme.typography.titleSmall)
            }
            if (!connected) Text("Not connected.", color = MaterialTheme.colorScheme.error)
            Text(
                "Saved per game. Module-relative entries (e.g. eboot.bin+0x120) follow the game across restarts; " +
                    "absolute heap addresses are only trusted in the session they were added. Nothing here is written to the PS4 unless you edit or freeze it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sel != null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledSwitch("Live values", live, vm::setLive, "Every ${vm.intervalMs} ms", enabled = connected)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = vm::refresh, enabled = connected && alive) { Text("Refresh") }
                    TextButton(onClick = { showAdd = !showAdd }, modifier = Modifier.testTag("addManual")) { Text(if (showAdd) "Close" else "Add address…") }
                }
                if (showAdd) AddAddressForm { a, t, l -> vm.addManual(a, t, l); showAdd = false }
                message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
                ErrorText(error)
            }
            if (rows.isEmpty()) {
                item { Text("No saved addresses for this game yet. Add one with + on a scan result or in the memory editor.") }
            }
            items(rows, key = { it.id }) { row ->
                WatchRowView(
                    row, vm.describe(row.resolved.address), expanded == row.id,
                    canWrite = settings.editingEnabled && alive && connected,
                    onToggle = { expanded = if (expanded == row.id) null else row.id },
                    onEdit = { onEdit(row.resolved.address, row.resolved.entry.type) },
                    onView = { onViewMemory(row.resolved.address) },
                    onFreeze = { vm.toggleFreeze(row) },
                    onRename = { vm.rename(row, it) },
                    onDelete = { vm.remove(row); expanded = null },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun WatchRowView(
    row: WatchRow, region: String, expanded: Boolean, canWrite: Boolean,
    onToggle: () -> Unit, onEdit: () -> Unit, onView: () -> Unit, onFreeze: () -> Unit,
    onRename: (String) -> Unit, onDelete: () -> Unit,
) {
    val r = row.resolved
    val e = r.entry
    Column(Modifier.fillMaxWidth().testTag("watch-${e.label}")) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.4f)) {
                Text(e.label + if (row.frozen) "  ❄" else "", style = MaterialTheme.typography.bodyLarge)
                Text("${Address.format(r.address)} · ${e.type.label}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text(
                    r.status.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (r.status) {
                        WatchStatus.UNMAPPED -> MaterialTheme.colorScheme.error
                        WatchStatus.ABSOLUTE_UNVERIFIED -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                row.value?.let { ValueCodec.format(e.type, it) } ?: if (r.status.readable) "…" else "—",
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).testTag("watchValue-${e.label}"),
            )
        }
        if (expanded) {
            var name by remember(e.id) { mutableStateOf(e.label) }
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(region, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                e.module?.let { Text("Saved as ${it.name}+0x${java.lang.Long.toHexString(it.offset).uppercase()}", style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onEdit, enabled = r.status.readable) { Text("Edit") }
                    OutlinedButton(onClick = onFreeze, enabled = canWrite && r.status.readable && (row.frozen || row.value != null), modifier = Modifier.testTag("freeze-${e.label}")) {
                        Text(if (row.frozen) "Unfreeze" else "Freeze")
                    }
                    OutlinedButton(onClick = onView, enabled = r.status.readable) { Text("Memory") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onRename(name) }) { Text("Rename") }
                }
                TextButton(onClick = onDelete) { Text("Remove from list", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun AddAddressForm(onAdd: (String, ValueType, String) -> Unit) {
    var address by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ValueType.INT32) }
    var label by remember { mutableStateOf("") }
    SectionCard("Add address") {
        OutlinedTextField(address, { address = it.trim() }, label = { Text("Address (hex)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("watchAddress"))
        Selector("Value type", ValueType.entries, type, { it.label }, { type = it })
        OutlinedTextField(label, { label = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onAdd(address, type, label) }, enabled = address.isNotBlank()) { Text("Add") }
    }
}
