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
import androidx.compose.material3.LinearProgressIndicator
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
import com.ps4smartcheat.app.ui.components.ChoiceRow
import com.ps4smartcheat.app.ui.components.ConfirmDialog
import com.ps4smartcheat.app.ui.components.ErrorText
import com.ps4smartcheat.app.ui.components.SectionCard
import com.ps4smartcheat.app.ui.components.Selector
import com.ps4smartcheat.app.ui.components.StatusPill
import com.ps4smartcheat.app.ui.components.Tone
import com.ps4smartcheat.app.ui.components.WarningBanner
import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.debug.resolve
import com.ps4smartcheat.core.debug.AccessMonitor
import com.ps4smartcheat.core.debug.AccessRecord
import com.ps4smartcheat.core.debug.DebugSession
import com.ps4smartcheat.core.debug.InterruptListener
import com.ps4smartcheat.core.debug.MonitorOptions
import com.ps4smartcheat.core.debug.ReleaseReport
import com.ps4smartcheat.core.debug.SavedWatchSession
import com.ps4smartcheat.core.debug.WatchResults
import com.ps4smartcheat.core.debug.WatchType
import com.ps4smartcheat.core.diag.ErrorAdvisor
import com.ps4smartcheat.core.logging.EventCategory
import com.ps4smartcheat.core.util.Address
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Where a watch session is. */
enum class WatchPhase(val label: String, val tone: Tone) {
    IDLE("Ready", Tone.NEUTRAL),
    ATTACHING("Attaching…", Tone.WARN),
    WATCHING("Watching", Tone.WARN),
    RELEASING("Releasing…", Tone.WARN),
    DONE("Finished", Tone.GOOD),
    FAILED("Failed", Tone.BAD),
}

/**
 * "Find out what writes / accesses this address" with a ps4debug-NG hardware watchpoint. Nothing is written to game
 * memory. Every hit pauses the game until the app's CONTINUE is confirmed; whatever happens, the session ends with a
 * verified release (watchpoint removed, game resumed, debugger detached). Results are saved for later.
 */
class WatchViewModel(private val c: AppContainer) : ViewModel() {
    val selected = c.selectedProcess
    val connection = c.backend.state

    private val _address = MutableStateFlow("")
    val address = _address.asStateFlow()
    private val _size = MutableStateFlow(4)
    val size = _size.asStateFlow()
    private val _type = MutableStateFlow(WatchType.WRITES)
    val type = _type.asStateFlow()
    private val _captureOnce = MutableStateFlow(true)
    /** Capture once (default): the game pauses exactly once. */
    val captureOnce = _captureOnce.asStateFlow()
    private val _phase = MutableStateFlow(WatchPhase.IDLE)
    val phase = _phase.asStateFlow()
    private val _records = MutableStateFlow<List<AccessRecord>>(emptyList())
    val records = _records.asStateFlow()
    private val _hits = MutableStateFlow(0)
    val hits = _hits.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()
    private val _release = MutableStateFlow<ReleaseReport?>(null)
    /** What the last cleanup could confirm. */
    val release = _release.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _saved = MutableStateFlow<List<SavedWatchSession>>(emptyList())
    val saved = _saved.asStateFlow()
    private var job: Job? = null
    @Volatile private var stopRequested = false
    private var initialized = false

    val running: Boolean get() = _phase.value == WatchPhase.ATTACHING || _phase.value == WatchPhase.WATCHING || _phase.value == WatchPhase.RELEASING

    fun init(address: Long?, size: Int?, writes: Boolean?) {
        if (initialized) return
        initialized = true
        address?.let { _address.value = Address.format(it) }
        size?.takeIf { it in SIZES }?.let { _size.value = it }
        writes?.let { _type.value = if (it) WatchType.WRITES else WatchType.ACCESSES }
        loadSaved()
    }

