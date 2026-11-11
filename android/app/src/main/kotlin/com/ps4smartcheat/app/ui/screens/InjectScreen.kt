package com.ps4smartcheat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ps4smartcheat.app.data.AppContainer
import com.ps4smartcheat.app.ui.appViewModel
import com.ps4smartcheat.app.ui.components.ChoiceRow
import com.ps4smartcheat.app.ui.components.ConfirmDialog
import com.ps4smartcheat.app.ui.components.ErrorText
import com.ps4smartcheat.app.ui.components.SectionCard
import com.ps4smartcheat.app.ui.components.StatusPill
import com.ps4smartcheat.app.ui.components.Tone
import com.ps4smartcheat.app.ui.components.WarningBanner
import com.ps4smartcheat.core.asm.AsmInstruction
import com.ps4smartcheat.core.asm.CaveAllocation
import com.ps4smartcheat.core.asm.CaveAllocationException
import com.ps4smartcheat.core.asm.CaveAllocator
import com.ps4smartcheat.core.asm.CaveCandidate
import com.ps4smartcheat.core.asm.CaveFinder
import com.ps4smartcheat.core.asm.CaveKind
import com.ps4smartcheat.core.asm.CavePartKind
import com.ps4smartcheat.core.asm.CaveScript
import com.ps4smartcheat.core.asm.CaveSpace
import com.ps4smartcheat.core.asm.CodeInjection
import com.ps4smartcheat.core.asm.CodePatchException
import com.ps4smartcheat.core.asm.InjectionPlanner
import com.ps4smartcheat.core.asm.InjectionState
import com.ps4smartcheat.core.asm.InjectionStep
import com.ps4smartcheat.core.asm.X86
import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.cheat.InjectionEntries
import com.ps4smartcheat.core.cheat.ModuleResolver
import com.ps4smartcheat.core.diag.ErrorAdvisor
import com.ps4smartcheat.core.edit.EditDeniedException
import com.ps4smartcheat.core.model.regionFor
import com.ps4smartcheat.core.util.Address
import com.ps4smartcheat.core.util.Hex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A write waiting for confirmation on the Inject screen. */
sealed interface InjectConfirm {
    data class Switch(val id: String, val enable: Boolean, val steps: List<InjectionStep>) : InjectConfirm
    data class Allocate(val hint: Long, val length: Int) : InjectConfirm
    data class Free(val allocation: CaveAllocation) : InjectConfirm
}

/**
 * Code cave / Assembly injection: hook an instruction with a jump to a cave holding new code, the moved original
 * instructions and a jump back. Read-only until the user confirms a write; every write is verified, ordered (cave
 * first, jump last) and undone if an enable fails. Nothing is applied automatically, also not after reconnecting.
 */
class InjectViewModel(private val c: AppContainer) : ViewModel() {
    val selected = c.selectedProcess
    val settings = c.settings.settings
    val connection = c.backend.state
    val profiles = c.cheats.profiles
    val injections = c.injections

    private val _hook = MutableStateFlow<Long?>(null)
    val hook = _hook.asStateFlow()
    private val _hookCode = MutableStateFlow<List<AsmInstruction>>(emptyList())
    /** Instructions at the hook (the first ones are overwritten by the jump). */
    val hookCode = _hookCode.asStateFlow()
    private val _sourceCode = MutableStateFlow<List<AsmInstruction>>(emptyList())
    /** Source Code (read-only): the instructions the jump overwrites, for the chosen cave and replace length. */
    val sourceCode = _sourceCode.asStateFlow()
    private val _replaceBytes = MutableStateFlow(0)
    /** Bytes the user chose to replace at the hook (0 = just what the jump needs). */
    val replaceBytes = _replaceBytes.asStateFlow()
    private val _source = MutableStateFlow("")
    /** Target Code (editable): starts as the Source Code followed by `jmp return`. */
    val source = _source.asStateFlow()
    private val _caveKind = MutableStateFlow(CaveKind.MODULE_PADDING)
    val caveKind = _caveKind.asStateFlow()
    private val _caves = MutableStateFlow<List<CaveCandidate>?>(null)
    val caves = _caves.asStateFlow()
    private val _cave = MutableStateFlow<CaveCandidate?>(null)
    val cave = _cave.asStateFlow()
    private val _allocation = MutableStateFlow<CaveAllocation?>(null)
    /** Memory allocated on this screen for the cave (session only). */
    val allocation = _allocation.asStateFlow()
    private val _current = MutableStateFlow<AppContainer.ActiveInjection?>(null)
    /** The injection built by "Build preview" (also kept in the session list once written). */
    val current = _current.asStateFlow()
    private val _confirm = MutableStateFlow<InjectConfirm?>(null)
    val confirm = _confirm.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState = _saveState.asStateFlow()
    private var initialized = false
    /** Bytes read at the hook when the screen was opened (for the Source Code). */
    private var window: ByteArray? = null

