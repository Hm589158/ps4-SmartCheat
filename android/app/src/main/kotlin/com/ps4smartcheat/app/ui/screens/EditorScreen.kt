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
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ps4smartcheat.app.data.AppContainer
import com.ps4smartcheat.app.ui.appViewModel
import com.ps4smartcheat.app.ui.components.ConfirmDialog
import com.ps4smartcheat.app.ui.components.ErrorText
import com.ps4smartcheat.app.ui.components.KeyValue
import com.ps4smartcheat.app.ui.components.SectionCard
import com.ps4smartcheat.app.ui.components.Selector
import com.ps4smartcheat.app.ui.components.WarningBanner
import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.cheat.CheatEntry
import com.ps4smartcheat.core.cheat.CheatProfile
import com.ps4smartcheat.core.cheat.ModuleResolver
import com.ps4smartcheat.core.edit.EditDeniedException
import com.ps4smartcheat.core.edit.EditTarget
import com.ps4smartcheat.core.edit.WriteOutcome
import com.ps4smartcheat.core.edit.WritePlan
import com.ps4smartcheat.core.scan.ValueCodec
import com.ps4smartcheat.core.scan.ValueParseException
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.core.scan.describe
import com.ps4smartcheat.core.util.Address
import com.ps4smartcheat.core.util.Hex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EditorViewModel(private val c: AppContainer) : ViewModel() {
    val selected = c.selectedProcess
    val settings = c.settings.settings
    val journal = c.editor.journal
    val frozen = c.freezer.frozen
    val freezeStop = c.freezer.lastStopReason
    val profiles = c.cheats.profiles

    private val _address = MutableStateFlow("")
    val address = _address.asStateFlow()
    private val _type = MutableStateFlow(ValueType.INT32)
    val type = _type.asStateFlow()
    private val _current = MutableStateFlow<ByteArray?>(null)
    val current = _current.asStateFlow()
    private val _location = MutableStateFlow<String?>(null)
    val location = _location.asStateFlow()
    private val _replacement = MutableStateFlow("")
    val replacement = _replacement.asStateFlow()
    private val _plan = MutableStateFlow<WritePlan?>(null)
    /** Plan waiting for user confirmation. */
    val plan = _plan.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var initialized = false

    fun init(address: Long?, type: ValueType?) {
        if (initialized) return
        initialized = true
        if (address != null) _address.value = Address.format(address)
        if (type != null) _type.value = type
        if (address != null) read()
    }

    fun setAddress(v: String) { _address.value = v.trim(); _current.value = null; _error.value = null }
    fun setType(t: ValueType) { _type.value = t; _current.value = null; read() }
    fun setReplacement(v: String) { _replacement.value = v; _error.value = null }
    fun dismissPlan() { _plan.value = null }

    private fun parsedAddress(): Long? = Address.parse(_address.value).also { if (it == null) _error.value = "Enter a hexadecimal address" }

    fun read() {
        val sel = c.selectedProcess.value ?: run { _error.value = "Select a process first"; return }
        val addr = parsedAddress() ?: return
        viewModelScope.launch {
            try {
                val snap = c.editor.read(sel.pid, addr, _type.value)
                _current.value = snap.bytes
                _location.value = sel.regions.describe(addr)
                _error.value = null
            } catch (e: EditDeniedException) {
                _error.value = e.message; _current.value = null
            } catch (e: BackendException) {
                _error.value = e.message; _current.value = null
            }
        }
    }

    fun prepareWrite() {
        val sel = c.selectedProcess.value ?: run { _error.value = "Select a process first"; return }
        val addr = parsedAddress() ?: return
        viewModelScope.launch {
            try {
                _plan.value = c.editor.plan(EditTarget(sel.pid, sel.name, addr, _type.value), _replacement.value)
                _error.value = null
            } catch (e: EditDeniedException) {
                _error.value = e.message
            } catch (e: ValueParseException) {
                _error.value = e.message
            } catch (e: BackendException) {
                _error.value = e.message
            }
        }
    }

    fun confirmWrite() {
        val p = _plan.value ?: return
        _plan.value = null
        viewModelScope.launch {
            val out = c.editor.execute(p)
            report(out, "Write ${Address.format(p.target.address)} = ${p.replacementText}")
            read()
        }
    }

    fun restore() {
        val sel = c.selectedProcess.value ?: return
        val addr = parsedAddress() ?: return
        c.freezer.unfreeze(sel.pid, addr)
        viewModelScope.launch {
            report(c.editor.restore(sel.pid, addr), "Restore ${Address.format(addr)}")
            read()
        }
    }

    fun toggleFreeze() {
        val sel = c.selectedProcess.value ?: return
        val addr = parsedAddress() ?: return
        if (c.freezer.isFrozen(sel.pid, addr)) {
            c.freezer.unfreeze(sel.pid, addr)
            _message.value = "Unfrozen"
            return
        }
        viewModelScope.launch {
            try {
                val p = c.editor.plan(EditTarget(sel.pid, sel.name, addr, _type.value), _replacement.value)
                if (p.dangerous) { _error.value = "Freeze is not allowed for writes with warnings: ${p.warnings.joinToString()}"; return@launch }
                // Journal the original via a normal verified write first.
                val out = c.editor.execute(p)
                report(out, "Freeze ${Address.format(addr)}")
                if (out is WriteOutcome.Verified) c.freezer.freeze(p, Address.format(addr))
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    private fun report(out: WriteOutcome, what: String) {
        if (out is WriteOutcome.Verified) {
            _message.value = out.message
            c.log.info("$what: verified")
        } else {
            _error.value = out.message
            c.log.error("$what: ${out.message}")
        }
    }

    fun addToList() {
        val addr = parsedAddress() ?: return
        viewModelScope.launch { _message.value = c.addToAddressList(addr, _type.value) }
    }

    fun profilesForSelected(): List<CheatProfile> {
        val sel = c.selectedProcess.value ?: return emptyList()
        return c.cheats.profilesForGame(sel.titleId, null, sel.name)
    }

    /** Saves the address as a cheat. [profileId] null → create a new profile with [newProfileName]/[gameVersion]. */
    fun saveCheat(name: String, profileId: String?, newProfileName: String, gameVersion: String) {
        val sel = c.selectedProcess.value ?: run { _error.value = "Select a process first"; return }
        val addr = parsedAddress() ?: return
        val type = _type.value
        val replacement = _replacement.value.trim().ifBlank { null }
        if (replacement != null) {
            try { ValueCodec.parse(type, replacement) } catch (e: ValueParseException) { _error.value = e.message; return }
        }
        if (name.isBlank()) { _error.value = "Enter a cheat name"; return }
        viewModelScope.launch {
            try {
                val original = c.editor.originalFor(sel.pid, addr)?.original ?: _current.value
                val pid = profileId ?: c.cheats.createProfile(
                    newProfileName.ifBlank { sel.titleId ?: sel.name }, sel.titleId, gameVersion, sel.name,
                ).id
                val entry = CheatEntry(
                    id = c.cheats.newId(), name = name.trim(), type = type, address = addr,
                    module = ModuleResolver.refFor(addr, sel.regions), replacementValue = replacement,
                    originalBytes = original?.takeIf { it.size == type.size }?.let { Hex.encode(it) },
                    discoveredPid = sel.pid,
                )
                c.cheats.upsertCheat(pid, entry)
                _message.value = "Saved \"${entry.name}\"" + if (entry.module == null) " (absolute address — may not survive a game restart)" else " (${entry.module!!.name} relative)"
            } catch (e: Exception) {
                _error.value = "Could not save cheat: ${e.message}"
            }
        }
    }

}

@Composable
fun EditorScreen(initialAddress: Long?, initialType: ValueType?, onViewMemory: (Long) -> Unit = {}) {
    val vm = appViewModel { EditorViewModel(it) }
    LaunchedEffect(Unit) { vm.init(initialAddress, initialType) }
    val selected by vm.selected.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val address by vm.address.collectAsStateWithLifecycle()
    val type by vm.type.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val replacement by vm.replacement.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val journal by vm.journal.collectAsStateWithLifecycle()
    val frozen by vm.frozen.collectAsStateWithLifecycle()
    val freezeStop by vm.freezeStop.collectAsStateWithLifecycle()
    var showSave by remember { mutableStateOf(false) }

    val addr = Address.parse(address)
    val original = journal.firstOrNull { it.pid == selected?.pid && it.address == addr }
    val isFrozen = frozen.any { it.pid == selected?.pid && it.address == addr }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selected == null) WarningBanner("No process selected.")
        if (!settings.editingEnabled) WarningBanner("Read-only mode. Enable memory editing in Settings to write values.")
        SectionCard("Address") {
            Text("Process: ${selected?.name ?: "-"} (pid ${selected?.pid ?: "-"})", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = address, onValueChange = vm::setAddress, label = { Text("Address (hex)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Selector("Value type", ValueType.entries, type, { it.label }, vm::setType)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::read, enabled = selected != null) { Text("Read") }
                OutlinedButton(onClick = { addr?.let(onViewMemory) }, enabled = selected != null && addr != null, modifier = Modifier.testTag("viewMemory")) { Text("View memory") }
                OutlinedButton(onClick = vm::addToList, enabled = selected != null && addr != null, modifier = Modifier.testTag("addAddress")) { Text("+ List") }
            }
            location?.let { KeyValue("Region", it, mono = true) }
            KeyValue("Current", current?.let { ValueCodec.format(type, it) + "  [" + Hex.encode(it, " ") + "]" } ?: "—", mono = true)
            KeyValue("Original", original?.let { ValueCodec.format(it.type, it.original) + "  [" + Hex.encode(it.original, " ") + "]" } ?: "not modified by this app", mono = true)
        }
        SectionCard("Modify") {
            OutlinedTextField(
                value = replacement, onValueChange = vm::setReplacement, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("newValue"),
                label = { Text("New value") },
                keyboardOptions = KeyboardOptions(keyboardType = if (type.isFloat) KeyboardType.Decimal else KeyboardType.Text),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::prepareWrite, enabled = selected != null && settings.editingEnabled, modifier = Modifier.testTag("write")) { Text("Write") }
                OutlinedButton(onClick = vm::restore, enabled = original != null && settings.editingEnabled, modifier = Modifier.testTag("restore")) { Text("Restore") }
                OutlinedButton(onClick = vm::toggleFreeze, enabled = selected != null && settings.editingEnabled) { Text(if (isFrozen) "Unfreeze" else "Freeze") }
            }
            Text(
                "Freeze rewrites the value every few hundred ms while this phone stays connected. It is not an on-console freeze.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            freezeStop?.let { Text("Freeze stopped: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
            ErrorText(error)
        }
        SectionCard("Cheat library") {
            if (!showSave) {
                OutlinedButton(onClick = { showSave = true }, enabled = selected != null && addr != null, modifier = Modifier.testTag("saveCheat")) { Text("Save as cheat") }
            } else {
                // Inline form instead of a dialog: stays usable while the soft keyboard is open.
                SaveCheatForm(
                    profiles = vm.profilesForSelected(),
                    defaultProfileName = selected?.titleId ?: selected?.name ?: "",
                    onCancel = { showSave = false },
                    onSave = { name, profileId, newName, version -> vm.saveCheat(name, profileId, newName, version); showSave = false },
                )
            }
        }
    }

    plan?.let { p ->
        ConfirmDialog(
            title = if (p.dangerous) "Confirm risky write" else "Confirm write",
            text = buildString {
                append("Write ${p.replacementText} (${p.target.type.label}) to ${Address.format(p.target.address)} in ${p.target.processName}?\n\n")
                append("Current bytes: ${Hex.encode(p.original, " ")}\nNew bytes: ${Hex.encode(p.replacement, " ")}")
                if (p.warnings.isNotEmpty()) append("\n\nWarnings:\n• " + p.warnings.joinToString("\n• "))
            },
            confirmLabel = "Write",
            onConfirm = vm::confirmWrite,
            onDismiss = vm::dismissPlan,
        )
    }

}

@Composable
private fun SaveCheatForm(
    profiles: List<CheatProfile>,
    defaultProfileName: String,
    onCancel: () -> Unit,
    onSave: (name: String, profileId: String?, newProfileName: String, version: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<CheatProfile?>(profiles.firstOrNull()) }
    var newName by remember { mutableStateOf(defaultProfileName) }
    var version by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            name, { name = it }, label = { Text("Cheat name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("cheatName"),
        )
        val options: List<CheatProfile?> = profiles + listOf(null)
        Selector("Profile", options, target, { it?.name ?: "New profile…" }, { target = it })
        if (target == null) {
            OutlinedTextField(newName, { newName = it }, label = { Text("New profile name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(version, { version = it }, label = { Text("Game version (e.g. 01.00)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("ps4debug does not report the game version; enter it from the game's info screen.", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(name, target?.id, newName, version) }, modifier = Modifier.testTag("saveConfirm")) { Text("Save") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