    fun setAddress(v: String) { _address.value = v.trim(); _error.value = null }
    fun setSize(v: Int) { _size.value = v }
    fun setType(v: WatchType) { _type.value = v }
    fun setCaptureOnce(v: Boolean) { _captureOnce.value = v }

    /** Address and size the watchpoint will use (the size shrinks to the alignment of the address). */
    fun target(): Pair<Long, Int>? {
        val a = Address.parse(_address.value) ?: return null
        var s = _size.value
        while (s > 1 && a % s != 0L) s /= 2
        return a to s
    }

    fun start() {
        val sel = c.selectedProcess.value ?: run { _error.value = "Select the game process first"; return }
        val (addr, size) = target() ?: run { _error.value = "Enter a hexadecimal address"; return }
        if (running) return
        val type = _type.value
        val once = _captureOnce.value
        _records.value = emptyList(); _hits.value = 0; _error.value = null; _release.value = null
        stopRequested = false
        _phase.value = WatchPhase.ATTACHING
        _status.value = "Attaching the debugger to ${sel.name}…"
        val startedAt = System.currentTimeMillis()
        job = viewModelScope.launch {
            try {
                // A high port needs no root; the extended attach request tells ps4debug-NG where to connect.
                val listener = withContext(Dispatchers.IO) { InterruptListener.open() }
                val session = DebugSession.attach(c.transport, sel.pid, listener, events = c.log)
                if (_phase.value == WatchPhase.ATTACHING) _phase.value = WatchPhase.WATCHING
                _status.value = if (once) "Waiting for the first ${type.label} of ${Address.format(addr)} — play the game"
                else "Watching ${Address.format(addr)} ($size bytes, ${type.label}) — play the game until the value changes"
                val options = MonitorOptions(type, maxHits = MAX_HITS, maxMillis = MAX_MILLIS, captureOnce = once)
                val result = AccessMonitor(c.backend, events = c.log).run(session, addr, size, options, shouldStop = { stopRequested }) { recs, n ->
                    _records.value = recs; _hits.value = n
                }
                _records.value = result.records
                _release.value = result.release
                _phase.value = if (result.release.confirmed) WatchPhase.DONE else WatchPhase.FAILED
                _status.value = "Finished: ${result.stopReason}. ${result.hits} hit(s)."
                if (result.records.isNotEmpty()) {
                    val regions = runCatching { c.backend.getMemoryRegions(sel.pid) }.getOrDefault(sel.regions)
                    val s = SavedWatchSession(
                        id = "w$startedAt", gameKey = c.gameKey(sel), processName = sel.name, pid = sel.pid, address = addr, size = size,
                        type = type, captureOnce = once, startedAtMillis = startedAt, finishedAtMillis = System.currentTimeMillis(),
                        hits = result.hits, stopReason = result.stopReason, released = result.release.confirmed,
                        records = result.records.map { WatchResults.toSaved(it, regions) },
                    )
                    _saved.value = withContext(Dispatchers.IO) { c.watchResults.add(s) }.filter { it.gameKey == c.gameKey(sel) }
                }
            } catch (e: CancellationException) {
                _status.value = "Stopped; debugger released."
                throw e
            } catch (e: BackendException) {
                _phase.value = WatchPhase.FAILED
                _error.value = ErrorAdvisor.diagnose(e).format()
                _status.value = null
            } catch (e: IllegalArgumentException) {
                _phase.value = WatchPhase.FAILED
                _error.value = e.message
                _status.value = null
            } catch (e: java.io.IOException) {
                _phase.value = WatchPhase.FAILED
                _error.value = ErrorAdvisor.diagnose(e).format()
                _status.value = null
            } finally {
                if (_phase.value == WatchPhase.ATTACHING || _phase.value == WatchPhase.WATCHING) _phase.value = WatchPhase.DONE
                c.log.info("watch session ended", EventCategory.PROCESS)
            }
        }
    }

    /** Ends monitoring normally: the game is resumed, the session released and the results kept. */
    fun stop() {
        // Never cancels: the monitor sees the flag within a quarter second and releases with every step verified.
        if (_phase.value == WatchPhase.WATCHING) _phase.value = WatchPhase.RELEASING
        stopRequested = true
    }