    fun init(address: Long?) {
        if (initialized) return
        initialized = true
        if (address != null) load(address)
    }

    /** Hook and cave of [inj] as text for the clipboard. */
    fun copyText(inj: CodeInjection, style: com.ps4smartcheat.core.asm.Listing.Style): String {
        _message.value = "Copied the injection (${style.label})"
        val f = { rows: List<AsmInstruction> -> com.ps4smartcheat.core.asm.Listing.format(rows, style, ::relative) }
        return if (style == com.ps4smartcheat.core.asm.Listing.Style.CODE) {
            f(inj.caveListing)
        } else {
            "; hook ${Address.format(inj.hookAddress)} (original)\n" + f(inj.stolen) +
                "\n; hook (injected)\n" + f(inj.hookListing) +
                "\n; cave ${Address.format(inj.caveAddress)}\n" + f(inj.caveListing)
        }
    }

    fun relative(a: Long): String = c.selectedProcess.value?.regions?.let { ModuleResolver.refFor(a, it) }
        ?.let { "${it.name}+0x${java.lang.Long.toHexString(it.offset).uppercase()}" } ?: ""

    private fun load(address: Long) {
        val sel = c.selectedProcess.value ?: run { _error.value = "Select a process first"; return }
        _hook.value = address
        launchBusy {
            val region = sel.regions.regionFor(address) ?: throw CodePatchException("${Address.format(address)} is not mapped")
            if (!region.isExecutable) throw CodePatchException("${Address.format(address)} is not executable code (${region.name.ifBlank { "anon" }} ${region.protString()}).")
            val len = minOf(InjectionPlanner.HOOK_WINDOW.toLong(), region.end - address).toInt()
            val bytes = c.backend.readMemory(sel.pid, address, len)
            window = bytes
            _hookCode.value = X86.decode(bytes, address).take(8)
            updateSourceCode()
            findCavesNow()
        }
    }

    fun setSource(v: String) { _source.value = v; _current.value = null; _error.value = null }

    /** Puts the Target Code back to the Source Code followed by `jmp return`. */
    fun resetTarget() = setSource(InjectionPlanner.template(_sourceCode.value))

    /**
     * Replace the hooked instructions up to and including [ins] (at least what the jump needs). Tapping an
     * instruction the jump overwrites anyway goes back to the minimum.
     */
    fun replaceThrough(ins: AsmInstruction) {
        val hook = _hook.value ?: return
        if (!ins.valid) return
        val w = window ?: return
        val bytes = (ins.end - hook).toInt()
        val t = caveTarget()
        val minimum = runCatching { InjectionPlanner.sourceFor(hook, w, t?.first, t?.second ?: 0).sumOf { it.length } }.getOrDefault(0)
        _replaceBytes.value = if (bytes <= minimum) 0 else bytes
        _current.value = null
        updateSourceCode()
    }

    /** Address and size of the cave the next build uses (the jump's size depends on its distance). */
    private fun caveTarget(): Pair<Long, Int>? = when (_caveKind.value) {
        CaveKind.MODULE_PADDING -> _cave.value?.let { it.address to it.size }
        CaveKind.ALLOCATED -> _allocation.value?.let { it.address to it.length }
    }

    /**
     * Recomputes the Source Code for the current cave and replace length. An untouched Target Code follows it; one
     * the user edited is kept (instructions it leaves out are reported in the preview).
     */
    private fun updateSourceCode() {
        val hook = _hook.value ?: return
        val w = window ?: return
        val old = _sourceCode.value
        val new = try {
            val t = caveTarget()
            InjectionPlanner.sourceFor(hook, w, t?.first, t?.second ?: 0, _replaceBytes.value)
        } catch (e: CodePatchException) {
            _error.value = e.message
            return
        }
        _sourceCode.value = new
        val target = _source.value.trim()
        if (target.isEmpty() || target == InjectionPlanner.template(old).trim()) _source.value = InjectionPlanner.template(new)
    }

    fun setCaveKind(k: CaveKind) {
        _caveKind.value = k
        _current.value = null
        if (k == CaveKind.MODULE_PADDING && _caves.value == null) findCaves()
        updateSourceCode()
    }

    fun pickCave(cand: CaveCandidate) { _cave.value = cand; _current.value = null; updateSourceCode() }

    /** Read-only search for padding in the executable around the hook. */
    fun findCaves() = launchBusy { findCavesNow() }

    private suspend fun findCavesNow() {
        val sel = c.selectedProcess.value ?: return
        val hook = _hook.value ?: return
        run {
            val regions = c.backend.getMemoryRegions(sel.pid).also { c.updateRegions(it) }
            val profile = c.cheats.profiles.value.firstOrNull { it.processName == sel.name && (it.titleId == null || it.titleId == sel.titleId) }
            // Caves of saved injections and of injections written in this session are not offered again.
            val reserved = InjectionEntries.reservedCaves(profile, regions) +
                c.injections.value.filter { it.pid == sel.pid && it.state != InjectionState.OFF }.map { it.injection.caveRange }
            val list = CaveFinder.find(c.backend, sel.pid, regions, hook, reserved = reserved)
            _caves.value = list
            _cave.value = list.firstOrNull()
            updateSourceCode()
            if (list.isEmpty()) _message.value = "No unused padding found near ${Address.format(hook)}. Allocated memory can be used instead (this session only)."
        }
    }

