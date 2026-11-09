package com.ps4smartcheat.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.ps4smartcheat.app.ui.components.ConfirmDialog
import com.ps4smartcheat.app.ui.components.ErrorText
import com.ps4smartcheat.app.ui.components.SectionCard
import com.ps4smartcheat.app.ui.components.WarningBanner
import com.ps4smartcheat.core.asm.AsmInstruction
import com.ps4smartcheat.core.asm.CodePatch
import com.ps4smartcheat.core.asm.CodePatchException
import com.ps4smartcheat.core.asm.CodePatchPlanner
import com.ps4smartcheat.core.asm.Flow
import com.ps4smartcheat.core.asm.X86
import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.cheat.ModuleResolver
import com.ps4smartcheat.core.cheat.PatchState
import com.ps4smartcheat.core.diag.ErrorAdvisor
import com.ps4smartcheat.core.edit.EditDeniedException
import com.ps4smartcheat.core.edit.WriteOutcome
import com.ps4smartcheat.core.edit.WritePlan
import com.ps4smartcheat.core.model.regionFor
import com.ps4smartcheat.core.util.Address
import com.ps4smartcheat.core.util.Hex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Progress of saving a code patch to the cheat library. */
sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data class Saved(val message: String) : SaveState
    data class Failed(val error: String) : SaveState
}

/**
 * Disassembly view and Assembly ON/OFF patch builder. Instructions are decoded on the phone from memory read with
 * CMD_PROC_READ; new code is assembled by ps4debug-NG's Keystone at the exact address. ON writes the new
 * instructions, OFF restores the originals — both confirmed and verified by a fresh read.
 */
class CodeViewModel(private val c: AppContainer) : ViewModel() {
    val selected = c.selectedProcess
    val settings = c.settings.settings
    val connection = c.backend.state
    val profiles = c.cheats.profiles

    private val _address = MutableStateFlow("")
    val address = _address.asStateFlow()
    private val _rows = MutableStateFlow<List<AsmInstruction>>(emptyList())
    val rows = _rows.asStateFlow()
    private val _row = MutableStateFlow<AsmInstruction?>(null)
    /** The instruction the patch starts at. */
    val row = _row.asStateFlow()
    private val _assembly = MutableStateFlow(true)
    val assembly = _assembly.asStateFlow()
    private val _source = MutableStateFlow("")
    val source = _source.asStateFlow()
    private val _preview = MutableStateFlow<CodePatch?>(null)
    val preview = _preview.asStateFlow()
    private val _state = MutableStateFlow<PatchState?>(null)
    val state = _state.asStateFlow()
    private val _plan = MutableStateFlow<Pair<Boolean, WritePlan>?>(null)
    /** Pending ON (true) / OFF (false) write waiting for confirmation. */
    val plan = _plan.asStateFlow()
    private val _references = MutableStateFlow<List<Long>?>(null)
    val references = _references.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _note = MutableStateFlow<String?>(null)
    /** Information about the region shown (e.g. not executable). */
    val note = _note.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var initialized = false

    fun init(address: Long?) {
        if (initialized) return
        initialized = true
        if (address != null) { _address.value = Address.format(address); go() }
    }

    fun setAddress(v: String) { _address.value = v.trim(); _error.value = null }
    fun setAssembly(v: Boolean) { _assembly.value = v; _preview.value = null; _source.value = if (v) _row.value?.text.orEmpty() else _row.value?.let { Hex.encode(it.bytes, " ") }.orEmpty() }
    fun setSource(v: String) { _source.value = v; _preview.value = null; _error.value = null }

    fun go() {
        val a = Address.parse(_address.value) ?: run { _error.value = "Enter a hexadecimal address"; return }
        load(a, select = true)
    }

    fun follow(target: Long) { _address.value = Address.format(target); load(target, select = true) }