    fun loadSaved() {
        val key = c.selectedProcess.value?.let { c.gameKey(it) }
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { c.watchResults.load() }
            _saved.value = if (key == null) all else all.filter { it.gameKey == key }
        }
    }

    fun deleteSaved(id: String) = viewModelScope.launch {
        val key = c.selectedProcess.value?.let { c.gameKey(it) }
        val all = withContext(Dispatchers.IO) { c.watchResults.delete(id) }
        _saved.value = if (key == null) all else all.filter { it.gameKey == key }
    }

    /** Where to open a saved instruction now: through its module offset when the game was restarted. */
    fun resolve(a: com.ps4smartcheat.core.debug.SavedAccess): Long = a.resolve(c.selectedProcess.value?.regions.orEmpty())

    /** Opens a live hit in the disassembler with its registers. Returns the address to show. */
    fun openRecord(r: AccessRecord): Long {
        c.registerContext.value = com.ps4smartcheat.core.debug.RegisterSnapshot.of(r)
        return r.instructionAddress
    }

    /** Like [openRecord], but shows [at] (a caller from the call stack) with the hit's registers and call stack. */
    fun openRecordAt(r: AccessRecord, at: Long): Long { openRecord(r); return at }

    /** Like [openSaved], but shows [at] (a caller from the saved call stack). */
    fun openSavedAt(a: com.ps4smartcheat.core.debug.SavedAccess, s: SavedWatchSession, at: Long): Long { openSaved(a, s); return at }

    /** Where a call-stack frame's call instruction is now (module offset after a restart). */
    fun frameAddress(f: com.ps4smartcheat.core.debug.StackFrame): Long =
        c.selectedProcess.value?.regions?.let { f.resolve(it) } ?: f.callAddress

    fun relativeOf(a: Long): String = c.selectedProcess.value?.regions?.let { com.ps4smartcheat.core.cheat.ModuleResolver.refFor(a, it) }
        ?.let { "${it.name}+0x${java.lang.Long.toHexString(it.offset).uppercase()}" } ?: ""

    /** Opens a saved hit in the disassembler with the registers stored with it. Returns the address to show. */
    fun openSaved(a: com.ps4smartcheat.core.debug.SavedAccess, s: SavedWatchSession): Long {
        val at = resolve(a)
        c.registerContext.value = com.ps4smartcheat.core.debug.RegisterSnapshot.of(a, at, s.finishedAtMillis)
        return at
    }

    override fun onCleared() {
        // Leaving the screen ends monitoring; the coroutine's cleanup (release) still runs to completion.
        stopRequested = true
        super.onCleared()
    }

    companion object {
        val SIZES = listOf(1, 2, 4, 8)
        const val MAX_HITS = 1000
        const val MAX_MILLIS = 120_000L
    }
}