    fun requestAllocate() {
        val hook = _hook.value ?: return
        if (!c.caveAllocator.available) { _error.value = "Allocating memory needs ps4debug-NG."; return }
        if (!settings.value.editingEnabled) { _error.value = "Read-only mode: enable memory editing in Settings first."; return }
        _confirm.value = InjectConfirm.Allocate(hook, CaveAllocator.MAX_ALLOCATION)
    }

    fun requestFree(a: CaveAllocation) { _confirm.value = InjectConfirm.Free(a) }

    fun dismiss() { _confirm.value = null }

    /** Assembles the code for the cave, plans the injection and reads its current state. Nothing is written. */
    fun build() {
        val sel = c.selectedProcess.value ?: return
        val hook = _hook.value ?: run { _error.value = "Open an instruction to hook first"; return }
        val src = _source.value
        launchBusy {
            val regions = c.backend.getMemoryRegions(sel.pid).also { c.updateRegions(it) }
            val region = regions.regionFor(hook)
            val hookBytes = c.backend.readMemory(sel.pid, hook, minOf(InjectionPlanner.HOOK_WINDOW.toLong(), (region?.end ?: (hook + InjectionPlanner.HOOK_WINDOW)) - hook).toInt())
            val space = when (_caveKind.value) {
                CaveKind.MODULE_PADDING -> {
                    val cand = _cave.value ?: throw CodePatchException("Find caves and pick one first.")
                    CaveSpace(cand.address, cand.size, CaveKind.MODULE_PADDING, c.backend.readMemory(sel.pid, cand.address, cand.size), cand.atSegmentEnd)
                }
                CaveKind.ALLOCATED -> {
                    val a = _allocation.value ?: throw CodePatchException("Allocate memory first.")
                    CaveSpace(a.address, a.length, CaveKind.ALLOCATED, c.backend.readMemory(sel.pid, a.address, a.length))
                }
            }
            val inj = InjectionPlanner.plan(hook, hookBytes, region, space, src, _replaceBytes.value, known = c.knownPatches(sel, regions)) { text, at ->
                c.codeTools.assemble(text, at).bytes
            }
            val id = "inj-${java.lang.Long.toHexString(hook)}-${java.lang.Long.toHexString(inj.caveAddress)}"
            val state = c.injectionWriter.state(sel.pid, inj)
            val name = c.injections.value.firstOrNull { it.id == id }?.name ?: defaultName(inj)
            val alloc = if (space.kind == CaveKind.ALLOCATED) _allocation.value else null
            _current.value = AppContainer.ActiveInjection(id, sel.pid, sel.name, name, inj, alloc, state)
            _saveState.value = SaveState.Idle
        }
    }

    private fun defaultName(inj: CodeInjection) = "Injection at ${relative(inj.hookAddress).ifBlank { Address.format(inj.hookAddress) }}"

    /** Prepares switching an injection ON/OFF (checks state and policy); written only after confirmation. */
    fun prepare(a: AppContainer.ActiveInjection, enable: Boolean) {
        val sel = c.selectedProcess.value ?: return
        if (sel.pid != a.pid) { _error.value = "This injection belongs to pid ${a.pid}; the selected process is ${sel.pid}."; return }
        launchBusy {
            try {
                val steps = c.injectionWriter.prepare(a.pid, a.processName, a.injection, enable)
                _confirm.value = InjectConfirm.Switch(a.id, enable, steps)
            } catch (e: EditDeniedException) {
                refresh(a)
                throw CodePatchException(e.message + if (e.message?.contains("not writable") == true) " (Settings → Allow writes to non-writable regions)" else "")
            }
        }
    }