    private fun load(a: Long, select: Boolean) {
        val sel = c.selectedProcess.value ?: run { _error.value = "Select a process first"; return }
        viewModelScope.launch {
            _loading.value = true
            try {
                val region = sel.regions.regionFor(a)?.takeIf { it.isReadable } ?: run { _error.value = "${Address.format(a)} is not mapped"; return@launch }
                val start = maxOf(region.start, a - BEFORE)
                val end = minOf(region.end, a + AFTER)
                val bytes = c.backend.readMemory(sel.pid, start, (end - start).toInt())
                val sync = X86.syncStart(bytes, start, a) ?: a
                val list = X86.decode(bytes, sync, offset = (sync - start).toInt())
                _rows.value = list
                if (select) list.firstOrNull { it.address == a }?.let { pick(it) }
                _note.value = if (!region.isExecutable) "${region.name.ifBlank { "anon" }} is not executable — this is probably data." else null
                _error.value = null
            } catch (e: BackendException) {
                _error.value = ErrorAdvisor.diagnose(e).format()
            } finally {
                _loading.value = false
            }
        }
    }

    fun pick(ins: AsmInstruction) {
        _row.value = ins
        _preview.value = null
        _state.value = null
        _references.value = null
        _source.value = if (_assembly.value) ins.text else Hex.encode(ins.bytes, " ")
        readOperand(ins)
    }

    /** Register values of the watchpoint hit this view was opened from (null = none). */
    val registers = c.registerContext
    fun clearRegisters() { c.registerContext.value = null; _operand.value = null }

    private val _operand = MutableStateFlow<String?>(null)
    /** "now in memory: …" for the selected instruction's memory operand, at the address computed from the registers. */
    val operand = _operand.asStateFlow()

