package com.ps4smartcheat.app.ui.screens

import com.ps4smartcheat.app.ui.components.ltr

import com.ps4smartcheat.app.ui.components.GameRequiredBanner

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

private const val MAX_IMPORT_BYTES = 4 * 1024 * 1024

class AddressesViewModel(private val c: AppContainer) : ViewModel() {
    val connection = c.backend.state
    val selected = c.selectedProcess
    val games = c.addresses.games
    val settings = c.settings.settings
    val frozen = c.freezer.frozen
    private val reader = WatchReader()

    private val _values = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val values = _values.asStateFlow()
    /** Last live resolution (pointer chains followed); falls back to [resolved] for entries not read yet. */
    private val _live = MutableStateFlow<List<ResolvedWatch>>(emptyList())
    val liveResolved = _live.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _liveOn = MutableStateFlow(true)
    val live = _liveOn.asStateFlow()
    private var reading = false

    val intervalMs: Long get() = c.settings.value.monitorIntervalMs.coerceIn(250, 10_000).toLong()

    fun setLive(v: Boolean) { _liveOn.value = v }

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
        val key = gameKey() ?: return
        val entries = c.addresses.entries(key)
        if (entries.isEmpty()) { _values.value = emptyMap(); _live.value = emptyList(); return }
        reading = true
        viewModelScope.launch {
            try {
                val items = WatchResolver.resolveLive(c.backend, sel.pid, entries, sel.regions)
                _values.value = reader.read(c.backend, sel.pid, items)
                _live.value = items
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

    /** Writes this game's list (or all lists when no game is selected) to [uri]. */
    fun exportTo(resolver: android.content.ContentResolver, uri: android.net.Uri) = viewModelScope.launch {
        try {
            val keys = gameKey()?.let { listOf(it) }
            val text = c.addresses.exportJson(keys)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { resolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(text) } }
            _message.value = "Address list exported"
        } catch (e: Exception) {
            _error.value = "Export failed: ${e.message}"
        }
    }

    fun importFrom(resolver: android.content.ContentResolver, uri: android.net.Uri) = viewModelScope.launch {
        try {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                resolver.openInputStream(uri)!!.use { input ->
                    // Bounded read (InputStream.readNBytes needs API 33).
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (out.size() <= MAX_IMPORT_BYTES) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    out.toString("UTF-8")
                }
            }
            if (text.length > MAX_IMPORT_BYTES) { _error.value = "File is too large for an address list"; return@launch }
            val r = c.addresses.importJson(text)
            _message.value = "Imported ${r.added} address(es)" + if (r.skipped > 0) ", ${r.skipped} already in the list" else ""
            _error.value = null
            refresh()
        } catch (e: com.ps4smartcheat.core.watch.WatchFormatException) {
            _error.value = e.message
        } catch (e: Exception) {
            _error.value = "Import failed: ${e.message}"
        }
    }

    fun profilesForSelected() = c.selectedProcess.value?.let { c.cheats.profilesForGame(it.titleId, null, it.name) }.orEmpty()

    /** Saves a (non-pointer) entry as a cheat with [value]; the current bytes become the stored original. */
    fun saveCheat(row: WatchRow, name: String, value: String, profileId: String?, newProfileName: String, version: String) {
        val r = row.resolved
        if (r.entry.pointer != null) { _error.value = "Pointer chains cannot be saved as cheats (GoldHEN files hold fixed offsets only)"; return }
        if (!r.status.readable) { _error.value = "\"${r.entry.label}\" is not mapped in this process"; return }
        viewModelScope.launch {
            try {
                val msg = c.saveCheat(r.address, r.entry.type, name, value, row.value, profileId, newProfileName, version)
                _message.value = msg
                c.log.notify(msg) // the list header may be scrolled away while the form is open
                _error.value = null
            } catch (e: IllegalArgumentException) {
                _error.value = e.message
            } catch (e: Exception) {
                _error.value = "Could not save cheat: ${e.message}"
            }
        }
    }
}