    fun confirm() {
        val pending = _confirm.value ?: return
        _confirm.value = null
        when (pending) {
            is InjectConfirm.Switch -> launchBusy {
                val a = (c.injections.value.firstOrNull { it.id == pending.id } ?: _current.value?.takeIf { it.id == pending.id }) ?: return@launchBusy
                val r = c.injectionWriter.execute(a.pid, a.injection, pending.steps, pending.enable)
                val updated = a.copy(state = r.stateAfter)
                c.putInjection(updated)
                if (_current.value?.id == a.id) _current.value = updated
                c.log.info("Code injection ${if (pending.enable) "on" else "off"} at ${Address.format(a.injection.hookAddress)}: ${r.message}")
                if (r.ok) _message.value = r.message else _error.value = r.message
            }
            is InjectConfirm.Allocate -> launchBusy {
                val sel = c.selectedProcess.value ?: return@launchBusy
                val a = c.caveAllocator.allocateNear(sel.pid, pending.hint, pending.length)
                _allocation.value = a
                updateSourceCode()
                c.updateRegions(c.backend.getMemoryRegions(sel.pid))
                _current.value = null
                c.log.info("Allocated ${a.mappedSize} bytes of code memory at ${Address.format(a.address)} for a code cave")
                _message.value = "Allocated ${a.length} bytes at ${Address.format(a.address)} (rwx, verified in the memory map)"
            }
            is InjectConfirm.Free -> launchBusy {
                val users = c.injections.value.filter { it.allocation == pending.allocation }
                for (u in users) {
                    val st = c.injectionWriter.state(u.pid, u.injection)
                    if (st == InjectionState.ON || st == InjectionState.MISMATCH) {
                        throw CodePatchException("\"${u.name}\" is ${st.label}: switch it OFF before freeing its memory.")
                    }
                }
                c.caveAllocator.free(pending.allocation)
                users.forEach { c.removeInjection(it.id) }
                if (_allocation.value == pending.allocation) { _allocation.value = null; updateSourceCode() }
                if (_current.value?.allocation == pending.allocation) _current.value = null
                c.selectedProcess.value?.let { c.updateRegions(c.backend.getMemoryRegions(it.pid)) }
                _message.value = "Freed ${Address.format(pending.allocation.address)} (verified: no longer mapped)"
            }
        }
    }

    /** Re-reads the bytes of an injection (read-only). */
    fun refresh(a: AppContainer.ActiveInjection) {
        viewModelScope.launch {
            try {
                val st = c.injectionWriter.state(a.pid, a.injection)
                val u = a.copy(state = st)
                if (c.injections.value.any { it.id == a.id }) c.putInjection(u)
                if (_current.value?.id == a.id) _current.value = u
            } catch (e: BackendException) {
                _error.value = ErrorAdvisor.diagnose(e).format()
            }
        }
    }

    fun forget(a: AppContainer.ActiveInjection) {
        if (a.allocation != null && a.state != InjectionState.OFF) { _error.value = "Free its memory instead (after switching it OFF)."; return }
        c.removeInjection(a.id)
    }

    fun resetSave() { if (_saveState.value !is SaveState.Saving) _saveState.value = SaveState.Idle }

    fun save(name: String, group: String, profileId: String?, newProfileName: String, version: String) {
        if (_saveState.value is SaveState.Saving) return
        val a = _current.value ?: run { _saveState.value = SaveState.Failed("Build a preview first."); return }
        _saveState.value = SaveState.Saving
        viewModelScope.launch {
            _saveState.value = try {
                SaveState.Saved(c.saveInjection(a.injection, name, group, profileId, newProfileName, version))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                SaveState.Failed(e.message ?: "Invalid input")
            } catch (e: Exception) {
                c.log.error("Saving code injection failed: ${e.message}", notifyUser = false)
                SaveState.Failed("Could not save: ${e.message ?: e.javaClass.simpleName}")
            }
            (_saveState.value as? SaveState.Saved)?.let { c.log.notify(it.message) }
        }
    }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            _message.value = null
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: CodePatchException) {
                _error.value = e.message
            } catch (e: CaveAllocationException) {
                _error.value = e.message
            } catch (e: EditDeniedException) {
                _error.value = e.message
            } catch (e: BackendException) {
                _error.value = ErrorAdvisor.diagnose(e).format()
            } catch (e: IllegalArgumentException) {
                _error.value = e.message
            } finally {
                _busy.value = false
                _error.value?.let { c.log.warn("Code injection: $it") }
            }
        }
    }
}