@Composable
fun WatchScreen(initialAddress: Long?, initialSize: Int?, writes: Boolean?, onOpenCode: (Long) -> Unit) {
    val vm = appViewModel { WatchViewModel(it) }
    LaunchedEffect(Unit) { vm.init(initialAddress, initialSize, writes) }
    val selected by vm.selected.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val address by vm.address.collectAsStateWithLifecycle()
    val size by vm.size.collectAsStateWithLifecycle()
    val type by vm.type.collectAsStateWithLifecycle()
    val once by vm.captureOnce.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    val hits by vm.hits.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val release by vm.release.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val info = (connection as? ConnectionState.Connected)?.info
    val ng = info?.capabilities?.isNg == true
    /** Capability level ≥ 1.2: the payload connects back to the app's high port (no root needed). */
    val portOk = info?.capabilities?.attachPort == true
    val running = phase == WatchPhase.ATTACHING || phase == WatchPhase.WATCHING || phase == WatchPhase.RELEASING
    var confirm by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    var openSaved by remember { mutableStateOf<String?>(null) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

    LazyColumn(Modifier.fillMaxSize().testTag("watchList"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GameRequiredBanner()
                if (info != null && !ng) WarningBanner("Watchpoints need ps4debug-NG.")
                if (ng && !portOk) WarningBanner(
                    "This ps4debug-NG reports capability level ${info?.capabilities?.capabilityLevel ?: "(none)"}. Watchpoints need " +
                        "level 1.2 or newer (configurable interrupt port). Load a newer ps4debug-NG and reconnect.",
                )
                Text(
                    "Finds the instruction that changes a value, using a hardware watchpoint of the PS4 debugger. Game memory is not " +
                        "changed; the game pauses briefly at each hit and is resumed right away. Offline games only.",
                    style = MaterialTheme.typography.bodySmall, color = dim,
                )
            }
        }
        if (phase != WatchPhase.IDLE || error != null) item {
            SectionCard("Session") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(phase.label, phase.tone)
                    if (hits > 0 || running) StatusPill("$hits hit(s) · ${records.size} instruction(s)", modifier = Modifier.testTag("wpHits"))
                }
                if (running) OutlinedButton(onClick = vm::stop, enabled = phase == WatchPhase.WATCHING || phase == WatchPhase.ATTACHING, modifier = Modifier.testTag("wpStop")) { Text("Stop") }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("wpStatus")) }
                release?.let { r ->
                    if (r.confirmed) {
                        Text("✓ Game resumed · watchpoint removed · debugger detached" + if (r.recoveredFromNewConnection) " (released from a new connection)" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.testTag("wpReleased"))
                    } else {
                        WarningBanner(
                            "The release could not be confirmed (${r.notes.joinToString("; ")}). If the game stays paused: Settings → Connection " +
                                "diagnostics → Resume process, or reload ps4debug-NG.",
                        )
                    }
                }
                ErrorText(error)
            }
        }
        items(records, key = { it.instructionAddress }) { r ->
            SectionCard(null) {
                Column(Modifier.fillMaxWidth().clickable { expanded = if (expanded == r.instructionAddress) null else r.instructionAddress }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusPill("${r.count}×", Tone.GOOD)
                        Text(Address.format(r.instructionAddress), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("wpRecord"))
                    }
                    Text(r.instruction?.text ?: "(instruction before ${Address.format(r.trapRip)} not decoded)", style = mono)
                    Text("thread ${r.threadName}" + (r.effectiveAddress?.let { " · accessed ${Address.format(it)}" } ?: "") + " · tap for registers", style = MaterialTheme.typography.bodySmall, color = dim)
                    if (expanded == r.instructionAddress) {
                        r.registers.general.forEach { (n, v) ->
                            Text(com.ps4smartcheat.core.debug.RegisterFormat.line(n, v, 64), style = mono)
                        }
                    }
                    OutlinedButton(onClick = { onOpenCode(vm.openRecord(r)) }, modifier = Modifier.testTag("openRecord")) { Text("Open in disassembler") }
                    r.callStack?.let { frames ->
                        CallStackView(
                            r.instructionAddress, r.instruction?.text, frames, vm::relativeOf,
                            onOpen = { at -> onOpenCode(vm.openRecordAt(r, at)) }, address = vm::frameAddress, tag = "wpStack",
                        )
                    }
                }
            }
        }
        item {
            SectionCard("Target") {
                OutlinedTextField(
                    value = address, onValueChange = vm::setAddress, textStyle = ltr(), label = { Text("Address (hex)") }, singleLine = true, enabled = !running,
                    modifier = Modifier.fillMaxWidth().testTag("wpAddress"),
                )
                ChoiceRow("Writes", "Instructions that change the value", type == WatchType.WRITES, { vm.setType(WatchType.WRITES) }, enabled = !running)
                ChoiceRow("Reads or writes", "Also instructions that only read it (usually many more hits)", type == WatchType.ACCESSES, { vm.setType(WatchType.ACCESSES) }, enabled = !running)
                Selector("Size", WatchViewModel.SIZES, size, { "$it bytes" }, vm::setSize, enabled = !running)
                vm.target()?.let { (a, s) -> if (s != size) Text("${Address.format(a)} is only $s-byte aligned: watching $s byte(s).", style = MaterialTheme.typography.bodySmall, color = dim) }
            }
        }
        item {
            SectionCard("Mode") {
                ChoiceRow(
                    "Capture once (recommended)", "Stops at the first hit with its registers. The game pauses only once.",
                    once, { vm.setCaptureOnce(true) }, enabled = !running, tag = "wpModeOnce",
                )
                ChoiceRow(
                    "Monitor", "Lists every instruction for up to ${WatchViewModel.MAX_MILLIS / 1000} s or ${WatchViewModel.MAX_HITS} hits; stops by itself if hits come too fast.",
                    !once, { vm.setCaptureOnce(false) }, enabled = !running, tag = "wpModeContinuous",
                )
                Button(onClick = { confirm = true }, enabled = !running && selected != null && ng && portOk && selected?.exited == false, modifier = Modifier.testTag("wpStart")) {
                    Text(if (phase == WatchPhase.IDLE) "Start" else "Start again")
                }
            }
        }
        item {
            SectionCard("Saved results") {
                if (saved.isEmpty()) Text("Finished sessions are kept here (newest first) and can be opened in the disassembler later — no debugger needed.", style = MaterialTheme.typography.bodySmall, color = dim)
                saved.forEach { s ->
                    Column(Modifier.fillMaxWidth().testTag("savedSession-${s.id}")) {
                        Row(Modifier.fillMaxWidth().clickable { openSaved = if (openSaved == s.id) null else s.id }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${if (s.type == WatchType.WRITES) "Writes to" else "Accesses to"} ${Address.format(s.address)}", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.finishedAtMillis))} · ${s.hits} hit(s) · " +
                                        "${s.records.size} instruction(s)" + if (s.captureOnce) " · capture once" else "",
                                    style = MaterialTheme.typography.bodySmall, color = dim,
                                )
                            }
                            StatusPill(if (s.released) "released" else "check", if (s.released) Tone.GOOD else Tone.WARN)
                        }
                        if (openSaved == s.id) {
                            s.records.forEach { a ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${a.count}×  " + (a.module?.let { "${it.name}+0x${java.lang.Long.toHexString(it.offset).uppercase()}" } ?: Address.format(a.instructionAddress)), style = mono)
                                        Text(a.text ?: "(not decoded)", style = mono, color = dim)
                                    }
                                    TextButton(onClick = { onOpenCode(vm.openSaved(a, s)) }, modifier = Modifier.testTag("openSaved-${s.id}")) { Text("Open") }
                                }
                                if (a.callStack.isNotEmpty()) {
                                    CallStackView(
                                        vm.resolve(a), a.text, a.callStack, vm::relativeOf,
                                        onOpen = { at -> onOpenCode(vm.openSavedAt(a, s, at)) }, address = vm::frameAddress, tag = "savedStack-${s.id}",
                                    )
                                }
                            }
                            TextButton(onClick = { vm.deleteSaved(s.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (confirm) ConfirmDialog(
        title = "Attach the debugger?",
        text = "ps4debug-NG attaches to ${selected?.name} (pid ${selected?.pid}) and sets a hardware watchpoint on ${vm.target()?.first?.let { Address.format(it) } ?: "?"}. " +
            if (once) "The game pauses once at the first ${type.label}; then the debugger is detached."
            else "The game pauses briefly at each hit. Monitoring stops after ${WatchViewModel.MAX_HITS} hits, ${WatchViewModel.MAX_MILLIS / 1000} s or too many hits per second, and the debugger is detached.",
        confirmLabel = "Attach",
        onConfirm = { confirm = false; vm.start() },
        onDismiss = { confirm = false },
    )
}