    /** Reads the memory operand's current value (mapped memory only; read-only). */
    private fun readOperand(ins: AsmInstruction) {
        _operand.value = null
        val snap = c.registerContext.value ?: return
        val sel = c.selectedProcess.value ?: return
        val ea = snap.effectiveAddress(ins) ?: return
        val size = ins.memory?.size?.takeIf { it in setOf(1, 2, 4, 8) } ?: return
        if (sel.regions.regionFor(ea, size)?.isReadable != true) { _operand.value = "not mapped now"; return }
        viewModelScope.launch {
            _operand.value = try {
                val b = c.backend.readMemory(sel.pid, ea, size)
                var v = 0L
                for (i in size - 1 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
                val hex = "0x" + java.lang.Long.toHexString(v).uppercase()
                val float = when (size) { 4 -> " · float ${java.lang.Float.intBitsToFloat(v.toInt())}"; 8 -> " · double ${java.lang.Double.longBitsToDouble(v)}"; else -> "" }
                val dec = when (size) { 1 -> v.toByte().toString(); 2 -> v.toShort().toString(); 4 -> v.toInt().toString(); else -> v.toString() }
                "$dec ($hex)$float"
            } catch (e: BackendException) {
                "could not read: ${e.message}"
            }
        }
    }

    fun relative(a: Long): String = c.selectedProcess.value?.regions?.let { ModuleResolver.refFor(a, it) }
        ?.let { "${it.name}+0x${java.lang.Long.toHexString(it.offset).uppercase()}" } ?: ""

    /** Assembles (on the PS4) or parses hex, then plans the patch and reads its current state. No write. */
    fun buildPreview() {
        val sel = c.selectedProcess.value ?: return
        val ins = _row.value ?: run { _error.value = "Tap an instruction first"; return }
        viewModelScope.launch {
            _loading.value = true
            try {
                val bytes = if (_assembly.value) c.codeTools.assemble(_source.value, ins.address).bytes else CodePatchPlanner.parseHex(_source.value)
                val region = sel.regions.regionFor(ins.address)
                val len = minOf(CodePatchPlanner.WINDOW_BYTES.toLong(), (region?.end ?: (ins.address + CodePatchPlanner.WINDOW_BYTES)) - ins.address).toInt()
                var window = c.backend.readMemory(sel.pid, ins.address, len)
                // If this app already patched here, plan against the journaled original bytes, not the patch.
                c.editor.originalFor(sel.pid, ins.address)?.original?.let { o -> if (o.size <= window.size) window = o + window.copyOfRange(o.size, window.size) }
                val p = CodePatchPlanner.plan(ins.address, window, bytes, if (_assembly.value) _source.value else null, region)
                _preview.value = p
                _state.value = p.stateOf(c.backend.readMemory(sel.pid, p.address, p.length))
                _error.value = null
            } catch (e: CodePatchException) {
                _error.value = e.message
            } catch (e: BackendException) {
                _error.value = ErrorAdvisor.diagnose(e).format()
            } catch (e: IllegalArgumentException) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    /** Prepares switching the patch ON (new code) or OFF (original code); written only after confirmation. */
    fun prepare(on: Boolean) {
        val sel = c.selectedProcess.value ?: return
        val p = _preview.value ?: return
        viewModelScope.launch {
            try {
                val now = p.stateOf(c.backend.readMemory(sel.pid, p.address, p.length))
                _state.value = now
                val expected = if (on) PatchState.OFF else PatchState.ON
                if (now != expected) {
                    _error.value = "The code is ${now.label} — ${if (on) "ON" else "OFF"} is only allowed from ${expected.label}. Nothing written."
                    return@launch
                }
                _plan.value = on to c.editor.planBytes(sel.pid, sel.name, p.address, if (on) p.on else p.off)
            } catch (e: EditDeniedException) {
                _error.value = e.message + if (e.message?.contains("not writable") == true) " (Settings → Allow writes to non-writable regions)" else ""
            } catch (e: BackendException) {
                _error.value = ErrorAdvisor.diagnose(e).format()
            }
        }
    }

    fun dismissPlan() { _plan.value = null }

    fun confirm() {
        val (on, plan) = _plan.value ?: return
        _plan.value = null
        viewModelScope.launch {
            val out = c.editor.execute(plan)
            if (out is WriteOutcome.Verified) {
                _message.value = "Patch ${if (on) "ON" else "OFF"}: ${plan.target.length} byte(s) verified at ${Address.format(plan.target.address)}"
                c.log.info("Code patch ${if (on) "on" else "off"} at ${Address.format(plan.target.address)}")
                _state.value = if (on) PatchState.ON else PatchState.OFF
            } else {
                _error.value = out.message
            }
            _row.value?.let { load(it.address, select = false) }
        }
    }

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    /** State of "Save…": the form closes only on [SaveState.Saved]. */
    val saveState = _saveState.asStateFlow()

    /** Called when the save form is opened again: forget the last result. */
    fun resetSave() { if (_saveState.value !is SaveState.Saving) _saveState.value = SaveState.Idle }

    /**
     * Saves the previewed patch to the cheat library. Ignored while a save is running (no double saves). The result
     * is [SaveState.Saved] only after the library file was written and read back with the patch in it.
     */
    fun save(name: String, group: String, profileId: String?, newProfileName: String, version: String) {
        if (_saveState.value is SaveState.Saving) return
        val p = _preview.value ?: run { _saveState.value = SaveState.Failed("Build a preview first (Preview button)."); return }
        _saveState.value = SaveState.Saving
        viewModelScope.launch {
            _saveState.value = try {
                SaveState.Saved(c.saveCodePatch(p, name, group, profileId, newProfileName, version))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                SaveState.Failed(e.message ?: "Invalid input")
            } catch (e: Exception) {
                c.log.error("Saving code patch failed: ${e.message}", notifyUser = false)
                SaveState.Failed("Could not save: ${e.message ?: e.javaClass.simpleName}")
            }
            (_saveState.value as? SaveState.Saved)?.let { c.log.notify(it.message) }
        }
    }

    /** Server-side search for instructions referencing [target] (RIP-relative) in the executable regions. */
    fun findReferences(target: Long) {
        val sel = c.selectedProcess.value ?: return
        viewModelScope.launch {
            _loading.value = true
            try {
                val code = sel.regions.filter { it.isExecutable && it.isReadable && it.size in 1..0xFFFFFFFFL }
                val out = ArrayList<Long>()
                for (r in code) out += c.codeTools.findReferences(sel.pid, r.start, r.size, target, maxResults = 200)
                _references.value = out.distinct().sorted()
                _error.value = null
            } catch (e: BackendException) {
                _error.value = ErrorAdvisor.diagnose(e).format()
            } finally {
                _loading.value = false
            }
        }
    }

    companion object {
        const val BEFORE = 64L
        const val AFTER = 192L
    }
}

@Composable
fun CodeScreen(initialAddress: Long?, onViewMemory: (Long) -> Unit, onWatch: (Long, Int, Boolean) -> Unit) {
    val vm = appViewModel { CodeViewModel(it) }
    LaunchedEffect(Unit) { vm.init(initialAddress) }
    val selected by vm.selected.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val address by vm.address.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val row by vm.row.collectAsStateWithLifecycle()
    val assembly by vm.assembly.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val refs by vm.references.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val saveState by vm.saveState.collectAsStateWithLifecycle()
    val regs by vm.registers.collectAsStateWithLifecycle()
    val operand by vm.operand.collectAsStateWithLifecycle()
    var allRegs by remember { mutableStateOf(false) }
    val connected = connection is ConnectionState.Connected
    val ng = (connection as? ConnectionState.Connected)?.info?.capabilities?.isNg == true
    var saving by remember { mutableStateOf(false) }
    // Close the save form only after the library confirmed the save.
    LaunchedEffect(saveState) { if (saveState is SaveState.Saved) saving = false }
    var group by remember { mutableStateOf("") }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val sel = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

    LazyColumn(Modifier.fillMaxSize().testTag("codeList"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selected == null) WarningBanner("No process selected.")
                OutlinedTextField(
                    value = address, onValueChange = vm::setAddress, label = { Text("Code address (hex)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("codeAddress"),
                )
                Button(onClick = vm::go, enabled = selected != null && connected, modifier = Modifier.testTag("codeGo")) { Text("Disassemble") }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                note?.let { WarningBanner(it) }
                message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("codeMessage")) }
                ErrorText(error)
                regs?.let { snap ->
                    SectionCard("Registers at the hit") {
                        Column(Modifier.testTag("regCard"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                com.ps4smartcheat.app.ui.components.StatusPill(if (snap.fromSaved) "saved" else "live hit", com.ps4smartcheat.app.ui.components.Tone.GOOD)
                                com.ps4smartcheat.app.ui.components.StatusPill("thread ${snap.thread}")
                                androidx.compose.material3.TextButton(onClick = vm::clearRegisters) { Text("Clear") }
                            }
                            Text(
                                "Values right after ${Address.format(snap.instructionAddress)} ran (a data watchpoint stops after the access). " +
                                    "Other instructions may have seen different values.",
                                style = MaterialTheme.typography.bodySmall, color = dim,
                            )
                            row?.let { ins ->
                                val uses = snap.usedBy(ins)
                                Text(if (uses.isEmpty()) "${ins.text}: no general-purpose registers" else "Used by ${ins.text}:", style = MaterialTheme.typography.bodyMedium)
                                uses.forEach { u ->
                                    Text(regLine(u.name, u.value, u.bits), style = mono, modifier = Modifier.testTag("regUse-${u.name}"))
                                }
                                snap.effectiveAddress(ins)?.let { ea ->
                                    Text("operand → ${Address.format(ea)} ${vm.relative(ea)}", style = mono, modifier = Modifier.testTag("regEa"))
                                    operand?.let { Text("   now in memory: $it", style = mono, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.testTag("regOperand")) }
                                }
                            }
                            androidx.compose.material3.TextButton(onClick = { allRegs = !allRegs }) { Text(if (allRegs) "Hide all registers" else "All registers") }
                            if (allRegs) {
                                com.ps4smartcheat.core.debug.Registers.DISPLAY.mapNotNull { n -> snap.values[n]?.let { n to it } }.chunked(2).forEach { pair ->
                                    Text(pair.joinToString("   ") { (n, v) -> n.padEnd(6) + "%016X".format(v) }, style = mono)
                                }
                            }
                        }
                    }
                }
                row?.let { ins ->
                    SectionCard("Patch at ${Address.format(ins.address)}") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${ins.text}   (${Hex.encode(ins.bytes, " ")})", style = mono)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ins.branchTarget?.let { t -> OutlinedButton(onClick = { vm.follow(t) }) { Text("Follow → ${Address.format(t)}") } }
                            }
                            (ins.ripTarget)?.let { t ->
                                Text("References ${Address.format(t)} ${vm.relative(t)}", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { onViewMemory(t) }) { Text("Memory") }
                                    OutlinedButton(onClick = { onWatch(t, ins.memory?.size?.takeIf { it in setOf(1, 2, 4, 8) } ?: 4, true) }) { Text("What writes?") }
                                    OutlinedButton(onClick = { vm.findReferences(t) }, enabled = ng) { Text("Code using it") }
                                }
                            }
                            refs?.let { list ->
                                Text(if (list.isEmpty()) "No instruction references it." else "${list.size} instruction(s):", style = MaterialTheme.typography.bodySmall)
                                list.take(50).forEach { a -> Text("→ ${Address.format(a)} ${vm.relative(a)}", style = mono, modifier = Modifier.clickable { vm.follow(a) }.padding(vertical = 2.dp)) }
                            }
                            com.ps4smartcheat.app.ui.components.Selector("Input", listOf(true, false), assembly, { if (it) "Assembly" else "Hex bytes" }, vm::setAssembly)
                            OutlinedTextField(
                                value = source, onValueChange = vm::setSource, minLines = 2,
                                label = { Text(if (assembly) "New instructions (Intel syntax, one per line)" else "New bytes (hex)") },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth().testTag("codeSource"),
                            )
                            if (assembly && !ng && connected) Text("Assembling runs on the PS4 and needs ps4debug-NG; use Hex instead.", style = MaterialTheme.typography.bodySmall, color = dim)
                            Button(onClick = vm::buildPreview, enabled = connected && source.isNotBlank(), modifier = Modifier.testTag("codePreview")) { Text("Preview") }
                        }
                    }
                }
                preview?.let { p ->
                    SectionCard("ON / OFF") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Now: ${state?.let { if (it == PatchState.ON) "ON (patched)" else if (it == PatchState.OFF) "OFF (original)" else it.label } ?: "?"}", modifier = Modifier.testTag("codeState"))
                            Text("OFF  " + Hex.encode(p.off, " "), style = mono)
                            p.replaced.forEach { Text("     ${it.text}", style = mono, color = dim) }
                            Text("ON   " + Hex.encode(p.on, " "), style = mono)
                            p.result.forEach { Text("     ${it.text}", style = mono, color = dim) }
                            p.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            if (!settings.editingEnabled || !settings.allowNonWritableRegions) {
                                Text("To switch code ON/OFF enable memory editing and \"Allow writes to non-writable regions\" in Settings.", style = MaterialTheme.typography.bodySmall, color = dim)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.prepare(true) }, enabled = state == PatchState.OFF && settings.editingEnabled, modifier = Modifier.testTag("codeOn")) { Text("ON (1)") }
                                OutlinedButton(onClick = { vm.prepare(false) }, enabled = state == PatchState.ON && settings.editingEnabled, modifier = Modifier.testTag("codeOff")) { Text("OFF (0)") }
                                OutlinedButton(
                                    onClick = { if (!saving) vm.resetSave(); saving = !saving },
                                    enabled = saveState !is SaveState.Saving, modifier = Modifier.testTag("codeSave"),
                                ) { Text("Save…") }
                            }
                            if (saving) {
                                OutlinedTextField(group, { group = it }, label = { Text("Group (optional — toggled and exported together)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                val s = selected
                                SaveCheatForm(
                                    profiles = profiles.filter { s != null && it.processName == s.name && (it.titleId.isNullOrBlank() || it.titleId == s.titleId) },
                                    defaultProfileName = s?.titleId ?: s?.name ?: "",
                                    onCancel = { saving = false },
                                    // The form stays open (with the input) until the save is confirmed; see LaunchedEffect below.
                                    onSave = { n, pid, newName, version -> vm.save(n, group, pid, newName, version) },
                                    initialName = p.source?.lineSequence()?.firstOrNull()?.let { "Patch: $it" } ?: "Code patch",
                                    busy = saveState is SaveState.Saving,
                                    error = (saveState as? SaveState.Failed)?.error,
                                )
                                Text("Saved patches can be switched ON/OFF in Cheats and exported as a GoldHEN cheat file.", style = MaterialTheme.typography.bodySmall, color = dim)
                            }
                            (saveState as? SaveState.Saved)?.let {
                                Text("✓ ${it.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.testTag("codeSaved"))
                            }
                        }
                    }
                }
                if (rows.isNotEmpty()) Text("Tap an instruction to patch it. W = writes memory, R = reads, → = branch.", style = MaterialTheme.typography.bodySmall, color = dim)
            }
        }
        items(rows, key = { it.address }) { ins ->
            val flag = when {
                ins.writesMemory -> "W"
                ins.readsMemory -> "R"
                ins.flow != Flow.NEXT -> "→"
                else -> " "
            }
            Column(
                Modifier.fillMaxWidth()
                    .then(if (row?.address == ins.address) Modifier.background(sel) else Modifier)
                    .clickable { vm.pick(ins) }
                    .padding(vertical = 3.dp)
                    .testTag("asm_" + java.lang.Long.toHexString(ins.address)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$flag ${Address.format(ins.address)}  ", style = mono, color = dim)
                    Text(ins.text, style = mono)
                }
                Text("   ${vm.relative(ins.address)}  ${Hex.encode(ins.bytes, " ")}", style = mono, color = dim)
                regs?.let { snap ->
                    val uses = snap.usedBy(ins)
                    if (uses.isNotEmpty() || ins.address == snap.instructionAddress) {
                        Text(
                            (if (ins.address == snap.instructionAddress) "   ◆ hit  " else "   ") +
                                uses.joinToString("  ") { u -> "${u.name}=" + hexOf(u.value, u.bits) },
                            style = mono, color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.testTag("regRow-" + java.lang.Long.toHexString(ins.address)),
                        )
                    }
                }
            }
        }
    }

    plan?.let { (on, p) ->
        ConfirmDialog(
            title = if (on) "Switch patch ON?" else "Switch patch OFF?",
            text = buildString {
                append("Write ${p.target.length} byte(s) of code at ${Address.format(p.target.address)} in ${p.target.processName}.\n\n")
                append("Now: ${Hex.encode(p.original, " ")}\nNew: ${Hex.encode(p.replacement, " ")}")
                if (p.warnings.isNotEmpty()) append("\n\n• " + p.warnings.joinToString("\n• "))
                append("\n\nPatching code can crash the game. The bytes are verified after writing.")
            },
            confirmLabel = if (on) "Write ON" else "Write OFF",
            onConfirm = vm::confirm,
            onDismiss = vm::dismissPlan,
        )
    }
}

/** Register value padded to its width, e.g. `0x00001387`; decimal added for 32 bits and below. */
private fun hexOf(v: Long, bits: Int): String = "0x" + java.lang.Long.toHexString(v).uppercase().padStart(bits / 4, '0')

private fun regLine(name: String, v: Long, bits: Int): String =
    name.padEnd(5) + " = " + hexOf(v, bits) + if (bits <= 32) "  (" + (if (bits == 32) v.toInt().toString() else v.toString()) + ")" else ""