@Composable
fun InjectScreen(initialAddress: Long?, onOpenCode: (Long) -> Unit) {
    val vm = appViewModel { InjectViewModel(it) }
    LaunchedEffect(Unit) { vm.init(initialAddress) }
    val selected by vm.selected.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val sessionList by vm.injections.collectAsStateWithLifecycle()
    val hook by vm.hook.collectAsStateWithLifecycle()
    val hookCode by vm.hookCode.collectAsStateWithLifecycle()
    val sourceCode by vm.sourceCode.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val caveKind by vm.caveKind.collectAsStateWithLifecycle()
    val caves by vm.caves.collectAsStateWithLifecycle()
    val cave by vm.cave.collectAsStateWithLifecycle()
    val allocation by vm.allocation.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val confirm by vm.confirm.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val saveState by vm.saveState.collectAsStateWithLifecycle()
    val connected = connection is ConnectionState.Connected
    val ng = (connection as? ConnectionState.Connected)?.info?.capabilities?.isNg == true
    var saving by rememberSaveable { mutableStateOf(false) }
    var group by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(saveState) { if (saveState is SaveState.Saved) saving = false }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val small = MaterialTheme.typography.bodySmall

    LazyColumn(Modifier.fillMaxSize().testTag("injectList"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selected == null) WarningBanner("No process selected.")
                if (hook == null) WarningBanner("Open this screen from an instruction in the disassembler (Code → tap an instruction → Inject code).")
                if (hook == null) ErrorText(error)
            }
        }
        hook?.let { h ->
            item {
                val sourceEnd = sourceCode.lastOrNull()?.end ?: h
                val replacedBytes = (sourceEnd - h).toInt()
                SectionCard(
                    "Hook", step = 1, subtitle = "${Address.format(h)}  ${vm.relative(h)}".trim(),
                    trailing = { if (sourceCode.isNotEmpty()) StatusPill("$replacedBytes bytes", Tone.NEUTRAL) },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        hookCode.forEachIndexed { i, ins ->
                            CodeLine(
                                ins, mono, dim, highlighted = ins.address < sourceEnd,
                                onClick = if (ins.valid) ({ vm.replaceThrough(ins) }) else null, tag = "hookRow-$i",
                            )
                        }
                    }
                    Text(
                        "Highlighted: the instructions the jump to the cave overwrites (a 5-byte jmp, 14 bytes for a cave more than " +
                            "2 GiB away, padded with NOPs). Tap a later instruction to replace more of them; tap the first one to go back.",
                        style = small, color = dim,
                    )
                    TextButton(onClick = { onOpenCode(h) }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Open in disassembler") }
                }
            }
            item {
                SectionCard("Source Code", step = 2, trailing = { StatusPill("read-only") }) {
                    CodePanel(Modifier.testTag("injSourceCode")) {
                        val width = sourceCode.maxOfOrNull { it.text.length } ?: 0
                        if (sourceCode.isEmpty()) Text("…", style = mono, color = dim)
                        // Address and instruction first (what matters); the bytes follow as a dim comment.
                        sourceCode.forEach { ins ->
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = dim)) { append(Address.format(ins.address) + "  ") }
                                    append(ins.text.padEnd(width))
                                    withStyle(SpanStyle(color = dim)) { append("  ; " + Hex.encode(ins.bytes, " ")) }
                                },
                                style = mono, softWrap = false,
                            )
                        }
                    }
                    Text(
                        "The original instructions at the hook. Each line kept unchanged in the Target Code is moved into the cave and " +
                            "re-encoded there, so RIP-relative addresses and branches still reach the same targets.",
                        style = small, color = dim,
                    )
                }
            }
            item { TargetCodeCard(vm, source, sourceCode, connected, ng, mono, dim) }
            item {
                SectionCard("Code cave", step = 4) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChoiceRow(
                            "Unused padding in the game executable", "Same address every run: can be saved, switched from Cheats and exported to GoldHEN.",
                            caveKind == CaveKind.MODULE_PADDING, { vm.setCaveKind(CaveKind.MODULE_PADDING) }, tag = "injCaveModule",
                        )
                        ChoiceRow(
                            "Allocate memory near the hook", "ps4debug-NG allocates 16 KiB (rwx) for this game session only; cannot be saved.",
                            caveKind == CaveKind.ALLOCATED, { vm.setCaveKind(CaveKind.ALLOCATED) }, enabled = ng, tag = "injCaveAlloc",
                        )
                        HorizontalDivider()
                        if (caveKind == CaveKind.MODULE_PADDING) {
                            OutlinedButton(onClick = vm::findCaves, enabled = connected && !busy, modifier = Modifier.testTag("injFindCaves")) { Text("Find caves") }
                            caves?.let { list ->
                                if (list.isEmpty()) Text("No unused padding found near the hook.", style = small)
                                list.take(8).forEach { cand ->
                                    ChoiceRow(
                                        "${Address.format(cand.address)}  ·  ${cand.size} bytes",
                                        "${cand.module?.let { "${it.name}+0x${java.lang.Long.toHexString(it.offset).uppercase()}" } ?: ""} · ${cand.confidence}",
                                        cave?.address == cand.address, { vm.pickCave(cand) },
                                        tag = "cave-" + java.lang.Long.toHexString(cand.address),
                                    )
                                }
                            }
                        } else {
                            allocation?.let { a ->
                                Text("Allocated: ${Address.format(a.address)} · ${a.length} bytes · rwx", style = mono, modifier = Modifier.testTag("injAllocated"))
                                OutlinedButton(onClick = { vm.requestFree(a) }, enabled = !busy) { Text("Free this memory") }
                            } ?: Button(onClick = vm::requestAllocate, enabled = connected && ng && !busy && settings.editingEnabled, modifier = Modifier.testTag("injAllocate")) { Text("Allocate near the hook…") }
                            if (!settings.editingEnabled) Text("Allocating changes the game's memory: enable memory editing in Settings.", style = small, color = dim)
                        }
                        Button(
                            onClick = vm::build, enabled = connected && !busy && source.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().testTag("injBuild"),
                        ) { Text("Build preview") }
                        Text("Nothing is written yet: the preview shows the exact bytes first.", style = small, color = dim)
                    }
                }
            }
            // Progress and results right below the actions (not at the top, out of view).
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = small, modifier = Modifier.testTag("injMessage")) }
                    ErrorText(error)
                }
            }
        }
        current?.let { a ->
            item { InjectionCard(a, vm, settings.editingEnabled, settings.allowNonWritableRegions, busy, mono, dim, preview = true) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (a.injection.caveKind == CaveKind.MODULE_PADDING) {
                        OutlinedButton(onClick = { if (!saving) vm.resetSave(); saving = !saving }, enabled = saveState !is SaveState.Saving, modifier = Modifier.testTag("injSave")) { Text("Save to Cheats…") }
                        if (saving) {
                            OutlinedTextField(group, { group = it }, label = { Text("Group (optional — default: the name)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            val s = selected
                            SaveCheatForm(
                                profiles = profiles.filter { s != null && it.processName == s.name && (it.titleId.isNullOrBlank() || it.titleId == s.titleId) },
                                defaultProfileName = s?.titleId ?: s?.name ?: "",
                                onCancel = { saving = false },
                                onSave = { n, pid, newName, version -> vm.save(n, group, pid, newName, version) },
                                initialName = a.name,
                                busy = saveState is SaveState.Saving,
                                error = (saveState as? SaveState.Failed)?.error,
                            )
                            Text(
                                "Saved as one group: the cave code (in ≤64-byte parts) and the jump. Cheats switches them ON in that order and OFF in reverse.",
                                style = small, color = dim,
                            )
                        }
                        (saveState as? SaveState.Saved)?.let {
                            Text("✓ ${it.message}", style = small, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.testTag("injSaved"))
                        }
                    } else {
                        Text("Injections into allocated memory last for this game session only and cannot be saved.", style = small, color = dim)
                    }
                }
            }
        }
        val others = sessionList.filter { it.id != current?.id }
        if (others.isNotEmpty()) {
            item { Text("This session", style = MaterialTheme.typography.titleMedium) }
            others.forEach { a -> item(key = a.id) { InjectionCard(a, vm, settings.editingEnabled, settings.allowNonWritableRegions, busy, mono, dim, preview = false) } }
        }
        item {
            Text(
                "Injections are never re-applied automatically (also not after reconnecting or restarting the game). " +
                    "Only use them in offline single-player games.",
                style = small, color = dim,
            )
        }
    }

    confirm?.let { pending ->
        when (pending) {
            is InjectConfirm.Switch -> {
                val a = (sessionList.firstOrNull { it.id == pending.id } ?: current)
                ConfirmDialog(
                    title = if (pending.enable) "Inject code?" else "Remove injection?",
                    text = buildString {
                        append("${pending.steps.size} verified write(s) in ${a?.processName ?: "the game"}, in this order:\n")
                        pending.steps.forEach { s -> append("• ${s.label}: ${s.plan.target.length} bytes at ${Address.format(s.plan.target.address)}\n") }
                        if (pending.enable) append("\nThe cave is written first and the jump last, so the game never runs incomplete code. If a write fails, the earlier ones are undone.")
                        else append("\nThe jump is removed first; then the cave is restored.")
                        val warnings = pending.steps.flatMap { it.plan.warnings }.distinct()
                        if (warnings.isNotEmpty()) append("\n\n• " + warnings.joinToString("\n• "))
                        append("\n\nChanging code can crash the game. Offline single-player only.")
                    },
                    confirmLabel = if (pending.enable) "Inject" else "Remove",
                    onConfirm = vm::confirm, onDismiss = vm::dismiss,
                )
            }
            is InjectConfirm.Allocate -> ConfirmDialog(
                title = "Allocate code memory?",
                text = "ps4debug-NG maps ${pending.length} bytes of readable, writable and executable memory in the game at the first free " +
                    "address above ${Address.format(pending.hint)}. The result is checked in the memory map before it is used. " +
                    "It stays allocated until you free it or the game exits.",
                confirmLabel = "Allocate", onConfirm = vm::confirm, onDismiss = vm::dismiss,
            )
            is InjectConfirm.Free -> ConfirmDialog(
                title = "Free code memory?",
                text = "Unmaps ${Address.format(pending.allocation.address)}. Only injections that are OFF may use it (checked first). " +
                    "A game thread that is still inside the cave at this moment would crash.",
                confirmLabel = "Free", onConfirm = vm::confirm, onDismiss = vm::dismiss,
            )
        }
    }
}