@Composable
fun AddressesScreen(
    onEdit: (Long, ValueType) -> Unit, onViewMemory: (Long) -> Unit, onPointerScan: (Long, ValueType) -> Unit = { _, _ -> },
    onWatch: (address: Long, size: Int, writes: Boolean) -> Unit = { _, _, _ -> },
) {
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
    val liveResolved by vm.liveResolved.collectAsStateWithLifecycle()
    val connected = connection is ConnectionState.Connected
    val sel = selected
    val alive = sel != null && !sel.exited

    // games is read so the rows recompute when the list changes.
    val rows = remember(games, sel, values, frozen, liveResolved) {
        vm.resolved().map { pure ->
            val r = liveResolved.firstOrNull { it.entry == pure.entry } ?: pure
            WatchRow(r, values[r.entry.id].takeIf { r.status.readable }, frozen.any { it.pid == sel?.pid && it.address == r.address })
        }
    }

    LaunchedEffect(sel?.pid, sel?.regions, games, connected) { vm.refresh() }
    LaunchedEffect(live, connected, alive) {
        while (live && connected && alive) {
            kotlinx.coroutines.delay(vm.intervalMs)
            vm.refresh()
        }
    }

    var expanded by remember { mutableStateOf<String?>(null) }
    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportTo(resolver, uri)
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFrom(resolver, uri)
    }
    var showAdd by rememberSaveable { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().testTag("addressList"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            when {
                sel == null || !connected -> GameRequiredBanner()
                sel.exited -> WarningBanner("${sel.exitedReason}. Values are shown again when the game runs.")
                else -> Text("${sel.titleId ?: "no title ID"} — ${sel.name} (pid ${sel.pid})", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "Saved per game. Module-relative entries (e.g. eboot.bin+0x120) and pointer chains follow the game across restarts; " +
                    "absolute heap addresses are only trusted in the session they were added (use \"Find pointers…\" for those). " +
                    "Nothing here is written to the PS4 unless you edit or freeze it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sel != null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledSwitch("Live values", live, vm::setLive, "Every ${vm.intervalMs} ms", enabled = connected, tag = "watchLive")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = vm::refresh, enabled = connected && alive) { Text("Refresh") }
                    TextButton(onClick = { showAdd = !showAdd }, modifier = Modifier.testTag("addManual")) { Text(if (showAdd) "Close" else "Add address…") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import") }
                    TextButton(onClick = { exportLauncher.launch("smartcheat-addresses-${(sel.titleId ?: sel.name).replace(Regex("[^A-Za-z0-9._-]"), "_")}.json") }, enabled = rows.isNotEmpty()) { Text("Export") }
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
                    onPointerScan = { onPointerScan(row.resolved.address, row.resolved.entry.type) },
                    onWatch = { writes -> onWatch(row.resolved.address, row.resolved.entry.type.size, writes) },
                    onFreeze = { vm.toggleFreeze(row) },
                    onRename = { vm.rename(row, it) },
                    onDelete = { vm.remove(row); expanded = null },
                    saveForm = {
                        SaveAsCheat(row, vm.profilesForSelected(), sel.titleId ?: sel.name) { n, v, pid, np, ver -> vm.saveCheat(row, n, v, pid, np, ver) }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun WatchRowView(
    row: WatchRow, region: String, expanded: Boolean, canWrite: Boolean,
    onToggle: () -> Unit, onEdit: () -> Unit, onView: () -> Unit, onPointerScan: () -> Unit, onFreeze: () -> Unit,
    onRename: (String) -> Unit, onDelete: () -> Unit,
    onWatch: (writes: Boolean) -> Unit = {},
    saveForm: @Composable () -> Unit = {},
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
                e.pointer?.let { Text("Pointer: ${it.describe()}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onEdit, enabled = r.status.readable) { Text("Edit") }
                    OutlinedButton(onClick = onFreeze, enabled = canWrite && r.status.readable && (row.frozen || row.value != null), modifier = Modifier.testTag("freeze-${e.label}")) {
                        Text(if (row.frozen) "Unfreeze" else "Freeze")
                    }
                    OutlinedButton(onClick = onView, enabled = r.status.readable) { Text("Memory") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onWatch(true) }, enabled = r.status.readable, modifier = Modifier.testTag("whatWrites-${e.label}")) { Text("What writes?") }
                    TextButton(onClick = { onWatch(false) }, enabled = r.status.readable) { Text("What accesses?") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onRename(name) }) { Text("Rename") }
                }
                if (e.pointer == null) saveForm()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (e.pointer == null) {
                        TextButton(onClick = onPointerScan, enabled = r.status.readable, modifier = Modifier.testTag("pointerScan-${e.label}")) { Text("Find pointers…") }
                    }
                    TextButton(onClick = onDelete) { Text("Remove from list", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun AddAddressForm(onAdd: (String, ValueType, String) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(ValueType.INT32) }
    var label by rememberSaveable { mutableStateOf("") }
    SectionCard("Add address") {
        OutlinedTextField(address, { address = it.trim() }, textStyle = ltr(), label = { Text("Address (hex)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("watchAddress"))
        Selector("Value type", ValueType.entries, type, { it.label }, { type = it })
        OutlinedTextField(label, { label = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onAdd(address, type, label) }, enabled = address.isNotBlank(), modifier = Modifier.testTag("watchAdd")) { Text("Add") }
    }
}

@Composable
private fun SaveAsCheat(
    row: WatchRow, profiles: List<com.ps4smartcheat.core.cheat.CheatProfile>, defaultProfile: String,
    onSave: (name: String, value: String, profileId: String?, newProfileName: String, version: String) -> Unit,
) {
    var open by remember(row.id) { mutableStateOf(false) }
    var value by remember(row.id) { mutableStateOf("") }
    if (!open) {
        TextButton(onClick = { open = true }, enabled = row.resolved.status.readable, modifier = Modifier.testTag("saveAsCheat-${row.resolved.entry.label}")) { Text("Save as cheat…") }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value, { value = it }, singleLine = true, label = { Text("Cheat value (${row.resolved.entry.type.label})") },
            modifier = Modifier.fillMaxWidth().testTag("cheatValue"),
        )
        SaveCheatForm(
            profiles = profiles, defaultProfileName = defaultProfile, initialName = row.resolved.entry.label,
            onCancel = { open = false },
            onSave = { n, pid, np, ver -> onSave(n, value, pid, np, ver); open = false },
        )
    }
}