/**
 * The editable Target Code: prefilled with the Source Code and `jmp return`. A summary under the editor shows which
 * original instructions are kept and how often the code returns, before anything is assembled.
 */
@Composable
private fun TargetCodeCard(
    vm: InjectViewModel, source: String, sourceCode: List<AsmInstruction>, connected: Boolean, ng: Boolean,
    mono: TextStyle, dim: Color,
) {
    var field by remember { mutableStateOf(TextFieldValue(source)) }
    // Follow changes made by the view model (template for another cave, reset); typing keeps the cursor.
    LaunchedEffect(source) { if (field.text != source) field = TextFieldValue(source, TextRange(source.length)) }
    fun insertLine(line: String) {
        val t = field.text
        val pos = field.selection.end.coerceIn(0, t.length)
        val lineEnd = t.indexOf('\n', pos).let { if (it < 0) t.length else it }
        val text = if (t.isBlank()) line else t.substring(0, lineEnd) + "\n" + line + t.substring(lineEnd)
        val cursor = if (t.isBlank()) line.length else lineEnd + 1 + line.length
        field = TextFieldValue(text, TextRange(cursor))
        vm.setSource(text)
    }
    val accent = MaterialTheme.colorScheme.primary
    SectionCard("Target Code", step = 3, subtitle = "Runs in the cave instead of the Source Code") {
        OutlinedTextField(
            value = field,
            onValueChange = { field = it; vm.setSource(it.text) },
            minLines = 6,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            placeholder = { Text("mov eax, 1000\njmp return", fontFamily = FontFamily.Monospace) },
            modifier = Modifier.fillMaxWidth().testTag("injTarget"),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { insertLine("jmp ${CaveScript.RETURN}") }, label = { Text("+ jmp return") }, modifier = Modifier.testTag("injInsertReturn"))
            AssistChip(onClick = vm::resetTarget, label = { Text("Reset to Source Code") }, modifier = Modifier.testTag("injResetTarget"))
        }
        val parsed = remember(source, sourceCode) { runCatching { CaveScript.parse(source, sourceCode) } }
        parsed.getOrNull()?.let { stmts ->
            val kept = stmts.filterIsInstance<CaveScript.Stmt.Original>().map { it.ins.address }.toSet()
            val keptCount = sourceCode.count { it.address in kept }
            val returns = CaveScript.returnsIn(stmts)
            val added = stmts.count { it is CaveScript.Stmt.Asm || it is CaveScript.Stmt.Bytes }
            Row(Modifier.horizontalScroll(rememberScrollState()).testTag("injTargetSummary"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill("original $keptCount/${sourceCode.size}", if (keptCount == sourceCode.size) Tone.GOOD else Tone.WARN)
                StatusPill("return ×$returns", if (returns == 0) Tone.WARN else Tone.GOOD)
                StatusPill("$added new line${if (added == 1) "" else "s"}")
            }
            if (sourceCode.isNotEmpty() && keptCount < sourceCode.size) {
                Text(
                    "Replaced (no longer run): " + sourceCode.filter { it.address !in kept }.joinToString("; ") { it.text },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        parsed.exceptionOrNull()?.let { ErrorText(it.message) }
        Text(
            buildAnnotatedString {
                append("Write your code before or after the original instructions, change them, or delete them to replace them. ")
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = accent)) { append("jmp return") }
                append(" goes back to the game right after the hook — use it as often as you need, also with a condition (")
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append("je return") }
                append("). Labels (")
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append("skip:") }
                append(") and raw bytes (")
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append("db 90 90") }
                append(") work too. Keep registers and flags the game needs.")
            },
            style = MaterialTheme.typography.bodySmall, color = dim,
        )
        if (connected && !ng) {
            Text(
                "Without ps4debug-NG nothing can be assembled: only the original instructions, db bytes and jmp return can be used.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** Monospace code on a tinted, rounded background; scrolls sideways instead of wrapping instructions. */
@Composable
private fun CodePanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            content()
        }
    }
}

/** One instruction at the hook; [highlighted] when the jump overwrites it. */
@Composable
private fun CodeLine(ins: AsmInstruction, mono: TextStyle, dim: Color, highlighted: Boolean, onClick: (() -> Unit)?, tag: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlighted) cs.primaryContainer.copy(alpha = 0.55f) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (highlighted) "▍" else " ", style = mono, color = cs.primary)
        Text(Address.format(ins.address), style = mono, color = if (highlighted) cs.onPrimaryContainer else dim)
        Text("  " + ins.text, style = mono, color = if (highlighted) cs.onPrimaryContainer else cs.onSurface, softWrap = false)
    }
}

@Composable
private fun partColor(kind: CavePartKind): Color = when (kind) {
    CavePartKind.CODE -> MaterialTheme.colorScheme.primary
    CavePartKind.ORIGINAL -> MaterialTheme.colorScheme.secondary
    CavePartKind.RETURN -> MaterialTheme.colorScheme.tertiary
    CavePartKind.BYTES -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun InjectionCard(
    a: AppContainer.ActiveInjection, vm: InjectViewModel, editing: Boolean, nonWritable: Boolean, busy: Boolean,
    mono: TextStyle, dim: Color, preview: Boolean,
) {
    val inj = a.injection
    val context = androidx.compose.ui.platform.LocalContext.current
    val st = a.state
    val partColors = CavePartKind.entries.associateWith { partColor(it) }
    val labelColor = MaterialTheme.colorScheme.primary
    SectionCard(
        if (preview) "Preview" else a.name, step = if (preview) 5 else null,
        subtitle = "Hook ${Address.format(inj.hookAddress)} → cave ${Address.format(inj.caveAddress)} · ${inj.caveOn.size} bytes",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(if (preview) "injPreview" else "injSession-${a.id}")) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    st?.label ?: "unknown",
                    when (st) { InjectionState.ON -> Tone.GOOD; InjectionState.OFF -> Tone.NEUTRAL; InjectionState.CAVE_ONLY -> Tone.WARN; else -> Tone.BAD },
                    modifier = Modifier.testTag(if (preview) "injState" else "injState-${a.id}"),
                )
                StatusPill(if (inj.caveKind == CaveKind.ALLOCATED) "allocated · session" else "executable padding")
                TextButton(onClick = { vm.refresh(a) }) { Text("Check") }
            }
            Text("Hook", style = MaterialTheme.typography.labelLarge)
            CodePanel {
                Text("OFF  " + Hex.encode(inj.hookOff, " "), style = mono, color = dim, softWrap = false)
                inj.stolen.forEach { Text("     ${it.text}", style = mono, softWrap = false) }
                Text("ON   " + Hex.encode(inj.hookOn, " "), style = mono, color = dim, softWrap = false)
                inj.hookListing.forEach { Text("     ${it.text}", style = mono, softWrap = false) }
            }
            Text("Cave", style = MaterialTheme.typography.labelLarge)
            CodePanel {
                inj.caveListing.forEach { ins ->
                    inj.labels[ins.address]?.forEach { l -> Text("$l:", style = mono, color = labelColor, softWrap = false) }
                    val part = inj.partAt(ins.address)?.kind
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = part?.let { partColors.getValue(it) } ?: dim, fontWeight = FontWeight.SemiBold)) { append((part?.tag ?: "").padEnd(5)) }
                            withStyle(SpanStyle(color = dim)) { append(Address.format(ins.address) + "  ") }
                            append(ins.text)
                            // Name branch targets the way the Target Code wrote them.
                            val target = ins.branchTarget
                            val name = when {
                                target == null -> null
                                target == inj.returnAddress -> CaveScript.RETURN
                                else -> inj.labels[target]?.firstOrNull()
                            }
                            if (name != null) withStyle(SpanStyle(color = dim)) { append("  ; $name") }
                        },
                        style = mono, softWrap = false,
                        modifier = Modifier.testTag("caveRow-" + java.lang.Long.toHexString(ins.address)),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CavePartKind.entries.forEach { k ->
                    Text(k.tag, style = mono, color = partColors.getValue(k), fontWeight = FontWeight.SemiBold)
                    Text(
                        when (k) { CavePartKind.CODE -> "your code"; CavePartKind.ORIGINAL -> "moved"; CavePartKind.RETURN -> "back"; CavePartKind.BYTES -> "bytes" },
                        style = MaterialTheme.typography.labelSmall, color = dim,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { com.ps4smartcheat.app.ui.components.copyText(context, "Assembly", vm.copyText(inj, com.ps4smartcheat.core.asm.Listing.Style.FULL)) },
                    modifier = Modifier.testTag(if (preview) "injCopy" else "injCopy-${a.id}"),
                ) { Text("Copy listing") }
                OutlinedButton(onClick = { com.ps4smartcheat.app.ui.components.copyText(context, "Assembly", vm.copyText(inj, com.ps4smartcheat.core.asm.Listing.Style.CODE)) }) { Text("Copy code") }
            }
            if (inj.warnings.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    inj.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
            if (!editing || !nonWritable) {
                Text(
                    "To inject, enable memory editing and \"Allow writes to non-writable regions\" (the hook is in code) in Settings.",
                    style = MaterialTheme.typography.bodySmall, color = dim,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.prepare(a, true) }, enabled = editing && !busy && (st == InjectionState.OFF || st == InjectionState.CAVE_ONLY),
                    modifier = Modifier.weight(1f).testTag(if (preview) "injOn" else "injOn-${a.id}"),
                ) { Text("Inject (ON)") }
                OutlinedButton(
                    onClick = { vm.prepare(a, false) }, enabled = editing && !busy && (st == InjectionState.ON || st == InjectionState.CAVE_ONLY && inj.caveKind == CaveKind.MODULE_PADDING),
                    modifier = Modifier.weight(1f).testTag(if (preview) "injOff" else "injOff-${a.id}"),
                ) { Text("Remove (OFF)") }
            }
            if (!preview) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    a.allocation?.let { al -> OutlinedButton(onClick = { vm.requestFree(al) }, enabled = !busy && a.state != InjectionState.ON) { Text("Free memory") } }
                    if (a.allocation == null) TextButton(onClick = { vm.forget(a) }) { Text("Forget") }
                }
            }
        }
    }
}
